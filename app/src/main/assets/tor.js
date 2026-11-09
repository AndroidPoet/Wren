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
 * all. Every method that formats a Date in local time is redirected to UTC; the instant itself
 * is untouched, so timers, timestamps and comparisons behave normally. Clocks are then
 * coarsened to 100 ms, Tor Browser's precision: a fine timer measures how long this device
 * takes to paint, decode or compute, which names the hardware. The screen is reported as the
 * page's own viewport, whose size the letterboxed WebView has already rounded off.
 */
(function () {
  'use strict';

  // Tor Browser's reduced timer precision. Applied to every clock a page can read.
  var PRECISION_MS = 100;
  function coarse(ms) { return Math.floor(ms / PRECISION_MS) * PRECISION_MS; }

  var DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function pad(n, width) {
    var s = String(n);
    while (s.length < (width || 2)) s = '0' + s;
    return s;
  }

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

  function replace(owner, name, fn) {
    try {
      var original = owner[name];
      Object.defineProperty(owner, name, { value: fn, writable: true, configurable: true });
      try { fn.toString = function () { return String(original); }; } catch (ignored) {}
    } catch (ignored) {}
  }

  var proto = Date.prototype;
  var valid = function (d) { return !isNaN(d.getTime()); };

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
    Object.defineProperty(NativeFormat.prototype, 'constructor', {
      value: DateTimeFormat, writable: true, configurable: true
    });
  } catch (ignored) {}

  // A Date built from local parts, or parsed from a local ISO string, would reveal the zone by
  // the distance between what was asked for and the instant that came back. Both mean UTC now.
  var NativeDate = Date;
  var LOCAL_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;
  function localIso(value) { return typeof value === 'string' && LOCAL_ISO.test(value); }
  // What the native Date would see: an object is coerced first, so a toString() that yields a
  // zone-less ISO string is caught like the string itself would be.
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
  function WrenDate(single) {
    if (!new.target) return String(new NativeDate(coarse(NativeDate.now())));
    var args = arguments.length === 0 ? [coarse(NativeDate.now())] :
      arguments.length === 1 ? [(function (p) { return localIso(p) ? p + 'Z' : p; })(primitive(single))] : [utcArgs(arguments)];
    return Reflect.construct(NativeDate, args, new.target);
  }
  WrenDate.prototype = NativeDate.prototype;
  WrenDate.UTC = NativeDate.UTC;
  WrenDate.now = function () { return coarse(NativeDate.now()); };
  WrenDate.parse = function (value) {
    var p = primitive(value, 'string');
    return NativeDate.parse(localIso(p) ? p + 'Z' : p);
  };
  try {
    Object.defineProperty(WrenDate, 'name', { value: 'Date', configurable: true });
    Object.defineProperty(WrenDate, 'length', { value: 7, configurable: true });
    Object.defineProperty(NativeDate.prototype, 'constructor', { value: WrenDate, writable: true, configurable: true });
  } catch (ignored) {}
  replace(window, 'Date', WrenDate);

  // What Tor Browser answers for the rest: English, no GPU details, no network type, no voices.
  function answer(proto, name, value) {
    try {
      Object.defineProperty(proto, name, { get: function () { return value; }, configurable: true });
    } catch (ignored) {}
  }
  if (window.Navigator) {
    answer(Navigator.prototype, 'language', 'en-US');
    answer(Navigator.prototype, 'languages', Object.freeze(['en-US', 'en']));
    answer(Navigator.prototype, 'gpu', undefined);
    answer(Navigator.prototype, 'connection', undefined);
  }
  if (window.SpeechSynthesis && SpeechSynthesis.prototype.getVoices) {
    replace(SpeechSynthesis.prototype, 'getVoices', function () { return []; });
  }

  // The monotonic clocks: performance.now(), event timestamps and animation-frame timestamps
  // all read the same clock and all come back on the 100 ms grid.
  if (window.Performance && Performance.prototype.now) {
    var nativeNow = Performance.prototype.now;
    replace(Performance.prototype, 'now', function () { return coarse(nativeNow.call(this)); });
  }
  if (window.Event) {
    var nativeStamp = Object.getOwnPropertyDescriptor(Event.prototype, 'timeStamp');
    if (nativeStamp && nativeStamp.get) {
      try {
        Object.defineProperty(Event.prototype, 'timeStamp', {
          get: function () { return coarse(nativeStamp.get.call(this)); }, configurable: true
        });
      } catch (ignored) {}
    }
  }
  if (typeof window.requestAnimationFrame === 'function') {
    var nativeFrame = window.requestAnimationFrame;
    replace(window, 'requestAnimationFrame', function (callback) {
      return nativeFrame.call(window, function (timestamp) { return callback(coarse(timestamp)); });
    });
  }

  // The screen is the viewport. The WebView under this page is letterboxed to 100 px steps, so
  // innerWidth and innerHeight are already round numbers; everything that would describe the
  // real display or the page's place on it answers with those or with zero, as Tor Browser does.
  function viewport(name) {
    return function () { return window[name]; };
  }
  function expose(owner, name, get) {
    try { Object.defineProperty(owner, name, { get: get, configurable: true }); } catch (ignored) {}
  }
  if (window.screen) {
    expose(screen, 'width', viewport('innerWidth'));
    expose(screen, 'height', viewport('innerHeight'));
    expose(screen, 'availWidth', viewport('innerWidth'));
    expose(screen, 'availHeight', viewport('innerHeight'));
    expose(screen, 'availLeft', function () { return 0; });
    expose(screen, 'availTop', function () { return 0; });
  }
  expose(window, 'outerWidth', viewport('innerWidth'));
  expose(window, 'outerHeight', viewport('innerHeight'));
  ['screenX', 'screenY', 'screenLeft', 'screenTop'].forEach(function (name) {
    expose(window, name, function () { return 0; });
  });
})();
