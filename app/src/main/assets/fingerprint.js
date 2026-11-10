/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Farbling: makes the readable-but-invisible parts of the page environment slightly wrong.
 *
 * Canvas, audio and WebGL all expose values that are identical every time for a given device,
 * which is what makes them a fingerprint. Returning a *fixed* fake would be worse than useless -
 * every Wren user would then share one very distinctive fingerprint. So each answer is
 * perturbed by a seed that is unique to this session and to the site at the top of the page.
 *
 * Three properties hold. The noise is deterministic: a page reading the same canvas twice sees
 * the same answer, because the noise is a pure function of the seed and of what was read, not
 * of how many times. The noise is scoped to the top-level site: a tracker frame embedded on two
 * sites gets two different answers, and the same site after a new session or a new Tor
 * identity gets a third. And nothing the page owns is modified: exports are taken from a noisy
 * copy, the canvas the page draws on is left exactly as drawn.
 *
 * What this cannot do is reach Web Workers, where no document-start script runs, or hide the
 * fact that the APIs are wrapped from a page determined to look. Those are engine-level limits.
 *
 * Under Tor the canvas answers blank, as Tor Browser's does: noise can be masked away by a
 * reader that drops the bits it touches, and every Tor user must give the same answer. All of
 * it is applied per realm; a child frame's window is hardened when the page reaches for it.
 */
