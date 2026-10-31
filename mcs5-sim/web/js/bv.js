/* bluevision display - look-alike of the MCS-5 operator display, built from photos of the real screens.
 * Pages that were photographed (Propulsion, Engine, Fuel Consumption, Alarm Page, LAN FB 11-12) follow the photos closely;
 * the others (Overview, T-Exhaust, LAN FB 13-20, Legend, Channel List) are best guesses until photos are available. */
'use strict';

const BV = (() => {
  let M, stage, pageEl, topEl, tabsEl, barEl, popEl, helmEl;
  let ups = [], pups = [], cur = 'propulsion', topBtns = false;
  const AL = { view: 'Actual', grp: 'Combined', pri: 'All', state: 'All', sel: null, menu: false };
  const FUEL = { line: 1, gal: false };
  const MSG = {};
  const S = t => M.IX.byTag.get(t);
  const E = (l, n) => `PV1${l}${n}`, G = (l, n) => `PV2${l}${n}`;
  const L_ = i => M.st.lines[i - 1];
  const pad = n => String(n).padStart(2, '0');
  const stamp = ms => { const d = new Date(ms); return { date: `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()}`, time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`, short: `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${String(d.getFullYear()).slice(2)}` }; };
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ------------------------------------------------------------ html helpers (page-relative absolute layout)
  const fv = (v, s, dec) => { if (v == null) return '--'; if (Number.isNaN(v)) return '???'; if (s && (s.u === 'bin' || s.t === 'D')) return v ? 'Yes' : 'No'; return dec !== '' && dec != null ? v.toFixed(+dec) : String(Math.round(v)); };
  const F = (x, y, w, tag, dec) => `<div class="fld" style="left:${x}px;top:${y}px;width:${w}px" data-t="${tag}" data-d="${dec == null ? '' : dec}"></div>`;
  const FN = (x, y, w, fn, arg, cls) => `<div class="fld st ${cls || ''}" style="left:${x}px;top:${y}px;width:${w}px" data-f="${fn}" data-a="${arg == null ? '' : arg}"></div>`;
  const LB = (x, y, t, c) => `<div class="lbl ${c || ''}" style="left:${x}px;top:${y + 2}px">${t}</div>`;
  const UN = (x, y, t) => `<div class="unit" style="left:${x}px;top:${y + 3}px">${t}</div>`;
  const BTN = (x, y, w, h, t, act, arg, cls) => `<div class="btn ${cls || ''}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px" data-act="${act}" data-a="${arg == null ? '' : arg}" ${cls && cls.includes('fx') ? `data-f="${cls.split(' ').find(c => c.startsWith('fx:')).slice(3)}" data-a2="${arg}"` : ''}>${t}</div>`;
  const GRP = (x, y, w, h) => `<div class="grp" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px"></div>`;
  const HD = (x, y, w, t) => `<div class="hd" style="left:${x}px;top:${y}px;width:${w}px">${t}</div>`;
  const SEP = (x, y, w) => `<div class="sep" style="left:${x}px;top:${y}px;width:${w}px"></div>`;
  const ROW = (lx, fx, ux, w, y, label, tag, dec, unit) => LB(lx, y, label) + F(fx, y, w, tag, dec) + UN(ux, y, unit);
  const BAR = (x, y, w, h, tag, max, ticks, mark) => { let t = ''; for (let i = 1; i < ticks; i++) t += `<div class="tk" style="bottom:${i / ticks * 100}%"></div>`; return `<div class="bar" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px" data-bt="${tag}" data-max="${max}">${t}<div class="fill"></div>${mark != null ? `<div class="mk" style="bottom:${mark / max * 100}%"></div>` : ''}</div>`; };
  const SCL = (x, y, h, max, n, dec) => { let s = ''; for (let i = 0; i <= n; i++) { const v = max * i / n; s += `<div class="scl" style="left:${x}px;width:44px;top:${y + h - (i / n) * h - 8}px">${dec ? v.toFixed(dec) : v}</div>`; } return s; };
  const MIM = (x, y, n) => {
    let c = ''; for (let k = 0; k < 8; k++) c += `<rect class="ec" x="25" y="${44 + k * 10}" width="10" height="8"/><rect class="ec" x="65" y="${44 + k * 10}" width="10" height="8"/>`;
    return `<svg class="mim" style="left:${x}px;top:${y}px" width="100" height="270" viewBox="0 0 100 270" data-f="mim" data-a="${n}">
      <rect x="20" y="12" width="60" height="118" fill="#cfcfe0" stroke="#333" stroke-width="2"/><rect x="24" y="16" width="30" height="22" fill="#fff" stroke="#333"/>
      <text x="30" y="33" font-size="17" font-weight="700" font-family="Arial">${n}</text><rect class="ec" x="40" y="19" width="12" height="14" stroke="#333"/>
      ${c}<rect x="38" y="44" width="24" height="80" fill="#e8e8f4" stroke="#333"/><rect class="ec" x="14" y="134" width="72" height="16" stroke="#333" stroke-width="2"/>
      <rect x="44" y="150" width="12" height="72" fill="#fff" stroke="#333"/><ellipse cx="34" cy="238" rx="19" ry="8" fill="#fff" stroke="#333"/><ellipse cx="66" cy="238" rx="19" ry="8" fill="#fff" stroke="#333"/><ellipse cx="50" cy="234" rx="7" ry="9" fill="#fff" stroke="#333"/></svg>`;
  };

  // ------------------------------------------------------------ dynamic value functions
  const setc = (e, t, c) => { if (e.textContent !== t) e.textContent = t; const k = 'fld st ' + c; if (e.className !== k) e.className = k; };
  const FNS = {
    clutch: (e, a) => { const l = L_(+a); setc(e, !l.clutch ? 'Neutral' : (l.lever < 0 ? 'Astern' : 'Ahead'), ''); },
    ctlmode: (e, a) => { const l = L_(+a); setc(e, l.local ? 'Local' : 'Remote', l.local ? 'yel' : 'grn'); },
    yn: (e, a) => { const [tag, good] = a.split('|'); const s = S(tag), v = s ? M.value(s) : null, yes = v === 1; const bad = v == null || Number.isNaN(v); setc(e, bad ? '???' : (yes ? 'Yes' : 'No'), !bad && good !== 'x' && yes === (good === '1') ? 'grn' : ''); },
    startstop: (e, a) => setc(e, L_(+a).eng === 'CRANK' ? 'Yes' : 'No', ''),
    mim: (e, a) => { const l = L_(+a), on = l.eng === 'RUN' || l.eng === 'CRANK' ? (l.clutch ? 'g' : 'o') : 'x'; if (e.dataset.on === on) return; e.dataset.on = on; e.querySelectorAll('.ec').forEach(r => r.setAttribute('fill', on === 'x' ? '#d9d9d9' : '#2fc42f')); },
    ctl: (e, a) => { const l = L_(+a); e.classList.toggle('on', !!l.ctl); },
    needctl: (e, a) => { const l = L_(+a); e.classList.toggle('dis', !l.ctl); },
    msg: (e, a) => { const t = MSG[a] || ''; if (e.textContent !== t) e.textContent = t; },
    fuel: (e, a) => { const [k, i] = a.split('|'); const l = L_(+i), cv = FUEL.gal ? 0.264172 : 1; let v = 0, d = 0;
      if (k === 'actual') v = l.fuelNow || 0; else if (k === 'mean') v = l.tripH > 0.01 ? l.fuelTrip / l.tripH : 0; else if (k === 'trip') v = l.fuelTrip; else if (k === 'total') v = l.fuelTot; else if (k === 'dur') { v = l.tripH; cv === 1; }
      const tx = k === 'dur' ? Math.round(v).toString() : Math.round(v * cv).toString(); if (e.textContent !== tx) e.textContent = tx; e.className = 'fld'; },
    fuelunit: (e, a) => { const t = a === 'rate' ? (FUEL.gal ? 'gal/h' : 'l/h') : (FUEL.gal ? 'gal' : 'l'); if (e.textContent !== t) e.textContent = t; },
    devstate: (e, a) => { const [id, pbn] = a.split('|'); const bad = M.st.devFail[id] || M.st.pbnFail[+pbn]; const k = 'dev' + (bad ? ' fail' : ''); if (e.className !== k) e.className = k; },
    nodestate: (e, a) => { const bad = M.st.pbnFail[+a]; const k = 'pps' + (bad ? ' fail' : ''); if (e.className !== k) e.className = k; if (bad) e.style.background = '#ff3a2a'; else e.style.background = ''; },
  };
  function bind(root, list) {
    root.querySelectorAll('[data-t]').forEach(e => {
      const s = S(e.dataset.t); if (!s) { e.textContent = '--'; return; }
      const d = e.dataset.d; list.push(() => { const v = M.value(s), tx = fv(v, s, d), k = 'fld ' + (e.dataset.c || '') + ' ' + status(s, v); if (e.textContent !== tx) e.textContent = tx; if (e.className !== k) e.className = k; });
    });
    root.querySelectorAll('[data-bt]').forEach(e => {
      const s = S(e.dataset.bt); if (!s) return; const f = e.querySelector('.fill'), max = +e.dataset.max;
      list.push(() => { const v = M.value(s); f.style.height = (Number.isNaN(v) ? 0 : Math.max(0, Math.min(100, v / max * 100))) + '%'; });
    });
    root.querySelectorAll('[data-f]').forEach(e => { const fn = FNS[e.dataset.f]; if (fn) { const a = e.dataset.a; list.push(() => fn(e, a)); } });
  }

  // ------------------------------------------------------------ actions
  const ACT = {
    go: a => go(a),
    start: a => { MSG[a] = M.cmd.start(+a); },
    stop: a => { MSG[a] = M.cmd.stop(+a); },
    ctl: a => { const l = L_(+a); l.ctl = !l.ctl; MSG[a] = l.ctl ? 'Control taken' : ''; },
    fuelopen: () => fuelPopup(),
    fuelx: () => closePop(),
    fuelprev: () => { FUEL.line = FUEL.line === 1 ? 2 : 1; fuelPopup(); },
    fuelreset: () => { const l = L_(FUEL.line); l.fuelTrip = 0; l.tripH = 0; },
    fuelgal: () => { FUEL.gal = !FUEL.gal; },
    texh: a => go('texh' + a),
    legend: () => infoPop('Legend', `<p><span class="pill" style="background:#bdbdd6">grey</span> unit communicating</p><p><span class="pill fail">red</span> unit / bus failed</p><p><span class="pill warn">yellow</span> warning</p><p>Group buttons (top): square shows number of active alarms in the group; it blinks while unacknowledged.</p><p>Value fields turn yellow (warning) or red (shutdown limit); <b>???</b> = no valid value from the bus.</p>`),
    chlist: () => infoPop('Channel List', '<table><tr><th>PBN</th><th>Node</th><th>Device</th><th>Blocks</th><th>Loaded</th></tr>' + M.data.nodes.map(n => `<tr><td>${n.pbn}</td><td>${esc(n.name)}</td><td>${esc(n.id)}</td><td>${n.blocks ?? ''}</td><td>${esc(n.date || '')}</td></tr>`).join('') + '</table>'),
    alsel: a => { AL.sel = a; renderAlarmRows(true); },
    almenu: () => { AL.menu = !AL.menu; const m = pageEl.querySelector('#almenu'); if (m) m.hidden = !AL.menu; },
    ackall: () => { M.cmd.ackAll(null); AL.menu = false; },
    acksel: () => { if (AL.sel) M.cmd.ack(AL.sel); AL.menu = false; },
    horn: () => { if (window.toggleHorn) window.toggleHorn(); },
    ackbtn: () => { if (AL.sel && M.st.alarms.has(AL.sel)) M.cmd.ack(AL.sel); else M.cmd.ackAll(null); },
    topx: () => { topBtns = true; buildTop(); },
    cyc: a => { const o = { view: ['Actual', 'Historical'], grp: ['Combined', 'Prop', 'EPower', 'Tanks', 'Doors', 'Bilges', 'Fire', 'System'], pri: ['All', 'Red', 'Yel'], state: ['All', 'Ack', 'UnAck'] }[a]; AL[a] = o[(o.indexOf(AL[a]) + 1) % o.length]; renderAlarmRows(true); pageEl.querySelectorAll('[data-cyc]').forEach(e => e.textContent = AL[e.dataset.cyc]); },
  };

  // ------------------------------------------------------------ navigation tree (as on the real display: groups > pages)
  const PAGES = {
    overview: { label: 'Overview', parent: null }, propulsion: { label: 'Propulsion', parent: 'overview' }, lan: { label: 'LAN', parent: 'overview' },
    engine1: { label: 'Engine 1', parent: 'propulsion' }, engine2: { label: 'Engine 2', parent: 'propulsion' },
    texh1: { label: 'T-Exhaust 1', parent: 'engine1' }, texh2: { label: 'T-Exhaust 2', parent: 'engine2' },
    lanfb11: { label: 'LAN FB 11-12', parent: 'lan' }, lanfb13: { label: 'LAN FB 13-20', parent: 'lan' },
    alarm: { label: 'Alarm Page', parent: 'overview', notab: true },
  };
  const sibs = id => Object.keys(PAGES).filter(k => PAGES[k].parent === PAGES[id].parent && !PAGES[k].notab);
  const kids = id => Object.keys(PAGES).filter(k => PAGES[k].parent === id && !PAGES[k].notab);
  const chain = id => { const c = []; for (let p = id; p; p = PAGES[p].parent) c.unshift(p); return c; };

  // ------------------------------------------------------------ pages
  function pgPropulsion() {
    let h = '';
    const col = (ox, l) => {
      h += GRP(ox, 8, 378, 414);
      [['Engine<br>Speed', E(l, '0002'), 3000, 4, 2300, 0, 'rpm'], ['Injection<br>Quantity', E(l, '0205'), 100, 4, null, 0, '%'], ['P-Charge<br>Air (a)', E(l, '0049'), 5, 4, null, 2, 'bar']].forEach((b, k) => {
        const bx = ox + 64 + k * 118;
        h += `<div class="lbl c" style="left:${bx - 42}px;top:18px;width:132px;font-weight:600">${b[0]}</div>` + SCL(bx - 52, 62, 170, b[2], b[3], b[5]) + BAR(bx, 62, 46, 170, b[1], b[2], b[3], b[4]) + F(bx - 24, 242, 76, b[1], b[5]) + UN(bx + 56, 242, b[6]);
      });
      h += ROW(ox + 14, ox + 250, ox + 328, 72, 286, 'P-Lube Oil', E(l, '0026'), 1, 'bar') + ROW(ox + 14, ox + 250, ox + 328, 72, 320, 'T-Coolant', E(l, '0126'), 1, '°C') + ROW(ox + 14, ox + 250, ox + 328, 72, 354, 'T-Exhaust Mean', E(l, '2221'), 0, '°C') + LB(ox + 14, 388, 'Control Mode Engine') + FN(ox + 250, 388, 96, 'ctlmode', l);
    };
    col(6, 2); col(640, 1);
    [[2, 404], [1, 520]].forEach(([l, mx]) => { h += MIM(mx, 14, l) + LB(mx + 4, 292, 'Clutch Pos', 'b') + FN(mx - 4, 316, 84, 'clutch', l) + `<div class="fld" style="left:${mx + 84}px;top:316px;width:24px">–</div>` + LB(mx + 4, 352, 'Shaft Speed', 'b') + F(mx - 4, 376, 72, G(l, '2013'), 0) + UN(mx + 72, 376, 'rpm'); });
    h += BTN(222, 450, 170, 38, 'Engine 2', 'go', 'engine2') + `<div class="ind" style="left:190px;top:456px"></div>` + BTN(660, 450, 170, 38, 'Engine 1', 'go', 'engine1') + `<div class="ind" style="left:628px;top:456px"></div>`;
    return h;
  }

  function pgEngine(l) {
    let h = '<div class="lbl b" style="left:20px;top:6px;font-size:17px">16V 4000 M90</div>' + MIM(40, 30, l);
    h += LB(48, 306, 'Clutch Pos', 'b') + FN(26, 330, 110, 'clutch', l) + `<div class="fld" style="left:140px;top:330px;width:24px">–</div>` + LB(48, 366, 'Shaft Speed', 'b') + F(36, 390, 86, G(l, '2013'), 0) + UN(128, 390, 'rpm');
    h += BTN(20, 446, 140, 38, 'T-Exhaust ' + l, 'texh', l);
    // ECU column 1
    h += GRP(190, 14, 272, 478) + HD(190, 18, 272, 'ECU');
    const r1 = [['Engine Speed', E(l, '0002'), 0, 'rpm'], ['ETC 1 Speed', E(l, '0057'), 1, 'krpm'], ['ETC 2 Speed', E(l, '0059'), 1, 'krpm'], ['Injection Quantity', E(l, '0205'), 0, '%']];
    r1.forEach((r, k) => h += ROW(200, 360, 436, 70, 48 + k * 29, r[0], r[1], r[2], r[3]));
    h += LB(200, 164, 'Injection Limitation') + FN(360, 164, 70, 'yn', E(l, '0008') + '|0') + LB(200, 193, 'Override') + FN(360, 193, 70, 'yn', E(l, '4470') + '|x');
    h += SEP(196, 226, 260);
    [['P-Lube Oil', E(l, '0026'), 1, 'bar'], ['P-Fuel', E(l, '0046'), 1, 'bar'], ['P-Coolant', E(l, '0036'), 1, 'bar'], ['P-Charge Air (a)', E(l, '0049'), 2, 'bar'], ['P-Crankcase', E(l, '0031'), 0, 'mbar']].forEach((r, k) => h += ROW(200, 360, 436, 70, 236 + k * 29, r[0], r[1], r[2], r[3]));
    h += SEP(196, 386, 260);
    [['Operating Hours', E(l, '0115'), 0, 'h'], ['ECU Voltage', E(l, '0104'), 1, 'V'], ['T-ECU', E(l, '0169'), 1, '°C']].forEach((r, k) => h += ROW(200, 360, 436, 70, 394 + k * 29, r[0], r[1], r[2], r[3]));
    // ECU column 2
    h += GRP(470, 14, 272, 478) + HD(470, 18, 272, 'ECU');
    [['T-Lube Oil', E(l, '0140'), 1, '°C'], ['T-Fuel', E(l, '0152'), 1, '°C'], ['T-Coolant', E(l, '0126'), 1, '°C'], ['T-Charge Air', E(l, '0131'), 1, '°C'], ['T-Charge Air Seq', E(l, '0258'), 0, '°C'], ['T-Intake Air', E(l, '0151'), 1, '°C'], ['T-Exh Comb A', E(l, '0145'), 0, '°C'], ['T-Exh Comb B', E(l, '0148'), 0, '°C']].forEach((r, k) => h += ROW(480, 640, 716, 70, 48 + k * 29, r[0], r[1], r[2], r[3]));
    h += SEP(476, 284, 260) + HD(470, 288, 272, 'Start / Stop');
    h += LB(480, 316, 'Control Mode') + FN(640, 316, 92, 'ctlmode', l) + LB(480, 343, 'Ready for Operation') + FN(640, 343, 92, 'yn', E(l, '4250') + '|1') + LB(480, 370, 'Start Interlock') + FN(640, 370, 92, 'yn', E(l, '4398') + '|0') + LB(480, 397, 'Start / Stop') + FN(640, 397, 92, 'startstop', l);
    h += BTN(490, 428, 112, 30, 'Start', 'start', l, 'fx:needctl') + BTN(612, 428, 112, 30, 'Stop', 'stop', l, 'fx:needctl') + BTN(520, 464, 164, 28, 'Control', 'ctl', l, 'fx:ctl');
    h += `<div class="lbl" style="left:480px;top:496px;font-size:12px;width:250px;height:14px;overflow:hidden" data-f="msg" data-a="${l}"></div>`;
    // EMU / GCU / GMU
    h += GRP(750, 14, 266, 478) + HD(750, 18, 266, 'EMU');
    [['Engine Speed', E(l, '2002'), 0, 'rpm'], ['P-Lube Oil', E(l, '2026'), 1, 'bar'], ['P-Raw Water', E(l, '2501'), 1, 'bar'], ['T-Coolant', E(l, '2126'), 1, '°C']].forEach((r, k) => h += ROW(760, 892, 968, 70, 48 + k * 29, r[0], r[1], r[2], r[3]));
    h += HD(750, 168, 266, 'GCU');
    [['P-Gear Control Oil', G(l, '0001'), 1, 'bar'], ['P-Gear Lube Oil', G(l, '0006'), 1, 'bar'], ['T-Gear Oil', G(l, '0011'), 1, '°C']].forEach((r, k) => h += ROW(760, 892, 968, 70, 196 + k * 29, r[0], r[1], r[2], r[3]));
    h += HD(750, 296, 266, 'GMU') + ROW(760, 892, 968, 70, 324, 'Shaft Speed', G(l, '2013'), 0, 'rpm');
    h += `<div class="lbl c" style="left:760px;top:392px;width:246px;color:#8a8aa6">Extension</div>` + GRP(760, 414, 246, 66);
    return h;
  }

  function pgTexh(l) {
    let h = `<div class="lbl b" style="left:20px;top:8px;font-size:18px">Engine ${l} - Exhaust gas temperatures</div>`;
    ['A', 'B'].forEach((b, bi) => {
      const y0 = 56 + bi * 190;
      h += GRP(10, y0 - 12, 1004, 178) + LB(24, y0 - 8, 'Bank ' + b, 'b');
      for (let c = 1; c <= 10; c++) { const bx = 130 + (c - 1) * 86, tag = E(l, String(2200 + (bi ? 10 : 0) + c)); h += `<div class="lbl c" style="left:${bx - 8}px;top:${y0 - 8}px;width:60px">${b}${c}</div>` + BAR(bx, y0 + 14, 44, 100, tag, 800, 8, 680) + F(bx - 6, y0 + 118, 56, tag, 0); }
      h += SCL(76, y0 + 14, 100, 800, 4, 0).replace(/width:44px/g, 'width:44px');
    });
    h += ROW(40, 170, 232, 60, 440, 'Mean', E(l, '2221'), 0, '°C') + ROW(300, 430, 492, 60, 440, 'Max', E(l, '2222'), 0, '°C') + ROW(560, 690, 752, 60, 440, 'Min', E(l, '2223'), 0, '°C');
    return h;
  }

  function pgOverview() {
    let h = GRP(8, 8, 500, 300) + HD(8, 12, 500, 'Propulsion');
    [[2, 24], [1, 270]].forEach(([l, x]) => {
      h += `<div class="lbl b" style="left:${x}px;top:46px">Engine ${l}</div>` + ROW(x, x + 120, x + 190, 64, 76, 'Speed', E(l, '0002'), 0, 'rpm') + ROW(x, x + 120, x + 190, 64, 106, 'Shaft', G(l, '2013'), 0, 'rpm') + ROW(x, x + 120, x + 190, 64, 136, 'P-Lube', E(l, '0026'), 1, 'bar') + ROW(x, x + 120, x + 190, 64, 166, 'T-Coolant', E(l, '0126'), 1, '°C') + LB(x, 200, 'Clutch') + FN(x + 90, 200, 96, 'clutch', l) + LB(x, 232, 'Mode') + FN(x + 90, 232, 96, 'ctlmode', l);
    });
    h += BTN(180, 262, 170, 36, 'Propulsion', 'go', 'propulsion');
    h += GRP(520, 8, 496, 300) + HD(520, 12, 496, 'Electric power');
    for (let i = 1; i <= 3; i++) h += LB(540, 44 + (i - 1) * 40, 'Genset ' + i, 'b') + ROW(640, 770, 840, 70, 44 + (i - 1) * 40, 'Speed', `PV4${i}0004`, 0, 'rpm');
    h += GRP(8, 318, 1008, 180) + HD(8, 322, 1008, 'Tanks');
    (M.data.tanks || []).forEach((t, k) => { const x = 24 + (k % 3) * 330, y = 354 + Math.floor(k / 3) * 70; h += LB(x, y, esc(t.name)) + F(x, y + 28, 90, t.tag, 1) + UN(x + 96, y + 28, 'm³'); });
    return h;
  }

  function lanDev(x, y, lbl, sub, id, idTxt, pbn) { return `<div class="dev" style="left:${x}px;top:${y}px" data-f="devstate" data-a="${id}|${pbn}"><span class="id">${idTxt}</span>${lbl}<small>${sub}</small></div>`; }
  function pgLanFB11() {
    let h = '';
    const side = (ox, pbn, pps, ppsId, sys, pims) => {
      h += `<div class="pps" style="left:${ox + 135}px;top:30px" data-f="nodestate" data-a="${pbn}"><small>${ppsId}</small>PPS ${pbn}</div>`;
      const dv = [['LCU', '-A010', `=${sys}+A001`, `=${sys}+A001-A010`, 78], ['DIS', '-A001', `=${sys}+A001`, `=${sys}+A001-A001`, 138], ['GCU', '-A030', `=${sys}+A002`, `=${sys}+A002-A030`, 198], ['EMU', '-EMU', `=${sys}+A004`, `=${sys}+A004-EMU`, 286], ['SAM', '-MEU', `=${sys}+A012`, `=${sys}+A012-MEU`, 346], ['ECU', '-ECU', `=${sys}+A003`, `=${sys}+A003-ECU`, 406]];
      h += `<div class="ln" style="left:${ox + 100}px;top:56px;width:3px;height:${406 + 30 - 56}px"></div><div class="ln" style="left:${ox + 103}px;top:56px;width:34px;height:3px"></div><div class="ln c" style="left:${ox + 104}px;top:60px;width:3px;height:${406 + 26 - 60}px"></div>`;
      dv.forEach(d => { h += `<div class="ln" style="left:${ox + 80}px;top:${d[4] + 28}px;width:22px;height:3px"></div>` + lanDev(ox + 4, d[4], d[0], d[1], d[3], d[2], pbn); });
      const rx = ox + 262; h += `<div class="ln" style="left:${rx - 24}px;top:56px;width:3px;height:${pims.length * 70 + 24}px"></div><div class="ln" style="left:${ox + 232}px;top:56px;width:${rx - 24 - ox - 232}px;height:3px"></div>`;
      pims.forEach((p, k) => { const y = 80 + k * 70; h += `<div class="ln" style="left:${rx - 24}px;top:${y + 28}px;width:24px;height:3px"></div>` + lanDev(rx, y, 'PIM', p[1], p[0], p[2], pbn); });
    };
    side(0, 11, 'PPS 11', '=371+A301-A500', 211, [['=371+A301-A110', '-A110', '=371+A301'], ['=371+A301-A117', '-A117', '=371+A301'], ['=371+A301-A127', '-A127', '=371+A301'], ['=371+A302-A120', '-A120', '=371+A302']]);
    side(500, 12, 'PPS 12', '=371+A301-A510', 212, [['=372+A301-A117', '-A117', '=372+A301'], ['=372+A301-A127', '-A127', '=372+A301']]);
    return h;
  }
  function pgLanFB13() {
    let h = '';
    const nm = id => /-ECU$/.test(id) ? ['ECU', 'ECU'] : /-EMU$/.test(id) ? ['EMU', 'EMU'] : /-MEU$/.test(id) ? ['SAM', 'MEU'] : /-CAN$/.test(id) ? ['CAN', 'CAN'] : /\+A001-A010$/.test(id) ? ['LCU', 'A010'] : /\+A001-A001$/.test(id) ? ['DIS', 'A001'] : /\+A002-A030$/.test(id) ? ['GCU', 'A030'] : ['PIM', id.split('-').pop()];
    [[13, '=373-A500', 14], [20, '=360-A530', 540]].forEach(([pbn, bus, ox]) => {
      const b = M.data.buses.find(x => x.name === bus), idp = (M.data.nodes.find(n => n.pbn === pbn) || {}).id || '';
      h += `<div class="pps" style="left:${ox + 190}px;top:30px" data-f="nodestate" data-a="${pbn}"><small>${idp}</small>PPS ${pbn}</div>`;
      (b ? b.members.filter(m => m.id !== idp) : []).forEach((m, k) => { const x = ox + (k % 5) * 96, y = 98 + Math.floor(k / 5) * 74, n = nm(m.id);
        h += lanDev(x, y, n[0], n[1], m.id, m.id.split('-')[0], pbn); });
    });
    return h;
  }
  function pgLan() {
    let h = GRP(8, 8, 1008, 490) + HD(8, 14, 1008, 'Process bus (CAN) - controllers and field bus converters');
    h += '<div class="ln" style="left:40px;top:130px;width:944px;height:4px"></div>';
    M.data.nodes.forEach((n, k) => { const x = 40 + k * 106, act = n.type === 'PPS' ? (n.pbn <= 12 ? 'lanfb11' : 'lanfb13') : ''; h += `<div class="ln" style="left:${x + 48}px;top:134px;width:3px;height:30px"></div><div class="pps" style="left:${x}px;top:164px;width:100px;height:64px;cursor:pointer" data-f="nodestate" data-a="${n.pbn}" ${act ? `data-act="go" data-a2="${act}"` : ''}><small>${n.id}</small>${esc(n.name)}</div>`; });
    h += LB(40, 260, 'Tap a PPS to open its field-bus page. Failed nodes turn red.');
    return h;
  }

  // ------------------------------------------------------------ alarm page
  let alKey = '';
  function alarmRows() {
    const st = M.st, okP = r => AL.pri === 'All' || (AL.pri === 'Red' ? r : !r);
    if (AL.view === 'Actual') {
      return [...st.alarms.values()].filter(a => (AL.grp === 'Combined' || a.s.top === AL.grp) && okP(a.s.red) && (AL.state === 'All' || (AL.state === 'Ack' ? a.ack : !a.ack))).sort((x, y) => y.seq - x.seq)
        .map(a => ({ t: a.t, state: a.ack ? (a.active ? 'ACK' : 'GONE') : (a.active ? 'UNACK' : 'GONE'), red: a.s.red, text: MODEL.stripPrio(a.s.n), tag: a.s.tag, grp: a.s.g, un: !a.ack, gone: !a.active }));
    }
    return st.hist.filter(h => okP(h.red)).slice(0, 200).map(h => ({ t: h.t, state: h.ev, red: h.red, text: MODEL.stripPrio(h.n), tag: h.tag, grp: h.g, un: false, gone: h.ev === 'GONE' }));
  }
  function renderAlarmRows(force) {
    const body = pageEl.querySelector('#alrows'); if (!body) return;
    const rows = alarmRows(), key = rows.map(r => r.tag + r.state + r.t).join('|') + AL.sel + AL.view;
    const cnt = pageEl.querySelector('#alcount'); if (cnt && cnt.textContent !== String(rows.length)) cnt.textContent = rows.length;
    if (!force && key === alKey) return; alKey = key;
    body.innerHTML = rows.slice(0, 15).map((r, k) => { const s = stamp(r.t); return `<div class="al-row ${r.red ? 'red' : 'yel'} ${r.un ? 'un' : ''} ${r.gone ? 'gone' : ''} ${AL.sel === r.tag ? 'sel' : ''}" style="top:${k * 26}px" data-act="alsel" data-a="${r.tag}"><span style="left:6px">${s.short}</span><span style="left:106px">${s.time}</span><span class="st" style="left:222px">${r.state}</span><span style="left:332px">${r.red ? 'Red' : 'Yel'}</span><span style="left:396px;width:320px">${esc(r.text)}</span><span style="left:722px">${r.tag}</span><span style="left:856px;width:150px">${esc(r.grp)}</span></div>`; }).join('');
  }
  function pgAlarm() {
    const Y = 500, cy = (x, y, w, k) => `<div class="fld st" style="left:${x}px;top:${y}px;width:${w}px;height:24px;line-height:20px;cursor:pointer;background:#bcbcd6" data-act="cyc" data-a="${k}" data-cyc="${k}">${AL[k]}</div>`;
    let h = '<div class="a al-h">Alarm Page</div><div class="a al-th">' + [['Date', 12], ['Time', 112], ['State', 228], ['Prio', 338], ['Measuring Point', 480], ['Tagname', 728], ['Alarm Group', 862]].map(c => `<span style="left:${c[1]}px">${c[0]}</span>`).join('') + '</div><div class="a al-body" id="alrows"></div>';
    h += `<div class="tb" style="left:8px;top:${Y}px;width:46px;height:34px;font-size:20px" data-act="horn">🔊</div><div class="tb" style="left:8px;top:${Y + 40}px;width:46px;height:34px;font-size:20px" data-act="ackbtn">⊖</div>`;
    h += BTN(66, Y, 128, 40, 'Alarm<br>Control', 'almenu') + BTN(66, Y + 46, 128, 26, 'Exit', 'go', 'propulsion');
    h += `<div class="pop" id="almenu" style="left:66px;top:${Y - 150}px;width:210px;height:130px;padding:6px;z-index:25" hidden>${['Acknowledge selected|acksel', 'Acknowledge all|ackall', 'Close|almenu'].map(t => { const [a, b] = t.split('|'); return `<div class="btn" style="position:relative;height:32px;margin:3px 0" data-act="${b}">${a}</div>`; }).join('')}</div>`;
    h += LB(212, Y, 'Alarm Page:') + `<div class="fld st grn" style="left:376px;top:${Y}px;width:128px;height:24px;line-height:20px">No Filter</div>` + LB(212, Y + 29, 'Alarm View:') + cy(376, Y + 29, 128, 'view') + LB(212, Y + 58, 'Number of Alarms:') + `<div class="fld st" style="left:376px;top:${Y + 58}px;width:128px;height:24px;line-height:20px" id="alcount">0</div>`;
    h += LB(560, Y, 'Alarm State:') + cy(704, Y, 128, 'state') + LB(560, Y + 29, 'Alarm Prio:') + cy(704, Y + 29, 128, 'pri') + LB(560, Y + 58, 'Alarm Group:') + cy(704, Y + 58, 150, 'grp');
    return h;
  }

  // ------------------------------------------------------------ popups
  function closePop() { pups = []; popEl.innerHTML = ''; popEl.hidden = true; }
  function infoPop(title, body) {
    pups = []; popEl.hidden = false; popEl.innerHTML = `<div class="pop" style="left:180px;top:200px;width:660px;height:420px;padding:10px;overflow:auto"><div class="hd" style="position:relative;margin-bottom:8px">${title}</div>${body}<div class="btn" style="position:absolute;left:250px;bottom:10px;width:160px;height:32px" data-act="fuelx">Exit</div></div>`;
  }
  function fuelPopup() {
    const l = FUEL.line; pups = []; popEl.hidden = false;
    let h = `<div class="pop" style="left:300px;top:196px;width:440px;height:494px"><div class="hd" style="top:12px;width:440px">Fuel Consumption</div>`;
    h += BTN(24, 48, 40, 34, '◀', 'fuelprev') + `<div class="fld st" style="left:80px;top:48px;width:276px;height:34px;line-height:30px;font-size:19px">Engine ${l}</div>` + BTN(372, 48, 40, 34, '▶', 'fuelprev');
    h += GRP(34, 100, 130, 256) + HD(34, 104, 130, 'Actual') + SCL(36, 134, 150, 700, 1, 0).replace(/>700</, '>700<') + `<div class="unit" style="left:44px;top:200px">l/h</div>` + BAR(100, 134, 44, 150, E(l, '0119'), 700, 7) + `<div class="fld" style="left:50px;top:300px;width:70px" data-f="fuel" data-a="actual|${l}"></div><div class="unit" style="left:124px;top:303px" data-f="fuelunit" data-a="rate"></div>`;
  	h += GRP(270, 100, 130, 256) + HD(270, 104, 130, 'Mean Trip') + SCL(272, 134, 150, 700, 1, 0) + `<div class="unit" style="left:280px;top:200px">l/h</div>` + BAR(336, 134, 44, 150, E(l, '0198'), 700, 7) + `<div class="fld" style="left:284px;top:300px;width:70px" data-f="fuel" data-a="mean|${l}"></div><div class="unit" style="left:358px;top:303px" data-f="fuelunit" data-a="rate"></div>`;
    [['Trip Duration', 'dur', 372], ['Trip Consump', 'trip', 402], ['Total Consump', 'total', 432]].forEach(([t, k, y]) => { h += LB(30, y, t) + `<div class="fld" style="left:240px;top:${y}px;width:120px" data-f="fuel" data-a="${k}|${l}"></div>` + (k === 'dur' ? UN(368, y, 'h') : `<div class="unit" style="left:368px;top:${y + 3}px" data-f="fuelunit" data-a="vol"></div>`); });
    h += BTN(24, 460, 140, 28, 'Reset', 'fuelreset') + BTN(180, 460, 120, 28, 'Gallon', 'fuelgal') + BTN(316, 460, 100, 28, 'Exit', 'fuelx') + '</div>';
    popEl.innerHTML = h; bind(popEl, pups);
  }

  // ------------------------------------------------------------ chrome
  function buildTop() {
    const bar = (n, red) => ''; void bar;
    let h = '';
    if (!topBtns && ['propulsion', 'engine1', 'engine2', 'texh1', 'texh2'].includes(cur)) {
      h += '<div class="grid" id="alline"></div>';
      h += `<div class="fld st" style="left:934px;top:4px;width:60px;height:24px;line-height:20px" id="alnum">0</div><div class="tb" style="left:934px;top:32px;width:60px;height:50px;font-size:20px" data-act="ackbtn">⊖</div><div class="btn sm" style="left:942px;top:86px;width:44px;height:20px;font-size:12px" data-act="topx">Exit</div>`;
    } else {
      h += `<div class="ind" id="indP" style="left:262px;top:38px"></div>` + BTN(298, 34, 190, 36, 'Propulsion', 'go', 'propulsion') + `<div class="ind" id="indL" style="left:688px;top:38px"></div>` + BTN(724, 34, 190, 36, 'LAN', 'go', 'lan');
    }
    topEl.innerHTML = h;
  }
  function buildTabs() {
    const p = PAGES[cur]; if (p.notab) { tabsEl.innerHTML = ''; return; }
    let h = ''; chain(cur).forEach((id, k, a) => { h += `<div class="tab ${id === cur ? 'act' : ''}" style="left:${10 + k * 196}px;width:190px" data-act="go" data-a="${id}">${PAGES[id].label}</div>`; });
    if (cur === 'propulsion') h += BTN(842, 4, 172, 34, 'Fuel Consump', 'fuelopen', '', 'sm');
    if (cur.startsWith('lanfb')) h += BTN(722, 4, 144, 34, 'Channel List', 'chlist', '', 'sm') + BTN(872, 4, 142, 34, 'Legend', 'legend', '', 'sm');
    tabsEl.innerHTML = h;
  }
  function buildBar() {
    const sb = sibs(cur), i = sb.indexOf(cur), prev = i > 0 ? sb[i - 1] : null, next = i >= 0 && i < sb.length - 1 ? sb[i + 1] : null, up = PAGES[cur].parent, down = kids(cur)[0];
    const tb = (x, w, t, act, a, dis) => `<div class="tb ${dis ? 'dis' : ''}" style="left:${x}px;width:${w}px;${dis ? 'opacity:.45;pointer-events:none;' : ''}" data-act="${act}" data-a="${a || ''}">${t}</div>`;
    barEl.innerHTML = tb(10, 56, '🔊', 'horn') + tb(72, 56, '⊖', 'ackbtn') + tb(140, 116, '⚠', 'go', 'alarm') + tb(262, 116, '〰', 'go', cur, true) + tb(396, 50, '▲', 'go', up, !up) + tb(450, 50, '▼', 'go', down, !down) + tb(504, 50, '◀', 'go', prev, !prev)
      + `<div class="tbf" style="left:560px;width:150px">${prev ? PAGES[prev].label : ''}</div><div class="tbf" style="left:716px;width:150px">${next ? PAGES[next].label : ''}</div>` + tb(872, 50, '▶', 'go', next, !next) + tb(930, 44, '?', 'legend') + tb(978, 40, '⚙', 'chlist');
    barEl.querySelectorAll('.tb').forEach(e => { if (e.dataset.act === 'go' && e.dataset.a === '') { e.style.opacity = .45; e.style.pointerEvents = 'none'; } });
  }

  function go(id) {
    if (!PAGES[id]) return; cur = id; topBtns = false; ups = []; closePop(); AL.menu = false; alKey = '';
    const pages = { overview: pgOverview, propulsion: pgPropulsion, engine1: () => pgEngine(1), engine2: () => pgEngine(2), texh1: () => pgTexh(1), texh2: () => pgTexh(2), lan: pgLan, lanfb11: pgLanFB11, lanfb13: pgLanFB13, alarm: pgAlarm };
    const full = PAGES[id].notab;
    pageEl.style.top = full ? '142px' : '186px'; pageEl.style.height = full ? '626px' : '508px'; barEl.style.display = full ? 'none' : '';
    pageEl.innerHTML = pages[id](); bind(pageEl, ups); buildTop(); buildTabs(); buildBar();
    if (id === 'alarm') renderAlarmRows(true);
    refresh();
  }

  function refresh() {
    if (!M) return;
    for (const u of ups) { try { u(); } catch (e) { console.error(e); } }
    for (const u of pups) { try { u(); } catch (e) { console.error(e); } }
    const t = stamp(M.st.clock); stage.querySelector('.d').textContent = 'Date: ' + t.date; stage.querySelector('.t').textContent = 'Time: ' + t.time;
    // group indicators / alarm line
    const cnt = { Prop: [0, 0, 0], System: [0, 0, 0] }; let un = 0; const list = [];
    for (const a of M.st.alarms.values()) { if (!a.active && a.ack) continue; const c = cnt[a.s.top]; if (c) { c[0]++; if (a.s.red) c[1] = 1; if (!a.ack) c[2] = 1; } if (!a.ack) un++; list.push(a); }
    const ind = (id, c) => { const e = stage.querySelector(id); if (!e) return; const k = 'ind' + (c[0] ? (c[1] ? ' red' : ' yel') : '') + (c[2] ? ' blk' : ''); if (e.className !== k) e.className = k; e.textContent = c[0] || ''; };
    ind('#indP', cnt.Prop); ind('#indL', cnt.System);
    const line = stage.querySelector('#alline'); if (line) { const rows = list.sort((x, y) => y.seq - x.seq).slice(0, 4); const html = rows.map((a, k) => `<div style="position:absolute;left:8px;top:${k * 22 + 2}px;font:700 15px 'Courier New',monospace;color:${a.s.red ? '#ff3b2b' : '#ffe600'};text-shadow:0 0 1px #000">${stamp(a.t).short} ${stamp(a.t).time} ${a.ack ? 'ACK  ' : 'UNACK'} ${a.s.red ? 'Red' : 'Yel'} ${esc(MODEL.stripPrio(a.s.n))}</div>`).join(''); if (line.dataset.h !== html) { line.dataset.h = html; line.innerHTML = html; } }
    const n = stage.querySelector('#alnum'); if (n) n.textContent = un;
    if (cur === 'alarm') renderAlarmRows(false);
    if (helmEl && !helmEl.hidden) helmRefresh();
  }

  // ------------------------------------------------------------ helm panel (clutch control panels, lever, steering: separate hardware on the real console)
  const HELM = { };
  function helmBuild() {
    const panel = (l, side) => `<div style="flex:1;background:#2b2b30;color:#fff;border:2px solid #777;padding:10px;min-width:0">
      <div style="text-align:center;font-weight:700;margin-bottom:8px">CLUTCH CONTROL PANEL - ${side}<br><small>Engine ${l}</small></div>
      ${[['CLUTCH IN', 'in', '#2fe02f'], ['CLUTCH OUT', 'out', '#2fe02f'], ['BACK FLUSH', 'bf', '#2fe02f'], ['BRIDGE IN CONTROL', 'br', '#ffb020'], ['FLYBRIDGE IN CONTROL', 'fb', '#ffb020']].map(b => `<div class="hb" data-h="${b[1]}" data-l="${l}" style="margin:6px 0;padding:10px 6px;text-align:center;font-size:14px;font-weight:600;border:3px solid #999;background:#14301a;color:#cfe;cursor:pointer" data-c="${b[2]}">${b[0]}</div>`).join('')}
      <div style="margin-top:10px;font-size:13px">Speed lever <b data-lv="${l}">0</b> %</div><input type="range" min="-100" max="100" value="0" data-lever="${l}" style="width:100%">
      <div style="font-size:13px">Steering <b data-sv="${l}">0</b> %</div><input type="range" min="-100" max="100" value="0" data-steer="${l}" style="width:100%">
      <div style="margin-top:6px;font-size:13px">Shaft <b data-sh="${l}">0</b> rpm &nbsp; Engine <b data-en="${l}">0</b> rpm</div></div>`;
    helmEl.innerHTML = `<div style="position:absolute;inset:20px 40px;background:#1c1c20;border:3px solid #888;padding:12px;display:flex;flex-direction:column;gap:8px;overflow:auto">
      <div style="display:flex;gap:10px">${panel(2, 'PORT')}${panel(1, 'STBD')}</div>
      <div style="display:flex;gap:8px;align-items:center;color:#fff"><div class="btn" style="position:relative;width:200px;height:34px;background:#ff8080" data-hx="estop">EMERGENCY STOP</div><div class="btn" style="position:relative;width:160px;height:34px" data-hx="ovr">Safety override</div><div class="btn" style="position:relative;width:160px;height:34px" data-hx="close">Close</div><span id="helmmsg" style="font-weight:700;color:#ffd"></span></div></div>`;
    helmEl.onclick = e => {
      const hb = e.target.closest('[data-h]'), hx = e.target.closest('[data-hx]'); const say = r => { if (typeof r === 'string') helmEl.querySelector('#helmmsg').textContent = r; };
      if (hb) { const l = +hb.dataset.l, k = hb.dataset.h; if (k === 'in') say(M.cmd.clutch(l, true)); else if (k === 'out') say(M.cmd.clutch(l, false)); else if (k === 'bf') { L_(l).bf = M.st.clock + 10000; } else if (k === 'br') M.cmd.station('BRIDGE'); else if (k === 'fb') M.cmd.station('FLYBRIDGE'); }
      if (hx) { const k = hx.dataset.hx; if (k === 'estop') { M.cmd.estop(!M.st.estop); say(M.st.estop ? 'Emergency stop active' : 'Emergency stop reset'); } else if (k === 'ovr') M.cmd.override(!M.st.override); else helmEl.hidden = true; }
    };
    helmEl.oninput = e => { const lv = e.target.dataset.lever, sv = e.target.dataset.steer; if (lv) { M.cmd.lever(+lv, +e.target.value); helmEl.querySelector(`[data-lv="${lv}"]`).textContent = e.target.value; } if (sv) { M.cmd.steer(+sv, +e.target.value); helmEl.querySelector(`[data-sv="${sv}"]`).textContent = e.target.value; } };
  }
  function helmRefresh() {
    helmEl.querySelectorAll('.hb').forEach(b => { const l = +b.dataset.l, k = b.dataset.h, L = L_(l); const on = k === 'in' ? L.clutch : k === 'out' ? !L.clutch : k === 'bf' ? (L.bf || 0) > M.st.clock : k === 'br' ? M.st.station === 'BRIDGE' : M.st.station === 'FLYBRIDGE'; const bg = on ? b.dataset.c : '#14301a'; if (b.style.background !== bg) { b.style.background = bg; b.style.color = on ? '#000' : '#cfe'; } });
    [1, 2].forEach(l => { const L = L_(l); helmEl.querySelector(`[data-sh="${l}"]`).textContent = Math.round(L.shaft); helmEl.querySelector(`[data-en="${l}"]`).textContent = Math.round(L.n); });
  }

  // ------------------------------------------------------------ setup
  function fit() { const w = window.innerWidth, h = window.innerHeight, s = Math.min(w / 1024, h / 768); stage.style.transform = `scale(${s})`; stage.style.left = (w - 1024 * s) / 2 + 'px'; stage.style.top = (h - 768 * s) / 2 + 'px'; }
  function init(model) {
    M = model; M.st.nLines = 2;
    const root = document.getElementById('bv'); if (root.dataset.ready) { go(cur); return; } root.dataset.ready = '1';
    root.innerHTML = `<div id="stage"><div class="a bv-title"><span class="d"></span><span class="l">blue<i>vision</i></span><span class="t"></span><span class="fb"><button id="bvHelm">Helm panel</button><button id="bvTrainer">Trainer</button></span></div><div class="a bv-top" id="bvtop"></div><div class="a bv-tabs" id="bvtabs"></div><div class="a bv-page" id="bvpage"></div><div class="a bv-bar" id="bvbar"></div><div id="bvpop" hidden></div><div id="bvhelm" hidden style="position:absolute;inset:0;background:rgba(0,0,0,.65);z-index:40"></div></div>`;
    stage = root.querySelector('#stage'); topEl = root.querySelector('#bvtop'); tabsEl = root.querySelector('#bvtabs'); pageEl = root.querySelector('#bvpage'); barEl = root.querySelector('#bvbar'); popEl = root.querySelector('#bvpop'); helmEl = root.querySelector('#bvhelm');
    helmBuild();
    const click = e => { const t = e.target.closest('[data-act]'); if (!t) return; const a = ACT[t.dataset.act]; if (a) a(t.dataset.a2 || t.dataset.a, t); };
    stage.addEventListener('click', e => { if (e.target.closest('#bvhelm')) return; click(e); });
    root.querySelector('#bvHelm').onclick = () => { helmEl.hidden = false; helmRefresh(); };
    root.querySelector('#bvTrainer').onclick = () => { document.body.dataset.mode = 'trainer'; if (window.go) window.go('control'); };
    window.addEventListener('resize', fit); fit(); root.hidden = false;
    setInterval(refresh, 250);
    go(cur);
  }
  return { init, go, show() { document.body.dataset.mode = 'bv'; fit(); } };
})();
