/*
 * 长夜行舟  ARK THROUGH THE LONG NIGHT — an unofficial Arknights fan film.
 *
 * The whole film is one pure function: Film.render(ctx, t) paints the frame for time t (seconds)
 * onto a 1920x1080 canvas. No state is carried between frames, so any frame can be rendered on
 * its own, in any order — which is what lets tools/render.mjs fan the work out over several
 * browser pages.
 *
 * Every cut sits on the bar grid of the soundtrack (《曙光》, 140 BPM, first downbeat at 29.79 s).
 */
(function (root) {
'use strict';

const W = 1920, H = 1080, FPS = 30;
const BPM = 140, BEAT = 60 / BPM, BAR = BEAT * 4, T0 = 29.79;
const bar = n => T0 + n * BAR;          // time of bar n (n = 0 is the first downbeat)
const DUR = 172;
const LB = 138;                          // letterbox bar height -> 2.39:1 picture
const PY0 = LB, PY1 = H - LB, PH = PY1 - PY0, PCY = H / 2;

const C = { bg: '#050608', ink: '#ece7dc', amber: '#ff9d2e', paper: '#ebe6da', night: '#0b0d12' };
const F = { serif: '"Noto Serif SC", serif', sans: '"Noto Sans SC", sans-serif', cond: '"Barlow Condensed", sans-serif', mono: '"JetBrains Mono", monospace' };

/* ---------- small maths ---------- */
const clamp = (x, a, b) => x < a ? a : x > b ? b : x;
const sat = x => clamp(x, 0, 1);
const lerp = (a, b, p) => a + (b - a) * p;
const seg = (t, a, b) => sat((t - a) / (b - a));
const smooth = p => p * p * (3 - 2 * p);
const S = (t, a, b) => smooth(seg(t, a, b));
const E = {
  lin: p => p,
  io: p => p < .5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2,
  sine: p => .5 - .5 * Math.cos(Math.PI * p),
  out: p => 1 - Math.pow(1 - p, 3),
  out4: p => 1 - Math.pow(1 - p, 4),
  expo: p => p >= 1 ? 1 : 1 - Math.pow(2, -10 * p),
  kick: p => .5 * (1 - Math.pow(1 - p, 7)) + .5 * p,   // lands fast on the cut, then drifts
};
const hash = i => { const x = Math.sin(i * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };
const rgba = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`; };

/* ---------- chapters (shown in the HUD; one lamp per chapter) ---------- */
const CH = [
  { a: 0,       zh: '苏醒', en: 'AWAKENING' },
  { a: 15.4,    zh: '大地', en: 'TERRA' },
  { a: bar(0),  zh: '行舟', en: 'THE ARK' },
  { a: bar(8),  zh: '众生', en: 'THE PEOPLE' },
  { a: bar(16), zh: '长夜', en: 'THE LONG NIGHT' },
  { a: bar(32), zh: '灯火', en: 'LAMPS' },
  { a: bar(48), zh: '曙光', en: 'FIRST LIGHT' },
  { a: bar(64), zh: '航行', en: 'ONWARD' },
];
const chapterAt = t => { let i = 0; for (let k = 0; k < CH.length; k++) if (t >= CH[k].a) i = k; return i; };

/* ---------- shot list ----------
 * cam = [u, v, zoom, rotationDeg]: (u,v) is the point of the still that sits at screen centre.
 * g   = grade {s: saturate, c: contrast, b: brightness, blur}; may be a function of local time.
 */
const SHOTS = [];
function shot(k, a, b, c0, c1, o) { SHOTS.push(Object.assign({ k, a, b, c0, c1, ease: 'lin', fin: 0, fout: 0, g: {} }, o || {})); }
const CROP = { hall: [0, .175, 1, .855] };

// 00 苏醒 — the sarcophagus opens; the first thing you see is a hand.
shot('wake', 3.2, 15.4, [.55, .52, 1.05, 0], [.6, .46, 1.24, 0], { ease: 'sine', fin: 2.4, fout: 1.0, fx: 'eyelid',
  g: (p, t) => ({ s: .22, c: 1.06, b: lerp(.5, .92, S(t, 4.5, 10)), blur: 26 * (1 - S(t, 4.6, 10.2)) }), tint: ['multiply', '#9fb0c4', .45] });
// 01 大地 — originium, then a Catastrophe.
shot('ori', 15.4, 21.6, [.47, .47, 1.1, 0], [.515, .45, 1.36, 0], { ease: 'sine', fin: 1.3,
  g: (p) => ({ s: lerp(.25, .9, p), c: 1.12, b: lerp(.8, 1.05, p) }), fx: 'amber' });
shot('meteor', 21.6, 27.45, [.56, .34, 1.42, -3], [.42, .6, 1.2, 0], { ease: 'io', fin: 1.2, fout: .55,
  g: (p) => ({ s: lerp(.35, .75, p), c: 1.1, b: .92 }) });
// 02 行舟 — the hit. Pull back from one person on a stairway to the whole landship.
shot('ship', bar(0), bar(4), [.5265, .545, 3.25, 0], [.5, .5, 1.0, 0], { ease: 'out4', pulse: .004,
  g: (p, t) => { const d = S(t, bar(1.7), bar(2.2)); return { s: .38, c: 1.12, b: lerp(.96, .46, d), blur: 5 * d }; }, tint: ['multiply', '#a9b9cc', .5] });
shot('depart', bar(4), bar(8), [.46, .4, 1.34, 0], [.58, .385, 1.14, 0], { ease: 'kick', pulse: .004, g: { s: .82, c: 1.06, b: .96 } });
// 03 众生 — the cities, on the bar.
const place = (zh, en, sub) => ({ zh, en, sub });
shot('lungmen', bar(8), bar(10), [.3, .5, 1.3, 0], [.62, .52, 1.22, 0], { ease: 'kick', pulse: .005, place: place('龙门', 'LUNGMEN', '炎国 · 移动城邦') });
shot('penguin', bar(10), bar(11), [.5, .52, 1.26, 0], [.5, .5, 1.12, 0], { ease: 'kick', pulse: .005, place: place('企鹅物流', 'PENGUIN LOGISTICS', '龙门 · 物流公司') });
shot('rhine', bar(11), bar(12), [.42, .5, 1.12, 0], [.5, .5, 1.26, 0], { ease: 'kick', pulse: .005, place: place('莱茵生命', 'RHINE LAB', '哥伦比亚 · 科研机构') });
shot('kjerag', bar(12), bar(14), [.62, .46, 1.3, 0], [.4, .46, 1.2, 0], { ease: 'kick', pulse: .005, place: place('谢拉格', 'KJERAG', '雪境 · 喀兰贸易') });
shot('ursus', bar(14), bar(15), [.5, .5, 1.12, 0], [.56, .5, 1.26, 0], { ease: 'kick', pulse: .005, place: place('乌萨斯', 'URSUS', '学生自治团') });
shot('kazimierz', bar(15), bar(16), [.6, .46, 1.3, 0], [.58, .44, 1.16, 0], { ease: 'kick', pulse: .005, place: place('卡西米尔', 'KAZIMIERZ', '骑士之国') });
// 04 长夜 — two bars each; colder, harder, embers in the air.
const NIGHT = { s: .62, c: 1.2, b: .86 };
shot('snake', bar(16), bar(18), [.56, .52, 1.36, 2], [.56, .5, 1.16, 0], { ease: 'kick', pulse: .006, shake: 14, g: NIGHT, fx: 'embers' });
shot('chixiao', bar(18), bar(20), [.5, .47, 1.16, -1.5], [.62, .45, 1.34, 0], { ease: 'kick', pulse: .006, shake: 12, g: NIGHT, fx: 'embers', slash: 1 });
shot('assassin', bar(20), bar(22), [.42, .5, 1.32, 0], [.6, .4, 1.2, 0], { ease: 'kick', pulse: .006, shake: 10, g: NIGHT, fx: 'embers' });
shot('regicide', bar(22), bar(24), [.5, .36, 1.18, 1.5], [.47, .34, 1.4, 0], { ease: 'kick', pulse: .006, shake: 12, g: NIGHT, fx: 'embers', slash: -1 });
shot('descent', bar(24), bar(26), [.5, .5, 1.36, 0], [.5, .46, 1.12, 0], { ease: 'kick', pulse: .006, shake: 16, g: NIGHT, fx: 'embers' });
shot('holyfool', bar(26), bar(28), [.48, .44, 1.1, -2], [.48, .42, 1.36, 0], { ease: 'kick', pulse: .006, shake: 12, g: NIGHT, fx: 'embers' });
shot('blade', bar(28), bar(30), [.56, .48, 1.3, 0], [.42, .44, 1.2, 0], { ease: 'kick', pulse: .006, shake: 10, g: NIGHT, fx: 'embers', slash: 1 });
shot('failures', bar(30), bar(32), [.5, .5, 1.1, 0], [.5, .56, 1.34, 0], { ease: 'sine', pulse: .004, g: (p) => ({ s: lerp(.6, .3, p), c: 1.15, b: lerp(.86, .6, p) }) });
// 05 灯火 — the music drops; slow dissolves; the lamps come on one at a time.
const LAMP = { s: .85, c: 1.06, b: .9 };
shot('reflection', bar(32), bar(34), [.5, .52, 1.3, 0], [.5, .34, 1.14, 0], { ease: 'sine', fin: 1.2, g: { s: .7, c: 1.1, b: .95 }, fx: 'bokeh' });
shot('village', bar(34), bar(36), [.4, .52, 1.14, 0], [.52, .56, 1.3, 0], { ease: 'sine', fin: 1.2, g: { s: .95, c: 1.08, b: 1.05 }, fx: 'bokeh' });
shot('surgery', bar(36), bar(38), [.34, .5, 1.3, 0], [.56, .44, 1.16, 0], { ease: 'sine', fin: 1.2, g: LAMP, fx: 'bokeh' });
shot('hope', bar(38), bar(40), [.56, .46, 1.14, 0], [.68, .6, 1.34, 0], { ease: 'sine', fin: 1.2, g: { s: .95, c: 1.06, b: .95 }, fx: 'bokeh' });
shot('street', bar(40), bar(43), [.3, .46, 1.34, 0], [.62, .5, 1.14, 0], { ease: 'sine', fin: 1.0, pulse: .003, g: (p) => ({ s: lerp(.85, 1.08, p), c: 1.06, b: lerp(.9, 1.04, p) }), fx: 'bokeh' });
shot('declaration', bar(43), bar(46), [.5, .62, 1.34, 0], [.46, .44, 1.12, 0], { ease: 'sine', fin: .7, pulse: .004, g: { s: 1.02, c: 1.05, b: 1.0 }, fx: 'bokeh' });
shot('choice', bar(46), bar(47), [.52, .46, 1.14, 0], [.57, .43, 1.3, 0], { ease: 'kick', pulse: .005, g: { s: 1.05, c: 1.06, b: 1.0 } });
shot('eagle', bar(47), bar(48), [.44, .4, 1.36, -2], [.4, .36, 1.16, 0], { ease: 'kick', pulse: .005, g: { s: 1.0, c: 1.1, b: 1.0 }, slash: 1, slashAt: 111.23 });
// 06 曙光 — full colour at last.
const DAWN = { s: 1.12, c: 1.06, b: 1.03 };
shot('parting', bar(48), bar(50), [.5, .46, 1.5, 0], [.52, .44, 1.14, 0], { ease: 'kick', pulse: .008, shake: 10, g: DAWN, fx: 'motes' });
shot('falling', bar(50), bar(52), [.5, .5, 1.12, -3], [.5, .48, 1.36, 0], { ease: 'kick', pulse: .008, g: DAWN, fx: 'motes' });
shot('drones', bar(52), bar(54), [.47, .62, 1.38, 0], [.48, .46, 1.14, 0], { ease: 'kick', pulse: .008, g: DAWN, fx: 'motes' });
// bars 54-56 are the three-strip shot, see STRIPS
shot('dusk', bar(56), bar(59), [.56, .5, 1.36, 0], [.46, .5, 1.14, 0], { ease: 'kick', pulse: .006, g: DAWN, fx: 'motes' });
shot('reunion', bar(59), bar(62), [.5, .62, 1.4, 0], [.5, .5, 1.1, 0], { ease: 'sine', fin: .9, pulse: .004, g: DAWN, fx: 'motes' });
shot('return', bar(62), bar(64), [.34, .46, 1.12, 0], [.52, .42, 1.3, 0], { ease: 'sine', fin: .9, pulse: .003, g: { s: 1.05, c: 1.04, b: 1.03 }, fx: 'motes' });
// 07 航行 — two people in front of a window, and whatever is beyond it.
shot('hall', bar(64), bar(72) + 1.4, [.455, .52, 1.0, 0], [.468, .525, 1.4, 0], { ease: 'sine', fin: 2.2,
  g: (p, t) => ({ s: lerp(.55, 1.0, S(t, bar(66), bar(71))), c: 1.04, b: lerp(.86, 1.06, S(t, bar(66), bar(72))) }), fx: 'motes' });

const STRIPS = { a: bar(54), b: bar(56), items: [
  { k: 'parliament', c0: [.36, .44, 1.5, 0], c1: [.34, .42, 1.36, 0] },
  { k: 'whither',    c0: [.44, .4, 1.5, 0],  c1: [.46, .36, 1.36, 0] },
  { k: 'farewell',   c0: [.56, .56, 1.5, 0], c1: [.58, .54, 1.36, 0] },
] };

SHOTS.sort((x, y) => x.a - y.a);
SHOTS.forEach((s, i) => { const n = SHOTS[i + 1]; s.end = s.b + (n && Math.abs(n.a - s.b) < 1e-6 && n.fin ? n.fin : 0); });

/* additive flashes on the hits */
const FLASH = [[bar(0), 1.0, .55], [bar(4), .32, .3], [bar(8), .22, .22], [bar(16), .4, .3], [bar(24), .3, .25], [111.23, .5, .25], [bar(48), 1.0, .6], [bar(50), .25, .25], [bar(52), .25, .25], [bar(54), .2, .2], [bar(56), .45, .4]];

/* ---------- narration ---------- */
const LL = { x: 150, y: PY1 - 96, align: 'l' };   // lower-left anchor
const CUES = [
  { a: 9.6, b: 14.9, zh: ['这片大地，从不许诺黎明。'], en: 'THIS LAND HAS NEVER PROMISED A DAWN.', ...LL },
  { a: 16.4, b: 21.3, zh: ['矿石在土里生长，', '在风里，在人的身体里。'], en: 'ORIGINIUM GROWS — IN SOIL, IN WIND, IN THE BODY.', x: 150, y: PCY + 66, align: 'l' },
  { a: 22.5, b: 27.25, zh: ['天灾过后，', '地图上又少了一座城。'], en: 'AFTER EACH CATASTROPHE, ONE LESS CITY ON THE MAP.', x: 1770, y: PY1 - 96, align: 'r' },
  { a: 27.62, b: 29.79, zh: ['于是，有人造了一艘船。'], en: 'SO SOMEONE BUILT A SHIP.', x: W / 2, y: PCY + 6, align: 'c', hard: true, size: 60, noscrim: true },
  { a: bar(4) + .45, b: bar(8) - .2, zh: ['不为逃离长夜，', '只为载着灯，穿过去。'], en: 'NOT TO FLEE THE NIGHT — TO CARRY A LAMP THROUGH IT.', ...LL },
  { a: bar(8) + .3, b: bar(12) - .12, zh: ['大地上有许多城，'], en: 'MANY CITIES STAND ON THIS LAND —', ...LL },
  { a: bar(12) + .3, b: bar(16) - .12, zh: ['城里有许多不肯熄灭的人。'], en: 'AND IN THEM, MANY WHO REFUSE TO GO DARK.', ...LL },
  { a: bar(16) + .4, b: bar(22) - .2, zh: ['长夜很长。'], en: 'THE NIGHT IS LONG.', ...LL },
  { a: bar(24) + .4, b: bar(31) + .2, zh: ['长到有人忘了，', '天原本会亮。'], en: 'LONG ENOUGH TO FORGET THE SKY EVER BRIGHTENS.', ...LL },
  { a: bar(32) + .9, b: bar(36) - .3, zh: ['可总有人，', '在最深的夜里点灯。'], en: 'YET SOMEONE ALWAYS LIGHTS A LAMP IN THE DEEPEST DARK.', ...LL },
  { a: bar(36) + .6, b: bar(40) - .3, zh: ['每一盏灯，都是一个名字。'], en: 'EVERY LAMP IS A NAME.', ...LL },
  { a: bar(40) + .6, b: bar(43) + .3, zh: ['灯一盏一盏亮起来，'], en: 'ONE BY ONE, THE LAMPS COME ON —', ...LL },
  { a: bar(43) + .7, b: bar(46) - .15, zh: ['就成了路。'], en: 'AND BECOME A ROAD.', ...LL },
  { a: bar(56) + .5, b: bar(59) - .1, zh: ['“因为还有我们，', '而我们还有明天。”'], en: '“BECAUSE WE ARE STILL HERE — AND WE STILL HAVE TOMORROW.”', ...LL },
  { a: bar(64) + 2.0, b: bar(68) - .4, zh: ['这片大地，从不许诺黎明。'], en: 'THIS LAND HAS NEVER PROMISED A DAWN.', ...LL },
  { a: bar(68) + .3, b: bar(72) - .5, zh: ['那就航行，直到它来。'], en: 'THEN WE SAIL — UNTIL IT COMES.', ...LL },
];

/* ---------- assets & caches ---------- */
const IMG = {};
let META = {};
let buf, bctx, grain = [], vign, glowA, glowW, glowR;

function makeCanvas(w, h) { const c = (typeof OffscreenCanvas !== 'undefined') ? new OffscreenCanvas(w, h) : Object.assign(document.createElement('canvas'), { width: w, height: h }); return c; }
function glowSprite(rgb) {
  const c = makeCanvas(128, 128), x = c.getContext('2d'), g = x.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, `rgba(${rgb},1)`); g.addColorStop(.18, `rgba(${rgb},.55)`); g.addColorStop(.5, `rgba(${rgb},.12)`); g.addColorStop(1, `rgba(${rgb},0)`);
  x.fillStyle = g; x.fillRect(0, 0, 128, 128); return c;
}
function init(images, meta) {
  Object.assign(IMG, images); META = meta || {};
  buf = makeCanvas(W, H); bctx = buf.getContext('2d');
  // film grain: a few 512px tiles of neutral-grey noise, picked per frame
  for (let k = 0; k < 6; k++) {
    const c = makeCanvas(512, 512), x = c.getContext('2d'), d = x.createImageData(512, 512);
    let s = 1234567 + k * 7919;
    for (let i = 0; i < d.data.length; i += 4) {
      s = (s * 1664525 + 1013904223) >>> 0; const a = (s >>> 8) / 16777216;
      s = (s * 1664525 + 1013904223) >>> 0; const b = (s >>> 8) / 16777216;
      const v = 128 + (a + b - 1) * 96; d.data[i] = d.data[i + 1] = d.data[i + 2] = v; d.data[i + 3] = 255;
    }
    x.putImageData(d, 0, 0); grain.push(c);
  }
  vign = makeCanvas(W, H);
  { const x = vign.getContext('2d'); x.translate(W / 2, PCY); x.scale(1, PH / W * 1.25);
    const g = x.createRadialGradient(0, 0, W * .28, 0, 0, W * .62); g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,.62)');
    x.fillStyle = g; x.fillRect(-W, -W, 2 * W, 2 * W); }
  glowA = glowSprite('255,164,60'); glowW = glowSprite('255,244,222'); glowR = glowSprite('255,96,48');
}

/* ---------- drawing a still through a camera ---------- */
function camAt(s, p) {
  const e = E[s.ease](sat(p)), a = s.c0, b = s.c1;
  return [lerp(a[0], b[0], e), lerp(a[1], b[1], e), a[2] * Math.pow(b[2] / a[2], e), lerp(a[3], b[3], e)];
}
function fit(im, cam, crop, vw, vh) {
  // scale and source centre so that `im` covers a vw x vh window, however the camera is set
  const iw = im.width, ih = im.height, cw = (crop[2] - crop[0]) * iw, ch = (crop[3] - crop[1]) * ih;
  const r = cam[3] * Math.PI / 180, co = Math.abs(Math.cos(r)), si = Math.abs(Math.sin(r));
  const nw = vw * co + vh * si, nh = vw * si + vh * co;      // window grown by the rotation
  const s = Math.max(nw / cw, nh / ch) * cam[2], hw = nw / 2 / s, hh = nh / 2 / s;
  return { s, r, cx: clamp(cam[0] * iw, crop[0] * iw + hw, crop[2] * iw - hw), cy: clamp(cam[1] * ih, crop[1] * ih + hh, crop[3] * ih - hh) };
}
function drawStill(ctx, im, cam, crop, vw, vh, cx0, cy0) {
  const f = fit(im, cam, crop, vw, vh);
  ctx.translate(cx0, cy0); if (f.r) ctx.rotate(f.r); ctx.scale(f.s, f.s); ctx.drawImage(im, -f.cx, -f.cy);
}
function project(s, t, u, v) {       // where does point (u,v) of a shot's still land on screen at time t?
  const im = IMG[s.k], cam = camAt(s, (t - s.a) / (s.end - s.a)); cam[2] *= beatPulse(t, s.pulse);
  const f = fit(im, cam, CROP[s.k] || [0, 0, 1, 1], W, PH);
  return [W / 2 + (u * im.width - f.cx) * f.s, PCY + (v * im.height - f.cy) * f.s];
}
function gradeFilter(g) {
  let f = '';
  if (g.blur > .3) f += `blur(${g.blur.toFixed(2)}px) `;
  if (g.s != null && g.s !== 1) f += `saturate(${g.s.toFixed(3)}) `;
  if (g.c != null && g.c !== 1) f += `contrast(${g.c.toFixed(3)}) `;
  if (g.b != null && g.b !== 1) f += `brightness(${g.b.toFixed(3)}) `;
  return f || 'none';
}
const beatPulse = (t, amp) => (amp && t >= T0) ? 1 + amp * Math.exp(-7 * (((t - T0) / BEAT) % 1)) : 1;

function paintShot(ctx, s, t) {
  const im = IMG[s.k]; if (!im) return;
  const p = (t - s.a) / (s.end - s.a), cam = camAt(s, p);
  cam[2] *= beatPulse(t, s.pulse);
  const g = typeof s.g === 'function' ? s.g(p, t) : s.g;
  let dx = 0, dy = 0;
  if (s.shake) { const k = s.shake * Math.exp(-(t - s.a) / .16); dx = k * Math.sin(t * 91.7 + s.a); dy = k * Math.cos(t * 77.3 + s.a * 2); cam[2] *= 1 + k * .0016; }
  ctx.save(); ctx.imageSmoothingQuality = 'high'; ctx.filter = gradeFilter(g);
  drawStill(ctx, im, cam, CROP[s.k] || [0, 0, 1, 1], W, PH, W / 2 + dx, PCY + dy);
  ctx.restore();
  if (s.tint) { ctx.save(); ctx.globalCompositeOperation = s.tint[0]; ctx.globalAlpha = s.tint[2]; ctx.fillStyle = s.tint[1]; ctx.fillRect(0, PY0, W, PH); ctx.restore(); }
  if (s.fx === 'eyelid') eyelid(ctx, t);
}
function shotAlpha(s, t) { return (s.fin ? seg(t, s.a, s.a + s.fin) : 1) * (s.fout ? 1 - seg(t, s.b - s.fout, s.b) : 1); }

function drawShots(ctx, t) {
  for (const s of SHOTS) {
    if (t < s.a || t >= s.end) continue;
    const a = shotAlpha(s, t); if (a <= 0) continue;
    if (a >= .999) paintShot(ctx, s, t);
    else { bctx.setTransform(1, 0, 0, 1, 0, 0); bctx.globalAlpha = 1; bctx.filter = 'none'; bctx.globalCompositeOperation = 'source-over'; bctx.fillStyle = C.bg; bctx.fillRect(0, 0, W, H);
      paintShot(bctx, s, t); ctx.save(); ctx.globalAlpha = smooth(a); ctx.drawImage(buf, 0, 0); ctx.restore(); }
  }
}

/* three leaning strips, one per beat */
function drawStrips(ctx, t) {
  const st = STRIPS; if (t < st.a || t >= st.b) return;
  ctx.fillStyle = C.bg; ctx.fillRect(0, PY0, W, PH);
  const n = st.items.length, gap = 14, lean = 150, sw = (W + lean - gap * (n - 1)) / n;
  st.items.forEach((it, i) => {
    const im = IMG[it.k]; if (!im) return;
    const ta = st.a + i * BEAT, q = E.expo(seg(t, ta, ta + .55)); if (q <= 0) return;
    const x0 = i * (sw + gap) - lean / 2, off = (1 - q) * (i % 2 ? -PH : PH);
    ctx.save(); ctx.beginPath();
    ctx.moveTo(x0 + lean, PY0); ctx.lineTo(x0 + sw + lean, PY0); ctx.lineTo(x0 + sw, PY1); ctx.lineTo(x0, PY1); ctx.closePath(); ctx.clip();
    const p = (t - ta) / (st.b - ta), c = [0, 1, 2, 3].map(j => j === 2 ? it.c0[2] * Math.pow(it.c1[2] / it.c0[2], E.kick(p)) : lerp(it.c0[j], it.c1[j], E.kick(p)));
    c[2] *= beatPulse(t, .008);
    ctx.imageSmoothingQuality = 'high'; ctx.filter = gradeFilter(DAWN);
    drawStill(ctx, im, c, [0, 0, 1, 1], sw + lean, PH, x0 + (sw + lean) / 2, PCY + off);
    ctx.restore();
  });
}

/* ---------- procedural layers ---------- */
function eyelid(ctx, t) {
  const o = .36 * S(t, 3.5, 4.7) - .3 * S(t, 4.9, 5.5) + .94 * S(t, 5.6, 8.6);
  if (o >= .999) return;
  const half = o * (PH / 2 + 150), fe = 150;
  for (const dir of [-1, 1]) {
    const edge = PCY + dir * half, g = ctx.createLinearGradient(0, edge, 0, edge - dir * fe);
    g.addColorStop(0, 'rgba(5,6,8,1)'); g.addColorStop(1, 'rgba(5,6,8,0)');
    ctx.fillStyle = g; ctx.fillRect(0, Math.min(edge, edge - dir * fe), W, fe);
    ctx.fillStyle = C.bg; if (dir < 0) ctx.fillRect(0, 0, W, Math.max(0, edge)); else ctx.fillRect(0, edge, W, H - edge);
  }
}
function ecgWave(u) {
  let y = 0;
  for (const c of [.3, .47, .62, .75, .86, .95]) {
    const x = (u - c) / .0105;
    y += Math.exp(-x * x * 7) - .2 * Math.exp(-(x + .6) * (x + .6) * 18) - .3 * Math.exp(-(x - .55) * (x - .55) * 16) + .2 * Math.exp(-(x - 2.4) * (x - 2.4) * 1.1) + .11 * Math.exp(-(x + 2.2) * (x + 2.2) * 1.4);
  }
  return y;
}
function drawECG(ctx, t) {
  const a = .55, b = 6.4; if (t < a || t > b) return;
  const al = seg(t, a, a + .5) * (1 - seg(t, 4.6, b)); if (al <= 0) return;
  const x0 = 430, x1 = 1490, head = seg(t, a + .25, 6.0), hx = lerp(x0, x1, head);
  ctx.save(); ctx.lineWidth = 1.6; ctx.lineJoin = 'round';
  const g = ctx.createLinearGradient(hx - 620, 0, hx, 0); g.addColorStop(0, rgba(C.ink, 0)); g.addColorStop(1, rgba(C.ink, .85 * al));
  ctx.strokeStyle = g; ctx.beginPath();
  for (let x = Math.max(x0, hx - 620); x <= hx; x += 2) { const u = (x - x0) / (x1 - x0), y = PCY - 74 * ecgWave(u); if (x === Math.max(x0, hx - 620)) ctx.moveTo(x, y); else ctx.lineTo(x, y); }
  ctx.stroke();
  ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = al; const hy = PCY - 74 * ecgWave(head); ctx.drawImage(glowA, hx - 26, hy - 26, 52, 52);
  ctx.restore();
  // boot log
  const lines = [[.9, 'PRTS ／ 石棺监测'], [2.0, 'VITAL SIGNS ········ DETECTED'], [3.1, '神经连接 ········ 恢复中']];
  ctx.save(); ctx.font = `400 17px ${F.mono}`; ctx.textBaseline = 'alphabetic'; ctx.letterSpacing = '2px';
  lines.forEach(([ta, s], i) => { if (t < ta) return; const n = Math.floor((t - ta) * 34); ctx.fillStyle = rgba(C.ink, .55 * al);
    ctx.fillText(s.slice(0, n) + (n < s.length && Math.floor(t * 8) % 2 ? '▌' : ''), 150, PY0 + 74 + i * 30); });
  ctx.restore();
}
/* drifting sprites: every particle's position is a closed-form function of t */
function sprites(ctx, t, o) {
  ctx.save(); ctx.globalCompositeOperation = 'lighter';
  for (let i = 0; i < o.n; i++) {
    const h1 = hash(i + o.seed), h2 = hash(i * 3.1 + o.seed + 17), h3 = hash(i * 7.7 + o.seed + 41), h4 = hash(i * 1.9 + o.seed + 5);
    const sp = lerp(o.v[0], o.v[1], h3), life = (t * sp / 900 + h4) % 1;
    const x = ((h1 * (W + 200) + o.dx * t * (0.5 + h2) + 40 * Math.sin(t * (.3 + h2) + i)) % (W + 200) + W + 200) % (W + 200) - 100;
    const y = PY1 + 60 - life * (PH + 120);
    const tw = .55 + .45 * Math.sin(t * (1 + 3 * h2) + i * 5), fade = Math.sin(Math.PI * life);
    const r = lerp(o.r[0], o.r[1], h2 * h2);
    ctx.globalAlpha = sat(o.a * tw * fade * (.35 + .65 * h4)); ctx.drawImage(o.sp, x - r, y - r, 2 * r, 2 * r);
  }
  ctx.restore();
}
function drawAtmosphere(ctx, t) {
  for (const s of SHOTS) {
    if (!s.fx || t < s.a || t >= s.end) continue;
    const a = shotAlpha(s, t);
    if (s.fx === 'amber') sprites(ctx, t, { n: 70, seed: 3, sp: glowA, a: .8 * a, r: [3, 15], v: [40, 120], dx: 6 });
    if (s.fx === 'embers') sprites(ctx, t, { n: 46, seed: 11, sp: glowR, a: .75 * a, r: [3, 11], v: [160, 420], dx: 38 });
    if (s.fx === 'bokeh') { const k = S(t, bar(32), bar(44)); sprites(ctx, t, { n: Math.round(10 + 50 * k), seed: 23, sp: glowA, a: (.2 + .18 * k) * a, r: [16, 70], v: [14, 46], dx: 4 }); }
    if (s.fx === 'motes') sprites(ctx, t, { n: 60, seed: 31, sp: glowW, a: .5 * a, r: [2, 9], v: [30, 110], dx: 16 });
    break;
  }
  if (t < 27.5) sprites(ctx, t, { n: 40, seed: 57, sp: glowW, a: .16 * seg(t, 3, 8), r: [1.5, 5], v: [10, 30], dx: 5 });
}
const HALL = () => SHOTS[SHOTS.length - 1], WINDOW = [.487, .49];     // the bright patch beyond the glass
function drawLight(ctx, t) {
  const ci = chapterAt(t); let col = null, a = 0, x = 0, y = 0, R = 1100;
  if (ci === 4) { col = '255,70,40'; a = .09 + .03 * Math.sin(t * 2.3); x = W * (.12 + .05 * Math.sin(t * .4)); y = PY1 - 60; }
  if (ci === 5) { col = '255,170,70'; a = .05 + .09 * S(t, bar(32), bar(46)); x = W * (.82 + .1 * Math.sin(t * .23)); y = PY0 + PH * (.2 + .12 * Math.cos(t * .19)); }
  if (ci === 6) { col = '255,205,130'; a = .13; x = W * (.5 + .34 * Math.sin(t * .27)); y = PY0 + PH * .12; R = 1300; }
  if (col) { ctx.save(); ctx.globalCompositeOperation = 'lighter'; const g = ctx.createRadialGradient(x, y, 0, x, y, R);
    g.addColorStop(0, `rgba(${col},${a})`); g.addColorStop(1, `rgba(${col},0)`); ctx.fillStyle = g; ctx.fillRect(0, PY0, W, PH); ctx.restore(); }
  const h = HALL();
  if (t > bar(67) && t < h.end && IMG[h.k]) {            // dawn gathers behind the two of them
    const k = S(t, bar(67), bar(72)), [gx, gy] = project(h, t, WINDOW[0], WINDOW[1]), r = lerp(140, 1000, k);
    ctx.save(); ctx.globalCompositeOperation = 'lighter'; const g = ctx.createRadialGradient(gx, gy, 0, gx, gy, r);
    g.addColorStop(0, `rgba(255,238,205,${.62 * k})`); g.addColorStop(.4, `rgba(255,225,180,${.26 * k})`); g.addColorStop(1, 'rgba(255,220,170,0)');
    ctx.fillStyle = g; ctx.fillRect(0, PY0, W, PH); ctx.restore();
  }
}
function drawFlashes(ctx, t) {
  let a = 0; for (const [tf, amp, d] of FLASH) if (t >= tf) a += amp * Math.exp(-(t - tf) / d * 2.6);
  if (a > .004) { ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = sat(a); ctx.fillStyle = '#fff6e8'; ctx.fillRect(0, PY0, W, PH); ctx.restore(); }
  for (const s of SHOTS) {          // a blade of light across the cut
    if (!s.slash) continue; const ts = s.slashAt || s.a, q = (t - ts) / .32; if (q < 0 || q > 1) continue;
    ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.translate(W / 2, PCY); ctx.rotate(s.slash * -.42);
    const len = 2600, x = lerp(-len, len, E.out(q)) * s.slash, w = 9 * (1 - q) + 1;
    const g = ctx.createLinearGradient(x - 900 * s.slash, 0, x, 0); g.addColorStop(0, 'rgba(255,240,225,0)'); g.addColorStop(1, `rgba(255,240,225,${.95 * (1 - q * q)})`);
    ctx.fillStyle = g; ctx.fillRect(Math.min(x, x - 900 * s.slash), -w / 2, 900, w); ctx.restore();
  }
}

/* ---------- typography ---------- */
function textWidth(ctx, s, tr) { let w = 0; for (const ch of s) w += ctx.measureText(ch).width + tr; return w - tr; }
function drawCue(ctx, c, t) {
  if (t < c.a || t >= c.b) return;
  const size = c.size || 58, tr = size * .09, lh = size * 1.42, out = c.hard ? 1 : 1 - S(t, c.b - .55, c.b);
  const n = c.zh.length, yLast = c.y;
  ctx.save(); ctx.textBaseline = 'alphabetic'; ctx.font = `600 ${size}px ${F.serif}`;
  const widths = c.zh.map(l => textWidth(ctx, l, tr)), wmax = Math.max(...widths);
  const left = c.align === 'l' ? c.x : c.align === 'r' ? c.x - wmax : c.x - wmax / 2;
  if (!c.noscrim) {              // a soft pool of shadow so the line reads on any picture
    const al = S(t, c.a, c.a + .8) * out; ctx.save(); ctx.translate(left + wmax / 2, yLast - (n - 1) * lh / 2 - size * .2); ctx.scale(1, .34);
    const R = wmax * .5 + 520, g = ctx.createRadialGradient(0, 0, R * .25, 0, 0, R); g.addColorStop(0, `rgba(4,5,8,${.62 * al})`); g.addColorStop(1, 'rgba(4,5,8,0)');
    ctx.fillStyle = g; ctx.fillRect(-R, -R, 2 * R, 2 * R); ctx.restore();
  }
  let idx = 0;
  c.zh.forEach((line, li) => {
    const y = yLast - (n - 1 - li) * lh; let x = c.align === 'l' ? c.x : c.align === 'r' ? c.x - widths[li] : c.x - widths[li] / 2;
    for (const ch of line) {
      const q = E.out(seg(t, c.a + idx * .06, c.a + idx * .06 + .5)); idx++;
      if (q > 0) { ctx.globalAlpha = q * out; ctx.shadowColor = 'rgba(0,0,0,.55)'; ctx.shadowBlur = 18; ctx.fillStyle = C.ink; ctx.fillText(ch, x, y + (1 - q) * 16); }
      x += ctx.measureText(ch).width + tr;
    }
  });
  ctx.shadowBlur = 0;
  // a short amber tick, then the English line
  const ta = c.a + .35 + idx * .03, q = E.out(seg(t, ta, ta + .7)) * out;
  if (q > 0) {
    ctx.font = `500 21px ${F.cond}`; ctx.letterSpacing = '5.2px'; const ew = ctx.measureText(c.en).width;
    const ex = c.align === 'l' ? left : c.align === 'r' ? c.x - ew + 5 : c.x - ew / 2;
    ctx.globalAlpha = q; ctx.fillStyle = C.amber; ctx.fillRect(c.align === 'c' ? c.x - 17 : (c.align === 'r' ? c.x - 34 * q : left), yLast + 24, 34 * q, 2);
    ctx.globalAlpha = q * .78; ctx.fillStyle = C.ink; ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 10; ctx.fillText(c.en, ex, yLast + 58);
  }
  ctx.restore();
}
function drawPlace(ctx, s, t) {
  const p = s.place, q = E.expo(seg(t, s.a, s.a + .45)), xr = W - 150, y = PY0 + 168;
  ctx.save(); ctx.translate(xr - 260, y - 10); ctx.scale(1, .42);
  const g = ctx.createRadialGradient(0, 0, 120, 0, 0, 900); g.addColorStop(0, `rgba(4,5,8,${.6 * q})`); g.addColorStop(1, 'rgba(4,5,8,0)');
  ctx.fillStyle = g; ctx.fillRect(-900, -900, 1800, 1800); ctx.restore();
  ctx.save(); ctx.textAlign = 'right'; ctx.textBaseline = 'alphabetic'; ctx.shadowColor = 'rgba(0,0,0,.5)'; ctx.shadowBlur = 22;
  ctx.globalAlpha = q; ctx.font = `600 124px ${F.cond}`; ctx.letterSpacing = '3px'; ctx.fillStyle = C.ink; ctx.fillText(p.en, xr + (1 - q) * 60, y);
  ctx.shadowBlur = 10; ctx.fillStyle = C.amber; ctx.fillRect(xr - 64 * q, y + 24, 64 * q, 3);
  ctx.fillStyle = C.ink; ctx.font = `600 36px ${F.serif}`; ctx.letterSpacing = '8px'; ctx.fillText(p.zh, xr + 8, y + 78);
  ctx.font = `400 15px ${F.sans}`; ctx.letterSpacing = '4px'; ctx.globalAlpha = q * .78; ctx.fillText(p.sub, xr + 4, y + 112);
  ctx.restore();
}
function drawTitle(ctx, t) {       // 长 夜 行 舟 — one character per beat
  const a = bar(2), b = bar(4); if (t < a - .1 || t >= b) return;
  const chars = ['长', '夜', '行', '舟'], size = 196, gap = 74;
  ctx.save(); ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic'; ctx.font = `900 ${size}px ${F.serif}`;
  const total = chars.length * size + (chars.length - 1) * gap, x0 = W / 2 - total / 2 + size / 2;
  chars.forEach((ch, i) => { const ta = a + i * BEAT, q = E.expo(seg(t, ta, ta + .38)); if (q <= 0) return;
    ctx.save(); ctx.translate(x0 + i * (size + gap), PCY + 46); ctx.scale(1 + .22 * (1 - q), 1 + .22 * (1 - q)); ctx.globalAlpha = q;
    ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 40; ctx.fillStyle = C.ink; ctx.fillText(ch, 0, 0); ctx.restore(); });
  const q = E.out(seg(t, bar(3), bar(3) + .6));
  if (q > 0) {
    ctx.globalAlpha = q; ctx.fillStyle = C.amber; ctx.fillRect(W / 2 - 150 * q, PCY + 104, 300 * q, 3);
    ctx.fillStyle = C.ink; ctx.font = `500 30px ${F.cond}`; ctx.letterSpacing = '16px'; ctx.fillText('ARK THROUGH THE LONG NIGHT', W / 2 + 8, PCY + 160);
    ctx.globalAlpha = q * .7; ctx.font = `400 16px ${F.mono}`; ctx.letterSpacing = '5px'; ctx.fillText('明日方舟 同人短片 ／ AN ARKNIGHTS FAN FILM', W / 2, PCY - 190);
  }
  ctx.restore();
}

/* ---------- frame furniture ---------- */
function drawLetterbox(ctx) { ctx.fillStyle = C.bg; ctx.fillRect(0, 0, W, PY0); ctx.fillRect(0, PY1, W, LB); }
const pad2 = n => String(n).padStart(2, '0');
function drawHUD(ctx, t) {
  const al = seg(t, .4, 1.6) * (1 - seg(t, bar(72) - .6, bar(72) + .8)); if (al <= 0) return;
  const ci = chapterAt(t), ch = CH[ci], ink = a => rgba(C.ink, a * al);
  ctx.save(); ctx.textBaseline = 'alphabetic';
  // picture corners
  ctx.strokeStyle = ink(.34); ctx.lineWidth = 1.5; const m = 26, L = 20;
  for (const [x, y, sx, sy] of [[m, PY0 + m, 1, 1], [W - m, PY0 + m, -1, 1], [m, PY1 - m, 1, -1], [W - m, PY1 - m, -1, -1]]) { ctx.beginPath(); ctx.moveTo(x + sx * L, y); ctx.lineTo(x, y); ctx.lineTo(x, y + sy * L); ctx.stroke(); }
  // top bar: film title (once it has been given) · timecode and bar counter
  const ta = seg(t, bar(4), bar(4) + 1);
  if (ta > 0) { ctx.font = `600 19px ${F.serif}`; ctx.letterSpacing = '5px'; ctx.fillStyle = ink(.6 * ta); ctx.fillText('长夜行舟', 150, 84);
    ctx.font = `500 16px ${F.cond}`; ctx.letterSpacing = '4px'; ctx.fillStyle = ink(.4 * ta); ctx.fillText('ARK THROUGH THE LONG NIGHT', 266, 83); }
  ctx.textAlign = 'right'; ctx.font = `400 15px ${F.mono}`; ctx.letterSpacing = '2px'; ctx.fillStyle = ink(.46);
  const fr = Math.floor(t * FPS + 1e-6), nb = t >= T0 ? Math.floor((t - T0) / BAR + 1e-6) + 1 : 0;
  ctx.fillText(`T+ ${pad2(Math.floor(fr / FPS / 60))}:${pad2(Math.floor(fr / FPS) % 60)}:${pad2(fr % FPS)}   BAR ${nb ? String(nb).padStart(3, '0') : '———'}`, W - 150, 84);
  // bottom bar: chapter · lamps · archive label
  ctx.textAlign = 'left'; const y = H - 62, ca = seg(t, ch.a, ch.a + .8);
  ctx.font = `400 15px ${F.mono}`; ctx.letterSpacing = '2px'; ctx.fillStyle = rgba(C.amber, .9 * al * ca); ctx.fillText(pad2(ci), 150, y);
  ctx.font = `600 18px ${F.serif}`; ctx.letterSpacing = '6px'; ctx.fillStyle = ink(.72 * ca); ctx.fillText(ch.zh, 190, y);
  ctx.font = `500 16px ${F.cond}`; ctx.letterSpacing = '4px'; ctx.fillStyle = ink(.45 * ca); ctx.fillText(ch.en, 252, y - 1);
  const lx = W / 2 - (CH.length - 1) * 13;         // one lamp per chapter; the lit ones are the ones behind us
  for (let i = 0; i < CH.length; i++) {
    const on = i < ci ? 1 : i === ci ? seg(t, CH[i].a, CH[i].a + .6) : 0, x = lx + i * 26, yy = y - 6;
    ctx.fillStyle = ink(.22); ctx.fillRect(x - 3, yy - 3, 6, 6);
    if (on > 0) { const pu = i === ci ? .75 + .25 * beatPulse(t, 1) / 2 : .8; ctx.fillStyle = rgba(C.amber, on * pu * al); ctx.fillRect(x - 3, yy - 3, 6, 6);
      ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = .5 * on * al * (i === ci ? 1 : .5); ctx.drawImage(glowA, x - 14, yy - 14, 28, 28); ctx.restore(); }
  }
  let label = null;
  for (const s of SHOTS) if (t >= s.a && t < s.b) label = [s.k, s.a];
  if (t >= STRIPS.a && t < STRIPS.b) { const i = Math.min(2, Math.floor((t - STRIPS.a) / BEAT)); label = [STRIPS.items[i].k, STRIPS.a + i * BEAT]; }
  if (label && META[label[0]]) { const mt = META[label[0]], q = seg(t, label[1], label[1] + .3);
    ctx.textAlign = 'right'; ctx.font = `400 14px ${F.sans}`; ctx.letterSpacing = '3px'; ctx.fillStyle = ink(.5 * q);
    ctx.fillText(`${mt.src || '官方画廊'} ／ ${mt.name}${mt.title && mt.title !== mt.name ? ' 「' + mt.title.replace(/[“”]/g, '') + '」' : ''}`, W - 150, y); }
  ctx.restore();
}
function drawGrain(ctx, t) {
  const f = Math.floor(t * FPS + 1e-6), g = grain[f % grain.length], ox = Math.floor(hash(f) * 512), oy = Math.floor(hash(f + 99) * 512);
  ctx.save(); ctx.globalCompositeOperation = 'overlay'; ctx.globalAlpha = .065;
  for (let y = -oy; y < H; y += 512) for (let x = -ox; x < W; x += 512) ctx.drawImage(g, x, y);
  ctx.restore();
}

/* ---------- coda: the screen itself becomes the dawn ---------- */
function drawCoda(ctx, t) {
  const a = bar(72) - 1.3; if (t < a) return;
  const k = S(t, a, bar(72) + 1.3);
  if (k < 1) { // light pours out of the window
    const h = HALL(), [gx, gy] = IMG[h.k] ? project(h, Math.min(t, h.end - .001), WINDOW[0], WINDOW[1]) : [W / 2, PCY];
    ctx.save(); const R = lerp(60, 2500, E.io(k)), g = ctx.createRadialGradient(gx, gy, 0, gx, gy, R);
    g.addColorStop(0, rgba(C.paper, 1)); g.addColorStop(.55, rgba(C.paper, .96 * k + .04)); g.addColorStop(1, rgba(C.paper, 0));
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = k * k; ctx.fillStyle = C.paper; ctx.fillRect(0, 0, W, H); ctx.restore();
  } else { ctx.fillStyle = C.paper; ctx.fillRect(0, 0, W, H); }
  const ink = '#14161a', t1 = bar(72) + 3.3;       // the swell starts here
  ctx.save(); ctx.textBaseline = 'alphabetic'; ctx.textAlign = 'left';
  const chars = ['长', '夜', '行', '舟'], size = 172, x0 = 150, yb = 520;
  ctx.font = `900 ${size}px ${F.serif}`;
  chars.forEach((ch, i) => { const q = E.out(seg(t, t1 + i * BEAT, t1 + i * BEAT + .7)); if (q <= 0) return; ctx.globalAlpha = q; ctx.fillStyle = ink; ctx.fillText(ch, x0 + i * (size + 44), yb + (1 - q) * 22); });
  const q = E.out(seg(t, t1 + 4 * BEAT, t1 + 4 * BEAT + .9));
  if (q > 0) {
    ctx.globalAlpha = q; ctx.fillStyle = C.amber; ctx.fillRect(x0 + 4, yb + 52, 120 * q, 4);
    ctx.fillStyle = ink; ctx.font = `500 30px ${F.cond}`; ctx.letterSpacing = '15px'; ctx.fillText('ARK THROUGH THE LONG NIGHT', x0 + 4, yb + 118);
    ctx.globalAlpha = q * .62; ctx.font = `400 16px ${F.mono}`; ctx.letterSpacing = '5px'; ctx.fillText('明日方舟 同人短片 ／ AN ARKNIGHTS FAN FILM', x0 + 4, yb - 216);
  }
  const rows = [['音乐  MUSIC', '《曙光》 · 明日方舟：黎明前奏 OST · 塞壬唱片 Monster Siren Records'],
                ['画面  VISUALS', '明日方舟官方网站画廊 · 主线「相变临界」CG 等 · © 鹰角网络 HYPERGRYPH'],
                ['剪辑  EDIT', 'Claude · 全片由代码逐帧绘制']];
  rows.forEach((r, i) => { const qq = E.out(seg(t, t1 + 2.0 + i * .28, t1 + 2.9 + i * .28)); if (qq <= 0) return; const y = 744 + i * 40;
    ctx.globalAlpha = qq * .6; ctx.fillStyle = ink; ctx.font = `400 15px ${F.mono}`; ctx.letterSpacing = '3px'; ctx.fillText(r[0], x0 + 4, y);
    ctx.globalAlpha = qq * .86; ctx.font = `400 19px ${F.sans}`; ctx.letterSpacing = '2px'; ctx.fillText(r[1], x0 + 250, y); });
  const q3 = E.out(seg(t, t1 + 3.1, t1 + 4.1));
  if (q3 > 0) { ctx.globalAlpha = q3 * .5; ctx.fillStyle = ink; ctx.fillRect(x0 + 4, 884, W - 2 * x0 - 8, 1);
    ctx.font = `400 15px ${F.sans}`; ctx.letterSpacing = '2.5px'; ctx.globalAlpha = q3 * .62;
    ctx.fillText('本片为非官方、非商业的同人作品；所用图像与音乐的版权均归原权利人所有。', x0 + 4, 922);
    ctx.font = `500 15px ${F.cond}`; ctx.letterSpacing = '3.5px'; ctx.fillText('UNOFFICIAL, NON-COMMERCIAL FAN WORK. ARKNIGHTS AND ALL RELATED MATERIALS © HYPERGRYPH.', x0 + 4, 950); }
  // the eight lamps, all lit
  const q4 = E.out(seg(t, t1 + 1.4, t1 + 2.6));
  for (let i = 0; i < CH.length; i++) { const on = seg(t, t1 + 1.4 + i * .12, t1 + 1.9 + i * .12); if (on <= 0) continue; const x = W - 150 - (CH.length - 1 - i) * 30;
    ctx.globalAlpha = on; ctx.fillStyle = C.amber; ctx.fillRect(x - 5, yb - 222 - 9, 10, 10); }
  ctx.globalAlpha = q4 * .62; ctx.fillStyle = ink; ctx.textAlign = 'right'; ctx.font = `400 15px ${F.mono}`; ctx.letterSpacing = '3px'; ctx.fillText('08 ／ 08', W - 150, yb - 182);
  ctx.restore();
}

/* ---------- one frame ---------- */
function render(ctx, t) {
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1; ctx.filter = 'none'; ctx.globalCompositeOperation = 'source-over'; ctx.shadowBlur = 0;
  ctx.textAlign = 'left'; ctx.letterSpacing = '0px';
  ctx.fillStyle = C.bg; ctx.fillRect(0, 0, W, H);
  if (t < bar(72) + 1.4) {
    drawShots(ctx, t); drawStrips(ctx, t); drawLight(ctx, t); drawAtmosphere(ctx, t); drawFlashes(ctx, t);
    ctx.drawImage(vign, 0, 0);
    drawECG(ctx, t);
    for (const s of SHOTS) if (s.place && t >= s.a && t < s.b) drawPlace(ctx, s, t);
    for (const c of CUES) drawCue(ctx, c, t);
    drawTitle(ctx, t);
    drawLetterbox(ctx); drawHUD(ctx, t);
  }
  drawCoda(ctx, t);
  drawGrain(ctx, t);
  const fo = seg(t, DUR - 4.4, DUR - .8);                  // out with the last note
  if (fo > 0) { ctx.globalAlpha = smooth(fo); ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1; }
}

root.Film = { W, H, FPS, DUR, BAR, BEAT, T0, bar, SHOTS, STRIPS, CUES, CH, keys: () => Array.from(new Set(SHOTS.map(s => s.k).concat(STRIPS.items.map(i => i.k)))), init, render };
})(typeof window !== 'undefined' ? window : globalThis);
