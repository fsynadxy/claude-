/* LO-O-OVE — synthesizer. Sample by sample: no audio files, no Web Audio graph.
   The song is rendered in time order, chunk by chunk, so playback can begin
   while the rest is still being written. */
const DSP = (() => {
  const TAU = Math.PI * 2;
  const mtof = m => 440 * Math.pow(2, (m - 69) / 12), cents = c => Math.pow(2, c / 1200);
  // formant targets (Hz). "uh" + "v" make the end of the word sound like love rather than "oh-eh"
  const VOW = { o: [430, 820, 2700], uh: [620, 1170, 2400], v: [260, 980, 2250], u: [340, 720, 2400], l: [370, 1300, 2600] };
  const VG = [1.5, 0.9, 0.4], VQ = [7, 9, 11];
  const GAIN = { kick: 0.9, heart: 0.7, clap: 1.1, hat: 2.4, bass: 0.36, pad: 0.6, arp: 0.36, vox: 0.17, bell: 0.4, fx: 0.5, rev: 0.5, dly: 0.5, master: 1.45, thr: -12, ratio: 2 };

  function create(sc, dur, opt) {
    opt = opt || {};
    const SR = opt.sr || 44100, N = Math.ceil(dur * SR), G = Object.assign({}, GAIN, opt.gain);
    const L = new Float32Array(N), R = new Float32Array(N), RV = new Float32Array(N), DY = new Float32Array(N);
    const A = new Float32Array(SR * 14), B = new Float32Array(SR * 14);
    let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    const NZ = new Float32Array(SR * 3); for (let i = 0; i < NZ.length; i++) NZ[i] = rnd() * 2 - 1;
    const nzo = () => (rnd() * SR * 1.5) | 0;

    /* sidechain: the mix ducks under every hard kick */
    const CR = 32, dk = new Float32Array(Math.ceil(N / CR) + 2);
    { const ev = []; for (const k of (sc.duck || sc.kicks)) if (!k.soft) ev.push([k.t - 0.004, 0.38, 0.004], [k.t + 0.03, 1, 0.085]);
      ev.sort((a, b) => a[0] - b[0]); let v = 1, tg = 1, tau = 0.085, j = 0; const dt = CR / SR;
      for (let i = 0; i < dk.length; i++) { const t = i * dt; while (j < ev.length && ev[j][0] <= t) { tg = ev[j][1]; tau = ev[j][2]; j++; }
        v += (tg - v) * (1 - Math.exp(-dt / tau)); dk[i] = v; } }

    /* ---- primitives ---- */
    const out = (buf, n, t0, gl, gr, rv, dy, ducked) => {
      let s = Math.round(t0 * SR); if (s < 0) s = 0; if (s + n > N) n = N - s;
      for (let i = 0; i < n; i++) { let x = buf[i]; const k = s + i;
        if (ducked) { const q = k / CR, j = q | 0; x *= dk[j] + (dk[j + 1] - dk[j]) * (q - j); }
        L[k] += x * gl; R[k] += x * gr; if (rv) RV[k] += x * rv; if (dy) DY[k] += x * dy; }
    };
    const pan = (g, p) => [g * Math.cos((p + 1) * Math.PI / 4), g * Math.sin((p + 1) * Math.PI / 4)];
    const saw = (buf, n, f, g) => { const dt = f / SR; let p = rnd();
      for (let i = 0; i < n; i++) { let v = 2 * p - 1;
        if (p < dt) { const x = p / dt; v -= x + x - x * x - 1; } else if (p > 1 - dt) { const x = (p - 1) / dt; v -= x * x + x + x + 1; }
        buf[i] += v * g; p += dt; if (p >= 1) p -= 1; } };
    const sqr = (buf, n, f, g) => { const dt = f / SR; let p = rnd();
      const bl = q => (q < dt ? (q /= dt, q + q - q * q - 1) : q > 1 - dt ? (q = (q - 1) / dt, q * q + q + q + 1) : 0);
      for (let i = 0; i < n; i++) { let q = p + 0.5; if (q >= 1) q -= 1; buf[i] += ((p < 0.5 ? 1 : -1) + bl(p) - bl(q)) * g; p += dt; if (p >= 1) p -= 1; } };
    const tri = (buf, n, f, g) => { const dt = f / SR; let p = rnd(); for (let i = 0; i < n; i++) { buf[i] += (4 * Math.abs(p - 0.5) - 1) * g; p += dt; if (p >= 1) p -= 1; } };
    const sine = (buf, n, f, g) => { const w = TAU * f / SR; for (let i = 0; i < n; i++) buf[i] += Math.sin(w * i) * g; };
    // zero-delay state variable filter; mode 0 low, 1 band (unity peak), 2 high; f0→f1 exponential over `sw` samples
    const svf = (buf, n, mode, f0, f1, Q, sw) => { const k = 1 / Q, sweep = f1 !== f0, se = sw || n, m = sweep ? Math.log(f1 / f0) / se : 0;
      let c1 = 0, c2 = 0, g = 0, a1 = 0, a2 = 0, a3 = 0;
      for (let i = 0; i < n; i++) {
        if ((i & 15) === 0 && (sweep || i === 0)) { let f = i < se ? f0 * Math.exp(m * i) : f1; if (f > 0.42 * SR) f = 0.42 * SR;
          g = Math.tan(Math.PI * f / SR); a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2; }
        const x = buf[i], v3 = x - c2, v1 = a1 * c1 + a2 * v3, v2 = c2 + a2 * c1 + a3 * v3; c1 = 2 * v1 - c1; c2 = 2 * v2 - c2;
        buf[i] = mode === 0 ? v2 : mode === 1 ? k * v1 : x - k * v1 - v2; } };
    const NH = NZ.slice(); svf(NH, NH.length, 2, 7200, 7200, 0.7);          // bright noise for hats and ticks

    /* ---- drums ---- */
    const mkKick = s => { const n = (0.5 * SR) | 0, K = new Float32Array(n), f0 = s ? 100 : 170, f1 = s ? 41 : 47, tp = s ? 0.15 : 0.08, at = s ? 0.014 : 0.003, dc = s ? 0.44 : 0.33;
      let ph = 0, ph2 = 0;
      for (let i = 0; i < n; i++) { const t = i / SR, f = t < tp ? f0 * Math.pow(f1 / f0, t / tp) : f1; ph += f / SR; ph2 += f * 2.5 / SR;
        const a = t < at ? t / at : Math.pow(0.0001, (t - at) / (dc - at));
        let x = Math.sin(TAU * ph) * a;
        if (s) x += Math.sin(TAU * ph2) * 0.4 * (t < at ? t / at : Math.exp(-(t - at) / 0.045));   // a knock small speakers can still hear
        else x = Math.tanh(1.7 * x) / 0.935 + (i < 700 ? (NZ[i] - NZ[i + 1]) * 0.22 * Math.exp(-i / 140) : 0);
        K[i] = x; }
      return K; };
    const KH = mkKick(false), KS = mkKick(true);
    const kick = k => { const g = (k.soft ? G.heart : G.kick) * k.v; out(k.soft ? KS : KH, KH.length, k.t, g, g, k.soft ? 0.05 * g : 0, 0, false); };
    const clap = c => {
      const n = (0.27 * SR) | 0, o = nzo();
      for (let i = 0; i < n; i++) { const t = i / SR; let e;
        if (t < 0.031) { const q = Math.min(0.009, t % 0.011); e = Math.pow(0.12, q / 0.009); } else e = 0.9 * Math.pow(0.00011, (t - 0.031) / 0.21);
        A[i] = NZ[o + i] * e * c.v * 2.2; }
      svf(A, n, 1, 1500, 1500, 0.9);
      let ph = 0; const nb = (0.1 * SR) | 0;
      for (let i = 0; i < nb; i++) { const t = i / SR; ph += (210 * Math.pow(150 / 210, t / 0.08)) / SR; A[i] += (4 * Math.abs(ph % 1 - 0.5) - 1) * 0.5 * c.v * Math.exp(-t / 0.02); }
      out(A, n, c.t, G.clap, G.clap, 0.35 * G.clap, 0, false);
    };
    const hat = h => {
      const d = h.open ? 0.26 : 0.042, n = ((d + 0.02) * SR) | 0, o = nzo(), [gl, gr] = pan(G.hat, h.pan), kd = Math.log(0.0001 / h.v) / (d * SR);
      for (let i = 0; i < n; i++) A[i] = NH[o + i] * h.v * Math.exp(kd * i);
      out(A, n, h.t, gl, gr, h.open ? 0.1 : 0, 0, false);
    };

    /* ---- bass, pads, plucks ---- */
    const bass = b => {
      const f = mtof(b.m), n = ((b.d + 0.09) * SR) | 0, e = b.d; A.fill(0, 0, n); B.fill(0, 0, n);
      sine(A, n, f, 1); saw(B, n, f * 2, 1); svf(B, n, 0, 520, 520, 0.8); tri(A, n, f * 2, 0.25);
      for (let i = 0; i < n; i++) { const t = i / SR, a = t < 0.012 ? t / 0.012 : t < e - 0.05 ? 1 : Math.max(0, 1 - (t - (e - 0.05)) / 0.08);
        A[i] = Math.tanh(1.76 * (A[i] + 0.3 * B[i]) * a * b.v) / 0.976; }
      out(A, n, b.t, G.bass, G.bass, 0, 0, true);
    };
    const pad = p => {
      const e = p.d, n = Math.min(A.length, ((e + 0.75) * SR) | 0); A.fill(0, 0, n); B.fill(0, 0, n);
      p.n.forEach((m, i) => { const f = mtof(m), a = 0.11 - i * 0.008;
        saw(A, n, f * cents(-9 - i), a); saw(B, n, f * cents(9 + i), a);
        if (i > 1) { tri(A, n, f * 2 * cents(3), 0.03); tri(B, n, f * 2 * cents(-3), 0.03); } });
      svf(A, n, 0, p.c0, p.c1, 0.9, e * SR); svf(B, n, 0, p.c0, p.c1, 0.9, e * SR);
      const s = Math.round(p.t * SR);
      for (let i = 0; i < n && s + i < N; i++) { const t = i / SR, k = s + i;
        const a = (t < 0.45 ? t / 0.45 : t < e - 0.1 ? 1 : Math.max(0, 1 - (t - (e - 0.1)) / 0.8)) * p.v * G.pad;
        const q = k / CR, j = q | 0, d = dk[j] + (dk[j + 1] - dk[j]) * (q - j), l = A[i] * a, r = B[i] * a;
        L[k] += (l * 0.95 + r * 0.31) * d; R[k] += (r * 0.95 + l * 0.31) * d; RV[k] += (l + r) * 0.5 * d; }
    };
    const pluck = (t, m, v, pn, br, dec) => {
      const n = ((dec + 0.02) * SR) | 0, f = mtof(m), at = (0.003 * SR) | 0, kd = Math.log(0.0001) / ((dec - 0.003) * SR); A.fill(0, 0, n);
      saw(A, n, f * cents(-5), 1); sqr(A, n, f * cents(6), 0.35);
      svf(A, n, 0, 700 + 5200 * br, 420, 4, dec * 0.8 * SR);
      for (let i = 0; i < n; i++) A[i] *= v * (i < at ? i / at : Math.exp(kd * (i - at)));
      const [gl, gr] = pan(G.arp, pn); out(A, n, t, gl, gr, 0.3 * G.arp, 0.45 * G.arp, true);
    };

    /* ---- the voice: a sawtooth throat and three moving formants ---- */
    const voice = v => {
      const f = mtof(v.m), long = v.d > 1.2, k = v.k, hum = k === 'hum', d = v.d;
      const rel = hum ? 0.9 : long ? 0.35 : 0.07, att = hum ? 0.7 : k === 'love' ? 0.12 : 0.02, n = ((d + rel + 0.02) * SR) | 0;
      // "lo" for each stuck syllable; o → uh → v only when the word is allowed to end
      const path = k === 'o' ? [['l', 0], ['o', 0.045]]
        : k === 've' ? [['o', 0], ['uh', 0.14], ['uh', d * 0.62], ['v', d * 0.98]]
        : k === 'love' ? [['l', 0], ['uh', 0.1], ['uh', d * 0.72], ['v', d * 0.98]]
        : [['u', 0], ['o', d * 0.5], ['u', d]];
      const fAt = (t, q) => { let j = 0; while (j < path.length - 1 && t >= path[j + 1][1]) j++;
        const a = VOW[path[j][0]][q]; if (j === path.length - 1) return a; const b = VOW[path[j + 1][0]][q], u = (t - path[j][1]) / (path[j + 1][1] - path[j][1]); return a + (b - a) * u; };
      const fric = k === 've' || k === 'love', f0 = d * 0.72;
      const dt = f / SR, d1 = dt * cents(-7), d2 = dt * cents(7), o = nzo(), lpc = 1 - Math.exp(-TAU * (hum ? 500 : 950) / SR), body = hum ? 0.22 : 0.1, fg = hum ? 0.55 : 1;
      const hpc = 1 - Math.exp(-TAU * 2500 / SR), vib = TAU * 5.3 / SR;
      let p1 = rnd(), p2 = rnd(), p3 = rnd(), lph = 0, vd = 0, lp = 0, hn = 0;
      const c1 = [0, 0, 0], c2 = [0, 0, 0], a1 = [0, 0, 0], a2 = [0, 0, 0], a3 = [0, 0, 0], kk = [1 / VQ[0], 1 / VQ[1], 1 / VQ[2]];
      for (let i = 0; i < n; i++) { const t = i / SR;
        if ((i & 15) === 0) { for (let q = 0; q < 3; q++) { let fq = fAt(t, q); if (q === 0 && fq < f * 0.99) fq = f * 0.99;
            const g = Math.tan(Math.PI * Math.min(fq, 0.42 * SR) / SR); a1[q] = 1 / (1 + g * (g + kk[q])); a2[q] = g * a1[q]; a3[q] = g * a2[q]; }
          vd = long ? Math.min(1, Math.max(0, (t - 0.18) / 0.42)) * (hum ? 9 : 16) * 0.00057762 : 0; }
        const vm = 1 + vd * Math.sin(lph); lph += vib;
        const e1 = d1 * vm, e2 = d2 * vm; let s1 = 2 * p1 - 1, s2 = 2 * p2 - 1;
        if (p1 < e1) { const x = p1 / e1; s1 -= x + x - x * x - 1; } else if (p1 > 1 - e1) { const x = (p1 - 1) / e1; s1 -= x * x + x + x + 1; }
        if (p2 < e2) { const x = p2 / e2; s2 -= x + x - x * x - 1; } else if (p2 > 1 - e2) { const x = (p2 - 1) / e2; s2 -= x * x + x + x + 1; }
        p1 += e1; if (p1 >= 1) p1 -= 1; p2 += e2; if (p2 >= 1) p2 -= 1; p3 += dt * vm; if (p3 >= 1) p3 -= 1;
        const nz = NZ[o + (i % SR)], x = s1 + s2 + 0.9 * (4 * Math.abs(p3 - 0.5) - 1) + 0.05 * nz;
        let y = 0;
        for (let q = 0; q < 3; q++) { const v3 = x - c2[q], v1 = a1[q] * c1[q] + a2[q] * v3, v2 = c2[q] + a2[q] * c1[q] + a3[q] * v3;
          c1[q] = 2 * v1 - c1[q]; c2[q] = 2 * v2 - c2[q]; y += kk[q] * v1 * VG[q]; }
        lp += lpc * (x - lp);
        let fz = 0; if (fric && t > f0) { hn += hpc * (nz - hn); fz = (nz - hn) * 0.22 * Math.min(1, (t - f0) / (d - f0 + 0.05)); }
        let a = t < att ? t / att : 1;
        if (k === 've') { if (t > att + 0.05) a = t < d ? 1 - 0.25 * (t - att - 0.05) / (d - att - 0.05) : 0.75 * Math.max(0, 1 - (t - d) / rel); }
        else if (t > d) a = Math.max(0, 1 - (t - d) / rel);
        A[i] = (y * fg + lp * body + fz) * a * v.v; }
      out(A, n, v.t, G.vox, G.vox, 0.55 * G.vox, 0.22 * G.vox, false);
    };

    /* ---- FM bells, typing ticks, one-shots ---- */
    const bell = b => {
      const f = mtof(b.m), n = ((b.dec + 0.05) * SR) | 0, w = TAU * f / SR, da = Math.exp(Math.log(0.0001) / (b.dec * SR)), di = Math.exp(Math.log(0.0263) / (0.5 * SR)), d2 = da * da, at = (0.003 * SR) | 0, hold = (0.5 * SR) | 0;
      let amp = b.v, h2 = 0.5, ixv = 0.54;
      for (let i = 0; i < n; i++) { A[i] = (Math.sin(w * i + ixv * Math.sin(w * 3.51 * i)) + h2 * Math.sin(2 * w * i)) * amp * (i < at ? i / at : 1);
        amp *= da; h2 *= d2; if (i < hold) ixv *= di; }
      const [gl, gr] = pan(G.bell, b.pan); out(A, n, b.t, gl, gr, 0.7 * G.bell, 0.35 * G.bell, false);
    };
    const tick = t => {
      const n = (0.03 * SR) | 0, w = TAU * (1900 + rnd() * 900) / SR, o = nzo(), [gl, gr] = pan(G.fx, (rnd() - 0.5) * 0.5), dc = 0.0032 * SR, nn = (0.011 * SR) | 0;
      for (let i = 0; i < n; i++) A[i] = 0.32 * Math.exp(-i / dc) * (Math.sin(w * i) + (i < nn ? 0.5 * NH[o + i] : 0));
      out(A, n, t, gl, gr, 0.2 * G.fx, 0, false);
    };
    const fx = x => {
      const d = x.d || 0, o = nzo(), g = G.fx * 2;
      if (x.type === 'riser') { const n = ((d + 0.03) * SR) | 0, ds = d * SR; for (let i = 0; i < n; i++) A[i] = NZ[(o + i) % NZ.length];
        svf(A, n, 1, 300, 9000, 1.4, ds); B.fill(0, 0, n); { let p = 0; for (let i = 0; i < n; i++) { p += 110 * Math.pow(4, Math.min(1, i / ds)) / SR; B[i] = 2 * (p % 1) - 1; } } svf(B, n, 0, 1800, 1800, 2);
        for (let i = 0; i < n; i++) { const u = Math.min(1, i / ds), a = 0.75 * x.v * Math.pow(u, 2.4) * (i > ds ? Math.max(0, 1 - (i - ds) / (0.03 * SR)) : 1); A[i] = (A[i] * 1.6 + B[i] * 0.07) * a; }
        out(A, n, x.t, 0.5 * g, 0.5 * g, 0.2 * g, 0, false);
      } else if (x.type === 'impact') { let n = (1.6 * SR) | 0, p = 0;
        for (let i = 0; i < n; i++) { const t = i / SR; p += (t < 0.9 ? 95 * Math.pow(33 / 95, t / 0.9) : 33) / SR; A[i] = Math.sin(TAU * p) * 0.55 * x.v * Math.pow(0.0001, t / 1.5); }
        out(A, n, x.t, 0.9, 0.9, 0, 0, false);
        n = (2.5 * SR) | 0; for (let i = 0; i < n; i++) A[i] = NZ[(o + i) % NZ.length] * 0.55 * x.v * Math.pow(0.0001, i / (2.4 * SR));
        svf(A, n, 2, 3500, 3500, 0.5); out(A, n, x.t, 0.5 * g, 0.5 * g, 0.2 * g, 0, false);
      } else if (x.type === 'down') { const n = (d * SR) | 0; for (let i = 0; i < n; i++) A[i] = NZ[(o + i) % NZ.length] * 0.8 * x.v * Math.pow(0.0001, i / n);
        svf(A, n, 1, 6000, 200, 1.2, n); out(A, n, x.t, 0.5 * g, 0.5 * g, 0.2 * g, 0, false);
      } else if (x.type === 'flat') { const n = (d * SR) | 0, w = TAU * 988 / SR, r1 = 0.01 * SR, r2 = 0.02 * SR;   // the monitor's flat line
        for (let i = 0; i < n; i++) A[i] = Math.sin(w * i) * 0.2 * Math.min(1, i / r1, (n - i) / r2); out(A, n, x.t, 0.5 * g, 0.5 * g, 0.06 * g, 0, false);
      } else if (x.type === 'blip') { const n = (0.06 * SR) | 0, dc = 0.0075 * SR; A.fill(0, 0, n); sqr(A, n, mtof(x.m), 0.07 * x.v);
        for (let i = 0; i < n; i++) A[i] *= Math.exp(-i / dc); const [gl, gr] = pan(0.5 * g, (rnd() - 0.5) * 1.6); out(A, n, x.t, gl, gr, 0.2 * g, 0.1 * g, false);
      } else if (x.type === 'bed') { const s = Math.round(x.t * SR), n = Math.min(N - s, (d * SR) | 0), kq = 2.5, gq = Math.tan(Math.PI * 1400 / SR), q1 = 1 / (1 + gq * (gq + kq)), q2 = gq * q1, q3 = gq * q2;
        let l1 = 0, l2 = 0, r1 = 0, r2 = 0; const fi = 2 * SR, fo = 3 * SR;
        for (let i = 0; i < n; i++) { const a = 0.011 * Math.min(1, i / fi, (n - i) / fo);
          let v3 = NZ[(o + i) % NZ.length] - l2, v1 = q1 * l1 + q2 * v3, v2 = l2 + q2 * l1 + q3 * v3; l1 = 2 * v1 - l1; l2 = 2 * v2 - l2; L[s + i] += kq * v1 * a;
          v3 = NZ[(o + i * 2 + 7777) % NZ.length] - r2; v1 = q1 * r1 + q2 * v3; v2 = r2 + q2 * r1 + q3 * v3; r1 = 2 * v1 - r1; r2 = 2 * v2 - r2; R[s + i] += kq * v1 * a; }
      }
    };

    /* ---- ping-pong delay (dotted eighth), stateful so it can run chunk by chunk ---- */
    const DLN = Math.round(0.375 * SR), dlL = new Float32Array(DLN), dlR = new Float32Array(DLN), dS = [0, 0, 0];
    const hcD = 1 - Math.exp(-TAU * 350 / SR), lcD = 1 - Math.exp(-TAU * 2600 / SR);
    const delay = (a, b) => { let idx = dS[0], hp = dS[1], lp = dS[2]; const g = G.dly;
      for (let i = a; i < b; i++) { hp += hcD * (DY[i] - hp); const x = DY[i] - hp, oL = dlL[idx], oR = dlR[idx];
        lp += lcD * (oR - lp); dlL[idx] = x + lp * 0.42; dlR[idx] = oL; if (++idx >= DLN) idx = 0;
        const q = i / CR, j = q | 0, d = (dk[j] + (dk[j + 1] - dk[j]) * (q - j)) * g;
        L[i] += oL * d; R[i] += oR * d; RV[i] += (oL + oR) * 0.25 * g; }
      dS[0] = idx; dS[1] = hp; dS[2] = lp; };

    /* ---- reverb: four diffusers into an eight-line feedback network ---- */
    const reverb = (() => {
      const sc_ = SR / 44100, len = n => Math.max(1, Math.round(n * sc_));
      const n0 = len(225), n1 = len(341), n2 = len(441), n3 = len(556), PD = len(530);
      const m0 = len(1613), m1 = len(1933), m2 = len(2311), m3 = len(2729), m4 = len(3181), m5 = len(3643), m6 = len(4111), m7 = len(4603);
      const p0 = new Float32Array(n0), p1 = new Float32Array(n1), p2 = new Float32Array(n2), p3 = new Float32Array(n3), pre = new Float32Array(PD);
      const d0 = new Float32Array(m0), d1 = new Float32Array(m1), d2 = new Float32Array(m2), d3 = new Float32Array(m3), d4 = new Float32Array(m4), d5 = new Float32Array(m5), d6 = new Float32Array(m6), d7 = new Float32Array(m7);
      const RT = 3.1, gg = n => Math.pow(10, -3 * n / (RT * SR));
      const g0 = gg(m0), g1 = gg(m1), g2 = gg(m2), g3 = gg(m3), g4 = gg(m4), g5 = gg(m5), g6 = gg(m6), g7 = gg(m7);
      const hc = 1 - Math.exp(-TAU * 220 / SR), damp = 1 - Math.exp(-TAU * 3820 / SR), h = 1 / Math.sqrt(8), S = new Float64Array(22);
      return (a, b) => {
        let i0 = S[0] | 0, i1 = S[1] | 0, i2 = S[2] | 0, i3 = S[3] | 0, j0 = S[4] | 0, j1 = S[5] | 0, j2 = S[6] | 0, j3 = S[7] | 0, j4 = S[8] | 0, j5 = S[9] | 0, j6 = S[10] | 0, j7 = S[11] | 0;
        let f0 = S[12], f1 = S[13], f2 = S[14], f3 = S[15], f4 = S[16], f5 = S[17], f6 = S[18], f7 = S[19], pi = S[20] | 0, hp = S[21];
        const rg = G.rev;
        for (let i = a; i < b; i++) {
          hp += hc * (RV[i] - hp); let x = pre[pi]; pre[pi] = RV[i] - hp; if (++pi >= PD) pi = 0;
          let y = p0[i0] - 0.5 * x; p0[i0] = x + 0.5 * y; if (++i0 >= n0) i0 = 0; x = y;
          y = p1[i1] - 0.5 * x; p1[i1] = x + 0.5 * y; if (++i1 >= n1) i1 = 0; x = y;
          y = p2[i2] - 0.5 * x; p2[i2] = x + 0.5 * y; if (++i2 >= n2) i2 = 0; x = y;
          y = p3[i3] - 0.5 * x; p3[i3] = x + 0.5 * y; if (++i3 >= n3) i3 = 0; x = y * 0.35;
          f0 += damp * (d0[j0] - f0); f1 += damp * (d1[j1] - f1); f2 += damp * (d2[j2] - f2); f3 += damp * (d3[j3] - f3);
          f4 += damp * (d4[j4] - f4); f5 += damp * (d5[j5] - f5); f6 += damp * (d6[j6] - f6); f7 += damp * (d7[j7] - f7);
          const s0 = f0 * g0, s1 = f1 * g1, s2 = f2 * g2, s3 = f3 * g3, s4 = f4 * g4, s5 = f5 * g5, s6 = f6 * g6, s7 = f7 * g7;
          const a0 = s0 + s1, a1 = s0 - s1, a2 = s2 + s3, a3 = s2 - s3, a4 = s4 + s5, a5 = s4 - s5, a6 = s6 + s7, a7 = s6 - s7;
          const b0 = a0 + a2, b1 = a1 + a3, b2 = a0 - a2, b3 = a1 - a3, b4 = a4 + a6, b5 = a5 + a7, b6 = a4 - a6, b7 = a5 - a7;
          d0[j0] = (b0 + b4) * h + x; d1[j1] = (b1 + b5) * h - x; d2[j2] = (b2 + b6) * h + x; d3[j3] = (b3 + b7) * h - x;
          d4[j4] = (b0 - b4) * h + x; d5[j5] = (b1 - b5) * h - x; d6[j6] = (b2 - b6) * h + x; d7[j7] = (b3 - b7) * h - x;
          if (++j0 >= m0) j0 = 0; if (++j1 >= m1) j1 = 0; if (++j2 >= m2) j2 = 0; if (++j3 >= m3) j3 = 0;
          if (++j4 >= m4) j4 = 0; if (++j5 >= m5) j5 = 0; if (++j6 >= m6) j6 = 0; if (++j7 >= m7) j7 = 0;
          const q = i / CR, j = q | 0, d = (dk[j] + (dk[j + 1] - dk[j]) * (q - j)) * rg;
          L[i] += (s0 - s2 + s4 - s6) * d; R[i] += (s1 - s3 + s5 - s7) * d; }
        S[0] = i0; S[1] = i1; S[2] = i2; S[3] = i3; S[4] = j0; S[5] = j1; S[6] = j2; S[7] = j3; S[8] = j4; S[9] = j5; S[10] = j6; S[11] = j7;
        S[12] = f0; S[13] = f1; S[14] = f2; S[15] = f3; S[16] = f4; S[17] = f5; S[18] = f6; S[19] = f7; S[20] = pi; S[21] = hp;
      };
    })();

    /* ---- master: glue compressor, then a soft ceiling ---- */
    const mS = [0, 1, 1, 0, 0], airC = 1 - Math.exp(-TAU * 3500 / SR), AIR = 0.55, aA = 1 - Math.exp(-1 / (0.012 * SR)), aR = 1 - Math.exp(-1 / (0.22 * SR)), thr = G.thr, knee = 9, sl = 1 - 1 / G.ratio;
    const th = x => (x < -3 ? -1 : x > 3 ? 1 : x * (27 + x * x) / (27 + 9 * x * x));
    const master = (a, b) => { if (opt.master === false) return; let env = mS[0], g = mS[1], gt = mS[2], aL = mS[3], aRr = mS[4]; const mk = G.master;
      for (let i = a; i < b; i++) { aL += airC * (L[i] - aL); aRr += airC * (R[i] - aRr);   // a gentle high shelf for air
        const l = L[i] + AIR * (L[i] - aL), r = R[i] + AIR * (R[i] - aRr), m = Math.max(Math.abs(l), Math.abs(r)); env += (m > env ? aA : aR) * (m - env);
        if ((i & 7) === 0) { const ov = 20 * Math.log10(env + 1e-9) - thr; const gr = ov <= -knee / 2 ? 0 : ov >= knee / 2 ? ov * sl : sl * (ov + knee / 2) * (ov + knee / 2) / (2 * knee); gt = Math.pow(10, -gr / 20); }
        g += (gt - g) * 0.02; L[i] = 0.86 * th(l * g * mk); R[i] = 0.86 * th(r * g * mk); }
      mS[0] = env; mS[1] = g; mS[2] = gt; mS[3] = aL; mS[4] = aRr; };

    /* ---- every note in time order; renderTo(t) finishes all samples before t ---- */
    const Q = [], add = (list, f) => { for (const e of list || []) Q.push({ t: typeof e === 'number' ? e : e.t, e, f }); };
    add(sc.kicks, kick); add(sc.claps, clap); add(sc.hats, hat); add(sc.bass, bass); add(sc.pads, pad);
    add(sc.arps, a => pluck(a.t, a.m, a.v, a.pan, a.br, 0.24));
    add(sc.stabs, s => s.n.forEach((m, i) => pluck(s.t, m, s.v * 0.55, (i - 1) * 0.5, 0.75, 0.3)));
    add(sc.voice, voice); add(sc.bells, bell); add(sc.ticks, tick); add(sc.fx, fx);
    Q.sort((a, b) => a.t - b.t);
    let qi = 0, pos = 0;
    const renderTo = tEnd => {
      const last = tEnd >= dur, end = last ? N : Math.min(N, Math.round(tEnd * SR));
      while (qi < Q.length && (last || Q[qi].t < tEnd)) { Q[qi].f(Q[qi].e); qi++; }
      if (end > pos) { delay(pos, end); reverb(pos, end); master(pos, end); pos = end; }
      return end;
    };
    return { SR, N, L, R, renderTo };
  }

  // one-shot render, used by the offline tools
  function render(sc, dur, onProgress, opt) {
    const r = create(sc, dur, opt);
    for (const t of chunks(dur)) { r.renderTo(t); if (onProgress) onProgress(t / dur); }
    return { L: r.L, R: r.R, sampleRate: r.SR };
  }
  // where the stream is cut: short chunks first so the sound can start almost at once
  const chunks = dur => { const e = []; for (let x = 3; x < dur; x += x < 15 ? 3 : 12) e.push(x); e.push(dur); return e; };
  return { create, render, chunks };
})();
if (typeof module !== 'undefined') module.exports = DSP;
