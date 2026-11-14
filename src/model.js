/* The rig: turns parameters into deformed meshes.
 *
 * Parameters follow the Live2D Cubism naming convention (ParamAngleX, ...).
 * Deformation is hand-written per part family:
 *   - head parts go through a pseudo-3D turn (each layer has a depth and
 *     wraps a bump around the face centre) and then roll about the neck;
 *   - body parts go through a spine bend about the hips plus breathing;
 *   - hair, ribbons, sash, tassel, earring and skirt hang from a root point
 *     and are driven by spring pendulums.
 */
(function (global) {
  'use strict';

  const DEG = Math.PI / 180;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const lerp = (a, b, t) => a + (b - a) * t;
  const smooth = (a, b, x) => {
    const t = clamp((x - a) / (b - a), 0, 1);
    return t * t * (3 - 2 * t);
  };

  // ------------------------------------------------------------------ params
  const PARAMS = [
    { id: 'ParamAngleX', name: '头部 左右', group: '头部', min: -30, max: 30, def: 0 },
    { id: 'ParamAngleY', name: '头部 上下', group: '头部', min: -30, max: 30, def: 0 },
    { id: 'ParamAngleZ', name: '头部 倾斜', group: '头部', min: -30, max: 30, def: 0 },
    { id: 'ParamEyeLOpen', name: '左眼 开闭', group: '眼睛', min: 0, max: 1.2, def: 1 },
    { id: 'ParamEyeROpen', name: '右眼 开闭', group: '眼睛', min: 0, max: 1.2, def: 1 },
    { id: 'ParamEyeSmile', name: '眼睛 笑意', group: '眼睛', min: 0, max: 1, def: 0 },
    { id: 'ParamEyeBallX', name: '眼珠 左右', group: '眼睛', min: -1, max: 1, def: 0 },
    { id: 'ParamEyeBallY', name: '眼珠 上下', group: '眼睛', min: -1, max: 1, def: 0 },
    { id: 'ParamBrowY', name: '眉毛 高低', group: '眉毛', min: -1, max: 1, def: 0 },
    { id: 'ParamBrowAngle', name: '眉毛 角度', group: '眉毛', min: -1, max: 1, def: 0 },
    { id: 'ParamMouthOpenY', name: '嘴 张开', group: '嘴', min: 0, max: 1, def: 0 },
    { id: 'ParamMouthForm', name: '嘴 形状', group: '嘴', min: -1, max: 1, def: 0 },
    { id: 'ParamCheek', name: '脸红', group: '嘴', min: 0, max: 1, def: 0 },
    { id: 'ParamBodyAngleX', name: '身体 左右', group: '身体', min: -10, max: 10, def: 0 },
    { id: 'ParamBodyAngleY', name: '身体 前后', group: '身体', min: -10, max: 10, def: 0 },
    { id: 'ParamBodyAngleZ', name: '身体 倾斜', group: '身体', min: -10, max: 10, def: 0 },
    { id: 'ParamBreath', name: '呼吸', group: '身体', min: 0, max: 1, def: 0 },
  ];

  const MESH = {
    hair_back: [18, 24], hair_side_r: [6, 26], hair_side_l: [6, 26], hair_front: [16, 14],
    face: [12, 14], neck: [6, 8], dress: [16, 24], skirt: [10, 12], arm_r: [5, 16], arm_l: [5, 16],
    acc_sash: [4, 16], acc_chain: [10, 10], acc_chest_bow: [8, 10], acc_hair_flower: [6, 16], acc_earring: [2, 6],
  };
  const MOUTHS = ['mouth_closed', 'mouth_smile', 'mouth_sad', 'mouth_grin', 'mouth_open_s', 'mouth_open_m',
    'mouth_open_l', 'mouth_smile_open', 'mouth_o', 'mouth_tongue_out'];

  // ----------------------------------------------------------------- physics
  /** A bob chasing an anchor; (bob - anchor) is the lag we render as sway. */
  class Spring {
    constructor(freq, zeta) {
      this.w = 2 * Math.PI * freq;
      this.z = zeta;
      this.x = 0;
      this.v = 0;
      this.ready = false;
    }
    step(target, dt) {
      if (!this.ready) { this.x = target; this.v = 0; this.ready = true; }
      const a = this.w * this.w * (target - this.x) - 2 * this.z * this.w * this.v;
      this.v += a * dt;
      this.x += this.v * dt;
    }
  }
  class Chain {
    /** gain scales the lag into visible sway; limit (px) soft-clamps it. */
    constructor(f1, z1, f2, z2, limit, gain) {
      this.s1 = new Spring(f1, z1);
      this.s2 = new Spring(f2, z2);
      this.limit = limit;
      this.gain = gain || 1;
      this.a = 0; // first-stage lag (px)
      this.b = 0; // extra lag of the tip (px)
    }
    step(anchor, dt) {
      this.s1.step(anchor, dt);
      this.s2.step(this.s1.x, dt);
    }
    /** Call once per frame after stepping. */
    read(anchor) {
      const L = this.limit, g = this.gain;
      // soft-limit so a violent head flick cannot fold the mesh
      this.a = L * Math.tanh((g * (this.s1.x - anchor)) / L);
      this.b = L * Math.tanh((g * (this.s2.x - this.s1.x)) / L);
    }
    kick(v) { this.s1.v += v; this.s2.v += v * 0.6; }
  }

  // ------------------------------------------------------------------- model
  class Model {
    constructor(layout, meta) {
      this.layout = layout;
      this.meta = meta;
      this.params = {};
      this.paramDefs = PARAMS;
      for (const p of PARAMS) this.params[p.id] = p.def;
      this.mouthOverride = null;   // force a mouth sprite (expressions)
      this.windStrength = 0.5;     // ambient breeze 0..1
      this.seeThrough = 0.42;      // how strongly eyes show through the bangs
      this.time = 0;

      const hs = layout.head.scale, ox = layout.head.origin[0], oy = layout.head.origin[1];
      const hx = (v) => ox + hs * v, hy = (v) => oy + hs * v;
      this.hs = hs;
      const G = (this.G = {
        Cx: hx(512), Cy: hy(690), Rx: hs * 310, Ry: hs * 390, Rz: hs * 250,
        Nx: layout.pivots.neck[0], Ny: layout.pivots.neck[1],
        hipX: layout.pivots.hip[0], hipY: layout.pivots.hip[1], shY: layout.pivots.shoulderY || 560,
        mouthX: hx(512), mouthY: hy(902), chinY: hy(1035), headTop: hy(100),
        pivotZ: 0.25,
      });
      G.tNeck = clamp((G.hipY - G.Ny) / (G.hipY - G.shY), 0, 1.35);

      // ---- parts
      this.parts = {};
      this.order = [];
      for (const e of layout.parts) {
        const m = meta[e.tex];
        const sx = e.s * (e.sx || 1), sy = e.s * (e.sy || 1);
        const dims = MESH[e.id] || (e.id.startsWith('eye') ? [8, 6] : e.id.startsWith('mouth') ? [8, 4] : e.id.startsWith('brow') ? [6, 2] : [2, 2]);
        const cols = dims[0], rows = dims[1], n = (cols + 1) * (rows + 1);
        const part = {
          id: e.id, tex: e.tex, z: e.z, group: e.group, s: e.s, cols, rows, n,
          rest: new Float32Array(n * 2), pos: new Float32Array(n * 2), uv: new Float32Array(n * 2),
          idx: new Uint16Array(cols * rows * 6),
          opacity: e.opacity === undefined ? 1 : e.opacity, base: e.opacity === undefined ? 1 : e.opacity,
          visible: !e.hidden, clip: null,
          // content rectangle (texture minus padding) in model space
          x0: e.x + m.pad * sx, y0: e.y + m.pad * sy,
          x1: e.x + (m.w - m.pad) * sx, y1: e.y + (m.h - m.pad) * sy,
        };
        part.w = part.x1 - part.x0; part.h = part.y1 - part.y0;
        part.cx = (part.x0 + part.x1) / 2; part.cy = (part.y0 + part.y1) / 2;
        let k = 0;
        for (let j = 0; j <= rows; j++) {
          for (let i = 0; i <= cols; i++) {
            const u = i / cols, v = j / rows;
            part.rest[k] = e.x + u * m.w * sx;
            part.rest[k + 1] = e.y + v * m.h * sy;
            part.uv[k] = e.flip ? 1 - u : u;
            part.uv[k + 1] = v;
            k += 2;
          }
        }
        k = 0;
        for (let j = 0; j < rows; j++) {
          for (let i = 0; i < cols; i++) {
            const a = j * (cols + 1) + i, b = a + 1, c = a + cols + 1, d = c + 1;
            part.idx[k++] = a; part.idx[k++] = b; part.idx[k++] = c;
            part.idx[k++] = b; part.idx[k++] = d; part.idx[k++] = c;
          }
        }
        part.pos.set(part.rest);
        this.parts[e.id] = part;
        this.order.push(part);
      }
      this.order.sort((a, b) => a.z - b.z);
      for (const s of ['l', 'r']) {
        this.parts[`eye_${s}_iris`].clip = this.parts[`eye_${s}_white`];
        this.parts[`eye_${s}_hl`].clip = this.parts[`eye_${s}_white`];
      }
      this.mouthAlpha = {};
      for (const m of MOUTHS) this.mouthAlpha[m] = m === 'mouth_closed' ? 1 : 0;

      // ---- draw list
      // Eyes and brows are drawn a second time, faintly and only where the
      // bangs cover them, so expressions stay readable through the fringe.
      this.drawList = [];
      const bangs = this.parts.hair_front;
      for (const p of this.order) {
        this.drawList.push({ part: p, k: 1, mask: p.clip ? [p.clip] : null });
        if (p.id !== 'hair_front') continue;
        for (const s of ['l', 'r']) this.drawList.push({ part: this.parts[`eye_${s}_iris`], k: 0.9, ghost: true, mask: [this.parts[`eye_${s}_white`], bangs] });
        for (const s of ['l', 'r']) {
          for (const n of [`eye_${s}_lower`, `eye_${s}_lash`, `eye_${s}_closed`, `brow_${s}`]) {
            this.drawList.push({ part: this.parts[n], k: n.startsWith('brow') ? 1.5 : 1, ghost: true, mask: [bangs] });
          }
        }
      }

      // ---- hanging things
      const P = this.parts;
      this.rig = {
        bangs: new Chain(2.3, 0.42, 3.0, 0.5, 16, 1.6),
        sideR: new Chain(1.2, 0.3, 0.85, 0.26, 70, 2.3),
        sideL: new Chain(1.3, 0.3, 0.9, 0.26, 70, 2.3),
        backL: new Chain(0.8, 0.34, 0.55, 0.3, 90, 2.8),
        backR: new Chain(0.9, 0.34, 0.6, 0.3, 90, 2.8),
        ribbon: new Chain(1.5, 0.3, 1.1, 0.26, 45, 2.0),
        earring: new Chain(2.0, 0.13, 2.6, 0.2, 20, 2.2),
        bow: new Chain(1.9, 0.32, 1.4, 0.3, 22, 2.2),
        tassel: new Chain(1.7, 0.15, 2.2, 0.2, 30, 3.0),
        sash: new Chain(1.1, 0.26, 0.8, 0.24, 60, 2.8),
        skirt: new Chain(0.95, 0.4, 0.7, 0.36, 40, 2.4),
        armR: new Chain(1.15, 0.5, 1.0, 0.5, 16, 1.6),
        armL: new Chain(1.2, 0.5, 1.0, 0.5, 16, 1.6),
      };
      this.strands = {
        hair_side_r: { root: [P.hair_side_r.x0 + 203 * P.hair_side_r.w / 296, P.hair_side_r.y0 + 4], len: P.hair_side_r.h, p1: 1.5, p2: 2.8, stiff: [0.02, 0.3], attach: 'head', depth: 0.02, curv: 0.7 },
        hair_side_l: { root: [P.hair_side_l.x0 + 52 * P.hair_side_l.w / 255, P.hair_side_l.y0 + 4], len: P.hair_side_l.h, p1: 1.5, p2: 2.8, stiff: [0.02, 0.3], attach: 'head', depth: 0.02, curv: 0.7 },
        acc_hair_flower: { root: [P.acc_hair_flower.x0 + 85 * P.acc_hair_flower.s, P.acc_hair_flower.y0 + 95 * P.acc_hair_flower.s], len: P.acc_hair_flower.y1 - (P.acc_hair_flower.y0 + 95 * P.acc_hair_flower.s), p1: 1.6, p2: 2.6, stiff: [0.22, 0.55], attach: 'head', depth: 0.06, curv: 0.9 },
        acc_earring: { root: [P.acc_earring.cx, P.acc_earring.y0 + 3], len: P.acc_earring.h, p1: 1, p2: 2, stiff: [0, 0.08], attach: 'head', depth: -0.02, curv: 1 },
        acc_chest_bow: { root: [P.acc_chest_bow.x0 + 179 * P.acc_chest_bow.s, P.acc_chest_bow.y0 + 90 * P.acc_chest_bow.s], len: P.acc_chest_bow.y1 - (P.acc_chest_bow.y0 + 90 * P.acc_chest_bow.s), p1: 1.7, p2: 2.6, stiff: [0.2, 0.6], attach: 'body' },
        acc_sash: { root: [P.acc_sash.x0 + 70, P.acc_sash.y0 + 6], len: P.acc_sash.h, p1: 1.5, p2: 2.8, stiff: [0.0, 0.14], attach: 'body' },
        acc_chain: { root: [P.acc_chain.x0 + 313 * P.acc_chain.s, P.acc_chain.y0 + 58 * P.acc_chain.s], len: P.acc_chain.y1 - (P.acc_chain.y0 + 58 * P.acc_chain.s), p1: 1.2, p2: 2.2, stiff: [0.0, 0.1], attach: 'mesh' },
        arm_r: { root: [P.arm_r.x1 - 50, P.arm_r.y0 + 20], len: P.arm_r.h, p1: 1, p2: 2, stiff: [0, 0.01], attach: 'body' },
        arm_l: { root: [P.arm_l.x0 + 50, P.arm_l.y0 + 20], len: P.arm_l.h, p1: 1, p2: 2, stiff: [0, 0.01], attach: 'body' },
      };
      this.S = {};
      this._x = 0; this._y = 0;
    }

    // ------------------------------------------------------- core transforms
    /** Spine bend, lean and breathing for a rest-pose point. Result in _x/_y. */
    bodyT(x, y) {
      const G = this.G, S = this.S;
      const t = clamp((G.hipY - y) / (G.hipY - G.shY), 0, 1.35);
      const a = S.bodyRoll * t;
      const dx = x - G.hipX, dy = y - G.hipY;
      const c = Math.cos(a), s = Math.sin(a);
      let X = G.hipX + dx * c - dy * s;
      let Y = G.hipY + dx * s + dy * c;
      const nx = dx / 170;
      const bump = Math.max(0, 1 - nx * nx);
      X += S.bodyX * (15 * t + 6 * t * bump);
      Y += S.bodyY * 6 * t;
      const chest = smooth(0.3, 1.0, t);
      Y -= S.breath * 2.6 * chest;
      X += dx * 0.008 * S.breath * chest * (1 - smooth(1.0, 1.3, t));
      this._x = X; this._y = Y;
    }

    /** Pseudo-3D head turn in rest space (no roll yet). */
    headLocal(x, y, depth, curv) {
      const G = this.G, S = this.S;
      const nx = (x - G.Cx) / G.Rx, ny = (y - G.Cy) / G.Ry;
      const r = Math.min(Math.sqrt(nx * nx + ny * ny), 1.25) / 1.25;
      const bump = 0.5 * (1 + Math.cos(Math.PI * r));
      const z = (curv * bump + depth + G.pivotZ) * G.Rz;
      this._x = G.Cx + (x - G.Cx) * S.cy + z * S.sy;
      this._y = G.Cy + (y - G.Cy) * S.cp - z * S.sp;
    }

    /** Roll about the neck and follow the body. */
    headWorld(x, y) {
      const G = this.G, S = this.S;
      const dx = x - G.Nx, dy = y - G.Ny;
      this._x = S.Nwx + dx * S.cr - dy * S.sr;
      this._y = S.Nwy + dx * S.sr + dy * S.cr;
    }

    headPoint(x, y, depth, curv) {
      this.headLocal(x, y, depth, curv);
      this.headWorld(this._x, this._y);
    }

    _wind(phase, amp) {
      const t = this.time;
      return this.windStrength * amp * (0.6 * Math.sin(0.9 * t + phase) + 0.4 * Math.sin(1.7 * t + 1.9 * phase + 1.0));
    }

    // ------------------------------------------------------------ per frame
    update(dt) {
      dt = Math.min(dt, 0.05);
      this.time += dt;
      const p = this.params, G = this.G, S = this.S;
      S.yaw = (p.ParamAngleX / 30) * 15 * DEG;
      S.pitch = (p.ParamAngleY / 30) * 11 * DEG;
      S.roll = p.ParamAngleZ * 0.55 * DEG;
      S.bodyX = p.ParamBodyAngleX / 10;
      S.bodyY = p.ParamBodyAngleY / 10;
      S.bodyRoll = (p.ParamBodyAngleZ / 10) * 4.5 * DEG;
      S.breath = p.ParamBreath;
      S.sy = Math.sin(S.yaw); S.cy = Math.cos(S.yaw);
      S.sp = Math.sin(S.pitch); S.cp = Math.cos(S.pitch);
      this.bodyT(G.Nx, G.Ny);
      S.Nwx = this._x; S.Nwy = this._y;
      S.rollTotal = S.roll + S.bodyRoll * G.tNeck;
      S.sr = Math.sin(S.rollTotal); S.cr = Math.cos(S.rollTotal);

      this.headPoint(G.Cx, G.Cy, 0, 1);
      S.headCx = this._x; S.headCy = this._y;

      this._physics(dt);
      this._deform(dt);
    }

    _rootWorld(st) {
      if (st.attach === 'head') this.headPoint(st.root[0], st.root[1], st.depth, st.curv);
      else this.bodyT(st.root[0], st.root[1]);
      st.rwx = this._x; st.rwy = this._y;
    }

    _physics(dt) {
      const G = this.G, R = this.rig, st = this.strands;
      for (const k in st) this._rootWorld(st[k]);
      this.headPoint(G.Cx, G.headTop + 40, 0.1, 0.5);
      const aBang = this._x;
      this.headPoint(G.Cx, G.chinY, -0.4, 0);
      const napeX = this._x;
      this.bodyT(G.hipX, G.shY);
      const shX = this._x;
      this.bodyT(G.hipX, 720);
      const waistX = this._x;
      const aBack = napeX * 0.45 + shX * 0.55;
      const anchors = {
        bangs: aBang, sideR: st.hair_side_r.rwx, sideL: st.hair_side_l.rwx, backL: aBack, backR: aBack,
        ribbon: st.acc_hair_flower.rwx, earring: st.acc_earring.rwx, bow: st.acc_chest_bow.rwx,
        tassel: st.acc_chain.rwx + waistX * 0.6, sash: st.acc_sash.rwx + waistX * 0.6, skirt: waistX,
        armR: st.arm_r.rwx, armL: st.arm_l.rwx,
      };
      const steps = Math.max(1, Math.ceil(dt / (1 / 180)));
      const h = dt / steps;
      for (const k in R) {
        const c = R[k], a = anchors[k];
        for (let i = 0; i < steps; i++) c.step(a, h);
        c.read(a);
      }
    }

    /** Add an impulse to everything that dangles (used for taps). */
    kick(v) {
      for (const k in this.rig) this.rig[k].kick(v * (0.6 + Math.random() * 0.8));
    }

    // ------------------------------------------------------------- deformers
    _deform(dt) {
      const P = this.parts, R = this.rig, p = this.params;
      this._hairBack(P.hair_back);
      this._bangs(P.hair_front);
      this._strand(P.hair_side_r, this.strands.hair_side_r, R.sideR.a + this._wind(0.3, 7), R.sideR.b + this._wind(1.1, 9));
      this._strand(P.hair_side_l, this.strands.hair_side_l, R.sideL.a + this._wind(2.1, 7), R.sideL.b + this._wind(2.9, 9));
      this._strand(P.acc_hair_flower, this.strands.acc_hair_flower, R.ribbon.a + this._wind(4.0, 4), R.ribbon.b + this._wind(4.6, 5));
      this._strand(P.acc_earring, this.strands.acc_earring, R.earring.a + this._wind(5.0, 1.2), 0);
      this._strand(P.acc_chest_bow, this.strands.acc_chest_bow, R.bow.a + this._wind(0.9, 2), R.bow.b);
      this._strand(P.acc_sash, this.strands.acc_sash, R.sash.a + this._wind(3.3, 5), R.sash.b + this._wind(3.9, 6));
      this._strand(P.acc_chain, this.strands.acc_chain, R.tassel.a + this._wind(1.7, 2), R.tassel.b);
      this._strand(P.arm_r, this.strands.arm_r, R.armR.a * 0.5 - this.S.breath * 1.2, 0);
      this._strand(P.arm_l, this.strands.arm_l, R.armL.a * 0.5 + this.S.breath * 1.2, 0);
      this._neck(P.neck);
      this._dress(P.dress);
      this._underskirt(P.skirt);
      this._face(P.face);
      this._simpleHead(P.nose, 0.14, 1);
      this._simpleHead(P.blush_l, 0.02, 1);
      this._simpleHead(P.blush_r, 0.02, 1);
      P.blush_l.opacity = P.blush_r.opacity = p.ParamCheek * 0.85;
      this._eye('l', p.ParamEyeLOpen);
      this._eye('r', p.ParamEyeROpen);
      this._mouth(dt);
    }

    _simpleHead(part, depth, curv) {
      const r = part.rest, o = part.pos;
      for (let i = 0; i < r.length; i += 2) {
        this.headPoint(r[i], r[i + 1], depth, curv);
        o[i] = this._x; o[i + 1] = this._y;
      }
    }

    _face(part) {
      const G = this.G, r = part.rest, o = part.pos;
      const jaw = this.jaw || 0;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i];
        let y = r[i + 1];
        y += jaw * 3.0 * smooth(G.mouthY - 6, G.chinY, y);
        this.headPoint(x, y, 0, 1);
        o[i] = this._x; o[i + 1] = this._y;
      }
    }

    _neck(part) {
      const G = this.G, S = this.S, r = part.rest, o = part.pos;
      const base = G.Ny + 60;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i], y = r[i + 1];
        this.bodyT(x, y);
        // the top of the neck leans after the head
        const k = 0.6 * smooth(base, G.Ny - 20, y);
        const a = S.roll * k;
        const dx = this._x - S.Nwx, dy = this._y - S.Nwy;
        const c = Math.cos(a), s = Math.sin(a);
        o[i] = S.Nwx + dx * c - dy * s + S.sy * G.Rz * 0.16 * k;
        o[i + 1] = S.Nwy + dx * s + dy * c;
      }
    }

    _dress(part) {
      const G = this.G, S = this.S, R = this.rig, r = part.rest, o = part.pos;
      const bottom = part.y1;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i], y = r[i + 1];
        this.bodyT(x, y);
        let X = this._x;
        const Y = this._y;
        const ts = clamp((y - G.hipY) / (bottom - G.hipY), 0, 1);
        const side = (x - G.hipX) / 420;
        X += (R.skirt.a + this._wind(side * 2.0, 3.5)) * Math.pow(ts, 1.4) * 1.0;
        // capelet wings breathe and trail after the arms
        const sgn = x < G.hipX ? -1 : 1;
        const wing = smooth(170, 300, Math.abs(x - G.hipX)) * smooth(520, 720, y) * (1 - smooth(900, 990, y));
        X += wing * ((sgn < 0 ? R.armR.a : R.armL.a) * 0.45 + sgn * S.breath * 1.4);
        o[i] = X; o[i + 1] = Y;
      }
    }

    _underskirt(part) {
      const G = this.G, R = this.rig, r = part.rest, o = part.pos;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i], y = r[i + 1];
        const ts = clamp((y - part.y0) / 520, 0, 1);
        o[i] = x + (R.skirt.a * 0.7 + R.skirt.b * 0.8 + this._wind(1.3 + (x - G.hipX) / 300, 4)) * Math.pow(ts, 1.4);
        o[i + 1] = y;
      }
    }

    _bangs(part) {
      const G = this.G, R = this.rig, r = part.rest, o = part.pos;
      const top = part.y0 + part.h * 0.3, span = part.h * 0.7;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i], y = r[i + 1];
        const t = clamp((y - top) / span, 0, 1);
        const ph = (x - G.Cx) / 60;
        const sway = (R.bangs.a + R.bangs.b * t + this._wind(ph, 2.2)) * t * t;
        this.headPoint(x + sway, y, 0.14, 1);
        o[i] = this._x; o[i + 1] = this._y;
      }
    }

    _hairBack(part) {
      const G = this.G, R = this.rig, r = part.rest, o = part.pos;
      const top = part.y0, bottom = part.y1, chin = G.chinY;
      this.headPoint(G.Cx, chin, -0.5, 0);
      const dnx = this._x - G.Cx, dny = this._y - chin;
      this.bodyT(G.hipX, G.shY);
      const dsx = this._x - G.hipX, dsy = this._y - G.shY;
      const y0 = chin - 50;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i], y = r[i + 1];
        // the crown moves with the fringe; depth only opens up behind the neck
        const k = smooth(top + 70, chin, y);
        this.headPoint(x, y, lerp(0.06, -0.7, k), 0.85 * (1 - k));
        const sx = this._x, sy = this._y;
        // below the jaw (and far out to the sides) the hair hangs instead of
        // turning rigidly with the skull
        const fall = 1 - 0.6 * smooth(chin, bottom, y);
        const hxp = x + (dsx * 0.7 + dnx * 0.3) * fall;
        const hyp = y + (dsy * 0.7 + dny * 0.3) * fall;
        const w = Math.max(smooth(chin - 70, chin + 170, y),
          smooth(175, 330, Math.abs(x - G.Cx)) * smooth(top + 90, chin - 20, y));
        let X = lerp(sx, hxp, w);
        const Y = lerp(sy, hyp, w);
        const t = clamp((y - y0) / (bottom - y0), 0, 1);
        const side = smooth(-320, 320, x - G.Cx);
        const s1 = lerp(R.backL.a, R.backR.a, side) + this._wind(0.8 + (x - G.Cx) / 190, 5);
        const s2 = lerp(R.backL.b, R.backR.b, side) + this._wind(2.2 + (x - G.Cx) / 150 + y / 400, 8);
        X += s1 * Math.pow(t, 1.4) + s2 * Math.pow(t, 2.6);
        o[i] = X; o[i + 1] = Y;
      }
    }

    /** Anything that hangs from a root point. s1/s2: lag in px at the tip. */
    _strand(part, st, s1, s2) {
      const G = this.G, S = this.S, r = part.rest, o = part.pos;
      const rx = st.root[0], ry = st.root[1], len = st.len;
      const rwx = st.rwx, rwy = st.rwy;
      let cb = 1, sb = 0;
      if (st.attach === 'body') {
        const tRoot = clamp((G.hipY - ry) / (G.hipY - G.shY), 0, 1.35);
        const a = S.bodyRoll * tRoot * 0.6;
        cb = Math.cos(a); sb = Math.sin(a);
      }
      const axis = st.axis === undefined ? rx : st.axis;
      for (let i = 0; i < r.length; i += 2) {
        const x = r[i], y = r[i + 1];
        const t = clamp((y - ry) / len, 0, 1.2);
        let fx, fy;
        if (st.attach === 'head') {
          this.headPoint(x, y, st.depth, st.curv);
          fx = this._x; fy = this._y;
        } else if (st.attach === 'mesh') {
          this.bodyT(x, y);
          fx = this._x; fy = this._y;
        } else {
          const dx = x - rx, dy = y - ry;
          fx = rwx + dx * cb - dy * sb; fy = rwy + dx * sb + dy * cb;
        }
        const w = smooth(st.stiff[0], st.stiff[1], t);
        let X = lerp(fx, rwx + (x - rx), w);
        let Y = lerp(fy, rwy + (y - ry), w);
        const g1 = Math.pow(t, st.p1), g2 = Math.pow(t, st.p2);
        const slope = t > 0 ? (s1 * st.p1 * g1 + s2 * st.p2 * g2) / (t * len) : 0;
        X += s1 * g1 + s2 * g2;
        Y -= (x - axis) * slope;
        o[i] = X; o[i + 1] = Y;
      }
    }

    _eye(side, openRaw) {
      const P = this.parts, p = this.params, hs = this.hs;
      const W = P[`eye_${side}_white`], I = P[`eye_${side}_iris`];
      // lids follow the gaze a little: looking up lifts them, looking down lowers them
      const gy = p.ParamEyeBallY;
      const open = clamp(openRaw * (1 + (gy > 0 ? 0.1 : 0.16) * gy), 0, 1.2), open01 = Math.min(open, 1);
      // 1 is the resting, half-lidded look of the illustration; above 1 the
      // lids pull back twice as fast so "surprised" really widens the eye
      const wide = Math.max(open - 1, 0) * 2.0;
      const g = open01 + wide;
      const smile = p.ParamEyeSmile;
      const yBot = W.y1, half = W.w / 2, cx = W.cx;
      const lashDy = (1 - open01) * W.h * 0.8 - wide * W.h;
      const lift = smile * W.h * 0.16;
      const aOpen = smooth(0.08, 0.26, open);
      const px = hs / 0.405; // art px -> model px factor for hand-tuned offsets
      // the lids are tilted (outer corner up); iris and catch-light stay upright
      const cfg = (this.layout.eyes && this.layout.eyes[side]) || { pivot: [W.cx, W.cy], slant: 0, browTilt: 0 };
      const cs = Math.cos(cfg.slant * DEG), sn = Math.sin(cfg.slant * DEG);
      const pvx = cfg.pivot[0], pvy = cfg.pivot[1];
      const self = this;
      const each = (part, fn, depth, tilt) => {
        const r = part.rest, o = part.pos;
        for (let i = 0; i < r.length; i += 2) {
          fn(r[i], r[i + 1]);
          if (tilt) {
            const dx = self._x - pvx, dy = self._y - pvy;
            self._x = pvx + dx * cs - dy * sn;
            self._y = pvy + dx * sn + dy * cs;
          }
          self.headPoint(self._x, self._y, depth, 1);
          o[i] = self._x; o[i + 1] = self._y;
        }
      };
      each(W, (x, y) => {
        let yy = yBot - (yBot - y) * Math.max(g, 0.03);
        yy -= lift * smooth(W.cy, yBot, y);
        self._x = x; self._y = yy;
      }, 0.04, true);
      const bx = p.ParamEyeBallX * 8.5 * px, by = -p.ParamEyeBallY * 4.6 * px;
      // widening shows more iris: stretch it upward so its top stays under the lid
      const irisGrow = 1 + wide * 0.85, iBot = I.y1;
      each(I, (x, y) => { self._x = x + bx; self._y = iBot - (iBot - y) * irisGrow + by + (1 - open01) * 2 * px; }, 0.05, false);
      each(P[`eye_${side}_hl`], (x, y) => { self._x = x + bx * 0.85; self._y = iBot - (iBot - y) * irisGrow + by * 0.85 + (1 - open01) * 2 * px; }, 0.06, false);
      each(P[`eye_${side}_lower`], (x, y) => {
        const u = (x - cx) / half;
        self._x = x; self._y = y - lift * 0.9 - smile * 1.2 * px * (1 - u * u);
      }, 0.045, true);
      each(P[`eye_${side}_lash`], (x, y) => {
        const u = (x - cx) / half;
        const bend = (1 - u * u);
        self._x = x;
        self._y = y + lashDy + (1 - open01) * 2.0 * px * bend - smile * 1.6 * px * bend * open01;
      }, 0.05, true);
      each(P[`eye_${side}_crease`], (x, y) => { self._x = x; self._y = y + lashDy * 0.8; }, 0.045, true);
      each(P[`eye_${side}_closed`], (x, y) => {
        const u = (x - cx) / half;
        self._x = x; self._y = y - smile * (11.0 * (1 - u * u) - 4.0) * px;
      }, 0.05, true);
      const brow = P[`brow_${side}`];
      const ang = (p.ParamBrowAngle * 20 + (cfg.browTilt || 0)) * DEG * (side === 'l' ? 1 : -1);
      const cb = Math.cos(ang), sb = Math.sin(ang);
      const bdy = (-p.ParamBrowY * 8 - wide * 6 + (1 - open01) * 1.4) * px;
      each(brow, (x, y) => {
        const dx = x - brow.cx, dy = y - brow.cy;
        self._x = brow.cx + dx * cb - dy * sb;
        self._y = brow.cy + dx * sb + dy * cb + bdy;
      }, 0.05, false);

      for (const n of ['white', 'iris', 'hl', 'lower', 'lash']) {
        const q = P[`eye_${side}_${n}`];
        q.opacity = aOpen * q.base;
      }
      // the faint lower-lid stroke would stack into a double line on a half-shut eye
      P[`eye_${side}_lower`].opacity *= smooth(0.3, 0.65, open);
      brow.opacity = brow.base;
      P[`eye_${side}_crease`].opacity = P[`eye_${side}_crease`].base * aOpen * (1 - 0.6 * smile) * smooth(0.3, 0.8, open);
      P[`eye_${side}_closed`].opacity = 1 - aOpen;
      P[`eye_${side}_closed`].visible = true;
    }

    _mouthTarget(o, f) {
      if (this.mouthOverride) return this.mouthOverride;
      if (o < 0.1) return f > 0.5 ? 'mouth_smile' : f < -0.5 ? 'mouth_sad' : 'mouth_closed';
      if (f > 0.5) return o < 0.38 ? 'mouth_grin' : 'mouth_smile_open';
      if (o < 0.36) return 'mouth_open_s';
      if (o < 0.7) return 'mouth_open_m';
      return 'mouth_open_l';
    }

    _mouth(dt) {
      const P = this.parts, G = this.G, p = this.params, self = this;
      const o = clamp(p.ParamMouthOpenY, 0, 1), f = clamp(p.ParamMouthForm, -1, 1);
      const target = this._mouthTarget(o, f);
      const kA = 1 - Math.exp(-dt * 26);
      const px = this.hs / 0.405;
      const jawTarget = target === 'mouth_closed' || target === 'mouth_smile' || target === 'mouth_sad' ? 0 : o;
      this.jaw = (this.jaw || 0) + (jawTarget - (this.jaw || 0)) * (1 - Math.exp(-dt * 20));
      const t01 = (a, b) => clamp((o - a) / (b - a), 0, 1);
      for (const id of MOUTHS) {
        const part = P[id];
        const a = (this.mouthAlpha[id] += ((id === target ? 1 : 0) - this.mouthAlpha[id]) * kA);
        part.visible = a > 0.01;
        part.opacity = a;
        if (!part.visible) continue;
        let sx = 1, sy = 1, bend = 0, anchorY = part.cy;
        switch (id) {
          case 'mouth_closed': sx = 0.9 + 0.08 * f; bend = f * 2.4 * px; break;
          case 'mouth_smile': sx = 0.92 + 0.1 * f; break;
          case 'mouth_sad': sx = 0.9 - 0.08 * f; break;
          case 'mouth_grin': anchorY = part.y0; sy = lerp(0.75, 1.25, t01(0.1, 0.38)); break;
          case 'mouth_open_s': anchorY = part.y0; sy = lerp(0.55, 1.15, t01(0.1, 0.36)); sx = 0.94 + 0.08 * t01(0.1, 0.36); break;
          case 'mouth_open_m': anchorY = part.y0; sy = lerp(0.8, 1.08, t01(0.36, 0.7)); break;
          case 'mouth_open_l': anchorY = part.y0; sy = lerp(0.84, 1.06, t01(0.7, 1)); break;
          case 'mouth_smile_open': anchorY = part.y0; sy = lerp(0.78, 1.18, t01(0.38, 1)); break;
          case 'mouth_o': sy = lerp(0.75, 1.15, o); sx = lerp(0.85, 1.05, o); break;
          default: break;
        }
        const r = part.rest, out = part.pos, half = part.w / 2;
        for (let i = 0; i < r.length; i += 2) {
          const u = (r[i] - G.mouthX) / half;
          const x = G.mouthX + (r[i] - G.mouthX) * sx;
          const y = anchorY + (r[i + 1] - anchorY) * sy - bend * (u * u - 0.35);
          self.headPoint(x, y, 0.05, 1);
          out[i] = self._x; out[i + 1] = self._y;
        }
      }
    }

    /** What sits under a model-space point: 'head', 'body' or null. */
    hitTest(x, y) {
      const G = this.G, S = this.S;
      const dx = (x - S.headCx) / (G.Rx * 1.25), dy = (y - (S.headCy - 20)) / (G.Ry * 1.2);
      if (dx * dx + dy * dy < 1) return 'head';
      if (x > 250 && x < 830 && y > G.Ny && y < 1500) return 'body';
      return null;
    }
  }

  global.L2D = global.L2D || {};
  global.L2D.Model = Model;
  global.L2D.util = { clamp, lerp, smooth, DEG };
})(typeof window !== 'undefined' ? window : globalThis);
