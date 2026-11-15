/* LO-O-OVE — score. Pure data: every sound and every visual hit is derived from here.
   120 BPM, 4/4. One bar = 2 s, one sixteenth = 0.125 s. */
const Score = (() => {
  const BAR = 2, S16 = 0.125, BARS = 92, DUR = 190;
  const T = (bar, step = 0) => bar * BAR + step * S16;

  const SEC = { intro: [0, 8], verse: [8, 16], build: [16, 24], ch1: [24, 40],
                brk: [40, 56], build2: [56, 64], ch2: [64, 80], outro: [80, 92] };
  const secOf = bar => { for (const k in SEC) if (bar >= SEC[k][0] && bar < SEC[k][1]) return k; return 'outro'; };

  // chord = bass root + pad voicing (MIDI)
  const CH = {
    F:  { r: 29, n: [53, 60, 64, 69] }, G:  { r: 31, n: [55, 62, 67, 71] },
    Em: { r: 28, n: [52, 59, 62, 67] }, Am: { r: 33, n: [57, 60, 64, 67] },
    Am9:{ r: 33, n: [57, 60, 64, 71] }, Dm: { r: 38, n: [50, 57, 60, 65] },
    C:  { r: 36, n: [48, 55, 62, 64] }, C7: { r: 36, n: [48, 55, 58, 64] },
    E7: { r: 28, n: [52, 56, 59, 62] }, A:  { r: 33, n: [57, 61, 64, 69] },
    A7: { r: 33, n: [57, 61, 64, 67] }, C9: { r: 36, n: [48, 55, 62, 64, 67] }
  };
  const PROG = [
    'F', 'F', 'Am', 'Am', 'F', 'F', 'G', 'G',
    'Am9', 'F', 'C', 'G', 'Am9', 'F', 'C', 'G',
    'F', 'G', 'Em', 'Am', 'Dm', 'Dm', 'G', 'G',
    'F', 'G', 'Em', 'Am', 'Dm', 'G', 'C', 'C7', 'F', 'G', 'Em', 'Am', 'Dm', 'G', 'C', 'C',
    'Am9', 'Am9', 'F', 'F', 'C', 'C', 'G', 'G', 'Am9', 'Am9', 'F', 'F', 'Dm', 'Dm', 'E7', 'E7',
    'F', 'G', 'Em', 'Am', 'Dm', 'G', 'A', 'A7',
    'F', 'G', 'Em', 'Am', 'Dm', 'G', 'C', 'C7', 'F', 'G', 'Em', 'Am', 'Dm', 'G', 'C', 'C',
    'F', 'G', 'Em', 'Am', 'F', 'G', 'C', 'C', 'C9', 'C9', 'C9', 'C9'
  ];
  const TR = bar => (bar >= 64 ? 2 : 0);           // the last chorus lifts a whole tone
  const chord = bar => {
    const c = CH[PROG[bar]], tr = TR(bar);
    return { name: PROG[bar], r: c.r + tr, n: c.n.map(x => x + tr) };
  };
  // the hook: three stuck O's (steps 0,3,6) and, at last, the resolution (step 8)
  const HOOK = [[69, 72], [71, 74], [67, 71], [72, 76], [74, 77], [74, 71], [76, 72], [67, 70]];

  let seed = 20261115;
  const rnd = () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };

  function build(ticks) {
    seed = 20261115;
    const s = { kicks: [], claps: [], hats: [], bass: [], pads: [], arps: [], stabs: [],
                voice: [], bells: [], fx: [], ticks: ticks || [] };
    const K = (b, st, v, soft) => s.kicks.push({ t: T(b, st), v, soft: !!soft });
    const C = (b, st, v) => s.claps.push({ t: T(b, st), v });
    const H = (b, st, v, open) => s.hats.push({ t: T(b, st), v, open: !!open, pan: (rnd() - 0.5) * 0.7 });
    const B = (b, st, d, m, v) => s.bass.push({ t: T(b, st), d: d * S16, m, v });
    const V = (b, st, d, m, v, k) => s.voice.push({ t: T(b, st), d: d * S16, m, v, k });
    const L = (b, st, m, v, dec) => s.bells.push({ t: T(b, st), m, v, dec: dec || 2.4, pan: (rnd() - 0.5) * 0.9 });
    const heart = (b, v) => { K(b, 0, v, true); K(b, 2, v * 0.66, true); };

    for (let b = 0; b < BARS; b++) {
      const sec = secOf(b), c = chord(b), tr = TR(b), lb = b - SEC[sec][0];

      /* ---------- drums ---------- */
      if (sec === 'intro') { if (b >= 2) heart(b, 0.55 + 0.05 * (b - 2)); }
      else if (sec === 'verse') {
        heart(b, 0.85); C(b, 8, 0.16);
        H(b, 4, 0.05); H(b, 12, 0.06); if (b % 2) H(b, 14, 0.045);
        if (b === 11 || b === 15) for (let i = 16; i < 32; i++) s.fx.push({ type: 'blip', t: T(b, 8) + i * S16 / 2 - 1, v: 0.5 + 0.5 * rnd(), m: 84 + Math.floor(rnd() * 5) * 3 });
      } else if (sec === 'build') {
        if (lb < 4) { K(b, 0, 0.8); K(b, 2, 0.5, true); K(b, 8, 0.7); for (let i = 0; i < 16; i += 2) H(b, i, 0.07 + 0.01 * lb); }
        else if (lb < 6) { for (let i = 0; i < 16; i += 4) K(b, i, 0.85); for (let i = 0; i < 16; i++) H(b, i, 0.06 + 0.012 * lb + (i % 4 === 2 ? 0.05 : 0)); C(b, 4, 0.2); C(b, 12, 0.22); }
        else if (lb === 6) { for (let i = 0; i < 16; i += 2) { K(b, i, 0.85); C(b, i, 0.14 + i * 0.012); } for (let i = 0; i < 16; i++) H(b, i, 0.13); }
        else { for (let i = 0; i < 12; i++) { K(b, i, 0.6 + i * 0.03); C(b, i, 0.24 + i * 0.024); H(b, i, 0.14); } }
      } else if (sec === 'ch1' || (sec === 'ch2' && lb < 8)) {
        const full = sec === 'ch2' || lb >= 8;
        K(b, 0, 1); K(b, 3, 0.86); K(b, 6, 0.92); K(b, 11, 0.8); if (b % 2) K(b, 14, 0.7);
        C(b, 8, 0.62);
        for (let i = 0; i < 16; i++) {
          if (i % 2 === 0) H(b, i, i === 14 && b % 2 === 0 ? 0.16 : (i % 4 === 2 ? 0.17 : 0.12), i === 14 && b % 2 === 0);
          else if (full) H(b, i, 0.055 + 0.03 * rnd());
        }
        if (lb % 8 === 7) { C(b, 12, 0.3); C(b, 14, 0.4); C(b, 15, 0.5); }
        if (lb % 8 === 0) s.fx.push({ type: 'impact', t: T(b), v: lb === 0 ? 1 : 0.6 });
      } else if (sec === 'ch2') {
        for (let i = 0; i < 16; i += 4) K(b, i, 1);
        C(b, 4, 0.6); C(b, 12, 0.64);
        for (let i = 0; i < 16; i++) H(b, i, i % 4 === 2 ? 0.2 : 0.07 + 0.03 * rnd(), i % 4 === 2);
        if (lb === 15) { for (let i = 8; i < 16; i++) C(b, i, 0.22 + (i - 8) * 0.045); }
        if (lb === 8) s.fx.push({ type: 'impact', t: T(b), v: 0.8 });
      } else if (sec === 'brk') { heart(b, 0.8); if (lb >= 8) C(b, 8, 0.1); }
      else if (sec === 'build2') {
        if (lb < 4) { heart(b, 0.85); K(b, 8, 0.6); for (let i = 0; i < 16; i += 2) H(b, i, 0.05 + 0.012 * lb); }
        else if (lb < 6) { for (let i = 0; i < 16; i += 4) K(b, i, 0.85); C(b, 4, 0.26); C(b, 12, 0.3); for (let i = 0; i < 16; i++) H(b, i, 0.08 + (i % 4 === 2 ? 0.06 : 0)); }
        else if (lb === 6) { for (let i = 0; i < 16; i += 2) { K(b, i, 0.88); C(b, i, 0.16 + i * 0.014); } for (let i = 0; i < 16; i++) H(b, i, 0.14); }
        else { for (let i = 0; i < 8; i++) { K(b, i, 0.65 + i * 0.045); C(b, i, 0.3 + i * 0.04); H(b, i, 0.15); } }
      } else if (sec === 'outro') { if (b <= 90) heart(b, b < 86 ? 0.75 : Math.max(0.22, 0.6 - (b - 86) * 0.09)); }

      /* ---------- bass ---------- */
      if (sec === 'intro') { if (b >= 4) B(b, 0, 15, c.r, 0.3); }
      else if (sec === 'verse') B(b, 0, 14, c.r, 0.32);
      else if (sec === 'build') {
        if (lb < 6) { B(b, 0, 6.5, c.r, 0.62); B(b, 8, 6.5, c.r, 0.62); }
        else for (let i = 0; i < (lb === 7 ? 12 : 16); i += 2) B(b, i, 1.7, c.r, 0.85);
      } else if (sec === 'ch1' || (sec === 'ch2' && lb < 8)) {
        B(b, 0, 2.6, c.r, 1); B(b, 3, 2.6, c.r, 0.9); B(b, 6, 1.7, c.r, 0.9);
        B(b, 8, 2.8, c.r, 0.85); B(b, 11, 2.6, c.r, 0.9); B(b, 14, 1.7, c.r + (b % 2 ? 0 : 7), 0.8);
      } else if (sec === 'ch2') { for (let i = 2; i < 16; i += 4) B(b, i, 1.9, c.r, 1); B(b, 15, 0.9, c.r + 12, 0.6); }
      else if (sec === 'brk') { if (lb >= 4) B(b, 0, 15, c.r, 0.4); }
      else if (sec === 'build2') {
        if (lb < 6) B(b, 0, 15, c.r, 0.5);
        else for (let i = 0; i < (lb === 7 ? 8 : 16); i += 2) B(b, i, 1.7, c.r, 0.85);
      } else if (sec === 'outro') { if (b < 86) B(b, 0, 15, c.r, 0.46); else if (b === 86) B(b, 0, 60, c.r, 0.42); }

      /* ---------- arp / stabs ---------- */
      const pool = [c.n[0] + 12, c.n[1] + 12, c.n[2] + 12, c.n[3] + 12, c.n[0] + 24, c.n[1] + 24];
      const PAT = [0, 1, 2, 3, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 4, 3];
      const ACC = [1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0];
      let arpV = 0, arpStep = 1, arpEnd = 16;
      if (sec === 'build' && lb >= 4) { arpV = 0.25 + 0.14 * (lb - 4); if (lb === 7) arpEnd = 12; }
      if (sec === 'ch1') arpV = lb < 8 ? 0.72 : 0.9;
      if (sec === 'build2' && lb >= 2) { arpV = 0.2 + 0.1 * (lb - 2); if (lb === 7) arpEnd = 8; }
      if (sec === 'ch2') arpV = 1;
      if (sec === 'outro' && b < 86) { arpV = 0.5 - 0.06 * lb; arpStep = 2; }
      if (arpV > 0) for (let i = 0; i < arpEnd; i += arpStep)
        s.arps.push({ t: T(b, i), m: pool[PAT[i]], v: arpV * (ACC[i] ? 1 : 0.6), pan: (i % 2 ? 0.55 : -0.55), br: (ACC[i] ? 1 : 0.55) * Math.min(1, 0.45 + arpV * 0.6) });
      if (sec === 'ch2') for (const st of [0, 3, 6]) s.stabs.push({ t: T(b, st), n: [c.n[1] + 12, c.n[2] + 12, c.n[3] + 12], v: st === 0 ? 1 : 0.8 });

      /* ---------- the voice ---------- */
      if (sec === 'verse') {
        const m = c.name === 'C' || c.name === 'G' ? 67 : 69;
        const n = [1, 2, 3, 0, 4, 5, 6, 0][lb];
        for (let i = 0; i < n; i++) V(b, i * 3, 1.6, m, 0.8, 'o');
        if (lb === 3) for (let i = 0; i < 7; i++) V(b, i, 0.7, m, 0.85 - i * 0.08, 'o');
        if (lb === 7) for (let i = 0; i < 12; i++) V(b, i, 0.7, m + (i > 7 ? 4 : 0), 0.5 + i * 0.03, 'o');
      } else if (sec === 'build') {
        const m = [69, 71, 71, 72, 74, 77, 79, 79][lb];
        if (lb < 7) { for (let g = b * 16; g < b * 16 + 16; g++) if ((g - 16 * 16) % 3 === 0) V(b, g - b * 16, 1.6, m, 0.7 + 0.03 * lb, 'o'); }
        else for (let i = 0; i < 12; i++) V(b, i, 0.7, m, 0.6 + i * 0.03, 'o');
      } else if (sec === 'ch1' || sec === 'ch2') {
        const h = HOOK[lb % 8], last = lb === 15, second = lb >= 8;
        const res = (last ? 72 : h[1]) + tr, st = h[0] + tr;
        for (const q of [0, 3, 6]) { V(b, q, 1.9, st, 0.95, 'o'); if (second) V(b, q, 1.9, st + 12, 0.3, 'o'); }
        V(b, 8, last ? 7.5 : 5.5, res, 1, 've'); if (second) V(b, 8, 5.5, res + 12, 0.32, 've');
        L(b, 8, res + 12, 0.3, 1.6);
      } else if (sec === 'brk') {
        const hum = [64, 0, 65, 0, 64, 0, 62, 0, 64, 0, 65, 0, 65, 0, 68, 0][lb];
        if (hum) V(b, 0, 28, hum, 0.34, 'hum');
        if (lb % 2 === 0) { L(b, 0, c.n[3] + 12, 0.5); L(b, 6, c.n[2] + 12, 0.34); L(b, 12, c.n[1] + 12, 0.3); }
        else { L(b, 0, c.n[2] + 12, 0.42); L(b, 8, c.n[3] + 12, 0.32); }
      } else if (sec === 'build2') {
        const m = [69, 71, 71, 72, 74, 74, 76, 79][lb];
        if (lb >= 2 && lb < 7) { for (let g = b * 16; g < b * 16 + 16; g++) if ((g - 56 * 16) % 3 === 0) V(b, g - b * 16, 1.6, m, 0.6 + 0.04 * lb, 'o'); }
        else if (lb === 7) for (let i = 0; i < 8; i++) V(b, i, 0.7, m, 0.7 + i * 0.03, 'o');
        else V(b, 0, 28, [64, 62][lb], 0.34, 'hum');
        if (lb < 4) { L(b, 0, c.n[3] + 12, 0.4); L(b, 8, c.n[2] + 12, 0.3); }
      } else if (sec === 'outro') {
        if (b < 84) { const h = HOOK[lb]; for (const q of [0, 3, 6]) V(b, q, 1.9, h[0] + tr, 0.55 - lb * 0.05, 'o'); }
        else if (b < 86) { for (const q of [0, 3, 6]) V(b, q, 1.9, [71, 74][b - 84] + tr, 0.36, 'o'); }
        else if (b === 86) {
          V(b, 0, 26, 72 + tr, 0.8, 'love');                         // the word, finally, in one breath
          [72, 76, 79, 84].forEach((m, i) => L(b, i * 4, m + tr, 0.6, 3.2));
        } else if (b === 88) { L(b, 0, 86 + tr, 0.4, 4); L(b, 4, 79 + tr, 0.3, 4); }
        if (b < 86 && lb % 2 === 0) L(b, 0, c.n[3] + 12, 0.34);
      } else if (sec === 'intro') {
        L(b, 0, c.n[3] + 12, b === 0 ? 0.55 : 0.4, 3); if (b >= 4) L(b, 10, c.n[2] + 12, 0.24, 3);
      }
    }

    /* ---------- pads: merge repeated chords into one long breath ---------- */
    const cut = { intro: [420, 800], verse: [800, 1100], build: [900, 2600], ch1: [2600, 2900], brk: [650, 900],
                  build2: [750, 3200], ch2: [3200, 3600], outro: [1900, 500] };
    for (let b = 0; b < BARS;) {
      let e = b + 1; const sec = secOf(b);
      while (e < BARS && PROG[e] === PROG[b] && secOf(e) === sec) e++;
      const [s0, s1] = SEC[sec], [c0, c1] = cut[sec], f = x => c0 * Math.pow(c1 / c0, (x - s0) / (s1 - s0));
      let d = (e - b) * BAR; if (b === 88) d += 4;
      if ((sec === 'build' && e === 24)) d -= 0.5; if (sec === 'build2' && e === 64) d -= 1;
      s.pads.push({ t: T(b), d, n: chord(b).n, c0: f(b), c1: f(e), v: ({ intro: 0.7, verse: 0.8, brk: 0.8, outro: 0.8 })[sec] || 1 });
      b = e;
    }

    /* ---------- one-shots ---------- */
    s.fx.push({ type: 'riser', t: T(20), d: 7.5, v: 1 }, { type: 'riser', t: T(60), d: 7, v: 1.1 },
              { type: 'flat', t: T(63, 8), d: 0.94, v: 1 }, { type: 'down', t: T(40), d: 3, v: 0.8 },
              { type: 'down', t: T(80), d: 4, v: 0.9 }, { type: 'bed', t: 0, d: 48, v: 1 },
              { type: 'bed', t: T(40), d: 48, v: 1 }, { type: 'bed', t: T(80), d: 28, v: 1 });
    for (const k in s) if (k !== 'ticks') s[k].sort((a, b) => a.t - b.t);
    return s;
  }

  return { BAR, S16, BARS, DUR, T, SEC, secOf, chord, TR, HOOK, build };
})();
if (typeof module !== 'undefined') module.exports = Score;
