/* Drives the model's parameters: idle motion, gaze following, blinking,
 * breathing, lip-sync demo, expressions, tap reactions and manual overrides.
 */
(function (global) {
  'use strict';
  const { clamp, lerp } = global.L2D.util;
  const rand = (a, b) => a + Math.random() * (b - a);

  /** Critically damped follow with velocity (smooth starts and stops). */
  class Damp {
    constructor(x, omega) { this.x = x; this.v = 0; this.omega = omega; }
    step(target, dt) {
      const w = this.omega;
      const f = 1 + 2 * dt * w, hoo = dt * w * w, hhoo = dt * hoo;
      const inv = 1 / (f + hhoo);
      const x = (f * this.x + dt * this.v + hhoo * target) * inv;
      this.v = (this.v + hoo * (target - this.x)) * inv;
      this.x = x;
      return x;
    }
  }

  const EXPRESSIONS = {
    neutral: { name: '平静' },
    smile: { name: '微笑', ParamMouthForm: 0.95, ParamEyeSmile: 0.55, ParamCheek: 0.35, ParamBrowY: 0.2, eyeL: 0.92, eyeR: 0.92 },
    joy: { name: '开心', ParamMouthForm: 1, ParamEyeSmile: 1, ParamCheek: 0.6, ParamBrowY: 0.45, eyeL: 0, eyeR: 0, ParamMouthOpenY: 0.62, ParamAngleZ: 5 },
    surprise: { name: '惊讶', ParamBrowY: 0.95, eyeL: 1.2, eyeR: 1.2, ParamMouthOpenY: 0.7, mouth: 'mouth_o', ParamAngleY: 4 },
    sad: { name: '难过', ParamMouthForm: -1, ParamBrowAngle: 0.95, ParamBrowY: -0.15, eyeL: 0.74, eyeR: 0.74, ParamEyeBallY: -0.45, ParamAngleY: -9 },
    angry: { name: '生气', ParamMouthForm: -0.85, ParamBrowAngle: -1, ParamBrowY: -0.5, eyeL: 0.84, eyeR: 0.84, ParamCheek: 0.2, ParamAngleY: -4 },
    wink: { name: '俏皮', ParamEyeSmile: 0.8, ParamCheek: 0.4, ParamBrowY: 0.3, eyeL: 0, eyeR: 1.05, mouth: 'mouth_tongue_out', ParamAngleZ: 9, ParamMouthOpenY: 0.4 },
    shy: { name: '害羞', ParamCheek: 1, ParamMouthForm: 0.4, ParamBrowAngle: 0.55, eyeL: 0.8, eyeR: 0.8, ParamEyeBallX: 0.55, ParamEyeBallY: -0.4, ParamAngleY: -6, ParamAngleX: -7 },
  };
  const EXPR_KEYS = ['ParamMouthForm', 'ParamEyeSmile', 'ParamCheek', 'ParamBrowY', 'ParamBrowAngle', 'eyeL', 'eyeR',
    'ParamMouthOpenY', 'ParamEyeBallX', 'ParamEyeBallY', 'ParamAngleX', 'ParamAngleY', 'ParamAngleZ'];
  const EXPR_DEFAULT = { eyeL: 1, eyeR: 1 };

  class Animator {
    constructor(model) {
      this.model = model;
      this.opts = { follow: true, idle: true, blink: true, breath: true, talk: false };
      this.restTilt = -9;          // the illustration's head tilt, kept as the idle pose
      this.time = 0;
      this.pointer = { x: 0, y: 0, active: false, last: -100 };
      this.pins = {};              // paramId -> value forced by a slider
      this.expression = 'neutral';
      this.reaction = null;        // {queue:[{expr, until}], back}
      this.d = {};
      const om = { ParamAngleX: 8, ParamAngleY: 8, ParamAngleZ: 6.5, ParamBodyAngleX: 5, ParamBodyAngleY: 5, ParamBodyAngleZ: 5,
        ParamEyeBallX: 20, ParamEyeBallY: 20, ParamMouthOpenY: 34 };
      for (const p of model.paramDefs) this.d[p.id] = new Damp(p.def, om[p.id] || 12);
      this.ex = {};
      for (const k of EXPR_KEYS) this.ex[k] = new Damp(EXPR_DEFAULT[k] || 0, 11);
      this.blink = { next: rand(1.2, 3), t: -1, again: false, value: 1 };
      this.gaze = { x: 0, y: 0, next: 1.5 };
      this.syl = { t: 0, dur: 0.2, amp: 0 };
      this.mic = null;             // optional () => level 0..1
    }

    setExpression(id) {
      if (!EXPRESSIONS[id]) return;
      this.expression = id;
      this.reaction = null;
    }

    pin(id, v) { this.pins[id] = v; }
    unpinAll() { this.pins = {}; }

    /** px, py in [-1, 1], measured from the head. */
    lookAt(px, py) {
      this.pointer.x = clamp(px, -1, 1);
      this.pointer.y = clamp(py, -1, 1);
      this.pointer.active = true;
      this.pointer.last = this.time;
    }
    lookAway() { this.pointer.active = false; }

    tap(area) {
      const m = this.model;
      if (area === 'head') {
        const back = this.reaction ? this.reaction.back : this.expression;
        this.reaction = { back, queue: [{ expr: 'surprise', until: this.time + 0.45 }, { expr: 'smile', until: this.time + 2.6 }] };
        this.d.ParamAngleY.v += 60;
        this.d.ParamAngleZ.v += rand(-50, 50);
        m.kick(rand(-60, 60));
      } else if (area === 'body') {
        const dir = Math.random() < 0.5 ? -1 : 1;
        this.d.ParamBodyAngleX.v += dir * 55;
        this.d.ParamBodyAngleZ.v += dir * 30;
        this.d.ParamAngleZ.v -= dir * 40;
        m.kick(dir * 90);
      }
    }

    _currentExpression() {
      const r = this.reaction;
      if (r) {
        while (r.queue.length && this.time > r.queue[0].until) r.queue.shift();
        if (r.queue.length) return r.queue[0].expr;
        this.reaction = null;
      }
      return this.expression;
    }

    update(dt) {
      dt = Math.min(dt, 0.05);
      const t = (this.time += dt);
      const m = this.model, o = this.opts, T = {};
      for (const p of m.paramDefs) T[p.id] = p.def;

      // ---- idle sway
      const idle = o.idle ? 1 : 0;
      const follow = o.follow && this.pointer.active && t - this.pointer.last < 4.5;
      const ik = idle * (follow ? 0.35 : 1);
      T.ParamAngleX += ik * (5 * Math.sin(0.31 * t + 1.0) + 2.5 * Math.sin(0.83 * t + 0.3));
      T.ParamAngleY += ik * (3 * Math.sin(0.47 * t) + 1.5 * Math.sin(1.13 * t + 2.0));
      T.ParamAngleZ += idle * this.restTilt * (follow ? 0.45 : 1) + ik * (3.5 * Math.sin(0.37 * t + 0.5) + 1.2 * Math.sin(0.9 * t));
      T.ParamBodyAngleX += ik * 2.4 * Math.sin(0.29 * t + 2.0);
      T.ParamBodyAngleY += ik * 1.2 * Math.sin(0.53 * t);
      T.ParamBodyAngleZ += ik * 1.5 * Math.sin(0.41 * t + 1.0);

      // ---- gaze
      if (follow) {
        const px = this.pointer.x, py = this.pointer.y;
        T.ParamAngleX += px * 26;
        T.ParamAngleY += -py * 24;
        T.ParamAngleZ += -px * 5;
        T.ParamBodyAngleX += px * 5.5;
        T.ParamBodyAngleZ += -px * 1.5;
        T.ParamEyeBallX = px;
        T.ParamEyeBallY = -py;
      } else if (idle) {
        const g = this.gaze;
        if (t > g.next) {
          const centre = Math.random() < 0.45;
          g.x = centre ? 0 : rand(-0.45, 0.45);
          g.y = centre ? 0 : rand(-0.25, 0.2);
          g.next = t + rand(1.4, 4.2);
        }
        T.ParamEyeBallX = g.x;
        T.ParamEyeBallY = g.y;
        T.ParamAngleX += g.x * 5;
      }

      // ---- breathing
      if (o.breath) T.ParamBreath = 0.5 - 0.5 * Math.cos((2 * Math.PI * t) / 3.8);

      // ---- expression layer
      const E = EXPRESSIONS[this._currentExpression()];
      const ev = {};
      for (const k of EXPR_KEYS) ev[k] = this.ex[k].step(E[k] !== undefined ? E[k] : (EXPR_DEFAULT[k] || 0), dt);
      for (const k of ['ParamMouthForm', 'ParamEyeSmile', 'ParamCheek', 'ParamBrowY', 'ParamBrowAngle', 'ParamMouthOpenY']) T[k] += ev[k];
      for (const k of ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ']) T[k] += ev[k];
      if (!follow) { T.ParamEyeBallX += ev.ParamEyeBallX; T.ParamEyeBallY += ev.ParamEyeBallY; }
      m.mouthOverride = E.mouth || null;

      // ---- talking
      let talk = 0;
      if (this.mic) talk = this.mic();
      else if (o.talk) {
        const s = this.syl;
        s.t += dt;
        if (s.t > s.dur) {
          s.t = 0;
          if (Math.random() < 0.14) { s.amp = 0; s.dur = rand(0.16, 0.5); }
          else { s.amp = rand(0.35, 1); s.dur = rand(0.11, 0.2); }
        }
        talk = s.amp * Math.pow(Math.sin((Math.PI * s.t) / s.dur), 0.8);
      }
      if (talk > 0 && !E.mouth) T.ParamMouthOpenY = Math.max(T.ParamMouthOpenY, talk);
      if ((o.talk || this.mic) && idle) T.ParamAngleY += talk * 2.5;

      // ---- blinking
      const b = this.blink;
      if (o.blink) {
        if (b.t < 0 && t > b.next) { b.t = 0; b.again = Math.random() < 0.16; }
        if (b.t >= 0) {
          b.t += dt;
          const c = 0.07, hold = 0.04, op = 0.14;
          if (b.t < c) b.value = 1 - b.t / c;
          else if (b.t < c + hold) b.value = 0;
          else if (b.t < c + hold + op) { const u = (b.t - c - hold) / op; b.value = u * u * (3 - 2 * u); }
          else {
            b.value = 1; b.t = -1;
            b.next = t + (b.again ? 0.12 : rand(2.2, 5.6));
            b.again = false;
          }
        }
      } else { b.value = 1; b.t = -1; }

      // ---- write out
      const P = m.params;
      for (const p of m.paramDefs) {
        const id = p.id;
        if (id === 'ParamEyeLOpen' || id === 'ParamEyeROpen') continue;
        const pinned = this.pins[id] !== undefined;
        const v = this.d[id].step(pinned ? this.pins[id] : clamp(T[id], p.min, p.max), dt);
        P[id] = clamp(v, p.min, p.max);
      }
      // eyes: expression sets the resting openness, the blink multiplies it
      for (const [id, k] of [['ParamEyeLOpen', 'eyeL'], ['ParamEyeROpen', 'eyeR']]) {
        if (this.pins[id] !== undefined) P[id] = this.d[id].step(this.pins[id], dt);
        else { P[id] = clamp(ev[k] * b.value, 0, 1.2); this.d[id].x = P[id]; this.d[id].v = 0; }
      }
    }
  }

  global.L2D.Animator = Animator;
  global.L2D.EXPRESSIONS = EXPRESSIONS;
})(typeof window !== 'undefined' ? window : globalThis);
