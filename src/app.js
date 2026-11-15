/* LO-O-OVE — the page: streamed audio, the editor, subtitles, controls and the frame loop.
   One clock rules everything: the audio context's own time. */
(() => {
  const $ = id => document.getElementById(id);
  const qs = new URLSearchParams(location.search), STILL = qs.has('t') ? parseFloat(qs.get('t')) : null;
  const reduced = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  const TICKS = Script.ticks(), SC = Score.build(TICKS), END = Score.DUR, OV = 256;
  Stage.init($('gl'), $('fx')); Stage.setScore(SC);
  if (!Stage.hasGL()) document.body.classList.add('nogl');
  addEventListener('resize', () => { Stage.resize(); layoutEditor(); });

  /* ---------- audio: rendered off the main thread, played as overlapping, cross-faded segments ---------- */
  const A = { ctx: null, segs: [], srcs: [], T0: null, progress: 0, ready: false, done: false };
  try { const AC = window.AudioContext || window.webkitAudioContext;
    try { A.ctx = new AC({ latencyHint: 'playback' }); } catch (e) { A.ctx = new AC(); } } catch (e) { A.ctx = null; }

  function onSeg(m) {
    const n = m.L.length;
    if (m.start > 0) for (let i = 0; i < OV && i < n; i++) { const w = (i + 0.5) / OV; m.L[i] *= w; m.R[i] *= w; }
    if (!m.last) for (let i = 0; i < OV && i < n; i++) { const w = (i + 0.5) / OV; m.L[n - 1 - i] *= w; m.R[n - 1 - i] *= w; }
    const buf = A.ctx.createBuffer(2, n, A.ctx.sampleRate);
    buf.getChannelData(0).set(m.L); buf.getChannelData(1).set(m.R);
    const seg = { buf, start: m.start };
    A.segs.push(seg); A.progress = m.progress;
    if (A.segs.length >= 3 || m.last) A.ready = true;
    if (m.last) A.done = true;
    if (A.T0 !== null) schedule(seg);
    status();
  }
  function schedule(seg) {
    const c = A.ctx, when = A.T0 + seg.start / c.sampleRate, now = c.currentTime, src = c.createBufferSource();
    src.buffer = seg.buf; src.connect(c.destination);
    if (when >= now + 0.005) src.start(when);
    else { const off = now + 0.03 - when; if (off >= seg.buf.duration) return; src.start(now + 0.03, off); }   // late: join in step
    A.srcs.push(src);
  }
  function startRender() {
    if (!A.ctx) { status(); return; }
    const sr = A.ctx.sampleRate;
    try {
      const code = $('src-score').textContent + '\n' + $('src-dsp').textContent + '\n' + $('src-worker').textContent;
      const w = new Worker(URL.createObjectURL(new Blob([code], { type: 'text/javascript' })));
      w.onmessage = e => onSeg(e.data);
      w.onerror = e => { if (e.preventDefault) e.preventDefault(); w.terminate(); if (!A.segs.length) renderHere(sr); };
      w.postMessage({ sr, ticks: TICKS });
    } catch (e) { renderHere(sr); }
  }
  async function renderHere(sr) {                                    // no worker allowed: same work, on this thread, a chunk at a time
    const r = DSP.create(Score.build(TICKS), END, { sr }), ends = DSP.chunks(END); let s = 0;
    for (let i = 0; i < ends.length; i++) {
      await new Promise(res => setTimeout(res, 0));
      const e = r.renderTo(ends[i]);
      onSeg({ start: s, L: r.L.slice(s, e), R: r.R.slice(s, e), last: i === ends.length - 1, progress: ends[i] / END }); s = e - OV;
    }
  }
  function play() {
    A.srcs.forEach(s => { try { s.stop(); } catch (e) {} }); A.srcs = [];
    A.T0 = A.ctx.currentTime + 0.15; A.segs.forEach(schedule);
  }
  const songTime = () => {
    if (STILL !== null) return STILL;
    if (!A.ctx || A.T0 === null) return -1;
    return A.ctx.currentTime - A.T0 - (A.ctx.outputLatency || 0) - (A.ctx.baseLatency || 0);
  };

  /* ---------- controls ---------- */
  const go = $('go'), st = $('status');
  function status() {
    if (!A.ctx) { go.disabled = true; st.textContent = '这个浏览器不能播放网页音频。'; return; }
    go.disabled = !A.ready;
    st.textContent = A.done ? '准备好了。建议戴上耳机。' : A.ready ? '可以开始了，后面的部分边播边写。' : '正在作曲 ' + Math.round(A.progress * 100) + '%';
  }
  async function begin() {
    if (!A.ready) return;
    try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch (e) {}
    try { await A.ctx.resume(); } catch (e) {}
    document.body.classList.add('playing'); $('start').hidden = true; $('end').hidden = true; setPaused(false); play();
  }
  function setPaused(p) { $('pause').setAttribute('aria-label', p ? '继续' : '暂停'); $('pause').textContent = p ? '▶' : '❚❚'; document.body.classList.toggle('paused', p); }
  function togglePause() {
    if (!A.ctx || A.T0 === null || !$('end').hidden) return;
    if (A.ctx.state === 'running') { A.ctx.suspend(); setPaused(true); } else { A.ctx.resume(); setPaused(false); }
  }
  go.addEventListener('click', begin);
  $('again').addEventListener('click', begin);
  $('pause').addEventListener('click', togglePause);
  addEventListener('keydown', e => { if (e.code === 'Space' && document.body.classList.contains('playing') && e.target === document.body) { e.preventDefault(); togglePause(); } });

  /* ---------- the editor ---------- */
  const code = $('code'), lineEls = [], cache = [];
  let rows = 14, lh = 20;
  function layoutEditor() {
    const narrow = innerWidth < 640, short = innerHeight < 620;
    rows = narrow ? (innerHeight < 700 ? 6 : 7) : short ? 8 : 11;
    code.style.setProperty('--rows', rows);
    const probe = lineEls[0] || code; lh = parseFloat(getComputedStyle(probe).lineHeight) || 20;
  }
  const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  function hl(text) {
    if (/^\s*(\/\*|\*)/.test(text)) return '<span class="c">' + esc(text) + '</span>';
    const ci = text.indexOf('//'), body = ci >= 0 ? text.slice(0, ci) : text, com = ci >= 0 ? text.slice(ci) : '';
    return esc(body).replace(/("[^"]*"?)|\b(const|let|function|for|of|if|else|while)\b|\b([A-Za-z_]\w*)(?=\()|([{}()[\];=.,])/g,
      (m, s, k, f, p) => s ? '<span class="s">' + s + '</span>' : k ? '<span class="k">' + k + '</span>' : f ? '<span class="f">' + f + '</span>' : '<span class="p">' + p + '</span>')
      + (com ? '<span class="c">' + esc(com) + '</span>' : '');
  }
  function lineHTML(l, idle) {
    const ch = [...l.text];
    if (l.sel) return hl(ch.slice(0, l.sel[0]).join('')) + '<span class="sel">' + esc(ch.slice(l.sel[0], l.sel[1]).join('')) + '</span>' + hl(ch.slice(l.sel[1]).join('')) + '<i class="caret"></i>';
    if (l.caret >= 0) return hl(ch.slice(0, l.caret).join('')) + '<i class="caret' + (idle ? ' blink' : '') + '"></i>' + hl(ch.slice(l.caret).join(''));
    return hl(l.text) || ' ';
  }
  function drawEditor(t) {
    const ed = Script.editorAt(t);
    while (lineEls.length < ed.lines.length) { const d = document.createElement('div'); d.className = 'ln'; d.innerHTML = '<span class="no"></span><span class="tx"></span>'; code.firstElementChild.appendChild(d); lineEls.push(d); cache.push(''); }
    lineEls.forEach((el, i) => {
      const l = ed.lines[i];
      if (!l) { if (cache[i] !== 'x') { el.hidden = true; cache[i] = 'x'; } return; }
      const key = (i + 1) + '|' + l.text + '|' + l.caret + '|' + (l.sel || '') + '|' + l.run + '|' + ed.idle;
      if (key === cache[i]) return;
      cache[i] = key; el.hidden = false;
      el.className = 'ln' + (l.run ? ' run' : '') + (l.dup ? ' dup' : '');
      el.firstChild.textContent = i + 1; el.lastChild.innerHTML = lineHTML(l, ed.idle);
    });
    const top = Math.max(0, Math.min(ed.lines.length - rows, ed.focus - rows + 2));
    code.firstElementChild.style.transform = 'translateY(' + (-top * lh) + 'px)';
  }

  /* ---------- per-frame page state ---------- */
  const curve = (K, t) => { if (t <= K[0][0]) return K[0][1]; for (let i = 1; i < K.length; i++) if (t <= K[i][0]) { const [a, va] = K[i - 1], [b, vb] = K[i]; return b === a ? vb : va + (vb - va) * (t - a) / (b - a); } return K[K.length - 1][1]; };
  const ED_A = [[0, 0], [0.4, 1], [47.4, 1], [47.6, 0.12], [80.3, 0.12], [81.3, 1], [126.95, 1], [127, 0], [128, 0], [128.6, 0.12], [160, 0.12], [161.2, 0.92], [184.6, 0.92], [186.4, 0]];
  const OT = SC.voice.filter(v => v.k === 'o' || v.k === 've').map(v => v.t).sort((a, b) => a - b);
  const fmt = s => { s = Math.max(0, Math.floor(s)); return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0'); };
  const nf = new Intl.NumberFormat('en-US');
  const ed = $('editor'), sub = $('sub'), hud = $('hud'), bar = $('bar'), clock = $('clock');
  let lastSub = '', lastPos = '', ended = false;
  function frame(now) {
    requestAnimationFrame(frame);
    const t = songTime(), playing = t >= 0;
    const s = Stage.frame(playing ? t : -1, { reduced, clock: now / 1000, progress: A.progress });
    if (!playing) return;
    ed.style.opacity = curve(ED_A, t).toFixed(3);
    let k = OT.length - 1; while (k >= 0 && OT[k] > t) k--;
    ed.style.setProperty('--pulse', k >= 0 ? Math.exp(-(t - OT[k]) / 0.12).toFixed(3) : 0);
    drawEditor(Math.min(t, END));
    hud.hidden = !(t >= 32 && t < 47.5);
    if (!hud.hidden) hud.textContent = 'print("O") × ' + nf.format(s.count);
    const sb = Script.subAt(t);
    const text = sb ? sb.text : '', pos = sb ? sb.pos : lastPos;
    if (text !== lastSub) { sub.textContent = text; lastSub = text; }
    if (pos !== lastPos) { sub.className = 'sub ' + pos; lastPos = pos; }
    sub.style.opacity = sb ? sb.a.toFixed(3) : 0;
    bar.style.transform = 'scaleX(' + Math.min(1, t / END).toFixed(4) + ')';
    clock.textContent = fmt(Math.min(t, END)) + ' / ' + fmt(END);
    const over = t >= 186.5;
    if (over !== ended) { ended = over; $('end').hidden = !over; document.body.classList.toggle('over', over); }
  }

  layoutEditor();
  if (STILL !== null) { document.body.classList.add('playing', 'still'); $('start').hidden = true; }
  else { status(); startRender(); }
  requestAnimationFrame(frame);
})();
