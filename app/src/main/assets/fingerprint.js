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
  var subarray = Uint8Array.prototype.subarray;
  var then = Promise.prototype.then;
  var weakGet = WeakMap.prototype.get;
  var weakSet = WeakMap.prototype.set;
  var weakHas = typeof WeakSet === 'function' ? WeakSet.prototype.has : null;
  var weakAdd = typeof WeakSet === 'function' ? WeakSet.prototype.add : null;
  var stringReplace = String.prototype.replace;
  var stringMatch = String.prototype.match;
  var getPrototypeOf = Object.getPrototypeOf;
  var Float32Proto = typeof Float32Array === 'function' ? Float32Array.prototype : null;
  var Float64Proto = typeof Float64Array === 'function' ? Float64Array.prototype : null;
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
  function sourcesOf(window) {
    var map = window[SOURCES];
    if (map) return map;
    map = new WeakMap();
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
        h ^= value.charCodeAt(i);
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
      var words = SITE_WORDS.slice();
      words.push(salt | 0, content | 0);
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
    function contentHash(data) {
      var h = 2166136261;
      for (var i = 0; i < data.length; i++) h = imul(h ^ (data[i] | 0), 16777619);
      return h >>> 0;
    }

    // Float samples hashed by their scaled value: enough to tell two readings apart.
    function floatHash(data) {
      var h = 2166136261;
      for (var i = 0; i < data.length; i++) h = imul(h ^ ((data[i] * 1e6) | 0), 16777619);
      return h >>> 0;
    }

    function isFloatView(view) {
      var proto = getPrototypeOf(view);
      return proto === Float32Proto || proto === Float64Proto;
    }

    // A handful of pixels per read get one low bit flipped: invisible, and enough to change any
    // hash of the image. Which pixels depends on the key, the read's geometry and the image
    // itself: a mask that depended on geometry alone could be read off a blank canvas of the
    // same size and subtracted from the real one. A read of fewer than four bytes - one byte
    // at a time through a pixel buffer, say - is touched too, or the bytes would come back
    // exact one by one.
    function perturbPixels(data, salt) {
      var length = data.length;
      if (length < 1) return;
      var noise = new Noise(salt ^ length, contentHash(data));
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
    function perturbFloats(data, salt) {
      var length = data.length;
      if (length < 1) return;
      var noise = new Noise(salt ^ length, floatHash(data));
      var touches = length >> 9;
      if (touches < 1) touches = 1;
      else if (touches > 48) touches = 48;
      for (var i = 0; i < touches; i++) {
        var index = (noise.next() * length) | 0;
        var value = data[index];
        // Relative to the value: a fixed step smaller than a float's spacing at that size
        // would round away and leave the read exact.
        var scale = value < 0 ? -value : value;
        if (scale < 1e-3) scale = 1e-3;
        data[index] = value + (noise.next() - 0.5) * 1e-6 * scale;
      }
    }

    function perturbView(view, salt) {
      if (isFloatView(view)) perturbFloats(view, salt);
      else perturbPixels(view, salt);
    }

    function blank(view) {
      apply(fill, view, [0]);
    }

    // The part of a destination the readback wrote: from the element offset on, as many
    // elements as asked for. What lies outside it is the page's, and is left alone.
    function written(view, offset, length) {
      if (!view || typeof view.length !== 'number' || typeof view.BYTES_PER_ELEMENT !== 'number') {
        return null;
      }
      var start = offset > 0 ? offset | 0 : 0;
      var end = length > 0 ? start + (length | 0) : view.length;
      if (end > view.length) end = view.length;
      if (start >= end) return null;
      if (start === 0 && end === view.length) return view;
      return apply(subarray, view, [start, end]);
    }

    function readSalt(sx, sy, sw, sh) {
      return mix(sx | 0, mix(sy | 0, mix(sw | 0, sh | 0)));
    }

    var native2dGetImageData = window.CanvasRenderingContext2D &&
      CanvasRenderingContext2D.prototype.getImageData;

    function wrapGetImageData(prototype) {
      wrap(prototype, 'getImageData', function (original) {
        return function (sx, sy, sw, sh) {
          var result = apply(original, this, arguments);
          try {
            if (TOR) blank(result.data);
            else perturbPixels(result.data, readSalt(sx, sy, sw, sh));
          } catch (ignored) {
            // Detached or cross-origin canvas; leave as-is.
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
    function noisyCopy(source, makeCanvas) {
      var width = source.width;
      var height = source.height;
      if (!(width > 0 && height > 0) || !native2dGetImageData) return null;
      var copy = makeCanvas(width, height);
      // Under Tor the export is an empty canvas of the same size: nothing drawn, nothing read.
      if (TOR) return copy;
      var context = copy.getContext('2d');
      if (!context) return null;
      context.drawImage(source, 0, 0);
      var image = apply(native2dGetImageData, context, [0, 0, width, height]);
      perturbPixels(image.data, readSalt(0, 0, width, height));
      context.putImageData(image, 0, 0);
      return copy;
    }

    function elementCanvas(width, height) {
      var canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      return canvas;
    }

    function exportFromCopy(original, makeCanvas) {
      return function () {
        var copy = null;
        try {
          copy = noisyCopy(this, makeCanvas);
        } catch (ignored) {
          // Tainted or detached; the original call on the real canvas decides what happens.
        }
        return apply(original, copy || this, arguments);
      };
    }

    if (window.HTMLCanvasElement) {
      wrap(HTMLCanvasElement.prototype, 'toDataURL', function (original) {
        return exportFromCopy(original, elementCanvas);
      });
      wrap(HTMLCanvasElement.prototype, 'toBlob', function (original) {
        return exportFromCopy(original, elementCanvas);
      });
    }
    if (window.OffscreenCanvas) {
      wrap(OffscreenCanvas.prototype, 'convertToBlob', function (original) {
        return exportFromCopy(original, function (width, height) {
          return new OffscreenCanvas(width, height);
        });
      });
    }

    // --- WebGL -------------------------------------------------------------------------
    // The pixel readback is a canvas read by another name. Only the range the call wrote is
    // touched, so a pooled destination keeps what was in the rest of it.
    function wrapReadPixels(prototype) {
      wrap(prototype, 'readPixels', function (original) {
        return function (x, y, width, height, format, type, pixels, dstOffset) {
          var result = apply(original, this, arguments);
          try {
            var view = written(pixels, dstOffset, 0);
            if (view) {
              if (TOR) blank(view);
              else perturbView(view, readSalt(x, y, width, height));
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
    // elsewhere only the pixel-pack buffer is treated as pixels, so a computation's own
    // buffers come back as computed.
    var PIXEL_PACK_BUFFER = 0x88EB;

    function wrapBufferRead(prototype) {
      wrap(prototype, 'getBufferSubData', function (original) {
        return function (target, srcOffset, out, dstOffset, length) {
          var result = apply(original, this, arguments);
          try {
            var view = written(out, dstOffset, length);
            if (view) {
              if (TOR) blank(view);
              else if (target === PIXEL_PACK_BUFFER) {
                perturbView(view, readSalt(srcOffset | 0, 0, view.length, target));
              }
            }
          } catch (ignored) {}
          return result;
        };
      });
    }

    if (window.WebGLRenderingContext) {
      maskGetParameter(WebGLRenderingContext.prototype);
      wrapReadPixels(WebGLRenderingContext.prototype);
    }
    if (window.WebGL2RenderingContext) {
      maskGetParameter(WebGL2RenderingContext.prototype);
      wrapReadPixels(WebGL2RenderingContext.prototype);
      wrapBufferRead(WebGL2RenderingContext.prototype);
    }

    // A decoded video frame copied out is a canvas read of the decoder: the exact pixels a
    // device produces for a known stream name its hardware decoder.
    if (window.VideoFrame && window.VideoFrame.prototype.copyTo) {
      wrap(VideoFrame.prototype, 'copyTo', function (original) {
        return function (destination) {
          var frame = this;
          var promise = apply(original, this, arguments);
          return apply(then, promise, [function (layout) {
            try {
              var view = destination instanceof Uint8Array
                ? destination
                : new Uint8Array(destination.buffer || destination, destination.byteOffset || 0, destination.byteLength);
              if (TOR) blank(view);
              else perturbPixels(view, readSalt(0, 0, frame.codedWidth || view.length, frame.codedHeight || 0));
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
      var noise = new Noise(salt ^ data.length, floatHash(data));
      for (var i = 0; i < data.length; i++) {
        var value = data[i];
        var scale = value < 0 ? -value : value;
        if (scale < 1e-3) scale = 1e-3;
        data[i] = value + (noise.next() - 0.5) * 1e-6 * scale;
      }
    }

    function perturbBuffer(buffer, nativeGetChannelData) {
      if (!noisedBuffers || apply(weakHas, noisedBuffers, [buffer])) return;
      apply(weakAdd, noisedBuffers, [buffer]);
      for (var channel = 0; channel < buffer.numberOfChannels; channel++) {
        perturbSamples(apply(nativeGetChannelData, buffer, [channel]), channel);
      }
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
              var noise = new Noise(hash(name) ^ array.length, floatHash(array));
              for (var i = 0; i < array.length; i++) {
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
              var noise = new Noise(hash(name) ^ array.length, contentHash(array));
              for (var i = 0; i < array.length; i++) {
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
