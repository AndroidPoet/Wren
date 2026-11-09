/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * What a page must not learn while Tor is on. WebRTC and the sensors are handled by private.js,
 * which runs on Tor pages too.
 *
 * The clock is moved to UTC the way Tor Browser does: the time zone is one of the strongest
 * signals of where a user is, and it leaks through Date and Intl with no network request at
 * all. Every method that formats a Date in local time is redirected to UTC, and every string
 * without a zone of its own is read as UTC; the instant itself is untouched, so timers,
 * timestamps and comparisons behave normally. Clocks are then coarsened to 100 ms, Tor
 * Browser's precision, with Firefox's per-bucket jitter so the bucket edges cannot be found by
 * spinning: a fine timer measures how long this device takes to paint, decode or compute, which
 * names the hardware. The screen is reported as the page's own viewport, whose size the
 * letterboxed WebView has already rounded off.
 *
 * Everything is applied per realm. A page that creates an iframe gets a fresh window with
 * native clocks before the engine injects this script into it, so the frame accessors hand the
 * child window through the same treatment the moment the page reaches for it.
 */
(function () {
  'use strict';

  // Natives captured before any page script can replace them: a page that swaps Math.floor
  // for a recorder would otherwise read every unrounded input.
  var floor = Math.floor;
  var imul = Math.imul;
  var construct = Reflect.construct;
  var defineProperty = Object.defineProperty;
  var getOwn = Object.getOwnPropertyDescriptor;
  var MARK = Symbol.for('wren.tor');

  var PRECISION_MS = 100;
  var SECRET = (Math.random() * 4294967296) >>> 0;

  function mix(a, b) {
    var h = ((a >>> 0) ^ 0x9e3779b9) >>> 0;
    h = imul(h ^ (b >>> 0), 0x85ebca6b) >>> 0;
    h ^= h >>> 13;
    h = imul(h, 0xc2b2ae35) >>> 0;
    h ^= h >>> 16;
    return h >>> 0;
  }

  // Firefox's reduced-precision clock: the value is one edge of its 100 ms bucket, and a
  // secret midpoint per bucket decides which, so the result stays monotonic while the moment
  // it ticks over cannot be recovered by counting iterations between two reads.
  function coarse(ms) {
    if (typeof ms !== 'number' || ms !== ms || !isFinite(ms)) return ms;
    var bucket = floor(ms / PRECISION_MS) * PRECISION_MS;
    var midpoint = mix(SECRET, bucket / PRECISION_MS) % PRECISION_MS;
    return ms - bucket >= midpoint ? bucket + PRECISION_MS : bucket;
  }

  var DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function pad(n, width) {
    var s = String(n);
    while (s.length < (width || 2)) s = '0' + s;
    return s;
  }

  function replace(owner, name, fn) {
    try {
      var original = owner[name];
      defineProperty(owner, name, { value: fn, writable: true, configurable: true });
      try { fn.toString = function () { return String(original); }; } catch (ignored) {}
    } catch (ignored) {}
  }

  function expose(owner, name, get, locked) {
    try {
      defineProperty(owner, name, { get: get, configurable: !locked, enumerable: true });
    } catch (ignored) {}
  }

  function coarseGetter(owner, name) {
    var native = owner && getOwn(owner, name);
    if (!native || !native.get) return;
    expose(owner, name, function () { return coarse(native.get.call(this)); });
  }

  // A zone-less ISO date-time, which the engine would read in local time.
  var LOCAL_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;
  // Any zone the legacy parser understands: Z, an offset, GMT/UT/UTC or a US abbreviation.
  var HAS_ZONE = /(\d[zZ]\b|\b(?:GMT|UTC?)\b|[+-]\d{2}:?\d{2}\b|\b[ECMP][SD]T\b)/;
  var COMMENT = /\([^)]*\)/g;

  function harden(window) {
    if (window[MARK]) return;
    try { defineProperty(window, MARK, { value: true }); } catch (ignored) { return; }

    var NativeDate = window.Date;
    var proto = NativeDate.prototype;
    var valid = function (d) { return !isNaN(d.getTime()); };

    function year(d) {
      var y = d.getUTCFullYear();
      return y < 0 ? '-' + pad(-y, 6) : y > 9999 ? '+' + pad(y, 6) : pad(y, 4);
    }
    function dateString(d) {
      return DAYS[d.getUTCDay()] + ' ' + MONTHS[d.getUTCMonth()] + ' ' + pad(d.getUTCDate()) + ' ' + year(d);
    }
    function timeString(d) {
      return pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds()) +
        ' GMT+0000 (Coordinated Universal Time)';
    }

    replace(proto, 'getTimezoneOffset', function () { return 0; });
    replace(proto, 'toString', function () {
      return valid(this) ? dateString(this) + ' ' + timeString(this) : 'Invalid Date';
    });
    replace(proto, 'toDateString', function () { return valid(this) ? dateString(this) : 'Invalid Date'; });
    replace(proto, 'toTimeString', function () { return valid(this) ? timeString(this) : 'Invalid Date'; });

    // Local getters and setters become their UTC twins: the same instant, read in UTC.
    ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (part) {
      replace(proto, 'get' + part, proto['getUTC' + part]);
      if (part !== 'Day') replace(proto, 'set' + part, proto['setUTC' + part]);
    });
    replace(proto, 'getYear', function () { return this.getUTCFullYear() - 1900; });

    // Intl formats in UTC unless the page names a zone itself; resolvedOptions() then reports UTC.
    var Intl = window.Intl;
    var NativeFormat = Intl.DateTimeFormat;
    function withUtc(options) {
      if (options && options.timeZone !== undefined) return options;
      var copy = {};
      if (options) for (var k in options) copy[k] = options[k];
      copy.timeZone = 'UTC';
      return copy;
    }
    function DateTimeFormat(locales, options) {
      return new NativeFormat(locales, withUtc(options));
    }
    DateTimeFormat.prototype = NativeFormat.prototype;
    DateTimeFormat.supportedLocalesOf = NativeFormat.supportedLocalesOf;
    try { DateTimeFormat.toString = function () { return String(NativeFormat); }; } catch (ignored) {}
    replace(Intl, 'DateTimeFormat', DateTimeFormat);

    ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (name) {
      var original = proto[name];
      replace(proto, name, function (locales, options) {
        return original.call(this, locales, withUtc(options));
      });
    });
    // The native constructor is still reachable through the prototype; point it at the wrapper.
    try {
      defineProperty(NativeFormat.prototype, 'constructor', {
        value: DateTimeFormat, writable: true, configurable: true
      });
    } catch (ignored) {}

    // A Date built from local parts, or parsed from a string without a zone, would reveal the
    // zone by the distance between what was asked for and the instant that came back. Every
    // such string means UTC now: ISO strings get a Z, the legacy spellings a UTC suffix.
    function localIso(value) { return typeof value === 'string' && LOCAL_ISO.test(value); }
    function zoneless(value) {
      return typeof value === 'string' && !HAS_ZONE.test(value.replace(COMMENT, ''));
    }
    function parseUtc(value) {
      if (localIso(value)) return NativeDate.parse(value + 'Z');
      var t = NativeDate.parse(value);
      if (t !== t || !zoneless(value)) return t;
      var u = NativeDate.parse(value + ' UTC');
      return u !== u ? t : u;
    }
    // What the native Date would see: an object is coerced first, so a toString() that yields a
    // zone-less string is caught like the string itself would be.
    function primitive(value, hint) {
      if (value === null || (typeof value !== 'object' && typeof value !== 'function')) return value;
      try {
        if (hint === 'string') return String(value);
        var v = typeof value.valueOf === 'function' ? value.valueOf() : value;
        if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return v;
        return String(value);
      } catch (ignored) {
        return value;
      }
    }
    function utcArgs(args) {
      var parts = Array.prototype.slice.call(args);
      while (parts.length < 7) parts.push(parts.length === 2 ? 1 : 0);
      return NativeDate.UTC.apply(NativeDate, parts);
    }
    function now() { return coarse(NativeDate.now()); }
    function WrenDate(single) {
      if (!new.target) return String(new NativeDate(now()));
      var args;
      if (arguments.length === 0) {
        args = [now()];
      } else if (arguments.length === 1) {
        var p = primitive(single);
        args = [typeof p === 'string' ? parseUtc(p) : p];
      } else {
        args = [utcArgs(arguments)];
      }
      return construct(NativeDate, args, new.target);
    }
    WrenDate.prototype = proto;
    WrenDate.UTC = NativeDate.UTC;
    WrenDate.now = now;
    WrenDate.parse = function (value) { return parseUtc(primitive(value, 'string')); };
    try {
      defineProperty(WrenDate, 'name', { value: 'Date', configurable: true });
      defineProperty(WrenDate, 'length', { value: 7, configurable: true });
      defineProperty(proto, 'constructor', { value: WrenDate, writable: true, configurable: true });
    } catch (ignored) {}
    replace(window, 'Date', WrenDate);

    // What Tor Browser answers for the rest: English, no GPU details, no network type, no voices.
    function answer(owner, name, value) {
      expose(owner, name, function () { return value; });
    }
    if (window.Navigator) {
      answer(window.Navigator.prototype, 'language', 'en-US');
      answer(window.Navigator.prototype, 'languages', Object.freeze(['en-US', 'en']));
      answer(window.Navigator.prototype, 'gpu', undefined);
      answer(window.Navigator.prototype, 'connection', undefined);
    }
    if (window.SpeechSynthesis && window.SpeechSynthesis.prototype.getVoices) {
      replace(window.SpeechSynthesis.prototype, 'getVoices', function () { return []; });
    }

    // The monotonic clocks: performance.now(), performance entries, event timestamps,
    // animation-frame and idle deadlines and the audio clock all come back on the 100 ms grid.
    var Performance = window.Performance;
    if (Performance && Performance.prototype.now) {
      var nativeNow = Performance.prototype.now;
      replace(Performance.prototype, 'now', function () { return coarse(nativeNow.call(this)); });
      coarseGetter(Performance.prototype, 'timeOrigin');
    }
    var PerformanceEntry = window.PerformanceEntry;
    if (PerformanceEntry) {
      coarseGetter(PerformanceEntry.prototype, 'startTime');
      coarseGetter(PerformanceEntry.prototype, 'duration');
      var nativeJson = PerformanceEntry.prototype.toJSON;
      if (nativeJson) {
        replace(PerformanceEntry.prototype, 'toJSON', function () {
          var json = nativeJson.call(this);
          try {
            if (json && typeof json === 'object') {
              if ('startTime' in json) json.startTime = coarse(json.startTime);
              if ('duration' in json) json.duration = coarse(json.duration);
            }
          } catch (ignored) {}
          return json;
        });
      }
    }
    if (window.Event) coarseGetter(window.Event.prototype, 'timeStamp');
    if (window.IdleDeadline && window.IdleDeadline.prototype.timeRemaining) {
      var nativeRemaining = window.IdleDeadline.prototype.timeRemaining;
      replace(window.IdleDeadline.prototype, 'timeRemaining', function () {
        return coarse(nativeRemaining.call(this));
      });
    }
    if (window.AnimationTimeline) coarseGetter(window.AnimationTimeline.prototype, 'currentTime');
    if (window.BaseAudioContext) {
      var audioClock = getOwn(window.BaseAudioContext.prototype, 'currentTime');
      if (audioClock && audioClock.get) {
        expose(window.BaseAudioContext.prototype, 'currentTime', function () {
          return coarse(audioClock.get.call(this) * 1000) / 1000;
        });
      }
    }
    if (typeof window.requestAnimationFrame === 'function') {
      var nativeFrame = window.requestAnimationFrame;
      replace(window, 'requestAnimationFrame', function (callback) {
        return nativeFrame.call(window, function (timestamp) { return callback(coarse(timestamp)); });
      });
    }

    // The screen is the viewport. The WebView under this page is letterboxed to 100 px steps,
    // so innerWidth and innerHeight are already round numbers; everything that would describe
    // the real display or the page's place on it answers with those or with zero, as Tor
    // Browser does. Defined on the prototype and locked, so neither `delete screen.width` nor
    // the prototype's own descriptor reaches the engine's getter.
    function viewport(name) {
      return function () { return window[name]; };
    }
    var Screen = window.Screen;
    if (Screen) {
      expose(Screen.prototype, 'width', viewport('innerWidth'), true);
      expose(Screen.prototype, 'height', viewport('innerHeight'), true);
      expose(Screen.prototype, 'availWidth', viewport('innerWidth'), true);
      expose(Screen.prototype, 'availHeight', viewport('innerHeight'), true);
      expose(Screen.prototype, 'availLeft', function () { return 0; }, true);
      expose(Screen.prototype, 'availTop', function () { return 0; }, true);
    }
    expose(window, 'outerWidth', viewport('innerWidth'));
    expose(window, 'outerHeight', viewport('innerHeight'));
    ['screenX', 'screenY', 'screenLeft', 'screenTop'].forEach(function (name) {
      expose(window, name, function () { return 0; });
    });

    hardenFrames(window);
  }

  // A child frame's window exists, with every native intact, before the engine injects this
  // script into it. The accessors a page uses to reach it apply the same treatment first.
  function hardenFrames(window) {
    ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement'].forEach(function (kind) {
      var element = window[kind];
      var proto = element && element.prototype;
      if (!proto) return;
      ['contentWindow', 'contentDocument'].forEach(function (name) {
        var native = getOwn(proto, name);
        if (!native || !native.get) return;
        var get = native.get;
        try {
          defineProperty(proto, name, {
            get: function () {
              var value = get.call(this);
              try {
                var child = name === 'contentWindow' ? value : value && value.defaultView;
                if (child) harden(child);
              } catch (ignored) {
                // Cross-origin: the engine injects into that document itself.
              }
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
