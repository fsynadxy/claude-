/* LO-O-OVE — what you see. A WebGL field of rings behind a 2D layer of wire letters.
   Both are pure functions of the song clock and the score, so every frame can be redrawn from t alone. */
const Stage = (() => {
  const TAU = Math.PI * 2;
  const C = { cold: [176, 204, 255], warm: [255, 112, 146], gold: [255, 206, 172], paper: [240, 235, 246] };
  const rgba = (c, a) => 'rgba(' + (c[0] | 0) + ',' + (c[1] | 0) + ',' + (c[2] | 0) + ',' + Math.max(0, Math.min(1, a)).toFixed(3) + ')';
  const mix = (a, b, u) => [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u];
  const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
  const sstep = (a, b, x) => { const u = clamp((x - a) / (b - a)); return u * u * (3 - 2 * u); };
  const ease = u => 1 - Math.pow(1 - clamp(u), 3);
  const hash = i => { const x = Math.sin(i * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };

  /* piecewise-linear curves over song time; integ() gives the tunnel a phase that never jumps */
  const curve = (K, t) => { if (t <= K[0][0]) return K[0][1];
    for (let i = 1; i < K.length; i++) if (t <= K[i][0]) { const [a, va] = K[i - 1], [b, vb] = K[i]; return b === a ? vb : va + (vb - va) * (t - a) / (b - a); }
    return K[K.length - 1][1]; };
  const integ = (K, t) => { let s = 0;
    for (let i = 1; i < K.length; i++) { const [a, va] = K[i - 1], [b, vb] = K[i];
      if (t <= b) { const vt = b === a ? vb : va + (vb - va) * (t - a) / (b - a); return s + Math.max(0, t - a) * (va + vt) / 2; }
      s += (b - a) * (va + vb) / 2; }
    return s + (t - K[K.length - 1][0]) * K[K.length - 1][1]; };
  const WARM = [[0, 0], [16, 0.05], [32, 0.15], [47.5, 0.45], [48, 0.8], [80, 0.8], [83, 0.22], [112, 0.25], [127, 0.55], [128, 1], [160, 1], [166, 0.7], [190, 0.6]];
  const DAWN = [[0, 0], [160, 0], [172, 0.55], [190, 1]];
  const TUN = [[0, 0], [16, 0], [20, 0.1], [32, 0.14], [47.4, 0.7], [47.5, 0], [47.99, 0], [48, 0.85], [64, 0.85], [64.1, 1], [80, 1], [82, 0.16], [112, 0.14], [126.9, 0.7], [127, 0], [127.99, 0], [128, 1], [160, 1], [163, 0.3], [172, 0.22], [186, 0]];
  const SPEED = [[0, 0.04], [16, 0.06], [32, 0.12], [47.5, 1.1], [48, 0.36], [80, 0.5], [82, 0.06], [112, 0.07], [127, 1.1], [128, 0.4], [160, 0.6], [163, 0.08], [190, 0.03]];
  const ECG_A = [[3.6, 0], [4.1, 0.85], [31, 0.85], [32.5, 0], [80, 0], [81.5, 0.7], [112, 0.7], [119, 0.55], [120.5, 0], [160, 0], [161, 0.8], [182, 0.7], [186, 0]];

  /* ---------- events from the score ---------- */
  let E = null;
  const lastIdx = (a, t, key) => { let lo = 0, hi = a.length - 1, r = -1;
    while (lo <= hi) { const m = (lo + hi) >> 1, v = key ? a[m][key] : a[m]; if (v <= t) { r = m; lo = m + 1; } else hi = m - 1; } return r; };
  const env = (a, t, tau) => { const i = lastIdx(a, t, 't'); if (i < 0) return 0; const dt = t - a[i].t; return dt > tau * 7 ? 0 : (a[i].v || 1) * Math.exp(-dt / tau); };
  function setScore(sc) {
    const uniq = a => a.filter((x, i) => i === 0 || x - a[i - 1] > 1e-6);
    const oTimes = (a, b) => uniq(sc.voice.filter(v => v.k === 'o' && v.t >= a && v.t < b).map(v => v.t).sort((x, y) => x - y));
    E = { hard: sc.kicks.filter(k => !k.soft), soft: sc.kicks.filter(k => k.soft), claps: sc.claps, bells: sc.bells, stabs: sc.stabs,
          blips: sc.fx.filter(x => x.type === 'blip'), impacts: sc.fx.filter(x => x.type === 'impact'),
          verseO: oTimes(16, 32), buildO: oTimes(32, 48).concat(oTimes(112, 128)), outroO: oTimes(160, 172) };
  }

  /* ---------- wire letters: L, O, V, E and the hyphen ---------- */
  const GLY = { L: { w: 0.56, s: [[[0, 0], [0, 1], [0.56, 1]]] }, O: { w: 1, o: true }, V: { w: 0.7, s: [[[0, 0], [0.35, 1], [0.7, 0]]] },
                E: { w: 0.56, s: [[[0.56, 0], [0, 0], [0, 1], [0.56, 1]], [[0, 0.5], [0.46, 0.5]]] }, '-': { w: 0.42, s: [[[0.06, 0.5], [0.36, 0.5]]] } };
  for (const k in GLY) if (GLY[k].s) GLY[k].len = GLY[k].s.reduce((s, p) => s + p.reduce((q, pt, i) => i ? q + Math.hypot(pt[0] - p[i - 1][0], pt[1] - p[i - 1][1]) : 0, 0), 0);
  function glyphPath(g, ch, x, y, h, prog) {                  // x, y: top-left corner; prog: how much of the stroke is drawn
    const G = GLY[ch]; if (prog <= 0) return;
    if (G.o) { const r = h / 2; g.moveTo(x + r, y); g.arc(x + r, y + r, r, -Math.PI / 2, -Math.PI / 2 + TAU * Math.min(1, prog)); return; }
    let left = G.len * prog;
    for (const p of G.s) { if (left <= 0) break; g.moveTo(x + p[0][0] * h, y + p[0][1] * h);
      for (let i = 1; i < p.length && left > 0; i++) { const dx = p[i][0] - p[i - 1][0], dy = p[i][1] - p[i - 1][1], l = Math.hypot(dx, dy), u = Math.min(1, left / l);
        g.lineTo(x + (p[i - 1][0] + dx * u) * h, y + (p[i - 1][1] + dy * u) * h); left -= l; } }
  }
  function glow(g, addPath, col, a, lw) {                      // three passes, added together: halo, body, hot core
    if (a <= 0.003) return;
    g.beginPath(); addPath();
    g.strokeStyle = rgba(col, a * 0.13); g.lineWidth = lw * 4; g.stroke();
    g.strokeStyle = rgba(col, a * 0.3); g.lineWidth = lw * 2; g.stroke();
    g.strokeStyle = rgba(mix(col, C.paper, 0.65), a); g.lineWidth = lw; g.stroke();
  }
  const GAP = 0.16;
  function layout(chars, h) { const xs = []; let x = 0; for (const ch of chars) { xs.push(x); x += (GLY[ch].w + GAP) * h; } return { xs, w: x - GAP * h }; }
  const WORD = ['L', 'O', '-', 'O', '-', 'O', 'V', 'E'];         // LO-O-OVE
  const AT = [0, 0.02, 0.375, 0.375, 0.75, 0.75, 1.0, 1.0];       // when each glyph lands inside a hook bar (s)
  const DUR = { L: 0.08, O: 0.12, '-': 0.05, V: 0.07, E: 0.09 };

  /* ---------- canvases ---------- */
  let glc, fxc, gl = null, P = null, U = {}, g, W = 0, H = 0, DPR = 1, GLS = 1, tile = null, tileKey = '';
  const FRAG = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes; uniform float uT, uPhase, uKick, uHeart, uTun, uWarm, uDawn, uFlat, uGlitch, uFade, uClap;
uniform vec4 uRing[12];
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float band(float r, float ph) { float f = fract(log(max(r, 1e-4)) * 3.2 - ph); return exp(-pow((f - 0.5) * 10.0, 2.0)); }
void main() {
  vec2 fc = gl_FragCoord.xy, p = (fc - 0.5 * uRes) / uRes.y;
  float row = floor(fc.y / uRes.y * 36.0);
  p.x += (hash(vec2(row, floor(uT * 20.0))) - 0.5) * 0.3 * uGlitch * step(0.55, hash(vec2(row * 3.1, floor(uT * 9.0))));
  float r = length(p);
  vec3 cold = vec3(0.69, 0.80, 1.0), warm = vec3(1.0, 0.44, 0.57), gold = vec3(1.0, 0.81, 0.67);
  vec3 tint = mix(mix(cold, warm, uWarm), gold, uDawn);
  vec3 ink = mix(vec3(0.026, 0.03, 0.05), vec3(0.055, 0.022, 0.04), uWarm);
  ink = mix(ink, vec3(0.06, 0.04, 0.038), uDawn * 0.7);
  vec3 col = ink * (1.0 + 0.9 * (1.0 - smoothstep(0.0, 0.9, r)));
  float ca = 0.004 + 0.012 * uKick + 0.045 * uGlitch;
  vec3 b = vec3(band(r * (1.0 + ca), uPhase), band(r, uPhase), band(r * (1.0 - ca), uPhase));
  col += tint * b * smoothstep(0.015, 0.22, r) * (1.0 - smoothstep(0.75, 1.25, r)) * uTun * (0.16 + 0.42 * uKick);
  for (int i = 0; i < 12; i++) {
    vec4 R = uRing[i];
    if (R.z >= 0.0) {
      vec2 c = vec2((R.x - 0.5) * uRes.x / uRes.y, R.y - 0.5);
      float d = length(p - c), rad = R.w + R.z * 0.42, w = 0.003 + R.z * 0.03;
      col += tint * exp(-pow((d - rad) / w, 2.0)) * exp(-R.z * 2.6) * 0.5;
    }
  }
  col += warm * exp(-r * r * 7.0) * uHeart * 0.12 + tint * uClap * 0.035;
  col += (hash(fc + fract(uT * 7.13) * 91.0) - 0.5) * 0.03;
  col *= 1.0 - 0.6 * smoothstep(0.45, 1.35, r);
  gl_FragColor = vec4(max(col * (1.0 - uFlat) * uFade, 0.0), 1.0);
}`;
  function initGL() {
    try { gl = glc.getContext('webgl', { antialias: false, alpha: false, depth: false, powerPreference: 'high-performance' }); } catch (e) { gl = null; }
    if (!gl) return;
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; };
    try {
      P = gl.createProgram();
      gl.attachShader(P, sh(gl.VERTEX_SHADER, 'attribute vec2 a; void main() { gl_Position = vec4(a, 0.0, 1.0); }'));
      gl.attachShader(P, sh(gl.FRAGMENT_SHADER, FRAG)); gl.linkProgram(P);
      if (!gl.getProgramParameter(P, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(P));
    } catch (e) { console.warn('WebGL shader failed, using the plain background', e); gl = null; return; }
    gl.useProgram(P);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const a = gl.getAttribLocation(P, 'a'); gl.enableVertexAttribArray(a); gl.vertexAttribPointer(a, 2, gl.FLOAT, false, 0, 0);
    for (const n of ['uRes', 'uT', 'uPhase', 'uKick', 'uHeart', 'uTun', 'uWarm', 'uDawn', 'uFlat', 'uGlitch', 'uFade', 'uClap', 'uRing']) U[n] = gl.getUniformLocation(P, n);
  }
  function init(glCanvas, fxCanvas) { glc = glCanvas; fxc = fxCanvas; g = fxc.getContext('2d'); initGL(); resize(); return !!gl; }
  function resize() {
    W = window.innerWidth; H = window.innerHeight; DPR = Math.min(2, window.devicePixelRatio || 1);
    fxc.width = Math.round(W * DPR); fxc.height = Math.round(H * DPR);
    GLS = Math.min(1.25, DPR) * (W * H > 1.6e6 ? 0.7 : 0.85);   // the background is soft; spend the pixels on the letters
    glc.width = Math.round(W * GLS); glc.height = Math.round(H * GLS);
  }

  /* ---------- 2D pieces ---------- */
  const rings = [];                                           // O-rings handed to the shader this frame
  const ring = (x, y, r, age) => { if (age >= 0 && age < 1.6) rings.push([x, y, r, age]); };
  function burst(x, y, r, age, col, a) {                       // a thin ring thrown outward by each O
    if (age < 0 || age > 0.9) return; const u = age / 0.9;
    glow(g, () => { const R = r * (1 + 1.8 * ease(u)); g.moveTo(x + R, y); g.arc(x, y, R, 0, TAU); }, col, a * (1 - u) * 0.8, Math.max(1, r * 0.035));
  }
  function drawWord(chars, starts, cx, cy, h, t, col, a, lw, echo, slow) {
    const L = layout(chars, h), x0 = cx - L.w / 2, y0 = cy - h / 2;
    chars.forEach((ch, i) => {
      const age = t - starts[i]; if (age < 0) return;
      const prog = ease(age / (DUR[ch] * (slow || 1))), pop = ch === 'O' ? 1 + 0.22 * Math.exp(-age / 0.07) : (ch === 'V' || ch === 'E') ? 1 + 0.3 * Math.exp(-age / 0.06) : 1;
      const w = GLY[ch].w * h, gx = x0 + L.xs[i] + w / 2, gy = cy, hh = h * pop;
      const draw = (s, al, c2) => glow(g, () => glyphPath(g, ch, gx - (w / 2) * pop * s, gy - (hh / 2) * s, hh * s, prog), c2, al, lw);
      if (echo) draw(1.07, a * 0.32 * echo, C.cold);
      draw(1, a, col);
      if (ch === 'O') { burst(gx, gy, h / 2, age, col, a); ring(gx, gy, h / 2, age); }
    });
    return { x0, y0, w: L.w };
  }
  function spike(u) { if (u < -0.08 || u > 0.34) return 0;
    return -0.12 * Math.exp(-(((u + 0.045) / 0.012) ** 2)) + Math.exp(-((u / 0.011) ** 2)) - 0.34 * Math.exp(-(((u - 0.027) / 0.012) ** 2)) + 0.17 * Math.exp(-(((u - 0.2) / 0.045) ** 2)); }
  function ecg(t, y, a, col) {
    if (a <= 0.01) return;
    const x0 = W * 0.06, x1 = W * 0.94, span = 4.2, amp = Math.min(H * 0.085, 64), step = W > 900 ? 2 : 1.5;
    g.beginPath();
    for (let x = x0; x <= x1; x += step) {
      const tau = t - (x1 - x) / (x1 - x0) * span; let yy = 0;
      for (let j = lastIdx(E.soft, tau + 0.08, 't'); j >= 0 && E.soft[j].t > tau - 0.4; j--) yy += spike(tau - E.soft[j].t) * E.soft[j].v;
      if (x === x0) g.moveTo(x, y - yy * amp); else g.lineTo(x, y - yy * amp);
    }
    const gr = g.createLinearGradient(x0, 0, x1, 0); gr.addColorStop(0, rgba(col, 0)); gr.addColorStop(0.65, rgba(col, a * 0.45)); gr.addColorStop(1, rgba(col, a));
    g.strokeStyle = gr; g.lineWidth = 1.5; g.stroke();
    let hy = 0; for (let j = lastIdx(E.soft, t + 0.08, 't'); j >= 0 && E.soft[j].t > t - 0.4; j--) hy += spike(t - E.soft[j].t) * E.soft[j].v;
    g.beginPath(); g.arc(x1, y - hy * amp, 2.5, 0, TAU); g.fillStyle = rgba(mix(col, C.paper, 0.5), a); g.fill();
  }
  function fitRows(n, h, boxW) {                               // how many rows "L" + n × "O" takes at height h
    let x = 0, rows = 1; const adv = ch => (GLY[ch].w + GAP) * h;
    for (let i = 0; i <= n; i++) { const w = adv(i ? 'O' : 'L'); if (x + w - GAP * h > boxW && x > 0) { rows++; x = 0; } x += w; }
    return rows;
  }
  function outputH(n, boxW, boxH, hMax) {
    let lo = 6, hi = hMax; for (let k = 0; k < 18; k++) { const m = (lo + hi) / 2; if (fitRows(n, m, boxW) * m * 1.4 - 0.4 * m <= boxH) lo = m; else hi = m; } return lo;
  }
  function verse(t, a) {                                       // say("LOVE") prints L, then gets stuck printing O
    const wide = W >= 900, times = E.verseO, n = lastIdx(times, t) + 1, boxW = W * 0.84, boxH = H * (wide ? 0.28 : 0.32), hMax = Math.min(H * 0.17, W * 0.15);
    const tl = n ? times[n - 1] : 16, h = outputH(Math.max(0, n - 1), boxW, boxH, hMax) + (outputH(n, boxW, boxH, hMax) - outputH(Math.max(0, n - 1), boxW, boxH, hMax)) * ease((t - tl) / 0.3);
    const glitch = env(E.blips, t, 0.05), col = mix(C.cold, C.warm, 0.12), lw = Math.max(1.2, h * 0.045);
    const rows = []; let row = [], x = 0;
    for (let i = 0; i <= n; i++) { const ch = i ? 'O' : 'L', w = (GLY[ch].w + GAP) * h; if (x + w - GAP * h > boxW && x > 0) { rows.push(row); row = []; x = 0; } row.push({ ch, x, i }); x += w; }
    rows.push(row);
    const y0 = H * (wide ? 0.56 : 0.47) - (rows.length * h * 1.4 - 0.4 * h) / 2;
    rows.forEach((r, ri) => {
      const rw = r.length ? r[r.length - 1].x + GLY[r[r.length - 1].ch].w * h : 0, jx = glitch > 0.02 ? (hash(ri * 7 + Math.floor(t * 24)) - 0.5) * W * 0.08 * glitch : 0;
      for (const c of r) {
        const born = c.i ? times[c.i - 1] : 16, age = t - born, gx = (W - rw) / 2 + c.x + jx, gy = y0 + ri * h * 1.4;
        const pop = c.ch === 'O' ? 1 + 0.25 * Math.exp(-age / 0.06) : 1, w = GLY[c.ch].w * h;
        glow(g, () => glyphPath(g, c.ch, gx + w / 2 - w * pop / 2, gy + h / 2 - h * pop / 2, h * pop, ease(age / 0.1)), col, a, lw);
        if (c.ch === 'O') { burst(gx + w / 2, gy + h / 2, h / 2, age, col, a); ring(gx + w / 2, gy + h / 2, h / 2, age); }
      }
    });
  }
  function flood(t, a) {                                       // the loop, unrolled: O × 2^n until the screen is full
    const cell = Math.max(9, Math.round(Math.min(W, H) / 44)), key = cell + '/' + DPR;
    if (tileKey !== key) { const c = document.createElement('canvas'), s = Math.round(cell * DPR); c.width = c.height = s;
      const q = c.getContext('2d'); q.strokeStyle = rgba(C.cold, 0.9); q.lineWidth = Math.max(1, DPR * 0.9); q.beginPath(); q.arc(s / 2, s / 2, s * 0.3, 0, TAU); q.stroke();
      tile = g.createPattern(c, 'repeat'); tileKey = key; }
    const cs = Math.round(cell * DPR), cols = Math.floor(fxc.width / cs), count = Math.floor(40 * Math.pow(2, (t - 32) / 1.55)), full = Math.floor(count / cols), rem = count % cols;
    const x0 = Math.round((fxc.width - cols * cs) / 2), top = Math.round(fxc.height * 0.1), over = Math.max(0, top + (full + 1) * cs - fxc.height), r0 = Math.max(0, Math.floor((over - top) / cs));
    g.save(); g.setTransform(1, 0, 0, 1, 0, 0); g.globalAlpha = a * (0.35 + 0.4 * sstep(32, 44, t)); g.translate(x0, top - over); g.fillStyle = tile;
    if (full > r0) g.fillRect(0, r0 * cs, cols * cs, (full - r0) * cs);
    if (rem) g.fillRect(0, full * cs, rem * cs, cs);
    g.restore();
    return count;
  }

  /* ---------- one frame ---------- */
  let last = { count: 0 };
  function frame(t, opt) {
    opt = opt || {};
    const reduced = !!opt.reduced, idle = t < 0;
    const tt = idle ? 0 : Math.min(t, 190), bar = Math.floor(tt / 2), tb = tt - bar * 2;
    const sec = idle ? 'idle' : tt >= 184 ? 'tail' : Score.secOf(bar), lb = Score.SEC[sec] ? bar - Score.SEC[sec][0] : 0;
    const kick = idle ? 0 : env(E.hard, tt, 0.13), heart = idle ? 0 : env(E.soft, tt, 0.2), clap = idle ? 0 : env(E.claps, tt, 0.1);
    const impact = idle ? 0 : env(E.impacts, tt, 0.35), flat = (tt >= 127 && tt < 127.96) ? 1 : 0;
    const warm = curve(WARM, tt), dawn = curve(DAWN, tt), tint = mix(mix(C.cold, C.warm, warm), C.gold, dawn);
    rings.length = 0;
    g.setTransform(DPR, 0, 0, DPR, 0, 0); g.clearRect(0, 0, W, H);
    g.globalCompositeOperation = 'lighter'; g.lineCap = 'round'; g.lineJoin = 'round';
    const m = Math.min(W, H), fadeOut = 1 - sstep(185.5, 189.5, tt);
    let count = 0;

    if (idle) {                                                // title card: the three O's double as the loading ring
      const now = opt.clock || 0, h = Math.min(W * 0.8 / 6.78, H * 0.16), L = layout(WORD, h), x0 = W / 2 - L.w / 2, cy = H * 0.36;
      WORD.forEach((ch, i) => { const k = WORD.slice(0, i).filter(c => c === 'O').length, w = GLY[ch].w * h;
        const prog = ch === 'O' ? clamp((opt.progress || 0) * 3 - k) : 1, br = ch === 'O' ? 0.55 + 0.45 * Math.sin(now * 2.2 - k * 0.9) ** 2 : 0.9;
        glow(g, () => glyphPath(g, ch, x0 + L.xs[i], cy - h / 2, h, prog), C.cold, br, Math.max(1.3, h * 0.04));
        if (ch === 'O' && prog < 1) glow(g, () => { g.moveTo(x0 + L.xs[i] + w, cy); g.arc(x0 + L.xs[i] + w / 2, cy, h / 2, 0, TAU); }, C.cold, 0.12, 1); });
    } else if (flat) {                                         // the heart monitor, flat
      const y = H * 0.5; glow(g, () => { g.moveTo(0, y); g.lineTo(W, y); }, mix(C.paper, C.warm, 0.3), 0.9, 1.6);
    } else {
      ecg(tt, H * (W < 640 ? 0.69 : W >= 900 ? 0.775 : 0.72), curve(ECG_A, tt), mix(C.warm, C.gold, dawn * 0.6));
      if (tt >= 16 && tt < 33) verse(tt, sstep(16, 16.15, tt) * (1 - sstep(32, 33, tt)));
      if (tt >= 32 && tt < 47.5) count = flood(tt, sstep(32, 33.5, tt));
      if (tt >= 47.5 && tt < 48) { const h = m * 0.07; glow(g, () => glyphPath(g, 'O', W / 2 - h / 2, H / 2 - h / 2, h, ease((tt - 47.5) / 0.4)), C.paper, 0.9, 1.5); }
      if (sec === 'ch1' || sec === 'ch2') {                    // the hook: LO - O - OVE, once per bar
        const h = Math.min(W * 0.86 / 6.78, H * 0.22), a = sstep(0, 0.04, tb) * (1 - sstep(1.74, 1.98, tb));
        const col = sec === 'ch2' ? mix(C.warm, C.gold, 0.25 + 0.15 * (lb >= 8)) : mix(C.warm, C.cold, lb < 8 ? 0.35 : 0.15);
        drawWord(WORD, AT.map(x => bar * 2 + x), W / 2, H * 0.47, h, tt, col, a * (0.85 + 0.15 * kick), Math.max(1.5, h * (sec === 'ch2' ? 0.05 : 0.042)), lb >= 8 ? 1 : 0);
      }
      if (tt >= 80 && tt < 114) {                              // the break: one O, breathing
        const R = m * 0.36 * (1 + 0.025 * heart), a = sstep(80.4, 82, tt) * (1 - sstep(111, 114, tt)), col = mix(C.cold, C.warm, 0.3);
        glow(g, () => { g.moveTo(W / 2, H / 2 - R); g.arc(W / 2, H / 2, R, -Math.PI / 2, -Math.PI / 2 + TAU * ease((tt - 80.4) / 1.6)); }, col, a * (0.3 + 0.5 * heart), 1.2);
        for (let i = lastIdx(E.bells, tt, 't'); i >= 0 && tt - E.bells[i].t < 2.6; i--) {
          const b = E.bells[i], age = tt - b.t, ang = hash(i) * TAU, px = W / 2 + Math.cos(ang) * R, py = H / 2 + Math.sin(ang) * R;
          g.beginPath(); g.arc(px, py, 1.5 + 5 * Math.exp(-age / 0.15), 0, TAU); g.fillStyle = rgba(mix(col, C.paper, 0.5), a * b.v * 1.6 * Math.exp(-age / 0.8)); g.fill();
        }
      }
      for (const tO of E.buildO) { const age = tt - tO; if (age >= 0 && age < 1.2) ring(W / 2, H / 2, m * 0.04, age); }
      for (const tO of E.outroO) { const age = tt - tO; if (age >= 0 && age < 1.2) { burst(W / 2, H * 0.47, m * 0.06, age, tint, 0.5); ring(W / 2, H * 0.47, m * 0.06, age); } }
      if (tt >= 172) {                                         // the word, whole, once
        const h0 = Math.min(W * 0.66 / 3.3, H * 0.2), sh = 1 - 0.55 * sstep(184, 186.5, tt), h = h0 * sh, cy = H * (0.47 - 0.11 * sstep(184, 186.5, tt));
        drawWord(['L', 'O', 'V', 'E'], [172, 172.22, 172.95, 173.15], W / 2, cy, h, tt, mix(C.warm, C.gold, 0.6), 0.95, Math.max(1.4, h * 0.04), 0, 6);
      }
      for (const x of E.impacts) { const age = tt - x.t; if (age >= 0 && age < 1.2) burst(W / 2, H / 2, m * 0.08, age * 0.75, tint, x.v); }
    }
    g.globalCompositeOperation = 'source-over';

    if (gl) {
      gl.viewport(0, 0, glc.width, glc.height);
      gl.uniform2f(U.uRes, glc.width, glc.height); gl.uniform1f(U.uT, idle ? (opt.clock || 0) : tt);
      gl.uniform1f(U.uPhase, idle ? (opt.clock || 0) * 0.05 : integ(SPEED, tt) + 0.06 * kick);
      gl.uniform1f(U.uKick, (kick + 0.6 * impact) * (reduced ? 0.4 : 1)); gl.uniform1f(U.uHeart, heart);
      gl.uniform1f(U.uTun, idle ? 0.12 : curve(TUN, tt)); gl.uniform1f(U.uWarm, warm); gl.uniform1f(U.uDawn, dawn);
      gl.uniform1f(U.uFlat, flat); gl.uniform1f(U.uClap, (clap + env(E.stabs, tt, 0.08) * 0.5) * (reduced ? 0.3 : 1));
      gl.uniform1f(U.uGlitch, idle ? 0 : (env(E.blips, tt, 0.05) + (tt > 46 && tt < 47.5 ? 0.25 * sstep(46, 47.5, tt) : 0)) * (reduced ? 0.2 : 1));
      gl.uniform1f(U.uFade, (idle ? 1 : sstep(-0.5, 2.5, tt)) * fadeOut * (tt >= 47.5 && tt < 48 ? 0.25 : 1));
      const R = new Float32Array(48); for (let i = 0; i < 12; i++) R[i * 4 + 2] = -1;
      rings.sort((p, q) => p[3] - q[3]).slice(0, 12).forEach((r, i) => { R[i * 4] = r[0] / W; R[i * 4 + 1] = 1 - r[1] / H; R[i * 4 + 2] = r[3]; R[i * 4 + 3] = r[2] / H; });
      gl.uniform4fv(U.uRing, R);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    last = { count, sec, lb, kick, heart };
    return last;
  }

  return { init, resize, setScore, frame, hasGL: () => !!gl };
})();
