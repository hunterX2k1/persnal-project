/* bluevision display - look-alike of the MCS-5 operator display, built from photos of the real screens.
 * Photos of the real display were used as a LAYOUT reference only. Everything shown (signal names, units, ranges, devices,
 * buses, number of engines/cylinders, tanks, alarms, limits) comes from the ACP data pack. Rows whose tag is not in the ACP are omitted. */
'use strict';

const BV = (() => {
  let M, stage, pageEl, topEl, tabsEl, barEl, popEl, helmEl;
  let ups = [], pups = [], cur = 'propulsion', topBtns = false;
  const AL = { view: 'Actual', grp: 'Combined', pri: 'All', state: 'All', sel: null, menu: false };
  const FUEL = { line: 1, gal: false };
  const NL = () => M.st.nLines || 3;
  const MSG = {};
  const S = t => M.IX.byTag.get(t);
  const E = (l, n) => `PV1${l}${n}`, G = (l, n) => `PV2${l}${n}`;
  const L_ = i => M.st.lines[i - 1];
  const pad = n => String(n).padStart(2, '0');
  const stamp = ms => { const d = new Date(ms); return { date: `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()}`, time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`, short: `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${String(d.getFullYear()).slice(2)}` }; };
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ------------------------------------------------------------ html helpers (page-relative absolute layout)
  const fv = (v, s, dec) => { if (v == null) return '--'; if (Number.isNaN(v)) return '???'; if (s && (s.u === 'bin' || s.t === 'D')) return v ? 'Yes' : 'No'; return dec !== '' && dec != null ? v.toFixed(+dec) : String(Math.round(v)); };
  const F = (x, y, w, tag, dec) => S(tag) ? `<div class="fld" style="left:${x}px;top:${y}px;width:${w}px" data-t="${tag}" data-d="${dec == null ? '' : dec}"></div>` : '';
  const FN = (x, y, w, fn, arg, cls) => `<div class="fld st ${cls || ''}" style="left:${x}px;top:${y}px;width:${w}px" data-f="${fn}" data-a="${arg == null ? '' : arg}"></div>`;
  const NM = tag => { const q = S(tag); return q ? String(q.n).trim() : ''; };
  const LBA = (x, y, w, tag) => S(tag) ? `<div class="lbl ac" style="left:${x}px;top:${y + 3}px;width:${w}px" title="${tag}">${esc(NM(tag))}</div>` : '';
  const LB = (x, y, t, c) => `<div class="lbl ${c || ''}" style="left:${x}px;top:${y + 2}px">${t}</div>`;
  const UN = (x, y, t) => `<div class="unit" style="left:${x}px;top:${y + 3}px">${t}</div>`;
  const BTN = (x, y, w, h, t, act, arg, cls) => `<div class="btn ${cls || ''}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px" data-act="${act}" data-a="${arg == null ? '' : arg}" ${cls && cls.includes('fx') ? `data-f="${cls.split(' ').find(c => c.startsWith('fx:')).slice(3)}" data-a2="${arg}"` : ''}>${t}</div>`;
  const GRP = (x, y, w, h) => `<div class="grp" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px"></div>`;
  const HD = (x, y, w, t) => `<div class="hd" style="left:${x}px;top:${y}px;width:${w}px">${t}</div>`;
  const SEP = (x, y, w) => `<div class="sep" style="left:${x}px;top:${y}px;width:${w}px"></div>`;
  // one data row: label, unit and tag come from the ACP signal; the row is omitted when the tag is not in the ACP
  const ROW = (lx, fx, ux, w, y, label, tag, dec, unit) => { const q = S(tag); if (!q) return ''; const u = q.u && q.u !== 'bin' ? q.u : (unit || ''); return LBA(lx, y, fx - lx - 6, tag) + F(fx, y, w, tag, dec) + UN(ux, y, esc(u)); };
  const YN = (lx, fx, w, y, tag, good) => S(tag) ? LBA(lx, y, fx - lx - 6, tag) + FN(fx, y, w, 'yn', tag + '|' + good) : '';
  const BAR = (x, y, w, h, tag, max, ticks, mark) => { if (!S(tag)) return ''; let t = ''; for (let i = 1; i < ticks; i++) t += `<div class="tk" style="bottom:${i / ticks * 100}%"></div>`; return `<div class="bar" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px" data-bt="${tag}" data-max="${max}">${t}<div class="fill"></div>${mark != null ? `<div class="mk" style="bottom:${mark / max * 100}%"></div>` : ''}</div>`; };
  const SCL = (x, y, h, max, n, dec) => { let s = ''; if (!max) return ''; for (let i = 0; i <= n; i++) { const v = max * i / n; s += `<div class="scl" style="left:${x}px;width:44px;top:${y + h - (i / n) * h - 8}px">${dec ? v.toFixed(dec) : Math.round(v * 10) / 10}</div>`; } return s; };
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
    clutch: (e, a) => { let t = '--'; for (const n of ['0113', '0114', '0115']) { const q = S(G(+a, n)); if (q && M.value(q) === 1) t = String(q.n).trim(); } setc(e, t, ''); },
    yn: (e, a) => { const [tag, good] = a.split('|'); const s = S(tag), v = s ? M.value(s) : null, yes = v === 1; const bad = v == null || Number.isNaN(v); setc(e, bad ? '???' : (yes ? 'Yes' : 'No'), !bad && good !== 'x' && yes === (good === '1') ? 'grn' : ''); },
    mim: (e, a) => { const l = L_(+a), on = l.eng === 'RUN' || l.eng === 'CRANK' ? (l.clutch ? 'g' : 'o') : 'x'; if (e.dataset.on === on) return; e.dataset.on = on; e.querySelectorAll('.ec').forEach(r => r.setAttribute('fill', on === 'x' ? '#d9d9d9' : '#2fc42f')); },
    ctl: (e, a) => { const l = L_(+a); e.classList.toggle('on', !!l.ctl); },
    needctl: (e, a) => { const l = L_(+a); e.classList.toggle('dis', !l.ctl); },
    msg: (e, a) => { const t = MSG[a] || ''; if (e.textContent !== t) e.textContent = t; },
    fuel: (e, a) => { const [k, i] = a.split('|'); const l = L_(+i), cv = FUEL.gal ? 0.264172 : 1; let v = 0;
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
    fuelprev: () => { FUEL.line = FUEL.line === 1 ? NL() : FUEL.line - 1; fuelPopup(); },
    fuelnext: () => { FUEL.line = FUEL.line === NL() ? 1 : FUEL.line + 1; fuelPopup(); },
    devfail: a => { M.st.devFail[a] = !M.st.devFail[a]; },
    nodefail: a => { M.st.pbnFail[+a] = !M.st.pbnFail[+a]; },
    fuelreset: () => { const l = L_(FUEL.line); l.fuelTrip = 0; l.tripH = 0; },
    fuelgal: () => { FUEL.gal = !FUEL.gal; fuelPopup(); },
    texh: a => go('texh' + a),
    legend: () => infoPop('Legend', `<p><span class="pill" style="background:#bdbdd6">grey</span> unit communicating</p><p><span class="pill fail">red</span> unit failed (tap a unit on the LAN page to simulate a failure)</p><p>Group buttons (top): the square shows the number of active alarms in the group and blinks while unacknowledged.</p><p>Value fields turn yellow / red at the warning / shutdown limit. <b>???</b> = no valid value from the bus.</p>`),
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
    lanfb11: { label: 'LAN FB 11-12', parent: 'lan' }, lanfb13: { label: 'LAN FB 13-20', parent: 'lan' },
    alarm: { label: 'Alarm Page', parent: 'overview', notab: true },
  };
  function buildPages() {   // one Engine / T-Exhaust page per engine in the ACP (K_Nr_AnzE)
    const n = NL(), np = {};
    for (const k of Object.keys(PAGES)) if (!/^(engine|texh)\d/.test(k)) np[k] = PAGES[k];
    const out = { overview: np.overview, propulsion: np.propulsion };
    for (let i = 1; i <= n; i++) out['engine' + i] = { label: 'Engine ' + i, parent: 'propulsion' };
    out.lan = np.lan; out.lanfb11 = np.lanfb11; out.lanfb13 = np.lanfb13; out.alarm = np.alarm;
    for (let i = 1; i <= n; i++) out['texh' + i] = { label: 'T-Exhaust ' + i, parent: 'engine' + i };
    for (const k of Object.keys(PAGES)) delete PAGES[k];
    Object.assign(PAGES, out);
  }
  const sibs = id => Object.keys(PAGES).filter(k => PAGES[k].parent === PAGES[id].parent && !PAGES[k].notab);
  const kids = id => Object.keys(PAGES).filter(k => PAGES[k].parent === id && !PAGES[k].notab);
  const chain = id => { const c = []; for (let p = id; p; p = PAGES[p].parent) c.unshift(p); return c; };

  // ------------------------------------------------------------ pages
  const ENG = l => { const e = ((M.data.engines || {}).list || []).find(x => x.no === l); return e && e.cylinders && e.series ? `${e.cylinders}V ${e.series}` : ''; };
  const CYL = l => { const e = ((M.data.engines || {}).list || []).find(x => x.no === l); return (e && e.cylinders) || 0; };
  function pgPropulsion() {
    let h = '';
    const n = NL(), cw = Math.floor(1012 / n);
    for (let k = 0; k < n; k++) {
      const l = k + 1, ox = 6 + k * cw;
      h += GRP(ox, 8, cw - 8, 452) + `<div class="lbl b" style="left:${ox + 12}px;top:12px;font-size:18px">Engine ${l} <span style="font-weight:500;font-size:14px">${ENG(l)}</span></div>`;
      [[E(l, '0002'), 0], [E(l, '0205'), 0], [E(l, '0049'), 2]].forEach((b, i) => {
        const q = S(b[0]); if (!q) return; const bx = ox + 50 + i * Math.floor((cw - 70) / 3), u = q.u || '';
        h += `<div class="lbl c" style="left:${bx - 34}px;top:40px;width:${Math.floor((cw - 70) / 3)}px;font-size:12px;line-height:1.1;white-space:normal;height:30px;overflow:hidden">${esc(NM(b[0]))}</div>` + SCL(bx - 46, 76, 130, q.max, 3, q.max < 10 ? 2 : 0) + BAR(bx, 76, 38, 130, b[0], q.max, 3) + F(bx - 12, 212, 62, b[0], b[1]) + `<div class="unit" style="left:${bx - 8}px;top:240px;font-size:12px">${esc(u)}</div>`;
      });
      h += ROW(ox + 12, ox + cw - 112, ox + cw - 44, 62, 266, '', E(l, '0026'), 1) + ROW(ox + 12, ox + cw - 112, ox + cw - 44, 62, 298, '', E(l, '0126'), 1) + ROW(ox + 12, ox + cw - 112, ox + cw - 44, 62, 330, '', E(l, '2221'), 0) + YN(ox + 12, ox + cw - 112, 62, 362, E(l, '0406'), 'x');
      h += LB(ox + 12, 398, 'Clutch', 'b') + FN(ox + 100, 396, cw - 130, 'clutch', l) + ROW(ox + 12, ox + cw - 112, ox + cw - 44, 62, 428, '', G(l, '2013'), 0);
      h += BTN(ox + Math.floor(cw / 2) - 70, 466, 140, 34, 'Engine ' + l, 'go', 'engine' + l);
    }
    return h;
  }

  function pgEngine(l) {
    let h = `<div class="lbl b" style="left:20px;top:6px;font-size:17px">Engine ${l}  ${ENG(l)}</div>` + MIM(40, 30, l);
    h += LB(48, 306, 'Clutch', 'b') + FN(14, 330, 150, 'clutch', l) + LBA(14, 366, 170, G(l, '2013')) + F(36, 390, 86, G(l, '2013'), 0) + UN(128, 390, esc((S(G(l, '2013')) || {}).u || ''));
    h += `<div style="position:absolute;left:14px;top:392px;width:160px"></div>` + BTN(20, 446, 140, 38, 'T-Exhaust ' + l, 'texh', l);
    const R = (lx, fx, ux, y0, list) => list.map((r, k) => ROW(lx, fx, ux, 58, y0 + k * 29, '', r[0], r[1])).join('');
    h += GRP(190, 14, 272, 478) + HD(190, 18, 272, 'ECU');
    h += R(198, 360, 422, 48, [[E(l, '0002'), 0], [E(l, '0057'), 1], [E(l, '0059'), 1], [E(l, '0205'), 0]]);
    h += YN(198, 360, 58, 164, E(l, '0008'), '0') + YN(198, 360, 58, 193, E(l, '4470'), 'x') + SEP(196, 226, 260);
    h += R(198, 360, 422, 236, [[E(l, '0026'), 1], [E(l, '0046'), 1], [E(l, '0036'), 1], [E(l, '0049'), 2], [E(l, '0031'), 0]]) + SEP(196, 386, 260);
    h += R(198, 360, 422, 394, [[E(l, '0115'), 0], [E(l, '0104'), 1], [E(l, '0169'), 1]]);
    h += GRP(470, 14, 272, 478) + HD(470, 18, 272, 'ECU');
    h += R(478, 652, 714, 48, [[E(l, '0140'), 1], [E(l, '0152'), 1], [E(l, '0126'), 1], [E(l, '0131'), 1], [E(l, '0258'), 0], [E(l, '0151'), 1], [E(l, '0145'), 0], [E(l, '0148'), 0]]);
    h += SEP(476, 284, 260) + HD(470, 288, 272, 'Start / Stop');
    h += YN(478, 652, 84, 316, E(l, '0406'), 'x') + YN(478, 652, 84, 343, E(l, '4250'), '1') + YN(478, 652, 84, 370, E(l, '4398'), '0');
    h += BTN(490, 410, 112, 30, 'Start', 'start', l, 'fx:needctl') + BTN(612, 410, 112, 30, 'Stop', 'stop', l, 'fx:needctl') + BTN(520, 448, 164, 28, 'Control', 'ctl', l, 'fx:ctl');
    h += `<div class="lbl" style="left:480px;top:480px;font-size:12px;width:250px;height:14px;overflow:hidden" data-f="msg" data-a="${l}"></div>`;
    h += GRP(750, 14, 266, 478);
    const emu = R(758, 894, 956, 48, [[E(l, '2002'), 0], [E(l, '2026'), 1], [E(l, '2501'), 1], [E(l, '2126'), 1]]);
    if (emu) h += HD(750, 18, 266, 'EMU') + emu;
    h += HD(750, 168, 266, 'GCU') + R(758, 894, 956, 196, [[G(l, '0001'), 1], [G(l, '0006'), 1], [G(l, '0011'), 1]]);
    h += HD(750, 296, 266, 'GMU') + R(758, 894, 956, 324, [[G(l, '2013'), 0]]);
    return h;
  }

  function pgTexh(l) {
    const nc = Math.max(1, Math.floor(CYL(l) / 2)) || 8, step = Math.floor(870 / nc);
    let h = `<div class="lbl b" style="left:20px;top:8px;font-size:18px">Engine ${l} - ${CYL(l)} cylinders</div>`;
    ['A', 'B'].forEach((b, bi) => {
      const y0 = 50 + bi * 188, t0 = E(l, String(2200 + (bi ? 10 : 0) + 1)), mx = (S(t0) || {}).max || 1500;
      h += GRP(10, y0 - 10, 1004, 176) + LB(22, y0 - 6, 'Bank ' + b, 'b');
      for (let c = 1; c <= nc; c++) { const bx = 110 + (c - 1) * step, tag = E(l, String(2200 + (bi ? 10 : 0) + c)); h += `<div class="lbl c" style="left:${bx - 10}px;top:${y0 - 6}px;width:${step - 20}px;font-size:12px">${esc(NM(tag))}</div>` + BAR(bx, y0 + 16, 40, 100, tag, mx, 5) + F(bx - 8, y0 + 120, 56, tag, 0); }
      h += SCL(60, y0 + 16, 100, mx, 5, 0);
    });
    h += ROW(40, 250, 316, 60, 436, '', E(l, '2221'), 0) + ROW(360, 570, 636, 60, 436, '', E(l, '2222'), 0) + ROW(680, 880, 946, 60, 436, '', E(l, '2223'), 0);
    return h;
  }

  function pgOverview() {
    const n = NL(), cw = Math.floor(1000 / n);
    let h = GRP(8, 8, 1008, 262) + HD(8, 12, 1008, 'Propulsion');
    for (let l = 1; l <= n; l++) { const x = 20 + (l - 1) * cw;
      h += `<div class="lbl b" style="left:${x}px;top:40px">Engine ${l} <span style="font-weight:500;font-size:13px">${ENG(l)}</span></div>` + ROW(x, x + cw - 124, x + cw - 56, 60, 68, '', E(l, '0002'), 0) + ROW(x, x + cw - 124, x + cw - 56, 60, 98, '', G(l, '2013'), 0) + ROW(x, x + cw - 124, x + cw - 56, 60, 128, '', E(l, '0026'), 1) + ROW(x, x + cw - 124, x + cw - 56, 60, 158, '', E(l, '0126'), 1) + LB(x, 196, 'Clutch', 'b') + FN(x + 70, 194, cw - 110, 'clutch', l); }
    h += BTN(430, 230, 170, 32, 'Propulsion', 'go', 'propulsion');
    h += GRP(8, 278, 500, 220) + HD(8, 282, 500, 'Gensets');
    for (let i = 1; i <= 3; i++) h += LB(24, 312 + (i - 1) * 60, 'Genset ' + i, 'b') + ROW(110, 380, 446, 62, 312 + (i - 1) * 60, '', `PV4${i}0004`, 0) + ROW(110, 380, 446, 62, 338 + (i - 1) * 60, '', `PV4${i}0002`, 1);
    h += GRP(516, 278, 500, 220) + HD(516, 282, 500, 'Tanks');
    (M.data.tanks || []).forEach((t, k) => { const q = S(t.tag); if (!q) return; h += `<div class="lbl ac" style="left:530px;top:${310 + k * 29}px;width:300px">${esc(t.name)}</div>` + F(850, 308 + k * 29, 80, t.tag, 1) + UN(936, 308 + k * 29, esc(q.u || '')); });
    return h;
  }

  const short = t => String(t || '').replace(/\s*\d.*$/, '').replace(/^DIS.*/, 'DIS').replace(/^ROS.*/, 'ROS') || '?';
  function devBox(x, y, m, pbn) {
    const dv = (M.data.devices || []).find(d => d.id === m.id), last = m.id.split('-').pop(), ty = dv ? dv.type : '';
    return `<div class="dev c" style="left:${x}px;top:${y}px;width:120px;height:26px;padding:0;line-height:22px;font-size:13px;cursor:pointer" title="${esc(m.id)}  ${esc(ty)}" data-f="devstate" data-a="${esc(m.id)}|${pbn}" data-act="devfail" data-a2="${esc(m.id)}">${esc(ty)} ${esc(last)}</div>`;
  }
  // one panel per PPS: the field bus members come from the ACP (left: other system's units, right: this PPS's own PIMs)
  function lanPanel(ox, pbn) {
    const nd = (M.data.nodes || []).find(n => n.pbn === pbn); if (!nd) return '';
    const bus = (M.data.buses || []).find(b => b.members[0] && b.members[0].id === nd.id); if (!bus) return '';
    const mem = bus.members.slice(1);
    const isMach = m => m.id.startsWith('=2');   // machinery units (ECU/EMU/gear...) left, PIMs of the cabinet bus right
    const left = mem.filter(isMach), right = mem.filter(m => !isMach(m));
    let h = `<div class="pps" style="left:${ox + 150}px;top:28px;width:96px;height:50px;cursor:pointer" data-f="nodestate" data-a="${pbn}" data-act="nodefail" data-a2="${pbn}"><small>${esc(nd.id)}</small>${esc(nd.name)}</div>`;
    const rows = Math.max(left.length, right.length, 1), st = Math.min(31, Math.floor(400 / rows));
    h += `<div class="ln" style="left:${ox + 132}px;top:54px;width:3px;height:${rows * st + 50}px"></div><div class="ln" style="left:${ox + 135}px;top:54px;width:15px;height:3px"></div><div class="ln" style="left:${ox + 250}px;top:54px;width:3px;height:${rows * st + 50}px"></div><div class="ln" style="left:${ox + 246}px;top:54px;width:6px;height:3px"></div>`;
    left.forEach((m, k) => { const y = 92 + k * st; h += `<div class="ln" style="left:${ox + 126}px;top:${y + 11}px;width:8px;height:3px"></div>` + devBox(ox + 6, y, m, pbn); });
    right.forEach((m, k) => { const y = 92 + k * st; h += `<div class="ln" style="left:${ox + 252}px;top:${y + 11}px;width:6px;height:3px"></div>` + devBox(ox + 258, y, m, pbn); });
    return h;
  }
  const pgLanFB11 = () => lanPanel(0, 11) + lanPanel(512, 12);
  const pgLanFB13 = () => lanPanel(0, 13) + lanPanel(512, 20);
  function pgLan() {
    let h = GRP(8, 8, 1008, 490) + HD(8, 14, 1008, 'Process bus (CAN)');
    h += '<div class="ln" style="left:40px;top:130px;width:944px;height:4px"></div>';
    M.data.nodes.forEach((n, k) => { const x = 40 + k * 106, act = n.type === 'PPS' ? (n.pbn <= 12 ? 'lanfb11' : 'lanfb13') : ''; h += `<div class="ln" style="left:${x + 48}px;top:134px;width:3px;height:30px"></div><div class="pps" style="left:${x}px;top:164px;width:100px;height:64px;cursor:pointer" data-f="nodestate" data-a="${n.pbn}" ${act ? `data-act="go" data-a2="${act}"` : `data-act="nodefail" data-a2="${n.pbn}"`}><small>${esc(n.id)}</small>${esc(n.name)}</div>`; });
    return h + LB(40, 260, 'Tap a PPS to open its field bus page; tap an MCU/CPS to simulate a node failure.');
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
    h += BTN(24, 48, 40, 34, '◀', 'fuelprev') + `<div class="fld st" style="left:80px;top:48px;width:276px;height:34px;line-height:30px;font-size:19px">Engine ${l}</div>` + BTN(372, 48, 40, 34, '▶', 'fuelnext');
    const bx = [[34, 100, E(l, '0119'), 'actual'], [270, 336, E(l, '0198'), 'mean']];
    bx.forEach(([gx, bxx, tag, k]) => { const q = S(tag); if (!q) return; h += GRP(gx, 100, 130, 256) + `<div class="lbl c" style="left:${gx}px;top:102px;width:130px;font-size:12px;white-space:normal;height:30px;line-height:1.1;overflow:hidden">${esc(NM(tag))}</div>` + SCL(gx + 2, 138, 140, q.max, 3, 0) + BAR(bxx, 138, 40, 140, tag, q.max, 3) + `<div class="fld" style="left:${gx + 14}px;top:300px;width:70px" data-f="fuel" data-a="${k}|${l}"></div><div class="unit" style="left:${gx + 88}px;top:303px" data-f="fuelunit" data-a="rate"></div>`; });
    [[E(l, '0255'), 'dur', 372], [E(l, '0120'), 'trip', 402], [E(l, '0121'), 'total', 432]].forEach(([tag, k, y]) => { if (!S(tag)) return; h += LBA(24, y, 210, tag) + `<div class="fld" style="left:240px;top:${y}px;width:120px" data-f="fuel" data-a="${k}|${l}"></div>` + (k === 'dur' ? UN(368, y, 'h') : `<div class="unit" style="left:368px;top:${y + 3}px" data-f="fuelunit" data-a="vol"></div>`); });
    h += BTN(24, 460, 140, 28, 'Reset', 'fuelreset') + BTN(180, 460, 120, 28, FUEL.gal ? 'Litre' : 'Gallon', 'fuelgal') + BTN(316, 460, 100, 28, 'Exit', 'fuelx') + '</div>';
    popEl.innerHTML = h; bind(popEl, pups);
  }

  // ------------------------------------------------------------ chrome
  function buildTop() {
    const bar = (n, red) => ''; void bar;
    let h = '';
    if (!topBtns && (cur === 'propulsion' || /^(engine|texh)\d$/.test(cur))) {
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
    const pages = { overview: pgOverview, propulsion: pgPropulsion, lan: pgLan, lanfb11: pgLanFB11, lanfb13: pgLanFB13, alarm: pgAlarm }; for (let i = 1; i <= NL(); i++) { pages['engine' + i] = () => pgEngine(i); pages['texh' + i] = () => pgTexh(i); }
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
  function stations() { const c = ((M.data.config || {}).ControlStand1) || {}; const out = []; Object.keys(c).forEach(k => { const m = /CS_Conf_(MCC|SCC\d)$/.exec(k); if (m && +c[k] > 0) out.push(m[1]); }); return out.length ? out : ['MCC']; }
  function helmBuild() {
    const n = NL(), nm = (l, k) => { const q = S(G(l, k)); return q ? String(q.n).trim() : ''; };
    const panel = l => `<div style="flex:1;background:#2b2b30;color:#fff;border:2px solid #777;padding:10px;min-width:0">
      <div style="text-align:center;font-weight:700;margin-bottom:8px">Engine ${l}<br><small>${ENG(l)}</small></div>
      ${[[nm(l, '0114') || 'IN', 'in'], [nm(l, '0113') || 'OUT', 'out']].map(b => `<div class="hb" data-h="${b[1]}" data-l="${l}" style="margin:6px 0;padding:10px 6px;text-align:center;font-size:14px;font-weight:600;border:3px solid #999;background:#14301a;color:#cfe;cursor:pointer" data-c="#2fe02f">${esc(b[1] === 'in' ? 'CLUTCH IN  (' + b[0] + ')' : 'CLUTCH OUT  (' + b[0] + ')')}</div>`).join('')}
      <div style="margin-top:10px;font-size:13px">Speed lever <b data-lv="${l}">0</b> %</div><input type="range" min="-100" max="100" value="0" data-lever="${l}" style="width:100%">
      <div style="font-size:13px">Steering <b data-sv="${l}">0</b> %</div><input type="range" min="-100" max="100" value="0" data-steer="${l}" style="width:100%">
      <div style="margin-top:6px;font-size:13px">Shaft <b data-sh="${l}">0</b> rpm &nbsp; Engine <b data-en="${l}">0</b> rpm</div></div>`;
    helmEl.innerHTML = `<div style="position:absolute;inset:20px 24px;background:#1c1c20;border:3px solid #888;padding:12px;display:flex;flex-direction:column;gap:8px;overflow:auto">
      <div style="display:flex;gap:8px;align-items:center;color:#fff"><b>Control station:</b>${stations().map(s => `<div class="hb" data-h="st" data-s="${s}" style="padding:6px 14px;border:3px solid #999;background:#14301a;color:#cfe;font-weight:600;cursor:pointer" data-c="#ffb020">${s}</div>`).join('')}</div>
      <div style="display:flex;gap:10px">${Array.from({ length: n }, (_, i) => panel(i + 1)).join('')}</div>
      <div style="display:flex;gap:8px;align-items:center;color:#fff"><div class="btn" style="position:relative;width:200px;height:34px;background:#ff8080" data-hx="estop">EMERGENCY STOP</div><div class="btn" style="position:relative;width:160px;height:34px" data-hx="ovr">Safety override</div><div class="btn" style="position:relative;width:160px;height:34px" data-hx="close">Close</div><span id="helmmsg" style="font-weight:700;color:#ffd"></span></div></div>`;
    helmEl.onclick = e => {
      const hb = e.target.closest('[data-h]'), hx = e.target.closest('[data-hx]'); const say = r => { if (typeof r === 'string') helmEl.querySelector('#helmmsg').textContent = r; };
      if (hb) { const k = hb.dataset.h; if (k === 'st') M.cmd.station(hb.dataset.s); else { const l = +hb.dataset.l; say(M.cmd.clutch(l, k === 'in')); } }
      if (hx) { const k = hx.dataset.hx; if (k === 'estop') { M.cmd.estop(!M.st.estop); say(M.st.estop ? 'Emergency stop active' : 'Emergency stop reset'); } else if (k === 'ovr') M.cmd.override(!M.st.override); else helmEl.hidden = true; }
    };
    helmEl.oninput = e => { const lv = e.target.dataset.lever, sv = e.target.dataset.steer; if (lv) { M.cmd.lever(+lv, +e.target.value); helmEl.querySelector(`[data-lv="${lv}"]`).textContent = e.target.value; } if (sv) { M.cmd.steer(+sv, +e.target.value); helmEl.querySelector(`[data-sv="${sv}"]`).textContent = e.target.value; } };
  }
  function helmRefresh() {
    helmEl.querySelectorAll('.hb').forEach(b => { const k = b.dataset.h; let on; if (k === 'st') on = M.st.station === b.dataset.s; else { const L = L_(+b.dataset.l); on = k === 'in' ? L.clutch : !L.clutch; } const bg = on ? b.dataset.c : '#14301a'; if (b.style.background !== bg) { b.style.background = bg; b.style.color = on ? '#000' : '#cfe'; } });
    for (let l = 1; l <= NL(); l++) { const L = L_(l); helmEl.querySelector(`[data-sh="${l}"]`).textContent = Math.round(L.shaft); helmEl.querySelector(`[data-en="${l}"]`).textContent = Math.round(L.n); }
  }

  // ------------------------------------------------------------ setup
  function fit() { const w = window.innerWidth, h = window.innerHeight, s = Math.min(w / 1024, h / 768); stage.style.transform = `scale(${s})`; stage.style.left = (w - 1024 * s) / 2 + 'px'; stage.style.top = (h - 768 * s) / 2 + 'px'; }
  function init(model) {
    M = model; M.st.nLines = (M.data.engines && M.data.engines.n) || 3;
    const fu = (M.data.engines || {}).fuelUnits || {}; FUEL.gal = !!fu.galUS && !fu.litre;
    buildPages(); if (!PAGES[cur]) cur = 'propulsion';
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
