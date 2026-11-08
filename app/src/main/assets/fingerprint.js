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
 */
(function () {
  'use strict';

  var SESSION_SEED = __SEED__;

  function hash(value) {
    var h = 2166136261;
    for (var i = 0; i < value.length; i++) {
      h ^= value.charCodeAt(i);
      h = Math.imul(h, 16777619) >>> 0;
    }
    return h >>> 0;
  }

  function mix(a, b) {
    var h = ((a >>> 0) ^ 0x9e3779b9) >>> 0;
    h = Math.imul(h ^ (b >>> 0), 0x85ebca6b) >>> 0;
    h ^= h >>> 13;
    h = Math.imul(h, 0xc2b2ae35) >>> 0;
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

  var SEED = mix(SESSION_SEED, hash(String(topSite())));

  /** A small deterministic generator for one read, seeded by the seed and the read's shape. */
  function Noise(salt) {
    this.s = (mix(SEED, salt) || 1) >>> 0;
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
      Object.defineProperty(target, name, { get: getter, configurable: true, enumerable: true });
    } catch (ignored) {
      // Some properties are locked down; skip rather than throw during page start-up.
    }
  }

  function wrap(owner, name, replacement) {
    try {
      var original = owner[name];
      if (typeof original !== 'function') return;
      owner[name] = replacement(original);
      // A page checking toString() should not see obviously patched source.
      try {
        owner[name].toString = function () { return String(original); };
      } catch (ignored) {
        // Non-writable toString; harmless.
      }
    } catch (ignored) {
      // Never let hardening break the page.
    }
  }

  // --- Canvas ------------------------------------------------------------------------
  // A handful of pixels per read get one low bit flipped: invisible, and enough to change any
  // hash of the image. Which pixels depends only on the seed and the read's geometry.
  function perturbPixels(data, salt) {
    var pixels = data.length >> 2;
    if (pixels < 1) return;
    var noise = new Noise(salt ^ pixels);
    var touches = Math.max(1, Math.min(48, pixels >> 9));
    for (var i = 0; i < touches; i++) {
      var index = ((Math.floor(noise.next() * pixels) << 2) + Math.floor(noise.next() * 3)) >>> 0;
      data[index] = data[index] ^ 1;
    }
  }

  function readSalt(sx, sy, sw, sh) {
    return mix(sx | 0, mix(sy | 0, mix(sw | 0, sh | 0)));
  }

  var native2dGetImageData = window.CanvasRenderingContext2D &&
    CanvasRenderingContext2D.prototype.getImageData;

  function wrapGetImageData(prototype) {
    wrap(prototype, 'getImageData', function (original) {
      return function (sx, sy, sw, sh) {
        var result = original.apply(this, arguments);
        try {
          perturbPixels(result.data, readSalt(sx, sy, sw, sh));
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
    var context = copy.getContext('2d');
    if (!context) return null;
    context.drawImage(source, 0, 0);
    var image = native2dGetImageData.call(context, 0, 0, width, height);
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
      return original.apply(copy || this, arguments);
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
  // The pixel readback is a canvas read by another name.
  function wrapReadPixels(prototype) {
    wrap(prototype, 'readPixels', function (original) {
      return function (x, y, width, height, format, type, pixels) {
        var result = original.apply(this, arguments);
        try {
          if (pixels && typeof pixels.length === 'number' && pixels.length >= 4) {
            perturbPixels(pixels, readSalt(x, y, width, height));
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
    var trimmed = value.replace(/\s+V@.*$/, '').replace(/\s*\(GIT@.*$/, '').replace(/[,\s]+$/, '');
    var open = (trimmed.match(/\(/g) || []).length;
    var close = (trimmed.match(/\)/g) || []).length;
    while (close < open) {
      trimmed += ')';
      close++;
    }
    return trimmed;
  }

  function maskGetParameter(prototype) {
    wrap(prototype, 'getParameter', function (original) {
      return function (parameter) {
        var value = original.apply(this, arguments);
        if (parameter === UNMASKED_RENDERER) return trimRenderer(value);
        if (parameter === UNMASKED_VENDOR) return value;
        return value;
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
  }

  // --- Audio -------------------------------------------------------------------------
  // Rendered audio is read back through the buffer. The noise is a pure function of the sample
  // index, far below hearing, and a buffer is touched once: reading it twice adds nothing.
  function sampleNoise(channel, index) {
    return (mix(SEED, mix(channel, index)) / 4294967296 - 0.5) * 1e-7;
  }

  var noisedBuffers = typeof WeakSet === 'function' ? new WeakSet() : null;

  function perturbBuffer(buffer, nativeGetChannelData) {
    if (!noisedBuffers || noisedBuffers.has(buffer)) return;
    noisedBuffers.add(buffer);
    for (var channel = 0; channel < buffer.numberOfChannels; channel++) {
      var data = nativeGetChannelData.call(buffer, channel);
      for (var i = 0; i < data.length; i += 1000) data[i] = data[i] + sampleNoise(channel, i);
    }
  }

  if (window.AudioBuffer) {
    var nativeGetChannelData = AudioBuffer.prototype.getChannelData;
    wrap(AudioBuffer.prototype, 'getChannelData', function (original) {
      return function () {
        try {
          perturbBuffer(this, nativeGetChannelData);
        } catch (ignored) {}
        return original.apply(this, arguments);
      };
    });
    wrap(AudioBuffer.prototype, 'copyFromChannel', function (original) {
      return function () {
        try {
          perturbBuffer(this, nativeGetChannelData);
        } catch (ignored) {}
        return original.apply(this, arguments);
      };
    });
  }

  if (window.AnalyserNode) {
    function wrapAnalyser(name, scale) {
      wrap(AnalyserNode.prototype, name, function (original) {
        return function (array) {
          original.apply(this, arguments);
          try {
            for (var i = 0; i < array.length; i += 97) {
              array[i] = array[i] + sampleNoise(0, i) * scale;
            }
          } catch (ignored) {}
        };
      });
    }
    wrapAnalyser('getFloatFrequencyData', 1000);
    wrapAnalyser('getFloatTimeDomainData', 1000);
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
      define(Screen.prototype, 'availWidth', function () { return screenWidth.get.call(this); });
      define(Screen.prototype, 'availHeight', function () { return screenHeight.get.call(this); });
      define(Screen.prototype, 'availTop', function () { return 0; });
      define(Screen.prototype, 'availLeft', function () { return 0; });
    }
  }

  if (window.StorageManager) {
    var GIB = 1073741824;
    wrap(StorageManager.prototype, 'estimate', function (original) {
      return function () {
        return original.apply(this, arguments).then(function (estimate) {
          try {
            estimate.quota = Math.max(GIB, Math.round(estimate.quota / GIB) * GIB);
          } catch (ignored) {
            // Frozen result object; the unrounded quota is still a weak signal on its own.
          }
          return estimate;
        });
      };
    });
  }
})();
