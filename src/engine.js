/* Minimal mesh-puppet renderer (WebGL2 with a WebGL1 fallback).
 *
 * Every model part is a textured grid mesh. The model deforms the grid on the
 * CPU each frame; this file only uploads vertices and draws them in order,
 * with optional clipping against an off-screen alpha mask (used for the eyes).
 */
(function (global) {
  'use strict';

  const VS = `
attribute vec2 aPos;
attribute vec2 aUV;
uniform vec4 uView;
varying vec2 vUV;
void main() {
  vUV = aUV;
  gl_Position = vec4(aPos * uView.xy + uView.zw, 0.0, 1.0);
}`;

  const FS = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUV;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform float uOpacity;
uniform float uUseMask;
uniform vec2 uInvRes;
void main() {
  vec4 c = texture2D(uTex, vUV);
  float m = 1.0;
  if (uUseMask > 0.5) m = texture2D(uMask, gl_FragCoord.xy * uInvRes).a;
  gl_FragColor = c * (uOpacity * m);
}`;

  class Renderer {
    constructor(canvas) {
      this.canvas = canvas;
      const opts = { alpha: true, premultipliedAlpha: true, antialias: true, depth: false, stencil: false };
      let gl = canvas.getContext('webgl2', opts);
      this.isGL2 = !!gl;
      if (!gl) gl = canvas.getContext('webgl', opts) || canvas.getContext('experimental-webgl', opts);
      if (!gl) throw new Error('WebGL is not available');
      this.gl = gl;
      this.maskOwner = null;
      this._setup();
    }

    _setup() {
      const gl = this.gl;
      const compile = (type, src) => {
        const s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
        return s;
      };
      const prog = gl.createProgram();
      gl.attachShader(prog, compile(gl.VERTEX_SHADER, VS));
      gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FS));
      gl.bindAttribLocation(prog, 0, 'aPos');
      gl.bindAttribLocation(prog, 1, 'aUV');
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
      this.prog = prog;
      this.u = {};
      for (const n of ['uView', 'uTex', 'uMask', 'uOpacity', 'uUseMask', 'uInvRes']) this.u[n] = gl.getUniformLocation(prog, n);
      gl.useProgram(prog);
      gl.uniform1i(this.u.uTex, 0);
      gl.uniform1i(this.u.uMask, 1);
      gl.enableVertexAttribArray(0);
      gl.enableVertexAttribArray(1);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.CULL_FACE);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      this.aniso = gl.getExtension('EXT_texture_filter_anisotropic');
      this.maskFbo = gl.createFramebuffer();
      this.blank = gl.createTexture();
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, this.blank);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]));
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.activeTexture(gl.TEXTURE0);
      this.maskTex = null;
      this.maskW = this.maskH = 0;
      this.maskOwner = null;
    }

    texture(image) {
      const gl = this.gl;
      const t = gl.createTexture();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      if (this.isGL2) {
        gl.generateMipmap(gl.TEXTURE_2D);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
        if (this.aniso) gl.texParameterf(gl.TEXTURE_2D, this.aniso.TEXTURE_MAX_ANISOTROPY_EXT, 4);
      } else {
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      }
      return t;
    }

    /** Create GPU buffers for a part ({pos, uv, idx} typed arrays). */
    mesh(part) {
      const gl = this.gl;
      const m = { pos: gl.createBuffer(), uv: gl.createBuffer(), idx: gl.createBuffer(), count: part.idx.length, frame: -1 };
      gl.bindBuffer(gl.ARRAY_BUFFER, m.pos);
      gl.bufferData(gl.ARRAY_BUFFER, part.pos, gl.DYNAMIC_DRAW);
      gl.bindBuffer(gl.ARRAY_BUFFER, m.uv);
      gl.bufferData(gl.ARRAY_BUFFER, part.uv, gl.STATIC_DRAW);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, m.idx);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, part.idx, gl.STATIC_DRAW);
      return m;
    }

    resize(w, h) {
      const gl = this.gl;
      if (this.canvas.width !== w || this.canvas.height !== h) {
        this.canvas.width = w;
        this.canvas.height = h;
      }
      if (this.maskW !== w || this.maskH !== h || !this.maskTex) {
        if (this.maskTex) gl.deleteTexture(this.maskTex);
        this.maskTex = gl.createTexture();
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.maskTex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.bindFramebuffer(gl.FRAMEBUFFER, this.maskFbo);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.maskTex, 0);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.bindTexture(gl.TEXTURE_2D, this.blank);
        gl.activeTexture(gl.TEXTURE0);
        this.maskOwner = null;
        this.maskW = w;
        this.maskH = h;
      }
    }

    /**
     * view: {cx, cy, scale} — model point (cx, cy) lands at the canvas centre,
     * `scale` is drawing-buffer pixels per model unit.
     */
    begin(view, frame) {
      const gl = this.gl;
      const w = this.canvas.width, h = this.canvas.height;
      this.frame = frame;
      this.maskOwner = null;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, w, h);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.useProgram(this.prog);
      const sx = (2 * view.scale) / w, sy = (-2 * view.scale) / h;
      gl.uniform4f(this.u.uView, sx, sy, -view.cx * sx, -view.cy * sy);
      gl.uniform2f(this.u.uInvRes, 1 / w, 1 / h);
    }

    _bind(part) {
      const gl = this.gl, m = part.gpu;
      gl.bindBuffer(gl.ARRAY_BUFFER, m.pos);
      if (m.frame !== this.frame) {
        gl.bufferSubData(gl.ARRAY_BUFFER, 0, part.pos);
        m.frame = this.frame;
      }
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, m.uv);
      gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, m.idx);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, part.texture);
    }

    /** Render the intersection of the given parts' alpha into the mask buffer. */
    _mask(parts) {
      let key = '';
      for (const p of parts) key += p.id + '|';
      if (this.maskOwner === key) return;
      const gl = this.gl;
      // the mask texture must not be bound for sampling while we render into it
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, this.blank);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.maskFbo);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform1f(this.u.uOpacity, 1);
      gl.uniform1f(this.u.uUseMask, 0);
      for (let i = 0; i < parts.length; i++) {
        this._bind(parts[i]);
        if (i === 1) gl.blendFunc(gl.ZERO, gl.SRC_ALPHA);
        gl.drawElements(gl.TRIANGLES, parts[i].gpu.count, gl.UNSIGNED_SHORT, 0);
      }
      // Later parts multiply the mask by their alpha. That only narrows the
      // mask inside their own quad, so a multiplying part must be the larger
      // one (here: the bangs quad always contains the eyes).
      if (parts.length > 1) gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      this.maskOwner = key;
    }

    draw(part, opacity, mask) {
      if (opacity <= 0.003) return;
      const gl = this.gl;
      if (mask) {
        this._mask(mask);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.maskTex);
      }
      this._bind(part);
      gl.uniform1f(this.u.uOpacity, opacity);
      gl.uniform1f(this.u.uUseMask, mask ? 1 : 0);
      gl.drawElements(gl.TRIANGLES, part.gpu.count, gl.UNSIGNED_SHORT, 0);
      if (mask) {
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, this.blank);
      }
    }
  }

  global.L2D = global.L2D || {};
  global.L2D.Renderer = Renderer;
})(typeof window !== 'undefined' ? window : globalThis);
