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
 * perturbed by a seed that is unique to this session and this origin: stable enough that a page
 * comparing two reads of its own canvas sees a consistent result, different enough that the same
 * page tomorrow, or a different site today, cannot join the two up.
 *
 * The noise is deliberately below the threshold of visible or audible difference.
 */
(function () {
  'use strict';

  var SESSION_SEED = __SEED__;

  function hash(value) {
    var h = 2166136261;
    for (var i = 0; i < value.length; i++) {
      h ^= value.charCodeAt(i);
      h = (h * 16777619) >>> 0;
    }
    return h;
  }

  // Per-origin so two sites cannot compare notes, per-session so it does not persist.
  var seed = (SESSION_SEED ^ hash(String(window.location.origin))) >>> 0;

  function nextRandom() {
    // xorshift: cheap, deterministic, good enough to spread noise around.
    seed ^= seed << 13;
    seed >>>= 0;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    seed >>>= 0;
    return seed / 4294967296;
  }

  function define(target, name, value) {
    try {
      Object.defineProperty(target, name, { get: function () { return value; }, configurable: true });
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
  // Perturb a handful of pixels by one unit. Invisible on screen, fatal to a hash.
  function perturb(data) {
    var pixels = data.length / 4;
    var touches = Math.min(24, pixels);
    for (var i = 0; i < touches; i++) {
      var index = (Math.floor(nextRandom() * pixels) * 4) >>> 0;
      data[index] = data[index] ^ (nextRandom() < 0.5 ? 1 : 0);
    }
    return data;
  }

  wrap(CanvasRenderingContext2D.prototype, 'getImageData', function (original) {
    return function () {
      var result = original.apply(this, arguments);
      try {
        perturb(result.data);
      } catch (ignored) {
        // Detached or cross-origin canvas; leave as-is.
      }
      return result;
    };
  });

  wrap(HTMLCanvasElement.prototype, 'toDataURL', function (original) {
    return function () {
      try {
        var context = this.getContext('2d');
        if (context && this.width > 0 && this.height > 0) {
          var snapshot = context.getImageData(0, 0, this.width, this.height);
          context.putImageData(snapshot, 0, 0);
        }
      } catch (ignored) {
        // Tainted canvas; the original call will handle it.
      }
      return original.apply(this, arguments);
    };
  });

  // --- Audio -------------------------------------------------------------------------
  if (window.AnalyserNode) {
    wrap(AnalyserNode.prototype, 'getFloatFrequencyData', function (original) {
      return function (array) {
        original.apply(this, arguments);
        for (var i = 0; i < array.length; i += 97) {
          array[i] = array[i] + (nextRandom() - 0.5) * 0.0001;
        }
      };
    });
  }

  if (window.AudioBuffer) {
    wrap(AudioBuffer.prototype, 'getChannelData', function (original) {
      return function () {
        var data = original.apply(this, arguments);
        for (var i = 0; i < data.length; i += 1000) {
          data[i] = data[i] + (nextRandom() - 0.5) * 1e-7;
        }
        return data;
      };
    });
  }

  // --- WebGL -------------------------------------------------------------------------
  // The unmasked vendor and renderer name the exact GPU, which is close to a serial number.
  var UNMASKED_VENDOR = 37445;
  var UNMASKED_RENDERER = 37446;

  function maskGetParameter(prototype) {
    wrap(prototype, 'getParameter', function (original) {
      return function (parameter) {
        if (parameter === UNMASKED_VENDOR) return 'Google Inc.';
        if (parameter === UNMASKED_RENDERER) return 'ANGLE (Google, Vulkan)';
        return original.apply(this, arguments);
      };
    });
  }

  if (window.WebGLRenderingContext) maskGetParameter(WebGLRenderingContext.prototype);
  if (window.WebGL2RenderingContext) maskGetParameter(WebGL2RenderingContext.prototype);

  // --- Navigator ---------------------------------------------------------------------
  // Rounded to values a large number of real devices share, rather than randomised: an
  // impossible core count would itself stand out.
  define(navigator, 'hardwareConcurrency', 4);
  define(navigator, 'deviceMemory', 4);
  define(navigator, 'webdriver', false);
  define(navigator, 'maxTouchPoints', 5);

  // --- Device shape -------------------------------------------------------------------
  // The space the system bars take, the battery level and the exact storage quota are all
  // readable, none of them is needed to lay out a page, and together they split one phone
  // model into many. The viewport itself is left alone: pages have to lay out in it.
  try {
    define(screen, 'availWidth', screen.width);
    define(screen, 'availHeight', screen.height);
    define(screen, 'availTop', 0);
    define(screen, 'availLeft', 0);
  } catch (ignored) {
    // A locked-down screen object; nothing to hide then.
  }

  try {
    if (window.Navigator && Navigator.prototype.getBattery) delete Navigator.prototype.getBattery;
  } catch (ignored) {
    // Non-configurable on this engine; the API is already gated behind a secure context.
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
