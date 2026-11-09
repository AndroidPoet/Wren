/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Storage for third-party frames, when third-party cookies are blocked or Tor is on.
 *
 * The engine blocks a third-party frame's cookies but still gives it its own site's storage:
 * localStorage, IndexedDB, the Cache API, the origin-private file system, BroadcastChannel and
 * the Web Locks all answer with the state the same site keeps everywhere else. A tracker in a
 * frame on site A writes an identifier there and reads it back from its frame on site B, so
 * the two visits are joined exactly as a cookie would have joined them. Chrome, with third-party
 * cookies blocked, denies that frame every one of those APIs with a SecurityError, and that
 * is what this script does: a frame whose site differs from the top page's gets no storage.
 *
 * The frame knows its ancestors' origins through location.ancestorOrigins; a frame whose top
 * origin is its own site is first-party and is left alone. A frame of about:blank or srcdoc
 * inherits its parent's origin and its parent's standing, and the frame accessors hand such a
 * child window through this same treatment the moment the page reaches for it.
 */
(function () {
  'use strict';

  var MARK = Symbol.for('wren.partition');
  var SOURCES = Symbol.for('wren.sources');
  var defineProperty = Object.defineProperty;
  var getOwn = Object.getOwnPropertyDescriptor;
  var nativeToString = Function.prototype.toString;

  function sourcesOf(window) {
    var map = window[SOURCES];
    if (map) return map;
    map = new WeakMap();
    try {
      defineProperty(window, SOURCES, { value: map });
      var FunctionPrototype = window.Function.prototype;
      var toString = FunctionPrototype.toString;
      var masked = function toString() {
        var text = map.get(this);
        return text !== undefined ? text : nativeToString.call(this);
      };
      map.set(masked, nativeToString.call(toString));
      defineProperty(FunctionPrototype, 'toString', { value: masked, writable: true, configurable: true });
    } catch (ignored) {}
    return map;
  }

  // The registrable domain, by the rule the engine applies to cookies: the last two labels, or
  // three when the second-level label is one of the short public suffixes (co.uk, com.au ...).
  var SHORT_SECOND_LEVEL = /^(co|com|net|org|gov|edu|ac|ltd|plc|me|or|ne|go|gr|sch)$/;
  function siteOf(origin) {
    if (typeof origin !== 'string') return null;
    var match = /^[a-z][a-z0-9+.-]*:\/\/([^/:?#]+)/i.exec(origin);
    if (!match) return null;
    var host = match[1].toLowerCase();
    if (/^[\d.]+$/.test(host) || host.indexOf(':') >= 0 || host.indexOf('.') < 0) return host;
    var labels = host.split('.');
    var keep = labels.length >= 3 && labels[labels.length - 1].length === 2 &&
      SHORT_SECOND_LEVEL.test(labels[labels.length - 2]) ? 3 : 2;
    return labels.slice(-keep).join('.');
  }

  function isThirdParty(window) {
    try {
      if (window.top === window) return false;
    } catch (ignored) {
      // A cross-origin top is unreachable, which already says the frame is not first-party;
      // the ancestor list below decides whether it is cross-site too.
    }
    var ancestors;
    try { ancestors = window.location.ancestorOrigins; } catch (ignored) { return true; }
    if (!ancestors || ancestors.length === 0) return false;
    var own = siteOf(window.location.origin || window.origin);
    var top = siteOf(ancestors[ancestors.length - 1]);
    if (own === null || top === null) return true;
    return own !== top;
  }

  function harden(window) {
    if (window[MARK]) return;
    try { defineProperty(window, MARK, { value: true }); } catch (ignored) { return; }
    hardenFrames(window);
    if (!isThirdParty(window)) return;
    var sources = sourcesOf(window);

    function denied(what) {
      var message = "Failed to read the '" + what + "' property from 'Window': Access is denied for this document.";
      try { return new window.DOMException(message, 'SecurityError'); } catch (ignored) { return new Error(message); }
    }
    function hide(fn, original, name) {
      try {
        sources.set(fn, typeof original === 'function'
          ? nativeToString.call(original)
          : 'function ' + (name || '') + '() { [native code] }');
      } catch (ignored) {}
    }
    function throwing(owner, name) {
      try {
        var native = getOwn(owner, name);
        var getter = function () { throw denied(name); };
        defineProperty(owner, name, { get: getter, configurable: true, enumerable: native ? native.enumerable : true });
        hide(getter, native && native.get, 'get ' + name);
      } catch (ignored) {}
    }
    function rejecting(owner, name, what) {
      try {
        var original = owner[name];
        if (typeof original !== 'function') return;
        var fn = function () { return window.Promise.reject(denied(what || name)); };
        defineProperty(owner, name, { value: fn, writable: true, configurable: true });
        hide(fn, original, name);
      } catch (ignored) {}
    }
    function failing(owner, name, what) {
      try {
        var original = owner[name];
        if (typeof original !== 'function') return;
        var fn = function () { throw denied(what || name); };
        defineProperty(owner, name, { value: fn, writable: true, configurable: true });
        hide(fn, original, name);
      } catch (ignored) {}
    }

    // What Chrome denies a third-party frame with its cookies blocked.
    throwing(window, 'localStorage');
    throwing(window, 'sessionStorage');
    throwing(window, 'indexedDB');
    throwing(window, 'caches');
    throwing(window, 'cookieStore');
    var Navigator = window.Navigator;
    if (Navigator) {
      var storage = getOwn(Navigator.prototype, 'storage');
      if (storage && storage.get && window.StorageManager) {
        rejecting(window.StorageManager.prototype, 'getDirectory', 'storage');
        rejecting(window.StorageManager.prototype, 'persist', 'storage');
        rejecting(window.StorageManager.prototype, 'persisted', 'storage');
        rejecting(window.StorageManager.prototype, 'estimate', 'storage');
      }
      if (window.LockManager) {
        rejecting(window.LockManager.prototype, 'request', 'locks');
        rejecting(window.LockManager.prototype, 'query', 'locks');
      }
      if (window.ServiceWorkerContainer) {
        rejecting(window.ServiceWorkerContainer.prototype, 'register', 'serviceWorker');
        rejecting(window.ServiceWorkerContainer.prototype, 'getRegistration', 'serviceWorker');
        rejecting(window.ServiceWorkerContainer.prototype, 'getRegistrations', 'serviceWorker');
      }
    }
    ['BroadcastChannel', 'SharedWorker'].forEach(function (kind) {
      var Native = window[kind];
      if (typeof Native !== 'function') return;
      var Wrapped = function () { throw denied(kind); };
      Wrapped.prototype = Native.prototype;
      try {
        defineProperty(Wrapped, 'name', { value: Native.name, configurable: true });
        defineProperty(Wrapped, 'length', { value: Native.length, configurable: true });
        defineProperty(Native.prototype, 'constructor', { value: Wrapped, writable: true, configurable: true });
        hide(Wrapped, Native);
        defineProperty(window, kind, { value: Wrapped, writable: true, configurable: true });
      } catch (ignored) {}
    });
    var Document = window.Document;
    if (Document) {
      var cookie = getOwn(Document.prototype, 'cookie');
      if (cookie && cookie.get) {
        try {
          var get = function () { return ''; };
          var set = function () {};
          defineProperty(Document.prototype, 'cookie', { get: get, set: set, configurable: true, enumerable: true });
          hide(get, cookie.get);
          hide(set, cookie.set);
        } catch (ignored) {}
      }
    }
  }

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
          var getter = function () {
            var value = get.call(this);
            try {
              var child = name === 'contentWindow' ? value : value && value.defaultView;
              if (child) harden(child);
            } catch (ignored) {
              // Cross-origin: the engine injects into that document itself.
            }
            return value;
          };
          defineProperty(proto, name, { get: getter, set: native.set, enumerable: native.enumerable, configurable: true });
          sourcesOf(window).set(getter, nativeToString.call(get));
        } catch (ignored) {}
      });
    });
  }

  harden(window);
})();
