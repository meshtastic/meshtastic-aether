// The WebXR view of the cloud. Kotlin fills the sprite and line arrays each frame (SpatialFrame's layout) and this
// draws them once per eye, additively, so the same frame reads on a dark field and over a camera image.
window.aetherSpatial = (() => {
  const SPRITE_STRIDE = 9;
  const LINE_STRIDE = 7;
  // Where a 'local' reference space puts its origin: at the viewer's eyes, not the floor (SpatialLayout.EYE_METERS).
  const EYE_METERS = 1.4;

  const SPRITE_VS = `#version 300 es
uniform mat4 uProj;
uniform mat4 uView;
uniform float uDrop;
in vec2 aCorner;
in vec4 aPlace;
in vec4 aColor;
in float aCrisp;
out vec2 vCorner;
out vec4 vColor;
out float vCrisp;
void main() {
  vec4 eye = uView * vec4(aPlace.x, aPlace.y - uDrop, aPlace.z, 1.0);
  eye.xy += aCorner * aPlace.w;
  gl_Position = uProj * eye;
  vCorner = aCorner;
  vColor = aColor;
  vCrisp = aCrisp;
}`;
  const SPRITE_FS = `#version 300 es
precision mediump float;
in vec2 vCorner;
in vec4 vColor;
in float vCrisp;
out vec4 color;
void main() {
  float r = length(vCorner);
  float halo = max(0.0, 1.0 - r);
  float disc = 1.0 - smoothstep(0.8, 1.0, r);
  float a = vColor.a * mix(halo, disc, vCrisp);
  color = vec4(vColor.rgb * a, a);
}`;
  const LINE_VS = `#version 300 es
uniform mat4 uProj;
uniform mat4 uView;
uniform float uDrop;
in vec3 aPlace;
in vec4 aColor;
out vec4 vColor;
void main() {
  gl_Position = uProj * uView * vec4(aPlace.x, aPlace.y - uDrop, aPlace.z, 1.0);
  vColor = aColor;
}`;
  const LINE_FS = `#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 color;
void main() {
  color = vec4(vColor.rgb * vColor.a, vColor.a);
}`;

  let sprites = new Float32Array(SPRITE_STRIDE * 256);
  let lines = new Float32Array(LINE_STRIDE * 512);
  let spriteCount = 0;
  let lineVertexCount = 0;
  const background = [0, 0, 0];
  let session = null;
  let starting = false;

  function program(gl, vs, fs) {
    const p = gl.createProgram();
    for (const [type, source] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
      const s = gl.createShader(type);
      gl.shaderSource(s, source);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('spatial: ' + gl.getShaderInfoLog(s));
      gl.attachShader(p, s);
    }
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('spatial: ' + gl.getProgramInfoLog(p));
    return p;
  }

  function attribute(gl, prog, name, size, stride, offset, divisor) {
    const at = gl.getAttribLocation(prog, name);
    gl.enableVertexAttribArray(at);
    gl.vertexAttribPointer(at, size, gl.FLOAT, false, stride * 4, offset * 4);
    gl.vertexAttribDivisor(at, divisor);
  }

  // Compiles both programs and returns draw(proj, view, drop), which uploads the arrays as they stand.
  function renderer(gl) {
    const spriteProg = program(gl, SPRITE_VS, SPRITE_FS);
    const spriteVao = gl.createVertexArray();
    gl.bindVertexArray(spriteVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    attribute(gl, spriteProg, 'aCorner', 2, 2, 0, 0);
    const spriteBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, spriteBuf);
    attribute(gl, spriteProg, 'aPlace', 4, SPRITE_STRIDE, 0, 1);
    attribute(gl, spriteProg, 'aColor', 4, SPRITE_STRIDE, 4, 1);
    attribute(gl, spriteProg, 'aCrisp', 1, SPRITE_STRIDE, 8, 1);

    const lineProg = program(gl, LINE_VS, LINE_FS);
    const lineVao = gl.createVertexArray();
    gl.bindVertexArray(lineVao);
    const lineBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, lineBuf);
    attribute(gl, lineProg, 'aPlace', 3, LINE_STRIDE, 0, 0);
    attribute(gl, lineProg, 'aColor', 4, LINE_STRIDE, 3, 0);
    gl.bindVertexArray(null);

    const uniforms = (prog) => ({
      proj: gl.getUniformLocation(prog, 'uProj'),
      view: gl.getUniformLocation(prog, 'uView'),
      drop: gl.getUniformLocation(prog, 'uDrop'),
    });
    const spriteU = uniforms(spriteProg);
    const lineU = uniforms(lineProg);

    return {
      upload() {
        gl.bindBuffer(gl.ARRAY_BUFFER, spriteBuf);
        gl.bufferData(gl.ARRAY_BUFFER, sprites.subarray(0, spriteCount * SPRITE_STRIDE), gl.DYNAMIC_DRAW);
        gl.bindBuffer(gl.ARRAY_BUFFER, lineBuf);
        gl.bufferData(gl.ARRAY_BUFFER, lines.subarray(0, lineVertexCount * LINE_STRIDE), gl.DYNAMIC_DRAW);
      },
      draw(proj, view, drop) {
        gl.disable(gl.DEPTH_TEST);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.ONE, gl.ONE);
        gl.useProgram(lineProg);
        gl.uniformMatrix4fv(lineU.proj, false, proj);
        gl.uniformMatrix4fv(lineU.view, false, view);
        gl.uniform1f(lineU.drop, drop);
        gl.bindVertexArray(lineVao);
        gl.drawArrays(gl.LINES, 0, lineVertexCount);
        gl.useProgram(spriteProg);
        gl.uniformMatrix4fv(spriteU.proj, false, proj);
        gl.uniformMatrix4fv(spriteU.view, false, view);
        gl.uniform1f(spriteU.drop, drop);
        gl.bindVertexArray(spriteVao);
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, spriteCount);
        gl.bindVertexArray(null);
      },
    };
  }

  async function begin(mode, onFrame, onEnd) {
    const xr = await navigator.xr.requestSession(mode, { optionalFeatures: ['local-floor'] });
    session = xr;
    xr.addEventListener('end', () => {
      session = null;
      onEnd();
    });
    const canvas = document.createElement('canvas');
    const gl = canvas.getContext('webgl2', { xrCompatible: true, alpha: true, antialias: true });
    await gl.makeXRCompatible();
    const scene = renderer(gl);
    xr.updateRenderState({ baseLayer: new XRWebGLLayer(xr, gl) });
    let drop = 0;
    const base = await xr.requestReferenceSpace('local-floor').catch(() => {
      drop = EYE_METERS;
      return xr.requestReferenceSpace('local');
    });
    // The platform picks local-floor's horizontal origin and heading, so the scene is anchored on the first viewer pose:
    // under the viewer at floor height, with -z (north) where they face.
    let space = null;
    const passthrough = mode === 'immersive-ar';
    const frame = (_time, xrFrame) => {
      xr.requestAnimationFrame(frame);
      onFrame();
      if (!space) {
        const first = xrFrame.getViewerPose(base);
        if (!first) return;
        const p = first.transform.position;
        const q = first.transform.orientation;
        const fx = -2 * (q.x * q.z + q.w * q.y);
        const fz = -(1 - 2 * (q.x * q.x + q.y * q.y));
        const yaw = Math.atan2(-fx, -fz);
        space = base.getOffsetReferenceSpace(new XRRigidTransform(
          { x: p.x, y: 0, z: p.z },
          { x: 0, y: Math.sin(yaw / 2), z: 0, w: Math.cos(yaw / 2) },
        ));
      }
      const pose = xrFrame.getViewerPose(space);
      if (!pose) return;
      const layer = xr.renderState.baseLayer;
      gl.bindFramebuffer(gl.FRAMEBUFFER, layer.framebuffer);
      if (passthrough) gl.clearColor(0, 0, 0, 0); else gl.clearColor(background[0], background[1], background[2], 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      scene.upload();
      for (const view of pose.views) {
        const port = layer.getViewport(view);
        gl.viewport(port.x, port.y, port.width, port.height);
        scene.draw(view.projectionMatrix, view.transform.inverse.matrix, drop);
      }
    };
    xr.requestAnimationFrame(frame);
  }

  return {
    // Calls back with what this browser can open, AR first: 1 for immersive-ar, 2 for immersive-vr, 0 for neither.
    probe(found) {
      const xr = navigator.xr;
      if (!xr || !window.WebGL2RenderingContext) { found(0); return; }
      const can = (mode) => xr.isSessionSupported(mode).catch(() => false);
      can('immersive-ar').then((ar) => ar ? found(1) : can('immersive-vr').then((vr) => found(vr ? 2 : 0)));
    },
    // Opens a session from a click; onFrame fills the arrays before each frame, onEnd runs when the session closes.
    start(mode, onFrame, onEnd) {
      if (session || starting) return;
      starting = true;
      // Runs once however the attempt ends: a session can end while setup is pending and setup then still reject.
      let ended = false;
      const finish = () => {
        if (ended) return;
        ended = true;
        onEnd();
      };
      begin(mode === 1 ? 'immersive-ar' : 'immersive-vr', onFrame, finish)
        .catch((e) => {
          console.warn('spatial: ' + e);
          // The session's own end listener clears it and finishes; with no session there is nothing to end.
          if (session) session.end(); else finish();
        })
        .finally(() => { starting = false; });
    },
    reserve(spriteFloats, lineFloats) {
      if (spriteFloats > sprites.length) sprites = new Float32Array(spriteFloats);
      if (lineFloats > lines.length) lines = new Float32Array(lineFloats);
    },
    put(lineArray, index, value) {
      (lineArray ? lines : sprites)[index] = value;
    },
    commit(spriteTotal, lineVertexTotal, r, g, b) {
      spriteCount = spriteTotal;
      lineVertexCount = lineVertexTotal;
      background[0] = r;
      background[1] = g;
      background[2] = b;
    },
    // For a check without a headset: draws the arrays as they stand into the given WebGL2 context.
    renderer,
  };
})();
