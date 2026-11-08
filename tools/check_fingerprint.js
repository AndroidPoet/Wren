#!/usr/bin/env node
/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Checks the properties assets/fingerprint.js promises, against stub DOM objects:
 * repeated reads are identical, exports never write to the page's canvas, the noise differs
 * between top-level sites and between seeds, and the trimmed renderer string keeps the GPU
 * family but drops the driver build. Run: node tools/check_fingerprint.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/fingerprint.js'), 'utf8');

function makeWorld(seed, topOrigin, frameOrigin) {
  const canvases = [];
  function pixelsFor(canvas) {
    if (!canvas._pixels) {
      canvas._pixels = new Uint8ClampedArray(canvas.width * canvas.height * 4);
      for (let i = 0; i < canvas._pixels.length; i++) canvas._pixels[i] = (i * 31) & 255;
    }
    return canvas._pixels;
  }
  class ImageData { constructor(data, w, h) { this.data = data; this.width = w; this.height = h; } }
  class CanvasRenderingContext2D {
    constructor(canvas) { this.canvas = canvas; }
    getImageData(x, y, w, h) { return new ImageData(Uint8ClampedArray.from(pixelsFor(this.canvas)), w, h); }
    putImageData(image) { pixelsFor(this.canvas).set(image.data); }
    drawImage(source) { pixelsFor(this.canvas).set(pixelsFor(source)); }
  }
  class HTMLCanvasElement {
    constructor() { this.width = 0; this.height = 0; canvases.push(this); }
    getContext(kind) { return kind === '2d' ? new CanvasRenderingContext2D(this) : null; }
    toDataURL() { return 'data:' + Buffer.from(pixelsFor(this)).toString('base64'); }
    toBlob(cb) { cb('data:' + Buffer.from(pixelsFor(this)).toString('base64')); }
  }
  class WebGLRenderingContext {
    getParameter(p) {
      if (p === 37446) return 'ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2 V@0615.0 (GIT@a1b2c3d, Iabc123))';
      if (p === 37445) return 'Qualcomm';
      return null;
    }
    readPixels(x, y, w, h, f, t, out) { for (let i = 0; i < out.length; i++) out[i] = (i * 7) & 255; }
  }
  class Navigator {}
  Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get() { return 8; }, configurable: true });
  class Screen {}
  Object.defineProperty(Screen.prototype, 'width', { get() { return 1080; }, configurable: true });
  Object.defineProperty(Screen.prototype, 'height', { get() { return 2400; }, configurable: true });
  const win = {
    location: { origin: frameOrigin, ancestorOrigins: frameOrigin === topOrigin ? [] : [topOrigin] },
    document: { referrer: '', createElement: (t) => (t === 'canvas' ? new HTMLCanvasElement() : null) },
    HTMLCanvasElement, CanvasRenderingContext2D, WebGLRenderingContext, Navigator, Screen,
    navigator: Object.create(Navigator.prototype), screen: Object.create(Screen.prototype),
  };
  win.window = win;
  win.top = win;
  win.URL = URL;
  const code = source.replace('__SEED__', String(seed));
  new Function('window', 'location', 'document', 'navigator', 'screen', 'HTMLCanvasElement',
    'CanvasRenderingContext2D', 'WebGLRenderingContext', 'Navigator', 'Screen', 'URL', code)(
    win, win.location, win.document, win.navigator, win.screen, HTMLCanvasElement,
    CanvasRenderingContext2D, WebGLRenderingContext, Navigator, Screen, URL);
  return win;
}

function canvasOf(win) {
  const c = new win.HTMLCanvasElement();
  c.width = 64; c.height = 64;
  return c;
}

const checks = [];
function check(name, ok) { checks.push([name, ok]); }

{
  const win = makeWorld(777, 'https://site-a.example', 'https://site-a.example');
  const c = canvasOf(win);
  const ctx = c.getContext('2d');
  const a = Array.from(ctx.getImageData(0, 0, 64, 64).data);
  const b = Array.from(ctx.getImageData(0, 0, 64, 64).data);
  check('sameCanvasReadStable', a.join() === b.join());
  const before = Array.from(c._pixels);
  const url1 = c.toDataURL();
  const url2 = c.toDataURL();
  check('toDataURLLeavesCanvasUntouched', before.join() === Array.from(c._pixels).join());
  check('toDataURLStable', url1 === url2);
  const raw = Buffer.from(before).toString('base64');
  check('toDataURLIsNoisy', url1 !== 'data:' + raw);
  let blobOut = null;
  c.toBlob((v) => { blobOut = v; });
  check('toBlobWrapped', blobOut === url1);
  const gl = new win.WebGLRenderingContext();
  check('rendererTrimmed', gl.getParameter(37446) === 'ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)');
  check('vendorKept', gl.getParameter(37445) === 'Qualcomm');
  const out1 = new Uint8Array(64 * 64 * 4); gl.readPixels(0, 0, 64, 64, 0, 0, out1);
  const out2 = new Uint8Array(64 * 64 * 4); gl.readPixels(0, 0, 64, 64, 0, 0, out2);
  check('readPixelsStable', out1.join() === out2.join());
  check('readPixelsNoisy', out1.some((v, i) => v !== ((i * 7) & 255)));
  check('prototypeGetterOverridden',
    Object.getOwnPropertyDescriptor(win.Navigator.prototype, 'hardwareConcurrency').get.call(win.navigator) === 4);
  check('availWidthEqualsWidth', win.screen.availWidth === 1080 && win.screen.availTop === 0);
}
{
  const a = makeWorld(777, 'https://site-a.example', 'https://tracker.example');
  const b = makeWorld(777, 'https://site-b.example', 'https://tracker.example');
  const ca = canvasOf(a), cb = canvasOf(b);
  check('trackerFrameDiffersAcrossTopSites', ca.toDataURL() !== cb.toDataURL());
  const a2 = makeWorld(777, 'https://site-a.example', 'https://tracker.example');
  check('trackerFrameStableOnSameTopSite', canvasOf(a2).toDataURL() === ca.toDataURL());
  const c = makeWorld(778, 'https://site-a.example', 'https://tracker.example');
  check('newSeedChangesAnswer', canvasOf(c).toDataURL() !== ca.toDataURL());
}

let failed = 0;
for (const [name, ok] of checks) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name);
  if (!ok) failed++;
}
process.exit(failed ? 1 : 0);