(function () {
  'use strict';

  var TOR = __TOR__;

  // Every native this file relies on at call time is taken now, before any page script runs: a
  // page that replaced Function.prototype.apply, Math.imul or a typed array's fill would
  // otherwise be handed the originals, the seed's arithmetic, or the chance to skip a blanking.
  var apply = Reflect.apply;
  var imul = Math.imul;
  var floor = Math.floor;
  var max = Math.max;
  var round = Math.round;
  var fill = Uint8Array.prototype.fill;
  var then = Promise.prototype.then;
  var weakGet = WeakMap.prototype.get;
  var weakSet = WeakMap.prototype.set;
  var weakHas = typeof WeakSet === 'function' ? WeakSet.prototype.has : null;
  var weakAdd = typeof WeakSet === 'function' ? WeakSet.prototype.add : null;
  var stringReplace = String.prototype.replace;
  var charCodeAt = String.prototype.charCodeAt;
  var construct = Reflect.construct;
  var stringMatch = String.prototype.match;
  var getPrototypeOf = Object.getPrototypeOf;
  var defineProperty = Object.defineProperty;
  var getOwn = Object.getOwnPropertyDescriptor;
  var MARK = Symbol.for('wren.fingerprint');
  var SOURCES = Symbol.for('wren.sources');
  var nativeToString = Function.prototype.toString;

  function each(list, fn) {
    for (var i = 0; i < list.length; i++) fn(list[i], i);
  }

  // Every shim answers Function.prototype.toString with the source of what it replaced. The map
  // is shared with the other privacy scripts of this realm; whichever runs first installs the
  // toString wrapper, which itself reads as native.
  // A child realm (an about:blank frame, a popup) shares this realm's map: the shims put into it
  // are functions of this realm, whose own toString is this realm's, so both must know them.
  var homeRealm = window;
  function sourcesOf(window) {
    var map = window[SOURCES];
    if (map) return map;
    map = window === homeRealm ? new WeakMap() : sourcesOf(homeRealm);
    try {
      defineProperty(window, SOURCES, { value: map });
      var FunctionPrototype = window.Function.prototype;
      var toString = FunctionPrototype.toString;
      var masked = function toString() {
        var text = apply(weakGet, map, [this]);
        return text !== undefined ? text : apply(nativeToString, this, []);
      };
      apply(weakSet, map, [masked, apply(nativeToString, toString, [])]);
      defineProperty(FunctionPrototype, 'toString', { value: masked, writable: true, configurable: true });
    } catch (ignored) {}
    return map;
  }

  function harden(window) {
    if (window[MARK]) return;
    try { defineProperty(window, MARK, { value: true }); } catch (ignored) { return; }
    var sources = sourcesOf(window);
    var document = window.document;
    var location = window.location;
    var HTMLCanvasElement = window.HTMLCanvasElement;
    var OffscreenCanvas = window.OffscreenCanvas;
    var CanvasRenderingContext2D = window.CanvasRenderingContext2D;
    var OffscreenCanvasRenderingContext2D = window.OffscreenCanvasRenderingContext2D;
    var WebGLRenderingContext = window.WebGLRenderingContext;
    var WebGL2RenderingContext = window.WebGL2RenderingContext;
    var AudioBuffer = window.AudioBuffer;
    var AnalyserNode = window.AnalyserNode;
    var Navigator = window.Navigator;
    var Screen = window.Screen;
    var StorageManager = window.StorageManager;
    var URL = window.URL;

    // What a read returned is measured with the engine's own getters, taken now. A page can
    // shadow `length`, `width` or `data` with its own property, or replace the prototype's
    // getter, and point the noise at a decoy or make the copy fail; it cannot reach these.
    function getterOf(proto, name) {
      var descriptor = proto && getOwn(proto, name);
      return descriptor && descriptor.get;
    }
    var TypedArrayProto = window.Uint8Array && getPrototypeOf(window.Uint8Array.prototype);
    var lengthGetter = getterOf(TypedArrayProto, 'length');
    var kindGetter = getterOf(TypedArrayProto, Symbol.toStringTag);
    var bufferGetter = getterOf(TypedArrayProto, 'buffer');
    var byteOffsetGetter = getterOf(TypedArrayProto, 'byteOffset');
    var dataViewBuffer = window.DataView && getterOf(window.DataView.prototype, 'buffer');
    var dataViewOffset = window.DataView && getterOf(window.DataView.prototype, 'byteOffset');
    var dataViewLength = window.DataView && getterOf(window.DataView.prototype, 'byteLength');
    var arrayBufferLength = window.ArrayBuffer && getterOf(window.ArrayBuffer.prototype, 'byteLength');
    var NativeBytes = window.Uint8Array;
    var imageDataGetter = window.ImageData && getterOf(window.ImageData.prototype, 'data');
    var channelCountGetter = AudioBuffer && getterOf(AudioBuffer.prototype, 'numberOfChannels');
    var ELEMENT_SIZES = {
      Int8Array: 1, Uint8Array: 1, Uint8ClampedArray: 1, Int16Array: 2, Uint16Array: 2,
      Float16Array: 2, Int32Array: 4, Uint32Array: 4, Float32Array: 4, Float64Array: 8,
      BigInt64Array: 8, BigUint64Array: 8
    };

    /** The typed array's kind, or undefined for anything that is not one. */
    function kindOf(view) {
      try { return apply(kindGetter, view, []); } catch (ignored) { return undefined; }
    }
    function lengthOf(view) {
      return apply(lengthGetter, view, []);
    }
    function isFloatKind(kind) {
      return kind === 'Float32Array' || kind === 'Float64Array' || kind === 'Float16Array';
    }
    /** The bytes of elements [start, end) of a typed array, as an array this script made. */
    function bytesOf(view, kind, start, end) {
      var size = ELEMENT_SIZES[kind];
      var offset = apply(byteOffsetGetter, view, []) + start * size;
      return construct(NativeBytes, [apply(bufferGetter, view, []), offset, (end - start) * size]);
    }

    // The session key: two words no page sees. A site learns its own noise, which is a keyed
    // hash of the key and the site, and nothing in that answer leads back to the key or to
    // another site's noise. (An invertible mix would have handed a page the key through one
    // observed read, and with it every other site's noise: the one shared identifier that
    // per-site noise exists to avoid.)
    var KEY0 = __SEED__ | 0;
    var KEY1 = __SEED2__ | 0;

    function rotl(x, b) {
      return (x << b) | (x >>> (32 - b));
    }

    // HalfSipHash-2-4: the authors' 32-bit SipHash, a keyed pseudorandom function over the
    // words of a short message. A few hundred integer operations per call.
    function siphash(k0, k1, words) {
      var v0 = k0 | 0;
      var v1 = k1 | 0;
      var v2 = (k0 ^ 0x6c796765) | 0;
      var v3 = (k1 ^ 0x74656462) | 0;
      function sipround() {
        v0 = (v0 + v1) | 0; v1 = rotl(v1, 5); v1 ^= v0; v0 = rotl(v0, 16);
        v2 = (v2 + v3) | 0; v3 = rotl(v3, 8); v3 ^= v2;
        v0 = (v0 + v3) | 0; v3 = rotl(v3, 7); v3 ^= v0;
        v2 = (v2 + v1) | 0; v1 = rotl(v1, 13); v1 ^= v2; v2 = rotl(v2, 16);
      }
      for (var i = 0; i < words.length; i++) {
        var m = words[i] | 0;
        v3 ^= m; sipround(); sipround(); v0 ^= m;
      }
      var tail = ((words.length * 4) & 255) << 24;
      v3 ^= tail; sipround(); sipround(); v0 ^= tail;
      v2 ^= 255; sipround(); sipround(); sipround(); sipround();
      return (v1 ^ v3) >>> 0;
    }

    // A string as the words the hash takes, four characters to a word.
    function wordsOf(text) {
      var words = [];
      for (var i = 0; i < text.length; i += 4) {
        words.push((text.charCodeAt(i) & 255) |
          ((text.charCodeAt(i + 1) & 255) << 8) |
          ((text.charCodeAt(i + 2) & 255) << 16) |
          ((text.charCodeAt(i + 3) & 255) << 24));
      }
      return words;
    }

    function hash(value) {
      var h = 2166136261;
      for (var i = 0; i < value.length; i++) {
        h ^= apply(charCodeAt, value, [i]);
        h = imul(h, 16777619) >>> 0;
      }
      return h >>> 0;
    }

    // Public mixing of public values, for folding a read's shape into one word; the key never
    // passes through it.
    function mix(a, b) {
      var h = ((a >>> 0) ^ 0x9e3779b9) >>> 0;
      h = imul(h ^ (b >>> 0), 0x85ebca6b) >>> 0;
      h ^= h >>> 13;
      h = imul(h, 0xc2b2ae35) >>> 0;
      h ^= h >>> 16;
      return h >>> 0;
    }

    // The site at the top of the page, not the frame's own origin: an embedded tracker then
    // sees a different canvas on every site it is embedded in, which is what stops it joining
    // visits up. Chromium exposes the ancestor chain; the referrer is the fallback for frames
    // that cannot see it.
    function topSite() {
      try {
        var ancestors = location.ancestorOrigins;
        if (ancestors && ancestors.length) return ancestors[ancestors.length - 1];
      } catch (ignored) {}
      try {
        if (window.top === window) return location.origin;
      } catch (ignored) {}
      try {
        if (document.referrer) return new URL(document.referrer).origin;
      } catch (ignored) {}
      return location.origin;
    }

    var SITE_WORDS = wordsOf(String(topSite()));

    /**
     * A small deterministic generator for one read, started from the keyed hash of the site
     * and the read's shape and content. Its stream can be read off the output, and gives away
     * exactly that one stream.
     */
    function Noise(salt, content) {
      var count = SITE_WORDS.length;
      var words = new Array(count + 2);
      for (var w = 0; w < count; w++) words[w] = SITE_WORDS[w];
      words[count] = salt | 0;
      words[count + 1] = content | 0;
      this.s = (siphash(KEY0, KEY1, words) || 1) >>> 0;
    }
    Noise.prototype.next = function () {
      var s = this.s;
      s ^= s << 13;
      s >>>= 0;
      s ^= s >>> 17;
      s ^= s << 5;
      s >>>= 0;
      this.s = s;
      return s / 4294967296;
    };

    function define(target, name, getter) {
      try {
        var native = getOwn(target, name);
        Object.defineProperty(target, name, { get: getter, configurable: true, enumerable: true });
        apply(weakSet, sources, [getter, native && native.get
          ? apply(nativeToString, native.get, [])
          : 'function get ' + name + '() { [native code] }']);
      } catch (ignored) {
        // Some properties are locked down; skip rather than throw during page start-up.
      }
    }

    function wrap(owner, name, replacement) {
      try {
        var original = owner[name];
        if (typeof original !== 'function') return;
        var wrapped = replacement(original);
        owner[name] = wrapped;
        // A page checking toString() sees the native source, through the shared toString.
        apply(weakSet, sources, [wrapped, apply(nativeToString, original, [])]);
      } catch (ignored) {
        // Never let hardening break the page.
      }
    }

    // --- Canvas ------------------------------------------------------------------------
    // Every byte of the image folded into one word: cheap, and any flipped bit changes it.
    function contentHash(data, start, end) {
      var h = 2166136261;
      for (var i = start; i < end; i++) h = imul(h ^ (data[i] | 0), 16777619);
      return h >>> 0;
    }

    // Float samples hashed by their scaled value: enough to tell two readings apart.
    function floatHash(data, start, end) {
      var h = 2166136261;
      for (var i = start; i < end; i++) h = imul(h ^ ((data[i] * 1e6) | 0), 16777619);
      return h >>> 0;
    }

    // A handful of pixels per read get one low bit flipped: invisible, and enough to change any
    // hash of the image. Which pixels depends on the key, the read's geometry and the image
    // itself: a mask that depended on geometry alone could be read off a blank canvas of the
    // same size and subtracted from the real one. A read of fewer than four bytes - one byte
    // at a time through a pixel buffer, say - is touched too, or the bytes would come back
    // exact one by one.
    function perturbPixels(data, salt) {
      var length = lengthOf(data);
      if (length < 1) return;
      var noise = new Noise(salt ^ length, contentHash(data, 0, length));
      var touches = length >> 11;
      if (touches < 1) touches = 1;
      else if (touches > 48) touches = 48;
      for (var i = 0; i < touches; i++) {
        var index = length >= 4
          ? ((((noise.next() * (length >> 2)) | 0) << 2) + ((noise.next() * 3) | 0)) >>> 0
          : (noise.next() * length) | 0;
        data[index] = data[index] ^ 1;
      }
    }

    // Floating-point readbacks get a nudge in the last places instead of a flipped bit, which
    // on a float would be a different number altogether.
    function perturbFloats(data, salt, start, end) {
      var length = end - start;
      if (length < 1) return;
      var noise = new Noise(salt ^ length, floatHash(data, start, end));
      var touches = length >> 9;
      if (touches < 1) touches = 1;
      else if (touches > 48) touches = 48;
      for (var i = 0; i < touches; i++) {
        var index = start + ((noise.next() * length) | 0);
        var value = data[index];
        // Relative to the value: a fixed step smaller than a float's spacing at that size
        // would round away and leave the read exact.
        var scale = value < 0 ? -value : value;
        if (scale < 1e-3) scale = 1e-3;
        data[index] = value + (noise.next() - 0.5) * 1e-6 * scale;
      }
    }

    // The part of a destination a readback wrote: elements [start, end), from the element
    // offset on, as many as the call wrote. What lies outside it is the page's, left alone.
    function Range(view, kind, start, end) {
      this.view = view;
      this.kind = kind;
      this.start = start;
      this.end = end;
    }
    function written(view, offset, count) {
      var kind = kindOf(view);
      if (!kind || !ELEMENT_SIZES[kind]) return null;
      var length = lengthOf(view);
      var start = offset > 0 ? floor(offset) : 0;
      var end = count > 0 ? start + floor(count) : length;
      if (end > length) end = length;
      if (start >= end) return null;
      return new Range(view, kind, start, end);
    }
    function perturbRange(range, salt) {
      if (isFloatKind(range.kind)) perturbFloats(range.view, salt, range.start, range.end);
      else perturbPixels(bytesOf(range.view, range.kind, range.start, range.end), salt);
    }
    function blankRange(range) {
      apply(fill, bytesOf(range.view, range.kind, range.start, range.end), [0]);
    }

    function readSalt(sx, sy, sw, sh) {
      return mix(sx | 0, mix(sy | 0, mix(sw | 0, sh | 0)));
    }

    // Taken before the reads below are wrapped: the copies an export is made from are read
    // with these, and noised once, by the export.
    var native2dGetImageData = window.CanvasRenderingContext2D &&
      CanvasRenderingContext2D.prototype.getImageData;
    var nativeOffscreenGetImageData = window.OffscreenCanvasRenderingContext2D &&
      OffscreenCanvasRenderingContext2D.prototype.getImageData;

    function wrapGetImageData(prototype) {
      wrap(prototype, 'getImageData', function (original) {
        return function (sx, sy, sw, sh) {
          var result = apply(original, this, arguments);
          // Read through the engine's getter: an ImageData.prototype.data the page replaced
          // would hand the blank to a decoy and the real pixels to the page.
          var range = written(apply(imageDataGetter, result, []), 0, 0);
          if (range) {
            if (TOR) blankRange(range);
            else perturbPixels(range.view, readSalt(sx, sy, sw, sh));
          }
          return result;
        };
      });
    }

    if (window.CanvasRenderingContext2D) wrapGetImageData(CanvasRenderingContext2D.prototype);
    if (window.OffscreenCanvasRenderingContext2D) {
      wrapGetImageData(OffscreenCanvasRenderingContext2D.prototype);
    }

    // Exports come from a copy carrying the same noise a full read would: the canvas the page
    // drew on is never written to. A tainted canvas cannot be copied; the original call then
    // throws exactly as it would have without us.
    // Everything a copy is made with is the engine's own, taken now: a page that replaced
    // createElement, getContext, drawImage or putImageData could otherwise make the copy fail
    // or come out unnoised.
    function canvasKit(Canvas, Context, getImageData, make) {
      if (!Canvas || !Context || !getImageData) return null;
      return {
        width: getterOf(Canvas.prototype, 'width'),
        height: getterOf(Canvas.prototype, 'height'),
        getContext: Canvas.prototype.getContext,
        drawImage: Context.prototype.drawImage,
        getImageData: getImageData,
        putImageData: Context.prototype.putImageData,
        make: make
      };
    }
    var createElement = document && window.Document && window.Document.prototype.createElement;
    var widthSetter = HTMLCanvasElement && getOwn(HTMLCanvasElement.prototype, 'width');
    var heightSetter = HTMLCanvasElement && getOwn(HTMLCanvasElement.prototype, 'height');
    var elementKit = canvasKit(HTMLCanvasElement, CanvasRenderingContext2D, native2dGetImageData, function (width, height) {
      var canvas = apply(createElement, document, ['canvas']);
      apply(widthSetter.set, canvas, [width]);
      apply(heightSetter.set, canvas, [height]);
      return canvas;
    });
    var offscreenKit = canvasKit(OffscreenCanvas, OffscreenCanvasRenderingContext2D, nativeOffscreenGetImageData, function (width, height) {
      return construct(OffscreenCanvas, [width, height]);
    });

    // Exports come from a copy carrying the same noise a full read would: the canvas the page
    // drew on is never written to. Under Tor the copy is an empty canvas of the same size.
    function noisyCopy(kit, source, width, height) {
      var copy = kit.make(width, height);
      if (TOR) return copy;
      var context = apply(kit.getContext, copy, ['2d']);
      apply(kit.drawImage, context, [source, 0, 0]);
      var image = apply(kit.getImageData, context, [0, 0, width, height]);
      perturbPixels(apply(imageDataGetter, image, []), readSalt(0, 0, width, height));
      apply(kit.putImageData, context, [image, 0, 0]);
      return copy;
    }

    // A copy that cannot be made never means exporting the original: that would hand over
    // exactly the pixels this exists to change. A canvas that cannot be copied because it is
    // tainted throws what the engine throws for it; anything else throws the same.
    function exportFromCopy(original, kit, method) {
      return function () {
        var width;
        var height;
        try {
          width = apply(kit.width, this, []);
          height = apply(kit.height, this, []);
        } catch (notACanvas) {
          return apply(original, this, arguments);
        }
        if (!(width > 0 && height > 0)) return apply(original, this, arguments);
        var copy = null;
        try {
          copy = noisyCopy(kit, this, width, height);
        } catch (ignored) {}
        if (!copy) {
          throw new window.DOMException("Failed to execute '" + method + "': Tainted canvases may not be exported.", 'SecurityError');
        }
        return apply(original, copy, arguments);
      };
    }

    if (elementKit && imageDataGetter) {
      wrap(HTMLCanvasElement.prototype, 'toDataURL', function (original) {
        return exportFromCopy(original, elementKit, 'toDataURL');
      });
      wrap(HTMLCanvasElement.prototype, 'toBlob', function (original) {
        return exportFromCopy(original, elementKit, 'toBlob');
      });
    }
    if (offscreenKit && imageDataGetter) {
      wrap(OffscreenCanvas.prototype, 'convertToBlob', function (original) {
        return exportFromCopy(original, offscreenKit, 'convertToBlob');
      });
    }

    // --- WebGL -------------------------------------------------------------------------
    // The pixel readback is a canvas read by another name. Only the range the call wrote is
    // touched, so a pooled destination keeps what was in the rest of it.
    // How many components a pixel of each readback format has, and which types pack a whole
    // pixel into one element.
    var COMPONENTS = {
      0x1906: 1, 0x1909: 1, 0x1903: 1, 0x8D94: 1, 0x190A: 2, 0x8227: 2, 0x8228: 2,
      0x1907: 3, 0x8D98: 3, 0x1908: 4, 0x8D99: 4
    };
    var PACKED_TYPES = { 0x8363: 1, 0x8033: 1, 0x8034: 1, 0x8368: 1, 0x8C3B: 1, 0x8C3E: 1 };
    var PACK_ALIGNMENT = 0x0D05;
    var PACK_ROW_LENGTH = 0x0D02;
    var PACK_SKIP_ROWS = 0x0D03;
    var PACK_SKIP_PIXELS = 0x0D04;

    // Elements a readPixels call wrote from the destination's start, rows padded as the pack
    // parameters say: the noise goes there and nowhere else, so a pooled destination keeps
    // the rest of what it held, and an oversized one still gets noise on what was read.
    // The row and skip parameters exist in WebGL 2 only; asked of WebGL 1 they would raise an
    // error the page can read back with getError().
    function pixelsWritten(context, getParameter, webgl2, width, height, format, type, kind) {
      var size = ELEMENT_SIZES[kind];
      var perPixel = PACKED_TYPES[type] ? size : (COMPONENTS[format] || 4) * size;
      var align = apply(getParameter, context, [PACK_ALIGNMENT]) || 4;
      var rowLength = webgl2 ? apply(getParameter, context, [PACK_ROW_LENGTH]) || 0 : 0;
      var skipRows = webgl2 ? apply(getParameter, context, [PACK_SKIP_ROWS]) || 0 : 0;
      var skipPixels = webgl2 ? apply(getParameter, context, [PACK_SKIP_PIXELS]) || 0 : 0;
      var rowBytes = (rowLength > 0 ? rowLength : width) * perPixel;
      var stride = floor((rowBytes + align - 1) / align) * align;
      var bytes = (skipRows + height - 1) * stride + (skipPixels + width) * perPixel;
      return floor((bytes + size - 1) / size);
    }

    // Buffers a readPixels has packed pixels into, and what each context has bound where: a
    // pixel buffer read back through COPY_READ_BUFFER, or copied into another buffer first,
    // is still pixels.
    var packedBuffers = weakHas ? new WeakSet() : null;
    var bindings = new WeakMap();
    var PIXEL_PACK_BUFFER = 0x88EB;

    function boundTo(context, target) {
      var bound = apply(weakGet, bindings, [context]);
      return bound ? bound['t' + target] : undefined;
    }
    function isPacked(buffer) {
      return !!buffer && !!packedBuffers && apply(weakHas, packedBuffers, [buffer]);
    }

    function trackBindings(prototype) {
      function remember(context, target, buffer) {
        var bound = apply(weakGet, bindings, [context]);
        if (!bound) {
          bound = {};
          apply(weakSet, bindings, [context, bound]);
        }
        bound['t' + (+target)] = buffer;
      }
      wrap(prototype, 'bindBuffer', function (original) {
        return function (target, buffer) {
          var result = apply(original, this, arguments);
          try { remember(this, target, buffer); } catch (ignored) {}
          return result;
        };
      });
      each(['bindBufferBase', 'bindBufferRange'], function (name) {
        wrap(prototype, name, function (original) {
          return function (target, index, buffer) {
            var result = apply(original, this, arguments);
            // An indexed binding also sets the target's generic one.
            try { remember(this, target, buffer); } catch (ignored) {}
            return result;
          };
        });
      });
      wrap(prototype, 'copyBufferSubData', function (original) {
        return function (readTarget, writeTarget) {
          var result = apply(original, this, arguments);
          try {
            if (packedBuffers && isPacked(boundTo(this, +readTarget))) {
              var target = boundTo(this, +writeTarget);
              if (target) apply(weakAdd, packedBuffers, [target]);
            }
          } catch (ignored) {}
          return result;
        };
      });
    }

    function wrapReadPixels(prototype, webgl2) {
      var getParameter = prototype.getParameter;
      wrap(prototype, 'readPixels', function (original) {
        return function (x, y, width, height, format, type, pixels, dstOffset) {
          var result = apply(original, this, arguments);
          try {
            if (typeof pixels === 'number') {
              // Into the bound pixel buffer, read later through getBufferSubData.
              var packed = boundTo(this, PIXEL_PACK_BUFFER);
              if (packed && packedBuffers) apply(weakAdd, packedBuffers, [packed]);
              return result;
            }
            var kind = kindOf(pixels);
            if (!kind || !ELEMENT_SIZES[kind] || !(width > 0 && height > 0)) return result;
            var count = pixelsWritten(this, getParameter, webgl2, width, height, +format, +type, kind);
            var range = written(pixels, dstOffset, count);
            if (range) {
              if (TOR) blankRange(range);
              else perturbRange(range, readSalt(x, y, width, height));
            }
          } catch (ignored) {}
          return result;
        };
      });
    }

    // The unmasked renderer names the exact GPU *and* the driver build, which is close to a
    // serial number. The GPU family is what a page may legitimately need for a workaround, so
    // it stays; the build suffix goes. Every phone with that GPU then answers the same way,
    // which is what Chrome users on the same phone look like too.
    var UNMASKED_VENDOR = 37445;
    var UNMASKED_RENDERER = 37446;

    function trimRenderer(value) {
      if (typeof value !== 'string') return value;
      // Everything from the driver build onwards goes; the parentheses it opened are closed again.
      var trimmed = apply(stringReplace, value, [/\s+V@.*$/, '']);
      trimmed = apply(stringReplace, trimmed, [/\s*\(GIT@.*$/, '']);
      trimmed = apply(stringReplace, trimmed, [/[,\s]+$/, '']);
      var open = (apply(stringMatch, trimmed, [/\(/g]) || []).length;
      var close = (apply(stringMatch, trimmed, [/\)/g]) || []).length;
      while (close < open) {
        trimmed += ')';
        close++;
      }
      return trimmed;
    }

    function maskGetParameter(prototype) {
      wrap(prototype, 'getParameter', function (original) {
        return function (parameter) {
          var value = apply(original, this, arguments);
          // Compared as the engine compares it, as a number: "37446" asks the same question.
          if (+parameter === UNMASKED_RENDERER) return trimRenderer(value);
          return value;
        };
      });
    }

    // A readback through a pixel buffer object lands in getBufferSubData instead. Under Tor
    // every buffer read answers blank, since pixels can be copied into any buffer first;
    // elsewhere a buffer counts as pixels when it is bound as the pixel-pack buffer or a
    // readPixels has packed into it, so a computation's own buffers come back as computed.
    function wrapBufferRead(prototype) {
      wrap(prototype, 'getBufferSubData', function (original) {
        return function (target, srcOffset, out, dstOffset, length) {
          var result = apply(original, this, arguments);
          try {
            var range = written(out, dstOffset, length);
            if (range) {
              var asked = +target;
              if (TOR) blankRange(range);
              else if (asked === PIXEL_PACK_BUFFER || isPacked(boundTo(this, asked))) {
                perturbRange(range, readSalt(srcOffset | 0, 0, range.end - range.start, asked));
              }
            }
          } catch (ignored) {}
          return result;
        };
      });
    }

    if (window.WebGLRenderingContext) {
      maskGetParameter(WebGLRenderingContext.prototype);
      wrapReadPixels(WebGLRenderingContext.prototype, false);
    }
    if (window.WebGL2RenderingContext) {
      maskGetParameter(WebGL2RenderingContext.prototype);
      trackBindings(WebGL2RenderingContext.prototype);
      wrapReadPixels(WebGL2RenderingContext.prototype, true);
      wrapBufferRead(WebGL2RenderingContext.prototype);
    }

    // A decoded video frame copied out is a canvas read of the decoder: the exact pixels a
    // device produces for a known stream name its hardware decoder.
    // The destination's bytes are found with the engine's getters and viewed through this
    // script's own Uint8Array: a page's global Uint8Array, or a destination with a lying
    // byteLength, must not send the noise elsewhere.
    function destinationBytes(destination) {
      var kind = kindOf(destination);
      if (kind && ELEMENT_SIZES[kind]) return bytesOf(destination, kind, 0, lengthOf(destination));
      try {
        var buffer = apply(dataViewBuffer, destination, []);
        return construct(NativeBytes, [buffer, apply(dataViewOffset, destination, []), apply(dataViewLength, destination, [])]);
      } catch (notADataView) {}
      apply(arrayBufferLength, destination, []);
      return construct(NativeBytes, [destination]);
    }
    var codedWidth = window.VideoFrame && getterOf(window.VideoFrame.prototype, 'codedWidth');
    var codedHeight = window.VideoFrame && getterOf(window.VideoFrame.prototype, 'codedHeight');

    if (window.VideoFrame && window.VideoFrame.prototype.copyTo) {
      wrap(VideoFrame.prototype, 'copyTo', function (original) {
        return function (destination) {
          var frame = this;
          var view = null;
          var width = 0;
          var height = 0;
          try {
            view = destinationBytes(destination);
            width = apply(codedWidth, frame, []);
            height = apply(codedHeight, frame, []);
          } catch (ignored) {}
          var promise = apply(original, this, arguments);
          return apply(then, promise, [function (layout) {
            try {
              if (view) {
                if (TOR) apply(fill, view, [0]);
                else perturbPixels(view, readSalt(0, 0, width, height));
              }
            } catch (ignored) {}
            return layout;
          }]);
        };
      });
    }

    // --- Audio -------------------------------------------------------------------------
    // Rendered audio is read back through the buffer. Every sample moves, by an amount far
    // below hearing, from a stream keyed on the channel, the length and the samples themselves:
    // noise at a few fixed positions could be skipped, and noise that did not depend on the
    // signal could be read off a silent buffer and subtracted. A buffer is touched once;
    // reading it twice adds nothing.
    var noisedBuffers = weakHas ? new WeakSet() : null;

    function perturbSamples(data, salt) {
      var length = lengthOf(data);
      var noise = new Noise(salt ^ length, floatHash(data, 0, length));
      for (var i = 0; i < length; i++) {
        var value = data[i];
        var scale = value < 0 ? -value : value;
        if (scale < 1e-3) scale = 1e-3;
        data[i] = value + (noise.next() - 0.5) * 1e-6 * scale;
      }
    }

    // The channel count through the engine's getter, and the buffer marked only once every
    // channel is done: a count of zero from a replaced getter must not mark it noised.
    function perturbBuffer(buffer, nativeGetChannelData) {
      if (!noisedBuffers || apply(weakHas, noisedBuffers, [buffer])) return;
      var channels = apply(channelCountGetter, buffer, []);
      for (var channel = 0; channel < channels; channel++) {
        perturbSamples(apply(nativeGetChannelData, buffer, [channel]), channel);
      }
      apply(weakAdd, noisedBuffers, [buffer]);
    }

    if (window.AudioBuffer) {
      var nativeGetChannelData = AudioBuffer.prototype.getChannelData;
      wrap(AudioBuffer.prototype, 'getChannelData', function (original) {
        return function () {
          try {
            perturbBuffer(this, nativeGetChannelData);
          } catch (ignored) {}
          return apply(original, this, arguments);
        };
      });
      wrap(AudioBuffer.prototype, 'copyFromChannel', function (original) {
        return function () {
          try {
            perturbBuffer(this, nativeGetChannelData);
          } catch (ignored) {}
          return apply(original, this, arguments);
        };
      });
    }

    if (window.AnalyserNode) {
      function wrapAnalyser(name, scale) {
        wrap(AnalyserNode.prototype, name, function (original) {
          return function (array) {
            apply(original, this, arguments);
            try {
              var length = lengthOf(array);
              var noise = new Noise(hash(name) ^ length, floatHash(array, 0, length));
              for (var i = 0; i < length; i++) {
                array[i] = array[i] + (noise.next() - 0.5) * scale;
              }
            } catch (ignored) {}
          };
        });
      }
      wrapAnalyser('getFloatFrequencyData', 1e-4);
      wrapAnalyser('getFloatTimeDomainData', 1e-4);
      // The byte reads of the same data: one step of the 256 either way, kept in range.
      function wrapByteAnalyser(name) {
        wrap(AnalyserNode.prototype, name, function (original) {
          return function (array) {
            apply(original, this, arguments);
            try {
              var length = lengthOf(array);
              var noise = new Noise(hash(name) ^ length, contentHash(array, 0, length));
              for (var i = 0; i < length; i++) {
                var value = array[i] + (noise.next() < 0.5 ? -1 : 1);
                array[i] = value < 0 ? 1 : value > 255 ? 254 : value;
              }
            } catch (ignored) {}
          };
        });
      }
      wrapByteAnalyser('getByteFrequencyData');
      wrapByteAnalyser('getByteTimeDomainData');
    }

    // --- Navigator ---------------------------------------------------------------------
    // Rounded to values a large number of real devices share, rather than randomised: an
    // impossible core count would itself stand out. Defined on the prototype, where the real
    // getters live, so reading the prototype's descriptor finds these and not the originals.
    if (window.Navigator) {
      define(Navigator.prototype, 'hardwareConcurrency', function () { return 4; });
      define(Navigator.prototype, 'deviceMemory', function () { return 4; });
      define(Navigator.prototype, 'webdriver', function () { return false; });
      define(Navigator.prototype, 'maxTouchPoints', function () { return 5; });
      try {
        if (Navigator.prototype.getBattery) delete Navigator.prototype.getBattery;
      } catch (ignored) {
        // Non-configurable on this engine; the API is already gated behind a secure context.
      }
    }

    // --- Device shape -------------------------------------------------------------------
    // The space the system bars take and the exact storage quota are readable, neither is
    // needed to lay out a page, and together they split one phone model into many. The viewport
    // itself is left alone: pages have to lay out in it.
    if (window.Screen) {
      var screenWidth = Object.getOwnPropertyDescriptor(Screen.prototype, 'width');
      var screenHeight = Object.getOwnPropertyDescriptor(Screen.prototype, 'height');
      if (screenWidth && screenWidth.get && screenHeight && screenHeight.get) {
        define(Screen.prototype, 'availWidth', function () { return apply(screenWidth.get, this, []); });
        define(Screen.prototype, 'availHeight', function () { return apply(screenHeight.get, this, []); });
        define(Screen.prototype, 'availTop', function () { return 0; });
        define(Screen.prototype, 'availLeft', function () { return 0; });
      }
    }

    if (window.StorageManager) {
      var GIB = 1073741824;
      wrap(StorageManager.prototype, 'estimate', function (original) {
        return function () {
          return apply(then, apply(original, this, arguments), [function (estimate) {
            try {
              estimate.quota = max(GIB, round(estimate.quota / GIB) * GIB);
            } catch (ignored) {
              // Frozen result object; the unrounded quota is still a weak signal on its own.
            }
            return estimate;
          }]);
        };
      });
    }

    hardenFrames(window);
  }

  // A child frame's window exists, with every native intact, before the engine injects this
  // script into it; the accessors a page uses to reach it apply the same treatment first.
  function hardenFrames(window) {
    // A popup from window.open() starts as a blank document of the opener's own origin that the
    // opener scripts directly; the engine injects nothing into it. It gets what a frame gets.
    var nativeOpen = window.open;
    if (typeof nativeOpen === 'function') {
      var open = function open() {
        var opened = apply(nativeOpen, this, arguments);
        try {
          if (opened) harden(opened);
        } catch (ignored) {
          // Another origin: the engine injects into that document itself.
        }
        return opened;
      };
      try {
        defineProperty(window, 'open', { value: open, writable: true, enumerable: true, configurable: true });
      apply(weakSet, sourcesOf(window), [open, apply(nativeToString, nativeOpen, [])]);
      } catch (ignored) {}
    }
    each(['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement'], function (kind) {
      var element = window[kind];
      var proto = element && element.prototype;
      if (!proto) return;
      each(['contentWindow', 'contentDocument'], function (name) {
        var native = getOwn(proto, name);
        if (!native || !native.get) return;
        var get = native.get;
        try {
          defineProperty(proto, name, {
            get: function () {
              var value = apply(get, this, []);
              try {
                var child = name === 'contentWindow' ? value : value && value.defaultView;
                if (child) harden(child);
              } catch (ignored) {}
              return value;
            },
            set: native.set,
            enumerable: native.enumerable,
            configurable: true
          });
        } catch (ignored) {}
      });
    });
  }

  harden(window);
})();
