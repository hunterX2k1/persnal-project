/* MCS-5 Trainer - user interface. Screens are built once per visit and refreshed by lightweight updaters. */
'use strict';

const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
function el(tag, cls, html) { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

let M = null;              // plant model
let screen = 'overview';
let cur = 1;               // selected propulsion line
let upd = [];              // updaters of current screen
let ctl = {};              // per-screen state (filters etc.)
let muted = false, audio = null;

// ---------------------------------------------------------------- helpers
const S = t => M.IX.byTag.get(t);
const V = t => { const s = S(t); return s ? M.value(s) : null; };
function fmt(v, s) {
  if (v == null) return '--';
  if (Number.isNaN(v)) return '???';
  if (s && (s.u === 'bin' || s.t === 'D')) return v ? 'ON' : 'OFF';
  const a = Math.abs(v), u = s ? s.u : '';
  const d = ['rpm', 'h', 'l', 'digit', 'mm', 'krpm'].includes(u) || a >= 1000 ? 0 : a >= 100 ? 1 : 2;
  return v.toFixed(d);
}
function running(s) {
  const st = M.st;
  if (s.dom === 'E' || s.dom === 'G' || s.dom === 'W') { const L = st.lines[s.line - 1]; return L && (s.dom === 'W' ? L.shaft > 20 : L.eng === 'RUN' || L.n > 50); }
  if (s.dom === 'D') { const G = st.gens[s.line - 1]; return G && G.n > 100; }
  return true;
}
function status(s, v) {
  if (v == null || s.lim) return '';
  if (Number.isNaN(v)) return 'bad';
  const l = M.limitsFor(s.q), run = running(s);
  if (run && ((l.lo2 != null && v < l.lo2) || (l.hi2 != null && v > l.hi2))) return 'red';
  if (run && ((l.lo1 != null && v < l.lo1) || (l.hi1 != null && v > l.hi1))) return 'yel';
  return '';
}
function cell(parent, label, tag, unitOverride) {
  const s = typeof tag === 'string' ? S(tag) : tag;
  if (!s) return null;
  const c = el('div', 'cell');
  c.innerHTML = `<span class="lbl" title="${esc(s.tag)}">${esc(label || s.q || s.n)}</span><span class="val">--</span><span class="unit">${esc(unitOverride ?? (s.u === 'bin' ? '' : s.u))}</span>`;
  const val = c.children[1];
  parent.appendChild(c);
  upd.push(() => { const v = M.value(s); val.textContent = fmt(v, s); val.className = 'val ' + status(s, v); });
  return c;
}
function bar(parent, label, tag) {
  const s = S(tag); if (!s) return;
  const b = el('div', 'bar', `<div class="n"><span>${esc(label)}</span><b>--</b></div><div class="t"><div class="f"></div></div>`);
  parent.appendChild(b);
  const nb = b.querySelector('b'), f = b.querySelector('.f');
  const lo = s.min ?? 0, hi = s.max ?? 100;
  upd.push(() => { const v = M.value(s); nb.textContent = fmt(v, s); f.style.width = (Number.isNaN(v) ? 0 : Math.max(0, Math.min(100, (v - lo) / (hi - lo) * 100))) + '%'; b.className = 'bar ' + status(s, v); });
}
function setText(node, fn) { upd.push(() => { const t = fn(); if (node.textContent !== t) node.textContent = t; }); }
function btn(parent, label, fn, cls) { const b = el('button', cls || '', label); b.onclick = fn; parent.appendChild(b); return b; }
function tabsLine(root, onChange) {
  const t = el('div', 'tabs'); root.appendChild(t);
  [1, 2, 3].forEach(i => { const b = btn(t, 'Propulsion ' + i, () => { cur = i; onChange(); }, cur === i ? 'act' : ''); });
}
const P = i => i;                              // line id helper
const E = (l, n) => `PV1${l}${n}`;              // engine / ECU / EMU tags
const G = (l, n) => `PV2${l}${n}`;              // gearbox tags
const W = (l, n) => `PV3${l}${n}`;              // waterjet / shaft tags
const D = (l, n) => `PV4${l}${n}`;              // genset diesel tags
const tm = ms => new Date(ms).toLocaleTimeString('en-GB');
const clockStr = ms => new Date(ms).toLocaleString('en-GB', { dateStyle: 'short', timeStyle: 'medium' });

// ---------------------------------------------------------------- alarm grouping
let groupTop = {};
function buildGroups(data) {
  const par = {}; (data.alarmGroups || []).forEach(g => par[g.name] = g.parent);
  const top = g => { let n = g, guard = 0; while (par[n] && par[n] !== 'Combined' && guard++ < 20) n = par[n]; return (par[n] === 'Combined' ? n : 'Other').replace(/%$/, '').replace(/_$/, ''); };
  M.data.signals.forEach(s => { if (s.pri != null) s.top = groupTop[s.g] || (groupTop[s.g] = top(s.g)); });
}
const TOPS = ['Prop', 'EPower', 'Tanks', 'Doors', 'Bilges', 'Fire', 'System'];

// ---------------------------------------------------------------- screens
const SCREENS = {
  overview: { label: 'Overview', build: buildOverview },
  engine: { label: 'Engine', build: buildEngine },
  control: { label: 'Control', build: buildControl },
  power: { label: 'Power & Tanks', build: buildPower },
  alarms: { label: 'Alarms', build: buildAlarms },
  network: { label: 'Network', build: buildNetwork },
  signals: { label: 'Signals', build: buildSignals },
  trainer: { label: 'Trainer', build: buildTrainer },
  info: { label: 'Plant info', build: buildInfo },
};

function buildOverview(root) {
  const g = el('div', 'grid g2'); root.appendChild(g);
  for (const i of [1, 2, 3]) {
    const c = el('div', 'card'); g.appendChild(c);
    const h = el('h2', '', `Propulsion ${i} `); const st = el('span', 'state', 'STOP'); h.appendChild(st); c.appendChild(h);
    upd.push(() => { const L = M.st.lines[i - 1]; st.textContent = L.eng; st.className = 'state ' + L.eng; });
    const ln = el('div', 'line'); c.appendChild(ln);
    const e = el('div', 'box', '<h4>Engine</h4>'), gb = el('div', 'box', '<h4>Gearbox</h4>'), wj = el('div', 'box', '<h4>Waterjet</h4>');
    const l1 = el('div', 'shaftlink'), l2 = el('div', 'shaftlink');
    ln.append(e, l1, gb, l2, wj);
    upd.push(() => { const L = M.st.lines[i - 1]; l1.className = 'shaftlink' + (L.n > 50 ? ' turn' : ''); l2.className = 'shaftlink' + (L.shaft > 20 ? ' turn' : ''); });
    cell(e, 'Speed', E(i, '0002')); cell(e, 'Load (P-grade)', E(i, '0205'));
    cell(e, 'P lube', E(i, '0026')); cell(e, 'T coolant', E(i, '0126')); cell(e, 'T lube', E(i, '0140')); cell(e, 'T exhaust', E(i, '0145'));
    cell(gb, 'Shaft speed', G(i, '2013')); cell(gb, 'P control', G(i, '0001')); cell(gb, 'P lube', G(i, '0006')); cell(gb, 'T oil', G(i, '0011'));
    const clu = el('div', 'cell', '<span class="lbl">Clutch</span><span class="val">--</span><span class="unit"></span>'); gb.appendChild(clu);
    upd.push(() => { const v = clu.children[1]; const on = M.st.lines[i - 1].clutch; v.textContent = on ? 'IN' : 'OUT'; v.className = 'val' + (on ? '' : ' off'); });
    cell(wj, 'Bucket', W(i, '0100')); cell(wj, 'Steering', W(i, '0101')); cell(wj, 'P hydraulic', W(i, '0036')); cell(wj, 'T oil', W(i, '0071')); cell(wj, 'T plummer fwd', W(i, '0076'));
    const row = el('div', 'row'); c.appendChild(row);
    btn(row, 'Engine page', () => { cur = i; go('engine'); }, 'sm'); btn(row, 'Control', () => { cur = i; go('control'); }, 'sm');
  }
  const c2 = el('div', 'card'); g.appendChild(c2); c2.innerHTML = '<h2>Gensets</h2>';
  for (const i of [1, 2, 3]) {
    const r = el('div', 'row'); c2.appendChild(r);
    const t = el('b', '', 'DG' + i); r.appendChild(t);
    const p = el('span', 'pill', '--'); r.appendChild(p);
    const w = el('span', '', ''); r.appendChild(w);
    upd.push(() => { const G_ = M.st.gens[i - 1]; p.textContent = G_.run ? (G_.cb ? 'ON LOAD' : 'RUNNING') : 'STOPPED'; p.className = 'pill ' + (G_.cb ? 'ok' : G_.run ? 'warn' : ''); w.textContent = `${G_.n.toFixed(0)} rpm  T-lube ${G_.tlo.toFixed(0)}°C  T-cw ${G_.tcw.toFixed(0)}°C`; });
  }
  const c3 = el('div', 'card'); g.appendChild(c3); c3.innerHTML = '<h2>Tanks</h2>';
  tankBars(c3);
}

function tankBars(parent) {
  const w = el('div', 'bars'); parent.appendChild(w);
  M.st.tanks.forEach(t => {
    const b = el('div', 'bar', `<div class="n"><span>${esc(t.name)}</span><b>--</b></div><div class="t"><div class="f"></div></div>`); w.appendChild(b);
    const nb = b.querySelector('b'), f = b.querySelector('.f');
    upd.push(() => { const p = t.vol / t.cap; nb.textContent = t.vol.toFixed(1) + ' m³'; f.style.width = (p * 100) + '%'; b.className = 'bar ' + (p < 0.1 ? 'red' : p < 0.2 ? 'yel' : ''); });
  });
}

function buildEngine(root) {
  tabsLine(root, () => go('engine'));
  const i = cur;
  const sigs = M.data.signals.filter(s => s.line === i && s.dom === 'E' && s.t !== 'D' && s.u !== 'bin' && !s.lim && !/^(Exhaust|Bearing|SplashOil)/.test(s.g));
  const seen = new Set();
  const c = el('div', 'card'); root.appendChild(c); c.innerHTML = `<h2>Engine ${i} - ECU / EMU values</h2>`;
  const cols = el('div', 'cols'); c.appendChild(cols);
  sigs.forEach(s => { const k = s.n; if (seen.has(k)) return; seen.add(k); cell(cols, s.n, s); });
  const sect = (title, re) => {
    const list = M.data.signals.filter(s => s.line === i && re.test(s.g) && s.t !== 'D' && s.u !== 'bin' && !s.lim && !/U[12]L|L[12]L|^U/.test(s.n));
    if (!list.length) return;
    const k = el('div', 'card'); root.appendChild(k); k.innerHTML = `<h2>${title}</h2>`;
    const b = el('div', 'bars'); k.appendChild(b);
    list.forEach(s => bar(b, s.n.replace(/^T-/, ''), s));
  };
  sect('Exhaust gas temperatures (per cylinder)', /^Exhaust/);
  sect('Main bearing temperatures', /^Bearing/);
  sect('Splash oil temperatures', /^SplashOil/);
  if (!sigs.length) c.appendChild(el('p', '', 'No engine signals for this line in the ship data.'));
}

function gauge(parent, max, label, unit) {
  const w = el('div'); parent.appendChild(w);
  w.innerHTML = `<svg class="gauge" viewBox="0 0 200 125"><path d="M20 105 A80 80 0 0 1 180 105" fill="none" stroke="#464646" stroke-width="14"/>
    <path id="gz" d="M20 105 A80 80 0 0 1 180 105" fill="none" stroke="#00be00" stroke-width="8" stroke-dasharray="0 400"/>
    <line id="gn" x1="100" y1="105" x2="30" y2="105" stroke="#c00" stroke-width="3"/><circle cx="100" cy="105" r="6" fill="#222"/>
    <text id="gv" x="100" y="88" text-anchor="middle" font-size="20" font-weight="700" font-family="monospace">0</text>
    <text x="100" y="122" text-anchor="middle" font-size="12">${esc(label)} [${esc(unit)}]</text></svg>`;
  const n = w.querySelector('#gn'), z = w.querySelector('#gz'), t = w.querySelector('#gv');
  return v => { const f = Math.max(0, Math.min(1, v / max)), a = Math.PI * (1 - f); n.setAttribute('x2', 100 + 72 * Math.cos(a)); n.setAttribute('y2', 105 - 72 * Math.sin(a)); z.setAttribute('stroke-dasharray', `${f * 251} 400`); t.textContent = v.toFixed(0); };
}

function buildControl(root) {
  tabsLine(root, () => go('control'));
  const i = cur, L = () => M.st.lines[i - 1];
  const msg = el('p', 'msg', '');
  const say = r => { if (typeof r === 'string') msg.textContent = r; };
  const g = el('div', 'grid g2'); root.appendChild(g);
  const a = el('div', 'card'); g.appendChild(a); a.innerHTML = `<h2>Remote control - propulsion ${i}</h2>`;
  const sr = el('div', 'row'); a.appendChild(sr); sr.appendChild(el('span', '', 'Station:'));
  const sel = el('select'); ['MCC', 'SCC1', 'SCC2', 'SCC3', 'HSG'].forEach(s => sel.appendChild(new Option(s, s))); sel.value = M.st.station; sel.onchange = () => M.cmd.station(sel.value); sr.appendChild(sel);
  const st = el('span', 'state', 'STOP'); sr.appendChild(st);
  upd.push(() => { st.textContent = L().eng; st.className = 'state ' + L().eng; });
  const r1 = el('div', 'row'); a.appendChild(r1);
  btn(r1, 'START', () => say(M.cmd.start(i)));
  btn(r1, 'STOP', () => say(M.cmd.stop(i)));
  const cb = btn(r1, 'CLUTCH IN', () => say(M.cmd.clutch(i, true)));
  btn(r1, 'CLUTCH OUT', () => say(M.cmd.clutch(i, false)));
  upd.push(() => cb.classList.toggle('on', L().clutch));
  const lv = el('div'); a.appendChild(lv); lv.innerHTML = '<div class="row"><b>Speed lever</b> <span id="lvv">0</span> % (− astern / + ahead)</div>';
  const rng = el('input'); rng.type = 'range'; rng.min = -100; rng.max = 100; rng.step = 1; rng.value = L().lever; lv.appendChild(rng);
  rng.oninput = () => { M.cmd.lever(i, +rng.value); lv.querySelector('#lvv').textContent = rng.value; };
  const r2 = el('div', 'row'); lv.appendChild(r2); btn(r2, 'Neutral', () => { rng.value = 0; rng.oninput(); }, 'sm'); btn(r2, 'Slow ahead', () => { rng.value = 25; rng.oninput(); }, 'sm'); btn(r2, 'Half', () => { rng.value = 60; rng.oninput(); }, 'sm'); btn(r2, 'Full', () => { rng.value = 100; rng.oninput(); }, 'sm'); btn(r2, 'Astern', () => { rng.value = -40; rng.oninput(); }, 'sm');
  const sv = el('div'); a.appendChild(sv); sv.innerHTML = '<div class="row"><b>Steering</b> <span id="stv">0</span> % (− port / + stbd)</div>';
  const rs = el('input'); rs.type = 'range'; rs.min = -100; rs.max = 100; rs.value = L().steer; sv.appendChild(rs);
  rs.oninput = () => { M.cmd.steer(i, +rs.value); sv.querySelector('#stv').textContent = rs.value; };
  const r3 = el('div', 'row'); a.appendChild(r3);
  const hp = btn(r3, 'Hydraulic pump', () => { M.cmd.hydPump(i, !L().hydPump); });
  upd.push(() => hp.classList.toggle('on', L().hydPump));
  const es = btn(r3, 'EMERGENCY STOP', () => { M.cmd.estop(!M.st.estop); say(M.st.estop ? 'Emergency stop active' : 'Emergency stop reset'); }, 'warn');
  const ov = btn(r3, 'Safety override', () => { M.cmd.override(!M.st.override); });
  upd.push(() => { es.classList.toggle('on', M.st.estop); ov.classList.toggle('on', M.st.override); });
  a.appendChild(msg);
  const cause = el('p', 'hint', ''); a.appendChild(cause); setText(cause, () => L().stopCause ? 'Last stop: ' + L().stopCause : '');
  const b = el('div', 'card'); g.appendChild(b); b.innerHTML = '<h2>Instruments</h2>';
  const gg = el('div', 'grid g3'); b.appendChild(gg);
  const g1 = gauge(gg, M.NMAX, 'Engine speed', 'rpm'), g2 = gauge(gg, 1000, 'Shaft speed', 'rpm');
  upd.push(() => { g1(L().n); g2(L().shaft); });
  const ks = el('div', 'cols'); b.appendChild(ks);
  cell(ks, 'Bucket position', W(i, '0100')); cell(ks, 'Steering position', W(i, '0101')); cell(ks, 'P hydraulic', W(i, '0036'));
  cell(ks, 'P lube engine', E(i, '0026')); cell(ks, 'T coolant', E(i, '0126')); cell(ks, 'T exhaust', E(i, '0145')); cell(ks, 'P gear control', G(i, '0001'));
  const fc = el('div', 'cell', '<span class="lbl">Fuel</span><span class="val">--</span><span class="unit">l/h</span>'); ks.appendChild(fc); upd.push(() => fc.children[1].textContent = (L().fuelNow || 0).toFixed(0));
  const rule = el('p', 'hint', 'Interlocks: clutch in only with engine running, speed near idle and lever in neutral. The safety system stops the engine on SS (shutdown) alarms unless the override is on; overspeed always stops it.');
  b.appendChild(rule);
}

function buildPower(root) {
  const g = el('div', 'grid g2'); root.appendChild(g);
  for (const i of [1, 2, 3]) {
    const c = el('div', 'card'); g.appendChild(c); c.innerHTML = `<h2>Genset ${i}</h2>`;
    const r = el('div', 'row'); c.appendChild(r);
    const run = btn(r, 'Start / stop', () => M.cmd.gen(i, !M.st.gens[i - 1].run));
    const cbk = btn(r, 'Breaker', () => { const x = M.cmd.cb(i, !M.st.gens[i - 1].cb); if (x) msg.textContent = x; });
    upd.push(() => { run.classList.toggle('on', M.st.gens[i - 1].run); cbk.classList.toggle('on', M.st.gens[i - 1].cb); });
    cell(c, 'Engine speed', D(i, '0004')); cell(c, 'P lube oil', D(i, '0001')); cell(c, 'T lube oil', D(i, '0002')); cell(c, 'T coolant', D(i, '0003'));
  }
  const msg = el('p', 'msg', ''); root.appendChild(msg);
  const t = el('div', 'card'); root.appendChild(t); t.innerHTML = '<h2>Tanks</h2>'; tankBars(t);
  const f = el('div', 'card'); root.appendChild(f); f.innerHTML = '<h2>Fire main</h2>'; cell(f, 'P fire main', 'PV500001');
}

function buildAlarms(root) {
  const st = M.st;
  const bar_ = el('div', 'row'); root.appendChild(bar_);
  ctl.view = ctl.view || 'Actual'; ctl.grp = ctl.grp || 'All'; ctl.pri = ctl.pri || 'All';
  const mk = (opts, key) => { const s = el('select'); opts.forEach(o => s.appendChild(new Option(o, o))); s.value = ctl[key]; s.onchange = () => { ctl[key] = s.value; }; bar_.appendChild(s); };
  mk(['Actual', 'Historical'], 'view'); mk(['All', ...TOPS], 'grp'); mk(['All', 'Red', 'Yel'], 'pri');
  btn(bar_, 'Acknowledge all', () => M.cmd.ackAll(a => (ctl.grp === 'All' || a.s.top === ctl.grp)));
  const cnt = el('span', 'hint', ''); bar_.appendChild(cnt);
  const sc = el('div', 'scroll'); root.appendChild(sc);
  const tb = el('table'); sc.appendChild(tb);
  let last = '';
  upd.push(() => {
    let rows = [];
    const okp = r => ctl.pri === 'All' || (ctl.pri === 'Red' ? r.red : !r.red);
    if (ctl.view === 'Actual') {
      const list = [...st.alarms.values()].filter(a => (ctl.grp === 'All' || a.s.top === ctl.grp) && okp(a.s)).sort((x, y) => y.seq - x.seq).slice(0, 300);
      const key = list.map(a => a.s.tag + a.active + a.ack).join();
      cnt.textContent = list.length + ' alarms';
      if (key === last) return; last = key;
      tb.innerHTML = '<tr><th>Time</th><th>State</th><th>Tag</th><th>Alarm text</th><th></th></tr>' + list.map(a => `<tr class="${a.s.red ? 'red' : 'yel'} ${a.ack ? '' : 'unack'} ${a.active ? '' : 'gone'}"><td>${tm(a.t)}</td><td>${a.active ? (a.ack ? 'ACK' : 'COME') : 'GONE'}</td><td class="mono">${a.s.tag}</td><td>${esc(MODEL.stripPrio(a.s.n))}<br><small>${esc(a.s.g)}</small></td><td>${a.ack ? '' : `<button class="sm" data-t="${a.s.tag}">Ack</button>`}</td></tr>`).join('');
      tb.querySelectorAll('button[data-t]').forEach(b => b.onclick = () => M.cmd.ack(b.dataset.t));
    } else {
      const list = st.hist.filter(h => (ctl.pri === 'All' || (ctl.pri === 'Red' ? h.red : !h.red))).slice(0, 300);
      const key = 'h' + st.hist.length + (st.hist[0] && st.hist[0].t);
      cnt.textContent = list.length + ' events';
      if (key === last) return; last = key;
      tb.innerHTML = '<tr><th>Time</th><th>Event</th><th>Tag</th><th>Alarm text</th></tr>' + list.map(h => `<tr class="${h.red ? 'red' : 'yel'} ${h.ev === 'GONE' ? 'gone' : ''}"><td>${tm(h.t)}</td><td>${h.ev}</td><td class="mono">${h.tag}</td><td>${esc(MODEL.stripPrio(h.n))}</td></tr>`).join('');
    }
  });
}

function buildNetwork(root) {
  const d = M.data, st = M.st;
  const c = el('div', 'card'); root.appendChild(c);
  c.innerHTML = `<h2>Process bus (CAN ${d.project.canBaud || 250} kbit/s) - controllers</h2><p class="hint">Tap a node to simulate a CAN node failure. All signals read through that node become invalid (???) and the LAN alarms are raised.</p>`;
  const w = el('div', 'row'); c.appendChild(w);
  d.nodes.forEach(n => { const b = btn(w, `${n.name}  (${n.type})`, () => { st.pbnFail[n.pbn] = !st.pbnFail[n.pbn]; }); upd.push(() => b.className = st.pbnFail[n.pbn] ? 'warn' : 'on'); });
  const t = el('div', 'card'); root.appendChild(t); t.innerHTML = '<h2>Field buses and devices</h2>';
  const tools = el('div', 'row'); t.appendChild(tools);
  const q = el('input'); q.type = 'search'; q.placeholder = 'filter e.g. 371 or PIM'; tools.appendChild(q);
  const dev = Object.fromEntries(d.devices.map(x => [x.id, x]));
  const holder = el('div', 'scroll'); t.appendChild(holder);
  const draw = () => {
    const f = q.value.toLowerCase();
    holder.innerHTML = '';
    d.buses.forEach(b => {
      const rows = b.members.filter(m => !f || (b.name + m.id + ((dev[m.id] || {}).type || '')).toLowerCase().includes(f));
      if (!rows.length) return;
      const h = el('h3', '', `${esc(b.name)} <small>${b.type || ''} ${b.baud ? b.baud + ' kbit/s' : ''}${b.redundant ? ' redundant' : ''}</small>`); holder.appendChild(h);
      const w2 = el('div', 'row'); holder.appendChild(w2);
      rows.forEach(m => { const x = dev[m.id]; const p = el('button', 'sm', `${m.node ?? ''} ${esc(m.id)} ${x ? esc(x.type) : ''}`); p.title = x ? x.cards.map(k => k.loc + ': ' + k.part).join('\n') : ''; p.onclick = () => { st.devFail[m.id] = !st.devFail[m.id]; p.classList.toggle('warn'); }; if (st.devFail[m.id]) p.classList.add('warn'); w2.appendChild(p); });
    });
  };
  q.oninput = draw; draw();
  if (d.serial && d.serial.length) { const s = el('div', 'card'); root.appendChild(s); s.innerHTML = '<h2>Serial interfaces</h2>' + d.serial.map(x => `<span class="pill">${esc(x.id)}: ${esc(x.proto)} ${x.baud || ''}</span>`).join(''); }
}

function buildSignals(root) {
  const c = el('div', 'card'); root.appendChild(c);
  c.innerHTML = '<h2>Signal browser</h2>';
  const r = el('div', 'row'); c.appendChild(r);
  const q = el('input'); q.type = 'search'; q.placeholder = 'tag or text, e.g. P-Lube or PV110026'; q.value = ctl.sq || ''; r.appendChild(q);
  const only = el('select'); ['All', 'Analog', 'Binary', 'Alarms only'].forEach(o => only.appendChild(new Option(o, o))); r.appendChild(only);
  const info = el('span', 'hint'); r.appendChild(info);
  const sc = el('div', 'scroll'); c.appendChild(sc);
  const tb = el('table'); sc.appendChild(tb);
  const sig = M.data.signals; let shown = [];
  const draw = () => {
    ctl.sq = q.value; const f = q.value.toLowerCase().trim();
    shown = sig.filter(s => (only.value === 'All' || (only.value === 'Analog' && s.t !== 'D' && s.u !== 'bin') || (only.value === 'Binary' && (s.t === 'D' || s.u === 'bin')) || (only.value === 'Alarms only' && s.pri != null)) && (!f || (s.tag + ' ' + s.n + ' ' + s.g).toLowerCase().includes(f))).slice(0, 120);
    info.textContent = shown.length + (shown.length === 120 ? '+ matches (showing 120)' : ' matches');
    tb.innerHTML = '<tr><th>Tag</th><th>Description</th><th>Value</th><th>Fault</th></tr>';
    shown.forEach(s => {
      const tr = el('tr'); const ana = s.t !== 'D' && s.u !== 'bin';
      tr.innerHTML = `<td class="mono">${s.tag}</td><td>${esc(s.n)}<br><small>${esc(s.g)}${s.item ? ' · ' + esc(s.item) : ''}</small></td><td class="mono v">--</td><td></td>`;
      const fc = tr.children[3];
      if (ana && !s.lim) {
        ['break', 'short', 'freeze'].forEach(k => { const b = el('button', 'sm', k); b.onclick = () => { if (k === 'freeze') { M.st.frozen = M.st.frozen || {}; M.st.frozen[s.tag] = M.value(s); } M.st.sensor[s.tag] = M.st.sensor[s.tag] === k ? undefined : k; draw2(); }; fc.appendChild(b); });
      } else if (!s.sdOf && !s.lanDev) { const b = el('button', 'sm', 'force'); b.onclick = () => { M.st.force[s.tag] = M.st.force[s.tag] ? undefined : 1; draw2(); }; fc.appendChild(b); }
      tb.appendChild(tr);
    });
    draw2();
  };
  const draw2 = () => { [...tb.rows].slice(1).forEach((tr, k) => { const s = shown[k]; tr.className = M.st.sensor[s.tag] || M.st.force[s.tag] != null ? 'yel' : ''; }); };
  q.oninput = draw; only.onchange = draw; draw();
  upd.push(() => { [...tb.rows].slice(1).forEach((tr, k) => { const s = shown[k]; if (!s) return; const v = M.value(s); tr.querySelector('.v').textContent = fmt(v, s) + (s.u && s.u !== 'bin' && s.t !== 'D' && v != null && !Number.isNaN(v) ? ' ' + s.u : ''); }); });
}

// ---------------------------------------------------------------- trainer
const faults = [];
function addFault(name, undo) { faults.push({ name, undo }); }
const RAMP = (l, k, rate, limit) => { const p = M.st.phys[l] = M.st.phys[l] || {}; p[k] = 0; return M.ramp(p, k, rate, limit); };
const PH = (l, k, v) => { const p = M.st.phys[l] = M.st.phys[l] || {}; p[k] = v; return () => { delete p[k]; }; };
function scenarios() {
  const st = M.st;
  return [
    { n: 'Falling lube oil pressure (slow, to warning)', h: 'Pressure sinks about 0.5 bar/min and levels off near the LO limit: LO alarm (yellow) at part load, engine keeps running (oil pressure depends on speed, so try a lever setting around 50-70 %). Use 5-10x speed.', run: l => RAMP(l, 'plo', -0.5, -2.7) },
    { n: 'Falling lube oil pressure (to shutdown)', h: 'Pressure keeps falling: LO alarm first, then SS (red) and the safety system stops the engine. Try the override.', run: l => RAMP(l, 'plo', -0.5, -6) },
    { n: 'Coolant temperature rising', h: 'T-coolant climbs about 3 °C/min: HI alarm, then SS shutdown if it is not corrected (use 5-10x speed).', run: l => RAMP(l, 'tcw', 3, 30) },
    { n: 'Lube oil temperature high', h: 'T-lube oil climbs about 3 °C/min: HI alarm.', run: l => RAMP(l, 'tlo', 3, 30) },
    { n: 'Exhaust temp. cylinder A3 rising', h: 'One cylinder runs hotter and hotter (e.g. injector fault) while the others stay normal: compare the cylinder bars on the Engine page. Alarm when it passes the HI limit.', run: l => { const p = st.phys[l] = st.phys[l] || {}; p.cyl = p.cyl || {}; return M.ramp(p.cyl, 'A3', 30, 300); } },
    { n: 'Engine overspeed', h: 'Engine runs away to 2400 rpm: overspeed shutdown cannot be overridden.', run: l => PH(l, 'overspeed', true) },
    { n: 'Engine will not start', h: 'Start fails after cranking: "Start failure" is recorded.', run: l => PH(l, 'nostart', true) },
    { n: 'Charge air temperature high', h: 'T-charge air climbs about 6 °C/min: HI alarm.', run: l => RAMP(l, 'tca', 6, 40) },
    { n: 'Gearbox control oil low', h: 'P-gear control oil sinks slowly: LO alarm.', run: l => RAMP(l, 'pgc', -2, -10) },
    { n: 'Gearbox lube oil low', h: 'P-gear lube oil sinks slowly: LO alarm.', run: l => RAMP(l, 'pgl', -0.5, -2.0) },
    { n: 'Waterjet hydraulic pump failure', h: 'Hydraulic pressure lost: bucket and steering stop responding.', run: l => PH(l, 'hyd', true) },
    { n: 'Waterjet bearing hot', h: 'Shaft plummer bearing heats up about 5 °C/min: HI alarm.', run: l => RAMP(l, 'plum', 5, 40) },
    { n: 'Sensor break: P-lube oil (ECU)', h: 'Sensor wire break: value goes to range minimum, sensor-defect (SD) alarm is raised, limit alarms are suppressed.', run: l => { const t = E(l, '0026'); st.sensor[t] = 'break'; return () => { delete st.sensor[t]; }; } },
    { n: 'Sensor short: T-coolant (ECU)', h: 'Sensor short circuit: value goes to range maximum, SD alarm.', run: l => { const t = E(l, '0126'); st.sensor[t] = 'short'; return () => { delete st.sensor[t]; }; } },
    { n: 'ECU supply voltage low', h: 'Supply drops to ~19 V.', run: l => PH(l, 'volt', -8) },
    { n: 'Genset trip', h: 'Genset stops: check power page and alarms.', run: l => { st.gens[l - 1].run = false; return () => {}; } },
    { n: 'Fire alarm', h: 'Fire alarm group.', run: () => { st.fire = true; return () => { st.fire = false; }; } },
    { n: 'Bilge high level', h: 'Bilge alarm group.', run: () => { st.bilge = true; return () => { st.bilge = false; }; } },
    { n: 'Watertight door open', h: 'Doors alarm group.', run: () => { st.door = true; return () => { st.door = false; }; } },
    { n: 'Tank leak', h: 'FO tank loses 0.3 m³/min (uses the Propulsion selector as tank number 1-6).', run: l => { const t = st.tanks[(l - 1) % st.tanks.length]; if (!t) return () => {}; st.phys['tank' + t.tag] = 0.3; return () => { delete st.phys['tank' + t.tag]; }; } },
  ];
}
function buildTrainer(root) {
  const c = el('div', 'card'); root.appendChild(c); c.innerHTML = '<h2>Fault trainer</h2><p class="hint">Inject a fault, then find it like on the ship: alarm list, engine page, signal browser. Real limits are not in the ACP package, so alarm thresholds are training defaults.</p>';
  const r = el('div', 'row'); c.appendChild(r); r.appendChild(el('span', '', 'Apply to:'));
  const sel = el('select'); [1, 2, 3].forEach(i => sel.appendChild(new Option('Line / unit ' + i, i))); sel.value = cur; r.appendChild(sel);
  const list = el('div'); c.appendChild(list);
  scenarios().forEach(s => { const row = el('div', 'fault'); const sp = el('span', '', `<b>${esc(s.n)}</b><div class="hint">${esc(s.h)}</div>`); row.appendChild(sp); btn(row, 'Apply', () => { const u = s.run(+sel.value); faults.push({ name: s.n + ' (' + sel.value + ')', undo: u }); redraw(); }, 'sm'); list.appendChild(row); });
  const a = el('div', 'card'); root.appendChild(a); a.innerHTML = '<h2>Active faults</h2>';
  const act = el('div'); a.appendChild(act);
  function redraw() {
    act.innerHTML = '';
    if (!faults.length) act.innerHTML = '<p class="hint">None.</p>';
    faults.forEach((f, k) => { const row = el('div', 'fault'); row.appendChild(el('span', '', esc(f.name))); btn(row, 'Remove', () => { try { f.undo && f.undo(); } catch (e) { } faults.splice(k, 1); redraw(); }, 'sm'); act.appendChild(row); });
    if (faults.length) btn(act, 'Clear all faults', clearAll, 'warn');
  }
  function clearAll() { const st = M.st; st.ramps.length = 0; faults.splice(0).forEach(f => { try { f.undo && f.undo(); } catch (e) { } }); st.phys = {}; st.sensor = {}; st.pbnFail = {}; st.devFail = {}; st.force = {}; st.fire = st.bilge = st.door = false; redraw(); }
  redraw();
}

function buildInfo(root) {
  const d = M.data, p = d.project;
  const c = el('div', 'card'); root.appendChild(c);
  c.innerHTML = `<h2>${esc(p.name)}</h2><div class="cols">${[['Order / project', p.order], ['Plant', p.plant], ['PU part number', p.puNumber], ['InTouch version', p.intouchVer], ['Process bus', (p.busType || '') + ' ' + (p.canBaud || '') + ' kbit/s'], ['MPC software', (p.mpc || '') + ' ' + (p.mpcEd || '')], ['Data generated', p.generated], ['Signals', d.signals.length], ['Alarms', d.signals.filter(s => s.pri != null).length], ['Parameters', d.params.reduce((a, x) => a + x.params.length, 0)]].map(([k, v]) => `<div class="cell"><span class="lbl">${k}</span><b>${esc(v ?? '')}</b></div>`).join('')}</div>`;
  const t = el('div', 'card'); root.appendChild(t); t.innerHTML = '<h2>Hardware</h2>' + d.deviceTypes.map(x => `<span class="pill">${esc(x.type)} × ${x.count}</span>`).join('');
  const n = el('div', 'card'); root.appendChild(n); n.innerHTML = '<h2>Controller nodes (software CRC)</h2><table><tr><th>PBN</th><th>Name</th><th>Device</th><th>Blocks</th><th>CRC</th><th>Loaded</th></tr>' + d.nodes.map(x => `<tr><td>${x.pbn}</td><td>${esc(x.name)}</td><td class="mono">${esc(x.id)}</td><td>${x.blocks ?? ''}</td><td class="mono">${x.crc ?? ''}</td><td>${esc(x.date || '')}</td></tr>`).join('') + '</table>';
  const sw = el('div', 'card'); root.appendChild(sw); sw.innerHTML = '<h2>Software versions</h2><div class="scroll"><table><tr><th>Group</th><th>Name</th><th>Var</th><th>Edition</th><th>Date</th></tr>' + d.software.map(x => `<tr><td>${esc(x.group)}</td><td>${esc(x.name)}</td><td>${esc(x.var)}</td><td>${esc(x.ed)}</td><td>${esc(x.date)}</td></tr>`).join('') + '</table></div>';
  const pa = el('div', 'card'); root.appendChild(pa); pa.innerHTML = '<h2>Parameters</h2>';
  const sel = el('select'); d.params.forEach((x, k) => sel.appendChild(new Option(`${x.device} (${x.params.length})`, k))); pa.appendChild(sel);
  const sc = el('div', 'scroll'); pa.appendChild(sc);
  const draw = () => { const x = d.params[+sel.value]; sc.innerHTML = x ? '<table><tr><th>No</th><th>Name</th><th>Value</th><th>Unit</th><th>Description</th></tr>' + x.params.map(q => `<tr><td>${q.no}</td><td>${esc(q.n)}</td><td class="mono">${esc(q.val)}</td><td>${esc(q.u)}</td><td>${esc(q.d)}</td></tr>`).join('') + '</table>' : ''; };
  sel.onchange = draw; draw();
}

// ---------------------------------------------------------------- shell
function go(id) {
  screen = id; upd = [];
  const v = $('#view'); v.innerHTML = '';
  try { SCREENS[id].build(v); } catch (e) { v.innerHTML = '<div class="card"><h2>Screen error</h2><pre>' + esc(e.stack) + '</pre></div>'; console.error(e); }
  document.querySelectorAll('#nav button').forEach(b => b.classList.toggle('act', b.dataset.id === id));
  refresh();
}
function refresh() {
  if (!M) return;
  for (const u of upd) { try { u(); } catch (e) { console.error(e); } }
  $('#clock').textContent = clockStr(M.st.clock);
  let red = 0, yel = 0, unR = 0, unY = 0;
  for (const a of M.st.alarms.values()) { if (!a.active && a.ack) continue; if (a.s.red) { red++; if (!a.ack) unR++; } else { yel++; if (!a.ack) unY++; } }
  $('#abRed').textContent = red; $('#abYel').textContent = yel;
  $('#abTxt').textContent = red + yel ? (unR + unY ? `${unR + unY} unacknowledged` : 'all acknowledged') : 'No alarms';
  $('#alarmbar').className = 'alarmbar' + (unR ? ' blinkR' : unY ? ' blinkY' : '');
  const badge = $('#nav button[data-id=alarms] .badge'); if (badge) badge.textContent = unR + unY || '';
  if (M.st.newAlarm) { M.st.newAlarm = false; if (unR && !muted) beep(); }
}
function beep() { try { audio = audio || new (window.AudioContext || window.webkitAudioContext)(); const o = audio.createOscillator(), g = audio.createGain(); o.frequency.value = 880; g.gain.value = .08; o.connect(g); g.connect(audio.destination); o.start(); o.stop(audio.currentTime + .25); } catch (e) { } }

function start(data) {
  if (!data || data.format !== 'mcs5-trainer/1') { $('#loaderMsg').textContent = 'This is not a ship data pack (format mcs5-trainer/1).'; $('#loader').hidden = false; return; }
  M = MODEL.create(data); buildGroups(data);
  $('#loader').hidden = true; $('#shipname').textContent = data.project.name + ' · ' + (data.project.plant || '');
  const nav = $('#nav'); nav.innerHTML = '';
  Object.entries(SCREENS).forEach(([id, s]) => { const b = el('button', '', esc(s.label) + (id === 'alarms' ? '<span class="badge"></span>' : '')); b.dataset.id = id; b.onclick = () => go(id); nav.appendChild(b); });
  $('#alarmbar').onclick = () => go('alarms');
  $('#speed').onchange = e => M.st.speed = +e.target.value;
  $('#mute').onclick = e => { muted = !muted; e.target.textContent = muted ? '🔕' : '🔔'; };
  let last = performance.now();
  setInterval(() => { const n = performance.now(); M.step((n - last) / 1000); last = n; }, 100);
  setInterval(refresh, 250);
  go(screen);
  window.__M = M;
}
async function boot() {
  $('#file').onchange = async e => { const f = e.target.files[0]; if (!f) return; try { const t = await f.text(); const d = JSON.parse(t); try { localStorage.setItem('mcs5ship', t); } catch (x) { } start(d); } catch (x) { $('#loaderMsg').textContent = 'Could not read file: ' + x.message; } };
  let data = null;
  try { const r = await fetch('ship.json'); if (r.ok) data = await r.json(); } catch (e) { }
  if (!data) { try { const t = localStorage.getItem('mcs5ship'); if (t) data = JSON.parse(t); } catch (e) { } }
  if (data) start(data); else $('#loader').hidden = false;
}
boot();
