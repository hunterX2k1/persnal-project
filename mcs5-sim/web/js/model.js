/* MCS-5 Trainer - plant model
 * Builds a live simulation from a ship data pack produced by tools/acp2json.py.
 * Every signal in the pack gets a value source:
 *   - analog signals are classified by name (e.g. "P-Lube Oil (ECU)") and driven
 *     by the physical model of their propulsion line / genset / tank
 *   - limit tags (L1L, U2L, ...) return the simulated limit value
 *   - binary alarms (LO/HI/SS/SD/...) are evaluated against the analog values,
 *     sensor faults, bus/device failures, or forced by the trainer
 * Limit values and dynamics are TRAINING DEFAULTS, not the values from the
 * engine ECU dataset (those are not part of the ACP package).
 */
'use strict';

const MODEL = (() => {
  const NMAX = 2100, NIDLE = 600, GEAR = 2.2;

  // ---------------- tag helpers ----------------
  const sysOf = tag => { const m = /^[A-Z]{2}(\d{3})/.exec(tag); return m ? +m[1] : 0; };
  function lineOf(tag) {
    const s = sysOf(tag), h = Math.floor(s / 100), d = Math.floor(s / 10) % 10;
    if (h === 1) { if (d >= 1 && d <= 4) return d; if (d >= 6 && d <= 8) return d - 5; }
    if (h === 2 && d >= 1 && d <= 3) return d;
    if (h === 3 && d >= 1 && d <= 3) return d;
    if (h === 4 && d >= 1 && d <= 3) return d;
    if (h === 6 && d >= 1 && d <= 8) return ((d - 1) % 3) + 1;
    if (h === 8 && d >= 1 && d <= 3) return d;
    return 0;
  }
  function domainOf(tag, grp) {
    const s = sysOf(tag), h = Math.floor(s / 100), d = Math.floor(s / 10) % 10;
    if (h === 1) return (d >= 6) ? 'F' : 'E';
    if (h === 2) return 'G';
    if (h === 3) return 'W';
    if (h === 4) return 'D';
    if (h === 5) return 'T' + d;
    if (h === 8) return 'R';
    return 'X' + h;
  }
  const LIMRX = /^(L1L|L2L|U1L|U2L|UL|LL|HL|L1H|L2H)\s+/;
  const KINDRX = /^(LO|HI|SS|SD|AL|WF|TD|MG|DUM|WB|LOLO|HIHI|L1L|L2L)\s+/;
  const stripPrio = n => n.replace(/^(Yel|Red|Gre|Blu)\s+/, '');
  const normQ = q => q.toLowerCase().replace(/\s+/g, ' ').trim();
  const baseQ = q => normQ(q).replace(/\s*\((ecu|emu|meu|pmu|pcu rev|pcu|visu|mcs|a)\)\s*$/, '').trim();

  // ---------------- default limits ----------------
  // [lo1, lo2, hi1, hi2]  (null = none)
  const LIMITS = [
    [/^p-lube oil pt21/, 1.0, null, null, null],
    [/^p-lube oil/, 3.0, 2.5, null, null],
    [/^p-coolant/, 1.0, 0.7, null, null],
    [/^t-coolant/, null, null, 95, 99],
    [/^t-lube oil/, null, null, 93, 98],
    [/^t-charge air seq/, null, null, 160, 180],
    [/^t-charge air/, null, null, 70, 80],
    [/^p-charge air/, 0.8, null, 5.5, null],
    [/^p-fuel/, 3.0, 2.0, null, null],
    [/^t-fuel/, null, null, 60, 70],
    [/^p-crankcase/, null, null, 10, 15],
    [/^etc \d speed/, null, null, 88, 95],
    [/^ecu power supply/, 20, 18, 32, 34],
    [/^t-ecu/, null, null, 80, 85],
    [/^t-exhaust/, 150, null, 680, 720],
    [/^p-raw water/, 0.5, null, null, null],
    [/^t-bearing/, null, null, 110, 120],
    [/^t-splash oil/, null, null, 120, 130],
    [/^engine speed/, null, null, 2250, 2350],
    [/^ul engine speed/, null, null, 2250, 2350],
    [/^p-gear control oil/, 15, 12, null, null],
    [/^p-gear lube oil/, 1.5, 1.0, null, null],
    [/^t-gear oil/, null, null, 85, 90],
    [/^t-gear thrust/, null, null, 90, 95],
    [/^p-hydraulic oil/, 60, null, null, null],
    [/^t-oil tt1/, null, null, 65, null],
    [/^t-plummer/, null, null, 80, null],
    [/^p-fire main/, 4, null, null, null],
  ];
  function limitsFor(q) {
    const s = baseQ(q);
    for (const L of LIMITS) if (L[0].test(s)) return { lo1: L[1], lo2: L[2], hi1: L[3], hi2: L[4] };
    return { lo1: null, lo2: null, hi1: null, hi2: null };
  }

  // ---------------- state ----------------
  function newState(data) {
    const st = {
      t: 0, clock: Date.now(), speed: 1,
      station: 'MCC', estop: false, override: false,
      lines: [1, 2, 3].map(i => ({
        i, eng: 'STOP', n: 0, dem: 0, crank: 0, clutch: false, lever: 0, steer: 0,
        rev: 0, revCmd: 0, steerPos: 0, hydPump: true, load: 0, hours: 12000 + i * 317,
        tlo: 32, tcw: 38, tgo: 30, fuelTrip: 0, fuelTot: 4.1e5 + i * 1e4, reduce: false,
        startFail: 0, stopCause: ''
      })),
      gens: [1, 2, 3].map(i => ({ i, run: i <= 2, n: i <= 2 ? 1500 : 0, cb: i <= 2, tlo: 82, tcw: 80 })),
      tanks: (data.tanks || []).map((t, k) => ({ ...t, cap: /fresh/i.test(t.name) ? 15 : (/ready/i.test(t.name) ? 6 : 22), vol: 0 })),
      sensor: {},     // tag -> 'break' | 'short' | 'freeze' | number(offset)
      devFail: {},    // device id -> true
      pbnFail: {},    // pbn -> true
      force: {},      // tag -> 1 (binary force) / number (analog force)
      phys: {},       // line -> {plo:-x, ...} physical fault offsets
      fire: false, bilge: false, door: false, ramps: [],
      hist: [], alarms: new Map(), seq: 0,
    };
    st.tanks.forEach((t, k) => t.vol = t.cap * (0.55 + 0.08 * k % 0.4));
    return st;
  }

  // ---------------- index & classification ----------------
  function build(data) {
    const sig = data.signals;
    const byTag = new Map(sig.map(s => [s.tag, s]));
    const busToPbn = {}, devOnBus = {};
    const pb = (data.buses || []).find(b => b.name === 'PB');
    const pbnOfDev = {};
    if (pb) pb.members.forEach(m => pbnOfDev[m.id] = m.node);
    (data.buses || []).forEach(b => {
      const head = b.members[0] && b.members[0].id;
      busToPbn[b.name] = pbnOfDev[head];
      b.members.forEach(m => { devOnBus[b.name + '#' + m.node] = m.id; });
    });
    const analog = new Map();  // key line|dom|baseQ -> signal
    for (const s of sig) {
      s.line = lineOf(s.tag); s.dom = domainOf(s.tag, s.g);
      s.red = s.pri != null && s.pri <= 2;
      if (s.bus) {
        s.pbn = s.bus === 'PB' ? null : busToPbn[s.bus];
        s.dev = devOnBus[s.bus + '#' + s.node] || null;
      }
      if (s.t !== 'D' && s.u !== 'bin') {
        const m = LIMRX.exec(s.n);
        if (m) { s.lim = m[1]; s.q = s.n.slice(m[0].length); }
        else s.q = s.n;
        s.bq = baseQ(s.q);
        if (!s.lim) {
          const k1 = s.line + '|' + s.dom + '|' + normQ(s.q);
          if (!analog.has(k1)) analog.set(k1, s);
          const k2 = s.line + '|' + s.dom + '|' + s.bq;
          if (!analog.has(k2)) analog.set(k2, s);
        }
      } else {
        const txt = stripPrio(s.n);
        const m = KINDRX.exec(txt);
        s.kind = m ? m[1] : '';
        s.q = m ? txt.slice(m[0].length) : txt;
        s.bq = baseQ(s.q);
      }
      if (/^SD/.test(s.tag)) s.sdOf = 'PV' + s.tag.slice(2);
      const dm = /=\d{3}\+[A-Z0-9]+-[A-Z0-9]+/.exec(s.n);
      if (s.g && /^LAN/.test(s.g) && dm) s.lanDev = dm[0];
    }
    // link binary alarms to analog source
    for (const s of sig) {
      if (s.kind && s.kind !== 'SD') {
        const src = analog.get(s.line + '|' + s.dom + '|' + normQ(s.q)) || analog.get(s.line + '|' + s.dom + '|' + s.bq);
        if (src) s.src = src.tag;
      }
    }
    return { byTag, analog, pbnOfDev, busToPbn, devOnBus };
  }

  // ---------------- physics ----------------
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const approach = (v, target, tau, dt) => v + (target - v) * (1 - Math.exp(-dt / tau));

  function stepLine(st, L, dt, ix) {
    const ph = st.phys[L.i] || {};
    // demand from lever (combinator): 0..100 % lever -> idle..max speed
    const lev = Math.abs(L.lever) / 100;
    let dem = NIDLE + (NMAX - NIDLE) * lev;
    if (L.reduce) dem = Math.min(dem, NIDLE + (NMAX - NIDLE) * 0.6);
    if (!L.clutch) dem = Math.min(dem, NIDLE + 200 * lev);
    L.dem = dem;
    // waterjet bucket: lever sign -> ahead/astern ; steering
    L.revCmd = L.clutch ? (L.lever >= 0 ? 100 : -100) * Math.min(1, Math.abs(L.lever) / 10) : 0;
    if (L.lever === 0) L.revCmd = 0;
    const hyd = L.hydPump && !ph.hyd;
    if (hyd) {
      L.rev = approach(L.rev, L.revCmd, 0.8, dt);
      L.steerPos = approach(L.steerPos, L.steer, 0.8, dt);
    }

    switch (L.eng) {
      case 'STOP':
        L.n = approach(L.n, 0, 2.5, dt); break;
      case 'CRANK':
        L.crank += dt;
        L.n = approach(L.n, 180, 0.6, dt);
        if (L.crank > 2.5) {
          if (ph.nostart) { L.eng = 'STOP'; L.startFail++; L.stopCause = 'Start failure'; }
          else L.eng = 'RUN';
        }
        break;
      case 'RUN': {
        const tgt = ph.overspeed ? 2400 : L.dem;
        L.n = approach(L.n, tgt, ph.overspeed ? 1.2 : 2.2, dt);
        break;
      }
    }
    const r = L.n / NMAX, running = L.eng === 'RUN';
    L.load = running ? clamp((L.clutch ? Math.pow(r, 3) : 0.02) * (1 + (ph.load || 0)), 0, 1.2) : 0;
    const ld = L.load;
    // thermal
    L.tcw = approach(L.tcw, running ? 79 + 6 * ld + (ph.tcw || 0) : 38, running ? 90 : 600, dt);
    L.tlo = approach(L.tlo, running ? 72 + 14 * ld + (ph.tlo || 0) : 35, running ? 120 : 700, dt);
    L.tgo = approach(L.tgo, running && L.clutch ? 58 + 16 * ld + (ph.tgo || 0) : 32, 150, dt);
    const fuel = running ? 22 + 690 * ld : 0;   // l/h
    L.fuelNow = fuel;
    L.fuelTrip += fuel * dt / 3600; L.fuelTot += fuel * dt / 3600;
    if (running) L.hours += dt / 3600;
    L.runT = L.eng === 'RUN' ? (L.runT || 0) + dt : 0;
    L.shaft = L.clutch ? L.n / GEAR : 0;
  }

  // physical value of a classified analog signal
  function physical(st, s) {
    const q = s.bq, i = s.line;
    const L = st.lines[i - 1];
    if (s.dom === 'E' && L) {
      const ph = st.phys[i] || {}, r = L.n / NMAX, ld = L.load, run = L.eng === 'RUN', turn = L.n > 50;
      if (/^engine speed .*%|0-100%/.test(q)) return r * 100;
      if (/engine speed|speed camshaft|speed crankshaft/.test(q)) return L.n;
      if (/^p-lube oil before filter/.test(q)) return turn ? 3.4 + 3.6 * r + (ph.plo || 0) + 0.6 : 0;
      if (/^p-lube oil/.test(q)) return turn ? Math.max(0, 2.8 + 3.4 * r + (ph.plo || 0)) : 0;
      if (/^p-oil re-fill/.test(q)) return 0;
      if (/^p-coolant/.test(q)) return turn ? Math.max(0, 1.4 + 1.6 * r + (ph.pcw || 0)) : 0.2;
      if (/^t-coolant/.test(q)) return L.tcw;
      if (/^t-lube oil/.test(q)) return L.tlo;
      if (/^p-charge air/.test(q)) return 1.0 + (run ? 3.1 * ld : 0);
      if (/^t-charge air seq/.test(q)) return run ? 40 + 90 * ld : 30;
      if (/^t-charge air/.test(q)) return run ? 44 + 10 * ld + (ph.tca || 0) : 30;
      if (/^t-intake air/.test(q)) return 32;
      if (/^p-fuel \(common rail\)/.test(q)) return run ? 800 + 1000 * ld : 0;
      if (/^p-fuel/.test(q)) return turn ? Math.max(0, 5.2 + 0.6 * r + (ph.pfuel || 0)) : 0;
      if (/^t-fuel/.test(q)) return run ? 38 + 6 * ld : 30;
      if (/^p-crankcase/.test(q)) return run ? 1.5 + 2 * ld + (ph.pcc || 0) : 0;
      const etc = /^etc (\d) speed/.exec(q);
      if (etc) { const k = +etc[1], cut = [0, 0, 0.3, 0.55, 0.75][k] || 0;
        return run && ld >= cut ? 18 + 64 * Math.min(1, (ld - cut) / (1 - cut + 1e-6) * 0.6 + ld * 0.4) : 0; }
      if (/^ecu power supply/.test(q)) return 27.4 + (ph.volt || 0);
      if (/^t-ecu/.test(q)) return 46;
      if (/operat\.hours/.test(q)) return L.hours;
      if (/^trip duration/.test(q)) return L.hours % 24;
      if (/^actual fuel consumption|mean trip fuel/.test(q)) return L.fuelNow;
      if (/^trip fuel/.test(q)) return L.fuelTrip;
      if (/^total fuel/.test(q)) return L.fuelTot;
      if (/^actual p-grade|injection in relation/.test(q)) return ld * 100;
      const cyl = /^t-exhaust ([ab])(\d+)/.exec(q);
      if (cyl) { const k = (cyl[1] === 'a' ? 0 : 10) + (+cyl[2]);
        const off = Math.sin(k * 2.3) * 14, f = (ph.cyl && ph.cyl[cyl[1].toUpperCase() + cyl[2]]) || 0;
        return run ? 255 + 335 * ld + off + f : Math.max(30, L.tcw - 5); }
      if (/^t-exhaust (mean|combined)/.test(q)) return run ? 255 + 335 * ld : 35;
      if (/^t-exhaust max/.test(q)) return run ? 255 + 335 * ld + 14 + maxCylFault(ph) : 35;
      if (/^t-exhaust min/.test(q)) return run ? 255 + 335 * ld - 14 : 35;
      if (/^p-raw water/.test(q)) return turn ? 0.3 + 1.6 * r + (ph.praw || 0) : 0;
      const brg = /^t-bearing (\d+|mean)/.exec(q);
      if (brg) return (run ? 72 + 28 * ld : L.tlo) + (brg[1] !== 'mean' ? Math.sin(+brg[1]) * 3 + (ph.brg && ph.brg[brg[1]] || 0) : 0);
      const so = /^t-splash oil b(\d+)/.exec(q);
      if (so) return (run ? 84 + 24 * ld : L.tlo) + Math.cos(+so[1]) * 2 + (ph.splash && ph.splash[so[1]] || 0);
      if (/power_supply emu/.test(q)) return 1.0;
      return null;
    }
    if (s.dom === 'G' && L) {
      const ph = st.phys[i] || {}, turn = L.n > 50;
      if (/^p-gear control oil/.test(q)) return s.u === 'mA' ? 4 + 16 * (turn ? 22 : 0) / 40 : (turn ? 22 + (ph.pgc || 0) : 0);
      if (/^p-gear lube oil/.test(q)) { const p = turn ? Math.max(0, 3.1 + (ph.pgl || 0)) : 0; return s.u === 'mA' ? 4 + 16 * p / 10 : p; }
      if (/^t-gear oil/.test(q)) return L.tgo;
      if (/^t-gear thrust/.test(q)) return L.tgo - 4 + 18 * L.load;
      if (/instr range max/.test(q)) return 1000;
      if (/instr range half/.test(q)) return 500;
      if (/instr range min/.test(q)) return 0;
      if (/^shaft speed norm/.test(q)) return L.shaft / (NMAX / GEAR) * 100;
      if (/^shaft speed/.test(q)) return L.shaft;
      if (/trolling feedback \(0-10v\)/.test(q)) return 0;
      if (/trolling feedback/.test(q)) return 4;
      return null;
    }
    if (s.dom === 'W' && L) {
      const ph = st.phys[i] || {}, sh = L.shaft / (NMAX / GEAR);
      if (/^reversing position/.test(q)) return s.u === 'mA' ? 4 + 16 * (L.rev + 100) / 200 : L.rev;
      if (/^steering position/.test(q)) return s.u === 'mA' ? 4 + 16 * (L.steerPos + 100) / 200 : L.steerPos;
      if (/^instrument steering/.test(q)) return L.steerPos;
      if (/^instrument reversing/.test(q)) return L.rev;
      if (/^p-lube oil pt21/.test(q)) return L.shaft > 20 ? Math.max(0, 2.4 + 0.4 * sh + (ph.pwl || 0)) : 0;
      if (/^p-hydraulic oil/.test(q)) return L.hydPump && !ph.hyd ? 125 + (ph.phyd || 0) : 0;
      if (/^t-oil tt1/.test(q)) return 38 + 14 * sh + (ph.twj || 0);
      if (/^t-plummer/.test(q)) return 34 + 26 * sh + (ph.plum || 0);
      if (/zero shift/.test(q)) return 0;
      return null;
    }
    if (s.dom === 'D') {
      const G = st.gens[i - 1]; if (!G) return null;
      if (/^p-lube oil/.test(q)) return G.n > 100 ? 4.4 + ((st.phys['g' + i] || {}).plo || 0) : 0;
      if (/^t-lube oil/.test(q)) return G.tlo;
      if (/^t-coolant/.test(q)) return G.tcw;
      if (/^engine speed/.test(q)) return G.n;
      return null;
    }
    if (s.dom === 'T0') { if (/fire main/.test(q)) return st.firePump === false ? 0.3 : 7.2 + ((st.phys.fire || {}).p || 0); }
    if (/^T1/.test(s.dom)) {
      const tk = st.tanks.find(t => t.tag === s.tag || t.raw === s.tag || t.level === s.tag);
      if (tk) { const f = tk.vol / tk.cap;
        if (s.tag === tk.raw) return 4 + 16 * f;
        if (s.tag === tk.level) return f * 2200;
        return tk.vol; }
    }
    if (s.dom === 'R' && /autopilot/.test(q)) return 12;
    return null;
  }
  function maxCylFault(ph) { if (!ph.cyl) return 0; return Math.max(0, ...Object.values(ph.cyl)); }

  function limitValue(st, s, IX) {
    const lim = limitsFor(s.q);
    switch (s.lim) {
      case 'L1L': case 'LL': return lim.lo1 ?? 0;
      case 'L2L': return lim.lo2 ?? lim.lo1 ?? 0;
      case 'U1L': case 'UL': case 'HL': case 'L1H': return lim.hi1 ?? s.max;
      case 'U2L': case 'L2H': return lim.hi2 ?? lim.hi1 ?? s.max;
    }
    return 0;
  }

  // ---------------- binary evaluation ----------------
  const PRESSLIKE = /^(p-|level|lo |speed demand)/;
  function evalBinary(st, s, IX, val) {
    if (st.force[s.tag] != null) return !!st.force[s.tag];
    // LAN / device alarms
    if (s.lanDev) { const p = IX.pbnOfDev[s.lanDev]; return !!(st.devFail[s.lanDev] || (p != null && st.pbnFail[p])); }
    if (s.sdOf) { const t = IX.byTag.get(s.sdOf); return sensorBad(st, t, IX); }
    const L = st.lines[s.line - 1];
    if (s.src) {
      const src = IX.byTag.get(s.src), v = val(src);
      if (v == null || Number.isNaN(v)) return s.kind === 'SD';
      const lim = limitsFor(src.q);
      const G = s.dom === 'D' ? st.gens[s.line - 1] : null;
      const engRun = G ? (G.run && G.n > 1200) : (!L || (s.dom !== 'E' && s.dom !== 'G' && s.dom !== 'W') || (L.eng === 'RUN' && L.runT > 8 && (s.dom !== 'W' || L.clutch)));   // low-pressure alarms wait for engine start-up (8 s) / clutch
      const isLow = s.kind === 'LO' || s.kind === 'LOLO' || (s.kind === 'SS' && PRESSLIKE.test(src.bq));
      if (s.kind === 'SD' || s.kind === 'WB' || s.kind === 'WF') return sensorBad(st, src, IX);
      if (sensorBad(st, src, IX)) return false;          // defective sensor: limit alarms suppressed, SD alarm raised
      if (isLow) {
        if (!engRun) return false;                     // LO alarms interlocked with "engine running"
        const l = (s.kind === 'SS' || s.kind === 'LOLO') ? (lim.lo2 ?? lim.lo1) : lim.lo1;
        return l != null && v < l;
      }
      if (s.kind === 'HI' || s.kind === 'SS' || s.kind === 'HIHI') {
        const l = (s.kind === 'SS' || s.kind === 'HIHI') ? (lim.hi2 ?? lim.hi1) : lim.hi1;
        return l != null && v > l;
      }
      return false;
    }
    if (s.kind === 'WB' || s.kind === 'WF') return false;   // wire break / wire fault: only via trainer
    // keyword driven status / alarm logic
    const q = s.bq;
    if (L) {
      if (/^stop activated/.test(q)) return L.eng === 'STOP';
      if (/overspeed/.test(q)) return L.n > 2300;
      if (/el\. hydraulic pump\d?, running/.test(q)) return L.hydPump;
      if (/el\. hydraulic pump remote/.test(q)) return true;
      if (/reversing ahead/.test(q)) return L.rev > 5;
      if (/reversing astern/.test(q)) return L.rev < -5;
      if (/bucket zero position/.test(q)) return Math.abs(L.rev) <= 5;
      if (/shaft lock\. device, disengaged/.test(q)) return true;
      if (/clutch.*(engaged|in)\b/.test(q)) return L.clutch;
      if (/power reduct/.test(q)) return L.reduce;
      if (/engine running|running$/.test(q) && s.dom === 'E') return L.eng === 'RUN';
      if (/autom\. power reduct/.test(q)) return L.reduce;
      if (/start fail/.test(q)) return L.startFail > 0 && L.eng === 'STOP';
      if (/emergency stop/.test(q)) return st.estop;
      if (/override/.test(q)) return st.override;
    }
    if (s.dom === 'D') {
      const G = st.gens[s.line - 1];
      if (G) {
        if (/generator on/.test(q)) return G.run && G.cb;
        if (/overspeed/.test(q)) return G.n > 1700;
      }
    }
    if (/^fire|fire alarm|fire detect/.test(q)) return st.fire;
    if (/bilge/.test(q)) return st.bilge;
    if (/door.*open|open.*door/.test(q)) return st.door;
    return false;
  }

  function sensorBad(st, s, IX) {
    if (!s) return false;
    if (st.sensor[s.tag] === 'break' || st.sensor[s.tag] === 'short') return true;
    return false;
  }

  // ---------------- engine ----------------
  function create(data) {
    const IX = build(data);
    const st = newState(data);
    const cache = new Map();

    function invalid(s) {
      if (s.pbn != null && st.pbnFail[s.pbn]) return true;
      if (s.dev && (st.devFail[s.dev] || (IX.pbnOfDev[s.dev] != null && st.pbnFail[IX.pbnOfDev[s.dev]]))) return true;
      return false;
    }

    function value(s) {
      if (!s) return null;
      if (cache.has(s.tag)) return cache.get(s.tag);
      let v;
      if (invalid(s)) v = NaN;
      else if (s.t === 'D' || s.u === 'bin' || s.lanDev) v = evalBinary(st, s, IX, value) ? 1 : 0;
      else if (st.force[s.tag] != null) v = +st.force[s.tag];
      else {
        const sf = st.sensor[s.tag];
        if (sf === 'break') v = s.min ?? 0;
        else if (sf === 'short') v = s.max ?? 0;
        else {
          v = s.lim ? limitValue(st, s, IX) : physical(st, s);
          if (v == null) v = s.u === 'mA' ? 4 : 0;
          if (!s.lim && v !== 0 && /°C|bar|mbar|V|krpm|%/.test(s.u)) v += noise(s.tag) * (s.u === '°C' ? 0.4 : s.u === 'bar' ? 0.03 : 0.1);
          if (typeof sf === 'number') v += sf;
          if (sf === 'freeze') { v = (st.frozen && st.frozen[s.tag]) ?? v; }
        }
      }
      cache.set(s.tag, v);
      return v;
    }
    function noise(tag) { let h = 0; for (let k = 0; k < tag.length; k++) h = (h * 31 + tag.charCodeAt(k)) | 0; return Math.sin(h + st.t * 1.7) * 0.5 + Math.sin(h * 0.3 + st.t * 0.37) * 0.5; }

    const alarmSigs = data.signals.filter(s => s.pri != null);

    function step(dtReal) {
      const dt = Math.min(0.5, dtReal) * st.speed;
      st.t += dt; st.clock += dt * 1000;
      for (const r of st.ramps) { const v = (r.obj[r.key] || 0) + r.rate * dt / 60; r.obj[r.key] = r.rate < 0 ? Math.max(v, r.limit) : Math.min(v, r.limit); }
      for (const L of st.lines) stepLine(st, L, dt, IX);
      for (const G of st.gens) {
        const ph = st.phys['g' + G.i] || {};
        G.n = approach(G.n, G.run ? (ph.overspeed ? 1800 : 1500) : 0, 1.5, dt);
        G.tlo = approach(G.tlo, G.run ? 84 + (ph.tlo || 0) : 35, G.run ? 100 : 600, dt);
        G.tcw = approach(G.tcw, G.run ? 81 + (ph.tcw || 0) : 35, G.run ? 100 : 600, dt);
        if (!G.run) G.cb = false;
      }
      const consum = st.lines.reduce((a, L) => a + (L.fuelNow || 0), 0) / 3600 / 1000 * dt;
      const fo = st.tanks.filter(t => /ready/i.test(t.name));
      fo.forEach(t => t.vol = Math.max(0, t.vol - consum / Math.max(1, fo.length) - ((st.phys['tank' + t.tag] || 0) * dt / 60)));
      st.tanks.forEach(t => { const lk = st.phys['tank' + t.tag]; if (lk && !/ready/i.test(t.name)) t.vol = Math.max(0, t.vol - lk * dt / 60); });
      cache.clear();
      // alarms
      const now = st.clock;
      for (const s of alarmSigs) {
        let on;
        if (s.t === 'D' || s.u === 'bin' || s.lanDev) on = value(s) === 1;
        else { const v = value(s); on = Number.isNaN(v) ? false : (s.hi != null ? v >= s.hi : false); }
        if (Number.isNaN(value(s))) on = false;
        let a = st.alarms.get(s.tag);
        if (on && (!a || !a.active)) {
          if (!a) { a = { s, ack: false, active: true, t: now, seq: ++st.seq }; st.alarms.set(s.tag, a); }
          else { a.active = true; a.ack = false; a.t = now; a.seq = ++st.seq; }
          st.hist.unshift({ t: now, tag: s.tag, n: s.n, g: s.g, red: s.red, ev: 'COME' });
          st.newAlarm = true;
        } else if (!on && a && a.active) {
          a.active = false; a.tGone = now;
          st.hist.unshift({ t: now, tag: s.tag, n: s.n, g: s.g, red: s.red, ev: 'GONE' });
          if (a.ack) st.alarms.delete(s.tag);
        }
      }
      if (st.hist.length > 2000) st.hist.length = 2000;
      // safety system: red SS / overspeed -> engine shutdown unless override
      for (const L of st.lines) {
        if (L.eng !== 'RUN') continue;
        let ss = null, red = false;
        for (const a of st.alarms.values()) {
          if (!a.active || a.s.line !== L.i || a.s.dom !== 'E') continue;
          if (a.s.kind === 'SS' && /power reduct/.test(a.s.bq)) continue;
          if (a.s.kind === 'SS' || /overspeed/.test(a.s.bq)) { ss = a.s; break; }
          if (a.s.red) red = true;
        }
        if (L.n > 2300) ss = ss || { n: 'Overspeed' };
        if (ss && (!st.override || /overspeed/i.test(ss.n))) { L.eng = 'STOP'; L.clutch = false; L.stopCause = 'Safety shutdown: ' + stripPrio(ss.n); }
        L.reduce = red && !st.override;
      }
    }

    // ---------------- commands (RCS) ----------------
    const cmd = {
      start(i) { const L = st.lines[i - 1]; if (st.estop) return 'Emergency stop active';
        if (L.eng !== 'STOP') return 'Engine not stopped';
        if (L.clutch) return 'Start interlock: clutch engaged';
        if (L.n > 20) return 'Start interlock: engine still turning';
        L.eng = 'CRANK'; L.crank = 0; L.stopCause = ''; return 'Start sequence initiated'; },
      stop(i) { const L = st.lines[i - 1]; L.eng = 'STOP'; L.clutch = false; L.stopCause = 'Stop request ' + st.station; return 'Stop'; },
      clutch(i, on) { const L = st.lines[i - 1];
        if (on) { if (L.eng !== 'RUN') return 'Clutch interlock: engine not running';
          if (L.n > NIDLE + 120) return 'Clutch interlock: speed above engage limit';
          if (Math.abs(L.lever) > 5) return 'Clutch interlock: lever not in neutral'; }
        L.clutch = !!on; return on ? 'Clutch in' : 'Clutch out'; },
      lever(i, v) { st.lines[i - 1].lever = clamp(Math.round(v), -100, 100); },
      steer(i, v) { st.lines[i - 1].steer = clamp(Math.round(v), -100, 100); },
      hydPump(i, on) { st.lines[i - 1].hydPump = !!on; },
      estop(on) { st.estop = !!on; if (on) st.lines.forEach(L => { L.eng = 'STOP'; L.clutch = false; L.stopCause = 'Emergency stop'; }); },
      override(on) { st.override = !!on; },
      station(s) { st.station = s; },
      gen(i, run) { const G = st.gens[i - 1]; G.run = !!run; if (!run) G.cb = false; },
      cb(i, on) { const G = st.gens[i - 1]; if (on && G.n < 1400) return 'Breaker interlock: generator not at speed'; G.cb = !!on; },
      ack(tag) { const a = st.alarms.get(tag); if (a) { a.ack = true; if (!a.active) st.alarms.delete(tag); } },
      ackAll(filter) { for (const [k, a] of [...st.alarms]) if (!filter || filter(a)) { a.ack = true; if (!a.active) st.alarms.delete(k); } },
    };

    // gradual degradation: obj[key] moves by `rate` per minute until `limit`
    function ramp(obj, key, rate, limit) { const r = { obj, key, rate, limit }; st.ramps.push(r); return () => { st.ramps.splice(st.ramps.indexOf(r), 1); delete obj[key]; }; }
    return { st, IX, data, value, step, cmd, ramp, limitsFor, invalid, NMAX, NIDLE };
  }

  return { create, limitsFor, stripPrio, lineOf, domainOf };
})();
