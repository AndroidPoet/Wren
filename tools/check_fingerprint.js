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

function makeWorld(seed, topOrigin, frameOrigin, tor = false) {
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
  class WebGL2RenderingContext extends WebGLRenderingContext {
    getBufferSubData(target, offset, out) { for (let i = 0; i < out.length; i++) out[i] = (i * 11) & 255; }
  }
  class AnalyserNode {
    getByteFrequencyData(array) { for (let i = 0; i < array.length; i++) array[i] = i & 255; }
    getByteTimeDomainData(array) { array.fill(128); }
    getFloatFrequencyData(array) { array.fill(-30); }
    getFloatTimeDomainData(array) { array.fill(0); }
  }
  class Navigator {}
  Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get() { return 8; }, configurable: true });
  class Screen {}
  Object.defineProperty(Screen.prototype, 'width', { get() { return 1080; }, configurable: true });
  Object.defineProperty(Screen.prototype, 'height', { get() { return 2400; }, configurable: true });
  const win = {
    location: { origin: frameOrigin, ancestorOrigins: frameOrigin === topOrigin ? [] : [topOrigin] },
    document: { referrer: '', createElement: (t) => (t === 'canvas' ? new HTMLCanvasElement() : null) },
    HTMLCanvasElement, CanvasRenderingContext2D, WebGLRenderingContext, WebGL2RenderingContext, AnalyserNode, Navigator, Screen,
    // A stand-in for the realm's Function so the shared toString wrapper lands somewhere observable.
    Function: { prototype: { toString: Function.prototype.toString } },
    navigator: Object.create(Navigator.prototype), screen: Object.create(Screen.prototype),
  };
  win.window = win;
  win.top = win;
  win.URL = URL;
  const code = source.replace('__SEED__', String(seed)).replace('__TOR__', String(tor));
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
{
  // Calibration attack: read a blank canvas of the same size to learn the noise mask, then
  // subtract it from the real read. The mask must depend on the image, so this recovers
  // nothing.
  const win = makeWorld(777, 'https://site-a.example', 'https://site-a.example');
  const blank = canvasOf(win);
  blank._pixels = new Uint8ClampedArray(64 * 64 * 4);
  const mask = blank.getContext('2d').getImageData(0, 0, 64, 64).data;
  const real = canvasOf(win);
  const noisy = real.getContext('2d').getImageData(0, 0, 64, 64).data;
  const raw = Array.from(real._pixels);
  const recovered = Array.from(noisy, (v, i) => v ^ mask[i]);
  check('knownCanvasCannotRecoverNativeFingerprint', recovered.join() !== raw.join());
  check('maskDependsOnImage', Array.from(noisy, (v, i) => v ^ raw[i]).join() !== Array.from(mask).join());
}

// Shims read as native through the realm's Function.prototype.toString and carry no own toString.
{
  const win = makeWorld(777, 'https://site-a.example', 'https://site-a.example');
  const toString = win.Function.prototype.toString;
  const getImageData = win.CanvasRenderingContext2D.prototype.getImageData;
  check('shimReadsAsNative', /\[native code\]|getImageData\(x, y, w, h\)/.test(toString.call(getImageData)) &&
    toString.call(getImageData).indexOf('perturb') < 0);
  check('shimHasNoOwnToString', !Object.prototype.hasOwnProperty.call(getImageData, 'toString'));
  check('toStringItselfReadsAsNative', /\[native code\]/.test(toString.call(toString)));
  check('hardwareConcurrencyGetterReadsAsNative',
    /\[native code\]|get\(\)/.test(toString.call(Object.getOwnPropertyDescriptor(win.Navigator.prototype, 'hardwareConcurrency').get)));

  // WebGL2 buffer readbacks and the byte analyser reads carry noise, in range and stable.
  const gl = new win.WebGL2RenderingContext();
  const a = new Uint8Array(4096), b = new Uint8Array(4096);
  gl.getBufferSubData(0, 0, a); gl.getBufferSubData(0, 0, b);
  check('bufferSubDataNoisy', a.some((v, i) => v !== ((i * 11) & 255)));
  check('bufferSubDataStable', a.join() === b.join());
  const analyser = new win.AnalyserNode();
  const bytes = new Uint8Array(1024);
  analyser.getByteFrequencyData(bytes);
  check('byteAnalyserNoisyInRange', bytes.some((v, i) => v !== (i & 255)) && bytes.every((v, i) => Math.abs(v - (i & 255)) <= 1 || (i & 255) === 0 || (i & 255) === 255));
  const flat = new Uint8Array(1024);
  analyser.getByteTimeDomainData(flat);
  check('byteTimeDomainNoisy', flat.some((v) => v !== 128));
}

// Under Tor every read is blank and every export is of an empty canvas, whatever the seed or site.
{
  const a = makeWorld(1, 'https://a.example', 'https://a.example', true);
  const b = makeWorld(2, 'https://b.example', 'https://b.example', true);
  const ca = canvasOf(a), cb = canvasOf(b);
  const readA = ca.getContext('2d').getImageData(0, 0, 64, 64).data;
  const readB = cb.getContext('2d').getImageData(0, 0, 64, 64).data;
  check('torCanvasReadIsBlank', readA.every((v) => v === 0) && readB.every((v) => v === 0));
  check('torCanvasExportSameEverywhere', ca.toDataURL() === cb.toDataURL());
  const out = new Uint8Array(64);
  new a.WebGLRenderingContext().readPixels(0, 0, 4, 4, 0, 0, out);
  check('torReadPixelsIsBlank', out.every((v) => v === 0));
  const sub = new Uint8Array(64);
  new a.WebGL2RenderingContext().getBufferSubData(0, 0, sub);
  check('torBufferSubDataIsBlank', sub.every((v) => v === 0));
  const normal = canvasOf(makeWorld(1, 'https://a.example', 'https://a.example', false));
  check('normalCanvasStillCarriesPixels', normal.getContext('2d').getImageData(0, 0, 64, 64).data.some((v) => v !== 0));
}

let failed = 0;
for (const [name, ok] of checks) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name);
  if (!ok) failed++;
}
process.exit(failed ? 1 : 0);
