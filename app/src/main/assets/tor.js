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
 * names the hardware. The locale is en-US everywhere, the keyboard a US layout, and the screen
 * is reported as the page's own viewport, whose size the letterboxed WebView has already
 * rounded off.
 *
 * Everything is applied per realm. A page that creates an iframe gets a fresh window with
 * native clocks before the engine injects this script into it, so the frame accessors hand the
 * child window through the same treatment the moment the page reaches for it. A worker is a
 * realm the engine never injects into at all, so Worker and SharedWorker are built from a
 * bootstrap that installs this same code in the worker before the page's script runs there.
 */
(function () {
  'use strict';

  var nativeFunctionToString = Function.prototype.toString;
  var apply = Reflect.apply;

  // The whole shim is one function so its source can be carried into a worker verbatim.
  // `base` is the real address of a bootstrapped worker's script, for the workers it creates.
  function install(window, inWorker, source, base) {
    // Natives captured before any page script can replace them: a page that swaps Math.floor
    // for a recorder would otherwise read every unrounded input.
    var floor = Math.floor;
    var imul = Math.imul;
    var construct = Reflect.construct;
    var apply = Reflect.apply;
    var finite = isFinite;
    var stringify = JSON.stringify;
    var fill = Uint8Array.prototype.fill;
    var weakGet = WeakMap.prototype.get;
    var weakSet = WeakMap.prototype.set;
    function each(list, fn) {
      for (var i = 0; i < list.length; i++) fn(list[i], i);
    }
    var defineProperty = Object.defineProperty;
    var getOwn = Object.getOwnPropertyDescriptor;
    var nativeToString = Function.prototype.toString;
    var MARK = Symbol.for('wren.tor');
    var SOURCES = Symbol.for('wren.sources');

    var PRECISION_MS = 100;
    var SECRET = (Math.random() * 4294967296) >>> 0;
    var EN = 'en-US';
    var LANGUAGES = Object.freeze(['en-US', 'en']);

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
      if (typeof ms !== 'number' || ms !== ms || !finite(ms)) return ms;
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

    // Every shim answers Function.prototype.toString with the source of what it replaced. The
    // map is shared with the other privacy scripts of this realm; the first one installs the
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

      function hide(fn, original, name) {
        try {
          apply(weakSet, sources, [fn, typeof original === 'function'
            ? apply(nativeToString, original, [])
            : 'function ' + (name || '') + '() { [native code] }']);
        } catch (ignored) {}
      }
      function replace(owner, name, fn) {
        try {
          var original = owner[name];
          defineProperty(owner, name, { value: fn, writable: true, configurable: true });
          hide(fn, original, name);
        } catch (ignored) {}
      }
      function expose(owner, name, get, locked) {
        try {
          var native = getOwn(owner, name);
          defineProperty(owner, name, { get: get, configurable: !locked, enumerable: true });
          hide(get, native && native.get, 'get ' + name);
        } catch (ignored) {}
      }
      function coarseGetter(owner, name) {
        var native = owner && getOwn(owner, name);
        if (!native || !native.get) return;
        expose(owner, name, function () { return coarse(apply(native.get, this, [])); });
      }
      function answer(owner, name, value) {
        expose(owner, name, function () { return value; });
      }
      // A constructor stand-in that is indistinguishable from the native one in name, length,
      // prototype identity, source text and the prototype's own constructor property.
      function mirror(Wrapped, Native, length) {
        Wrapped.prototype = Native.prototype;
        try {
          defineProperty(Wrapped, 'name', { value: Native.name, configurable: true });
          defineProperty(Wrapped, 'length', { value: length === undefined ? Native.length : length, configurable: true });
          defineProperty(Native.prototype, 'constructor', { value: Wrapped, writable: true, configurable: true });
        } catch (ignored) {}
        each(Object.getOwnPropertyNames(Native), function (key) {
          if (key === 'prototype' || key === 'name' || key === 'length') return;
          if (getOwn(Wrapped, key)) return;
          try { defineProperty(Wrapped, key, getOwn(Native, key)); } catch (ignored) {}
        });
        hide(Wrapped, Native);
        return Wrapped;
      }

      // A zone-less ISO date-time, which the engine would read in local time.
      var LOCAL_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;
      // Any zone the legacy parser understands: Z, an offset, GMT/UT/UTC or a US abbreviation.
      var HAS_ZONE = /(\d[zZ]\b|\b(?:GMT|UTC?)\b|[+-]\d{2}:?\d{2}\b|\b[ECMP][SD]T\b)/;
      var COMMENT = /\([^)]*\)/g;

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

      replace(proto, 'getTimezoneOffset', function getTimezoneOffset() { return 0; });
      replace(proto, 'toString', function toString() {
        return valid(this) ? dateString(this) + ' ' + timeString(this) : 'Invalid Date';
      });
      replace(proto, 'toDateString', function toDateString() { return valid(this) ? dateString(this) : 'Invalid Date'; });
      replace(proto, 'toTimeString', function toTimeString() { return valid(this) ? timeString(this) : 'Invalid Date'; });

      // Local getters and setters become their UTC twins: the same instant, read in UTC.
      each(['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'], function (part) {
        replace(proto, 'get' + part, proto['getUTC' + part]);
        if (part !== 'Day') replace(proto, 'set' + part, proto['setUTC' + part]);
      });
      replace(proto, 'getYear', function getYear() { return this.getUTCFullYear() - 1900; });
      // The legacy setter works in local time too; left native it would set the year by the
      // real zone and hand the offset back through the UTC getters.
      var nativeSetTime = proto.setTime;
      var nativeSetUTCFullYear = proto.setUTCFullYear;
      replace(proto, 'setYear', function setYear(year) {
        var number = +year;
        if (number !== number) {
          apply(nativeSetTime, this, [NaN]);
          return NaN;
        }
        var whole = number < 0 ? -floor(-number) : floor(number);
        if (whole >= 0 && whole <= 99) whole += 1900;
        if (!valid(this)) apply(nativeSetTime, this, [0]);
        return apply(nativeSetUTCFullYear, this, [whole]);
      });

      // Everything locale-sensitive answers as en-US unless the page names a locale itself, and
      // Intl formats in UTC unless it names a zone; resolvedOptions() then reports both.
      var Intl = window.Intl;
      function withLocale(locales) { return locales === undefined ? EN : locales; }
      function withUtc(options) {
        if (options && options.timeZone !== undefined) return options;
        var copy = {};
        if (options) for (var k in options) copy[k] = options[k];
        copy.timeZone = 'UTC';
        return copy;
      }
      var NativeFormat = Intl.DateTimeFormat;
      var DateTimeFormat = mirror(function DateTimeFormat(locales, options) {
        return construct(NativeFormat, [withLocale(locales), withUtc(options)], new.target || DateTimeFormat);
      }, NativeFormat);
      replace(Intl, 'DateTimeFormat', DateTimeFormat);
      each(['NumberFormat', 'Collator', 'PluralRules', 'RelativeTimeFormat', 'ListFormat', 'DisplayNames',
        'Segmenter', 'DurationFormat'], function (name) {
        var Native = Intl[name];
        if (typeof Native !== 'function') return;
        var Wrapped = mirror(function (locales, options) {
          return construct(Native, [withLocale(locales), options], new.target || Wrapped);
        }, Native);
        replace(Intl, name, Wrapped);
      });
      each(['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'], function (name) {
        var original = proto[name];
        replace(proto, name, function (locales, options) {
          return apply(original, this, [withLocale(locales), withUtc(options)]);
        });
      });
      each([window.Number, window.BigInt, window.Array], function (type) {
        var original = type && type.prototype && type.prototype.toLocaleString;
        if (!original) return;
        replace(type.prototype, 'toLocaleString', function toLocaleString(locales, options) {
          return apply(original, this, [withLocale(locales), options]);
        });
      });
      var StringPrototype = window.String.prototype;
      var nativeCompare = StringPrototype.localeCompare;
      replace(StringPrototype, 'localeCompare', function localeCompare(that, locales, options) {
        return apply(nativeCompare, this, [that, withLocale(locales), options]);
      });
      each(['toLocaleUpperCase', 'toLocaleLowerCase'], function (name) {
        var original = StringPrototype[name];
        replace(StringPrototype, name, function (locales) {
          return apply(original, this, [withLocale(locales)]);
        });
      });

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
        var parts = apply(Array.prototype.slice, args, []);
        while (parts.length < 7) parts.push(parts.length === 2 ? 1 : 0);
        return apply(NativeDate.UTC, NativeDate, parts);
      }
      function now() { return coarse(NativeDate.now()); }
      var WrenDate = mirror(function Date(single) {
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
      }, NativeDate, 7);
      WrenDate.now = now;
      hide(now, NativeDate.now);
      WrenDate.parse = function parse(value) { return parseUtc(primitive(value, 'string')); };
      hide(WrenDate.parse, NativeDate.parse);
      replace(window, 'Date', WrenDate);

      // What Tor Browser answers for the rest: English, no GPU details, no network type, no voices.
      var Navigator = window.Navigator || window.WorkerNavigator;
      if (Navigator) {
        answer(Navigator.prototype, 'language', EN);
        answer(Navigator.prototype, 'languages', LANGUAGES);
        answer(Navigator.prototype, 'gpu', undefined);
        answer(Navigator.prototype, 'connection', undefined);
        if ('keyboard' in Navigator.prototype) answer(Navigator.prototype, 'keyboard', undefined);
      }
      if (window.SpeechSynthesis && window.SpeechSynthesis.prototype.getVoices) {
        replace(window.SpeechSynthesis.prototype, 'getVoices', function getVoices() { return []; });
      }

      // The keyboard layout: Tor Browser reports every key as if typed on a US layout, so the
      // physical key behind a character does not name the user's country. `code` is derived
      // from `key` for printable characters, and is empty for one the US layout cannot type.
      var US_CODES = {
        ' ': 'Space', '-': 'Minus', '_': 'Minus', '=': 'Equal', '+': 'Equal',
        '[': 'BracketLeft', '{': 'BracketLeft', ']': 'BracketRight', '}': 'BracketRight',
        '\\': 'Backslash', '|': 'Backslash', ';': 'Semicolon', ':': 'Semicolon',
        "'": 'Quote', '"': 'Quote', ',': 'Comma', '<': 'Comma', '.': 'Period', '>': 'Period',
        '/': 'Slash', '?': 'Slash', '`': 'Backquote', '~': 'Backquote',
        ')': 'Digit0', '!': 'Digit1', '@': 'Digit2', '#': 'Digit3', '$': 'Digit4',
        '%': 'Digit5', '^': 'Digit6', '&': 'Digit7', '*': 'Digit8', '(': 'Digit9'
      };
      function usCode(key, native) {
        if (typeof key !== 'string' || key.length !== 1) return native;
        if (key >= 'a' && key <= 'z') return 'Key' + key.toUpperCase();
        if (key >= 'A' && key <= 'Z') return 'Key' + key;
        if (key >= '0' && key <= '9') return 'Digit' + key;
        return US_CODES[key] || '';
      }
      var KeyboardEvent = window.KeyboardEvent;
      if (KeyboardEvent) {
        var nativeCode = getOwn(KeyboardEvent.prototype, 'code');
        var nativeKey = getOwn(KeyboardEvent.prototype, 'key');
        if (nativeCode && nativeCode.get && nativeKey && nativeKey.get) {
          expose(KeyboardEvent.prototype, 'code', function () {
            return usCode(apply(nativeKey.get, this, []), apply(nativeCode.get, this, []));
          });
        }
      }

      // Temporal (Chromium 144+) is a second clock with its own idea of the zone.
      var Temporal = window.Temporal;
      if (Temporal && Temporal.Now && Temporal.Instant) {
        var fromMillis = Temporal.Instant.fromEpochMilliseconds;
        var zoneOf = function (zone) { return zone === undefined ? 'UTC' : zone; };
        replace(Temporal.Now, 'timeZoneId', function timeZoneId() { return 'UTC'; });
        replace(Temporal.Now, 'instant', function instant() { return apply(fromMillis, Temporal.Instant, [now()]); });
        replace(Temporal.Now, 'zonedDateTimeISO', function zonedDateTimeISO(zone) {
          return apply(fromMillis, Temporal.Instant, [now()]).toZonedDateTimeISO(zoneOf(zone));
        });
        replace(Temporal.Now, 'plainDateTimeISO', function plainDateTimeISO(zone) {
          return Temporal.Now.zonedDateTimeISO(zoneOf(zone)).toPlainDateTime();
        });
        replace(Temporal.Now, 'plainDateISO', function plainDateISO(zone) {
          return Temporal.Now.zonedDateTimeISO(zoneOf(zone)).toPlainDate();
        });
        replace(Temporal.Now, 'plainTimeISO', function plainTimeISO(zone) {
          return Temporal.Now.zonedDateTimeISO(zoneOf(zone)).toPlainTime();
        });
      }

      // The monotonic clocks: performance.now(), performance entries, event timestamps,
      // animation-frame and idle deadlines and the audio clock all come back on the 100 ms grid.
      var Performance = window.Performance;
      if (Performance && Performance.prototype.now) {
        var nativeNow = Performance.prototype.now;
        replace(Performance.prototype, 'now', function now() { return coarse(apply(nativeNow, this, [])); });
        coarseGetter(Performance.prototype, 'timeOrigin');
      }
      var PerformanceEntry = window.PerformanceEntry;
      if (PerformanceEntry) {
        coarseGetter(PerformanceEntry.prototype, 'startTime');
        coarseGetter(PerformanceEntry.prototype, 'duration');
        var nativeJson = PerformanceEntry.prototype.toJSON;
        if (nativeJson) {
          replace(PerformanceEntry.prototype, 'toJSON', function toJSON() {
            var json = apply(nativeJson, this, []);
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
      // The other clocks a resource or navigation entry carries, and the legacy timing object:
      // each is a millisecond reading of the same grid-free clock, coarsened the same way.
      var RESOURCE_TIMES = ['workerStart', 'redirectStart', 'redirectEnd', 'fetchStart',
        'domainLookupStart', 'domainLookupEnd', 'connectStart', 'connectEnd',
        'secureConnectionStart', 'requestStart', 'responseStart', 'responseEnd',
        'firstInterimResponseStart', 'finalResponseHeadersStart'];
      var NAVIGATION_TIMES = ['unloadEventStart', 'unloadEventEnd', 'domInteractive',
        'domContentLoadedEventStart', 'domContentLoadedEventEnd', 'domComplete',
        'loadEventStart', 'loadEventEnd', 'activationStart', 'criticalCHRestart'];
      var LEGACY_TIMES = ['navigationStart', 'unloadEventStart', 'unloadEventEnd', 'redirectStart',
        'redirectEnd', 'fetchStart', 'domainLookupStart', 'domainLookupEnd', 'connectStart',
        'connectEnd', 'secureConnectionStart', 'requestStart', 'responseStart', 'responseEnd',
        'domLoading', 'domInteractive', 'domContentLoadedEventStart', 'domContentLoadedEventEnd',
        'domComplete', 'loadEventStart', 'loadEventEnd'];
      function coarseEntry(kind, names) {
        var Kind = window[kind];
        if (!Kind || !Kind.prototype) return;
        each(names, function (name) { coarseGetter(Kind.prototype, name); });
        var nativeEntryJson = Kind.prototype.hasOwnProperty('toJSON') ? Kind.prototype.toJSON : null;
        if (!nativeEntryJson) return;
        replace(Kind.prototype, 'toJSON', function toJSON() {
          var json = apply(nativeEntryJson, this, []);
          try {
            if (json && typeof json === 'object') {
              each(names, function (name) { if (typeof json[name] === 'number') json[name] = coarse(json[name]); });
              if (typeof json.startTime === 'number') json.startTime = coarse(json.startTime);
              if (typeof json.duration === 'number') json.duration = coarse(json.duration);
            }
          } catch (ignored) {}
          return json;
        });
      }
      coarseEntry('PerformanceResourceTiming', RESOURCE_TIMES);
      coarseEntry('PerformanceNavigationTiming', NAVIGATION_TIMES);
      coarseEntry('PerformanceTiming', LEGACY_TIMES);
      if (window.AudioContext && window.AudioContext.prototype.getOutputTimestamp) {
        var nativeOutputTimestamp = window.AudioContext.prototype.getOutputTimestamp;
        replace(window.AudioContext.prototype, 'getOutputTimestamp', function getOutputTimestamp() {
          var stamp = apply(nativeOutputTimestamp, this, []);
          try {
            if (stamp && typeof stamp === 'object') {
              if (typeof stamp.contextTime === 'number') stamp.contextTime = coarse(stamp.contextTime * 1000) / 1000;
              if (typeof stamp.performanceTime === 'number') stamp.performanceTime = coarse(stamp.performanceTime);
            }
          } catch (ignored) {}
          return stamp;
        });
      }
      if (window.Event) coarseGetter(window.Event.prototype, 'timeStamp');
      if (window.IdleDeadline && window.IdleDeadline.prototype.timeRemaining) {
        var nativeRemaining = window.IdleDeadline.prototype.timeRemaining;
        replace(window.IdleDeadline.prototype, 'timeRemaining', function timeRemaining() {
          // An expired deadline stays expired: a bucket's worth of time out of nothing would
          // make a page run work it had no time for.
          var remaining = apply(nativeRemaining, this, []);
          return remaining <= 0 ? 0 : coarse(remaining);
        });
      }
      if (window.AnimationTimeline) coarseGetter(window.AnimationTimeline.prototype, 'currentTime');
      if (window.BaseAudioContext) {
        var audioClock = getOwn(window.BaseAudioContext.prototype, 'currentTime');
        if (audioClock && audioClock.get) {
          expose(window.BaseAudioContext.prototype, 'currentTime', function () {
            return coarse(apply(audioClock.get, this, []) * 1000) / 1000;
          });
        }
      }
      if (typeof window.requestAnimationFrame === 'function') {
        var nativeFrame = window.requestAnimationFrame;
        replace(window, 'requestAnimationFrame', function requestAnimationFrame(callback) {
          return apply(nativeFrame, window, [function (timestamp) { return callback(coarse(timestamp)); }]);
        });
      }

      // The screen is the viewport. The WebView under this page is letterboxed to 100 px steps,
      // so innerWidth and innerHeight are already round numbers; everything that would describe
      // the real display or the page's place on it answers with those or with zero, as Tor
      // Browser does. Defined on the prototype and locked, so neither `delete screen.width` nor
      // the prototype's own descriptor reaches the engine's getter. Pointer and touch events
      // carry screen coordinates too, which would otherwise hand back the letterbox margins.
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
      if (!inWorker) {
        expose(window, 'outerWidth', viewport('innerWidth'));
        expose(window, 'outerHeight', viewport('innerHeight'));
        each(['screenX', 'screenY', 'screenLeft', 'screenTop'], function (name) {
          expose(window, name, function () { return 0; });
        });
      }
      each(['MouseEvent', 'Touch'], function (kind) {
        var Kind = window[kind];
        if (!Kind) return;
        each(['X', 'Y'], function (axis) {
          var client = getOwn(Kind.prototype, 'client' + axis);
          if (client && client.get) {
            expose(Kind.prototype, 'screen' + axis, function () { return apply(client.get, this, []); });
          }
        });
      });

      if (inWorker) hardenWorkerCanvas(window);
      hardenWorkers(window);
      hardenFrames(window);
    }

    // Inside a worker the canvas is Tor Browser's blank canvas, as fingerprint.js makes it in
    // the page: every read answers zeros and every export is an empty canvas of the same size.
    function hardenWorkerCanvas(window) {
      var sources = sourcesOf(window);
      function replace(owner, name, fn) {
        try {
          var original = owner[name];
          defineProperty(owner, name, { value: fn, writable: true, configurable: true });
          apply(weakSet, sources, [fn, apply(nativeToString, original, [])]);
        } catch (ignored) {}
      }
      var Context2D = window.OffscreenCanvasRenderingContext2D;
      if (Context2D && Context2D.prototype.getImageData) {
        var nativeGet = Context2D.prototype.getImageData;
        replace(Context2D.prototype, 'getImageData', function getImageData() {
          var image = apply(nativeGet, this, arguments);
          try { apply(fill, image.data, [0]); } catch (ignored) {}
          return image;
        });
      }
      each(['WebGLRenderingContext', 'WebGL2RenderingContext'], function (kind) {
        var Context = window[kind];
        if (!Context) return;
        var nativeRead = Context.prototype.readPixels;
        if (nativeRead) {
          replace(Context.prototype, 'readPixels', function readPixels() {
            var result = apply(nativeRead, this, arguments);
            try { var pixels = arguments[6]; if (pixels && typeof pixels.fill === 'function') apply(fill, pixels, [0]); } catch (ignored) {}
            return result;
          });
        }
        var nativeSub = Context.prototype.getBufferSubData;
        if (nativeSub) {
          replace(Context.prototype, 'getBufferSubData', function getBufferSubData() {
            var result = apply(nativeSub, this, arguments);
            try { var out = arguments[2]; if (out && typeof out.fill === 'function') apply(fill, out, [0]); } catch (ignored) {}
            return result;
          });
        }
      });
      var Offscreen = window.OffscreenCanvas;
      if (Offscreen && Offscreen.prototype.convertToBlob) {
        var nativeBlob = Offscreen.prototype.convertToBlob;
        replace(Offscreen.prototype, 'convertToBlob', function convertToBlob(options) {
          var blank = new Offscreen(this.width || 1, this.height || 1);
          return apply(nativeBlob, blank, [options]);
        });
      }
      var WorkerNavigator = window.WorkerNavigator;
      if (WorkerNavigator) {
        try {
          defineProperty(WorkerNavigator.prototype, 'hardwareConcurrency', {
            get: function () { return 4; }, configurable: true, enumerable: true
          });
        } catch (ignored) {}
      }
    }

    // A worker runs the page's script in a realm the engine never injects this file into. The
    // worker is therefore started from a bootstrap that installs the same code first and then
    // loads the page's script, with relative addresses still resolved against that script.
    function hardenWorkers(window) {
      // Taken now: a page that later swapped URL.createObjectURL for one that throws must not
      // be handed a worker started from its own script, unhardened.
      var NativeURL = window.URL;
      var NativeBlob = window.Blob;
      var createObjectURL = NativeURL && NativeURL.createObjectURL;
      if (!source || !NativeURL || !NativeBlob || typeof createObjectURL !== 'function') return;
      var sources = sourcesOf(window);
      var bootstraps = {};
      function bootstrapFor(url, options) {
        var resolved = new NativeURL(String(url), base || window.location.href).href;
        var isModule = !!(options && options.type === 'module');
        var key = (isModule ? 'module ' : 'classic ') + resolved;
        if (bootstraps[key]) return bootstraps[key];
        var load = isModule
          ? 'import(' + stringify(resolved) + ');'
          : 'importScripts(' + stringify(resolved) + ');';
        var body = '(' + source + ')(self, true, ' + stringify(source) + ', ' + stringify(resolved) + ');\n' +
          '(' + apply(nativeToString, resolveAgainst, []) + ')(' + stringify(resolved) + ');\n' + load;
        var blobUrl = apply(createObjectURL, NativeURL, [new NativeBlob([body], { type: 'text/javascript' })]);
        bootstraps[key] = blobUrl;
        return blobUrl;
      }
      each(['Worker', 'SharedWorker'], function (kind) {
        var Native = window[kind];
        if (typeof Native !== 'function') return;
        var Wrapped = function (url, options) {
          if (!new.target) {
            throw new TypeError("Failed to construct '" + kind + "': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
          }
          // No bootstrap, no worker: the page's script must not run in a realm with the real
          // clocks and zone because the page broke what the bootstrap is built from.
          var target;
          try {
            target = bootstrapFor(url, options);
          } catch (ignored) {
            throw new window.DOMException("Failed to construct '" + kind + "': the script could not be prepared.", 'SecurityError');
          }
          return construct(Native, [target, options], new.target);
        };
        Wrapped.prototype = Native.prototype;
        try {
          defineProperty(Wrapped, 'name', { value: Native.name, configurable: true });
          defineProperty(Wrapped, 'length', { value: Native.length, configurable: true });
          defineProperty(Native.prototype, 'constructor', { value: Wrapped, writable: true, configurable: true });
          each(Object.getOwnPropertyNames(Native), function (key) {
            if (key === 'prototype' || key === 'name' || key === 'length') return;
            try { defineProperty(Wrapped, key, getOwn(Native, key)); } catch (ignored) {}
          });
          apply(weakSet, sources, [Wrapped, apply(nativeToString, Native, [])]);
          defineProperty(window, kind, { value: Wrapped, writable: true, configurable: true });
        } catch (ignored) {}
      });
    }

    // Runs inside the bootstrap worker: its own address is a blob, so anything the page's
    // worker script addresses relative to itself is resolved against the script's real address.
    function resolveAgainst(base) {
      var apply = Reflect.apply;
      function each(list, fn) {
        for (var i = 0; i < list.length; i++) fn(list[i], i);
      }
      var toAbsolute = function (value) {
        if (typeof value !== 'string') return value;
        try { return new URL(value, base).href; } catch (ignored) { return value; }
      };
      var nativeImport = self.importScripts;
      if (nativeImport) {
        self.importScripts = function importScripts() {
          var urls = [];
          for (var i = 0; i < arguments.length; i++) urls.push(toAbsolute(arguments[i]));
          return apply(nativeImport, self, urls);
        };
      }
      var nativeFetch = self.fetch;
      if (nativeFetch) {
        self.fetch = function fetch(input, init) { return apply(nativeFetch, self, [toAbsolute(input), init]); };
      }
      var NativeRequest = self.Request;
      if (NativeRequest) {
        var Request = function Request(input, init) { return new NativeRequest(toAbsolute(input), init); };
        Request.prototype = NativeRequest.prototype;
        self.Request = Request;
      }
      var Xhr = self.XMLHttpRequest;
      if (Xhr && Xhr.prototype.open) {
        var nativeOpen = Xhr.prototype.open;
        Xhr.prototype.open = function open() {
          var args = apply(Array.prototype.slice, arguments, []);
          if (args.length > 1) args[1] = toAbsolute(args[1]);
          return apply(nativeOpen, this, args);
        };
      }
      each(['WebSocket', 'EventSource'], function (kind) {
        var Native = self[kind];
        if (typeof Native !== 'function') return;
        var Wrapped = function (url, extra) {
          return extra === undefined ? new Native(toAbsolute(url)) : new Native(toAbsolute(url), extra);
        };
        Wrapped.prototype = Native.prototype;
        each(Object.getOwnPropertyNames(Native), function (key) {
          if (key === 'prototype' || key === 'name' || key === 'length') return;
          try { Object.defineProperty(Wrapped, key, Object.getOwnPropertyDescriptor(Native, key)); } catch (ignored) {}
        });
        self[kind] = Wrapped;
      });
      var NativeURL = self.URL;
      var blobBase = self.location && self.location.href;
      var URLWrapper = function URL(url, urlBase) {
        if (urlBase === blobBase || (urlBase && urlBase.href === blobBase)) urlBase = base;
        return urlBase === undefined ? new NativeURL(url) : new NativeURL(url, urlBase);
      };
      URLWrapper.prototype = NativeURL.prototype;
      each(Object.getOwnPropertyNames(NativeURL), function (key) {
        if (key === 'prototype' || key === 'name' || key === 'length') return;
        try { Object.defineProperty(URLWrapper, key, Object.getOwnPropertyDescriptor(NativeURL, key)); } catch (ignored) {}
      });
      self.URL = URLWrapper;
    }

    // A child frame's window exists, with every native intact, before the engine injects this
    // script into it. The accessors a page uses to reach it apply the same treatment first.
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
            var getter = function () {
              var value = apply(get, this, []);
              try {
                var child = name === 'contentWindow' ? value : value && value.defaultView;
                if (child) harden(child);
              } catch (ignored) {
                // Cross-origin: the engine injects into that document itself.
              }
              return value;
            };
            defineProperty(proto, name, {
              get: getter,
              set: native.set,
              enumerable: native.enumerable,
              configurable: true
            });
            apply(weakSet, sourcesOf(window), [getter, apply(nativeToString, get, [])]);
          } catch (ignored) {}
        });
      });
    }

    harden(window);
  }

  var source = apply(nativeFunctionToString, install, []);
  install(window, false, source);
})();
