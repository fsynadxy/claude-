/* Page glue: loads textures, runs the loop, wires the controls. */
(function () {
  'use strict';
  const { Renderer, Model, Animator, EXPRESSIONS } = window.L2D;
  const { clamp } = window.L2D.util;
  const DATA = window.L2D_DATA;
  const $ = (id) => document.getElementById(id);

  const FRAMES = {
    full: [-18, -44, 1098, 1500],   // centred on the body; the hair fans out wider on her left
    half: [170, -14, 910, 1010],
    face: [300, -4, 784, 588],
  };
  const FLOOR = 1500;             // nothing below this line is ever shown (art is cropped there)

  const stage = $('stage'), canvas = $('view');
  let renderer, model, animator;
  let frame = 'full', frameNo = 0, last = 0, running = false;
  const cam = { cx: 512, cy: 728, scale: 1, ready: false };
  const images = {};

  function loadImage(src) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => reject(new Error('texture failed to load'));
      img.src = src;
    });
  }

  function upload() {
    const tex = {};
    for (const id in images) tex[id] = renderer.texture(images[id]);
    for (const p of model.order) {
      p.texture = tex[p.tex];
      p.gpu = renderer.mesh(p);
    }
  }

  // ------------------------------------------------------------------ camera
  function targetView() {
    const w = canvas.width, h = canvas.height;
    const [x0, y0, x1, y1] = FRAMES[frame];
    const scale = Math.min(w / (x1 - x0), h / (y1 - y0));
    let cy = (y0 + y1) / 2;
    cy = Math.min(cy, FLOOR - h / (2 * scale));
    return { cx: (x0 + x1) / 2, cy, scale };
  }

  function stepCamera(dt) {
    const t = targetView();
    if (!cam.ready) { Object.assign(cam, t); cam.ready = true; return; }
    const k = 1 - Math.exp(-dt * 7);
    cam.cx += (t.cx - cam.cx) * k;
    cam.scale *= Math.pow(t.scale / cam.scale, k);
    cam.cy += (t.cy - cam.cy) * k;
    cam.cy = Math.min(cam.cy, FLOOR - canvas.height / (2 * cam.scale));
  }

  function resize() {
    const r = stage.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(2, Math.round(r.width * dpr)), h = Math.max(2, Math.round(r.height * dpr));
    if (w !== canvas.width || h !== canvas.height) { renderer.resize(w, h); cam.ready = false; }
  }

  function toModel(clientX, clientY) {
    const r = canvas.getBoundingClientRect();
    const bx = (clientX - r.left) * (canvas.width / r.width), by = (clientY - r.top) * (canvas.height / r.height);
    return [cam.cx + (bx - canvas.width / 2) / cam.scale, cam.cy + (by - canvas.height / 2) / cam.scale];
  }

  // -------------------------------------------------------------------- loop
  function step(dt) {
    animator.update(dt);
    model.update(dt);
    stepCamera(dt);
  }

  function draw() {
    frameNo++;
    renderer.begin(cam, frameNo);
    const see = model.seeThrough;
    for (const d of model.drawList) {
      const p = d.part;
      if (!p.visible) continue;
      renderer.draw(p, d.ghost ? p.opacity * see * d.k : p.opacity, d.mask);
    }
  }

  function loop(now) {
    if (!running) return;
    const dt = Math.min((now - last) / 1000, 0.05);
    last = now;
    resize();
    step(dt);
    draw();
    syncSliders(now);
    requestAnimationFrame(loop);
  }

  function start() {
    if (running) return;
    running = true;
    last = performance.now();
    requestAnimationFrame(loop);
  }

  // ---------------------------------------------------------------- controls
  const sliders = {};
  let lastSync = 0;
  function syncSliders(now) {
    if (now - lastSync < 90) return;
    lastSync = now;
    for (const id in sliders) {
      const s = sliders[id];
      if (document.activeElement !== s.input || animator.pins[id] === undefined) {
        if (animator.pins[id] === undefined) s.input.value = model.params[id];
      }
      s.out.textContent = Number(model.params[id]).toFixed(s.digits);
    }
  }

  function buildParams() {
    const host = $('params');
    let group = null, box = null;
    for (const p of model.paramDefs) {
      if (p.group !== group) {
        group = p.group;
        box = document.createElement('div');
        box.className = 'pgroup';
        const h = document.createElement('h3');
        h.textContent = group;
        box.appendChild(h);
        host.appendChild(box);
      }
      const row = document.createElement('label');
      row.className = 'prow';
      row.htmlFor = 'p-' + p.id;
      const digits = p.max - p.min > 5 ? 1 : 2;
      row.innerHTML = `<span class="pname">${p.name}</span><code class="pid">${p.id}</code><output class="pval"></output>`;
      const input = document.createElement('input');
      input.type = 'range';
      input.id = 'p-' + p.id;
      input.min = p.min; input.max = p.max; input.step = (p.max - p.min) / 200; input.value = p.def;
      input.addEventListener('input', () => {
        animator.pin(p.id, Number(input.value));
        row.classList.add('pinned');
        $('unpin').disabled = false;
      });
      row.appendChild(input);
      box.appendChild(row);
      sliders[p.id] = { input, out: row.querySelector('.pval'), row, digits };
    }
    $('unpin').addEventListener('click', () => {
      animator.unpinAll();
      for (const id in sliders) sliders[id].row.classList.remove('pinned');
      $('unpin').disabled = true;
    });
  }

  function buildExpressions() {
    const host = $('expressions');
    for (const id in EXPRESSIONS) {
      const b = document.createElement('button');
      b.type = 'button';
      b.id = 'expr-' + id;
      b.className = 'chip';
      b.textContent = EXPRESSIONS[id].name;
      b.setAttribute('aria-pressed', id === 'neutral' ? 'true' : 'false');
      b.addEventListener('click', () => {
        animator.setExpression(id);
        for (const el of host.children) el.setAttribute('aria-pressed', el === b ? 'true' : 'false');
      });
      host.appendChild(b);
    }
  }

  function wire() {
    buildExpressions();
    buildParams();
    for (const key of ['follow', 'idle', 'blink', 'breath', 'talk']) {
      const el = $('opt-' + key);
      el.checked = animator.opts[key];
      el.addEventListener('change', () => { animator.opts[key] = el.checked; });
    }
    const wind = $('wind');
    wind.value = model.windStrength;
    wind.addEventListener('input', () => { model.windStrength = Number(wind.value); });

    for (const b of document.querySelectorAll('[data-frame]')) {
      b.addEventListener('click', () => {
        frame = b.dataset.frame;
        for (const o of document.querySelectorAll('[data-frame]')) o.setAttribute('aria-pressed', o === b ? 'true' : 'false');
      });
    }
    for (const b of document.querySelectorAll('[data-bg]')) {
      if (b === stage) continue;
      b.addEventListener('click', () => {
        stage.dataset.bg = b.dataset.bg;
        for (const o of document.querySelectorAll('button[data-bg]')) o.setAttribute('aria-pressed', o === b ? 'true' : 'false');
      });
    }

    // gaze follows the pointer anywhere on the page
    const look = (e) => {
      const [mx, my] = toModel(e.clientX, e.clientY);
      animator.lookAt((mx - model.S.headCx) / 420, (my - model.S.headCy) / 420);
    };
    window.addEventListener('pointermove', (e) => { if (e.pointerType !== 'touch' || e.buttons) look(e); }, { passive: true });
    document.documentElement.addEventListener('mouseleave', () => animator.lookAway());
    window.addEventListener('blur', () => animator.lookAway());

    let down = null;
    canvas.addEventListener('pointerdown', (e) => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; look(e); });
    canvas.addEventListener('pointerup', (e) => {
      if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) < 12 && performance.now() - down.t < 500) {
        const [mx, my] = toModel(e.clientX, e.clientY);
        const hit = model.hitTest(mx, my);
        if (hit) { animator.tap(hit); $('hint').classList.add('seen'); }
      }
      down = null;
      if (e.pointerType === 'touch') setTimeout(() => animator.lookAway(), 1200);
    });

    canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); running = false; });
    canvas.addEventListener('webglcontextrestored', () => { renderer._setup(); renderer.maskTex = null; resize(); upload(); start(); });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) running = false; else start();
    });
    if (window.ResizeObserver) new ResizeObserver(() => { if (renderer) resize(); }).observe(stage);
  }

  async function boot() {
    try {
      renderer = new Renderer(canvas);
    } catch (err) {
      $('loading').textContent = '这个浏览器没有开启 WebGL，无法显示模型。请换用新版 Chrome、Edge、Safari 或 Firefox。';
      return;
    }
    model = new Model(DATA.layout, DATA.parts);
    animator = new Animator(model);
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      animator.opts.idle = false;
      model.windStrength = 0.1;
    }
    const need = [...new Set(model.order.map((p) => p.tex))];
    try {
      await Promise.all(need.map(async (id) => { images[id] = await loadImage(DATA.textures[id]); }));
    } catch (err) {
      $('loading').textContent = '贴图载入失败，请刷新页面重试。';
      return;
    }
    resize();
    upload();
    wire();
    // settle springs and the idle pose before the first visible frame
    for (let i = 0; i < 90; i++) step(1 / 60);
    $('loading').hidden = true;
    stage.classList.add('ready');
    window.L2DApp = {
      model, animator, renderer, cam,
      setFrame(f) { frame = f; cam.ready = false; },
      /** Deterministic stepping for tests: stop the loop, advance, draw. */
      advance(seconds) {
        running = false;
        resize();
        const n = Math.round(seconds * 60);
        for (let i = 0; i < n; i++) step(1 / 60);
        draw();
      },
      resume: start,
    };
    start();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
