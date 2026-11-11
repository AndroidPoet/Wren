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
  // Natives used at call time, taken before any page script can replace them.
  var apply = Reflect.apply;
  var weakGet = WeakMap.prototype.get;
  var weakSet = WeakMap.prototype.set;

  function each(list, fn) {
    for (var i = 0; i < list.length; i++) fn(list[i], i);
  }

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

  // The registrable domain, by the rule the engine applies to cookies: the last two labels, or
  // three when the second-level label is one of the short public suffixes (co.uk, com.au ...).
  var SHORT_SECOND_LEVEL = /^(co|com|net|org|gov|edu|ac|ltd|plc|me|or|ne|go|gr|sch)$/;
  // The site is schemeful, as the engine's cookie boundary is: an http page and an https frame
  // of one host are two sites. An IPv6 host keeps its brackets whole; an address is its own site.
  function siteOf(origin) {
    if (typeof origin !== 'string') return null;
    var match = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]+)/i.exec(origin);
    if (!match) return null;
    var scheme = match[1].toLowerCase();
    var authority = match[2];
    var host;
    if (authority.charAt(0) === '[') {
      var close = authority.indexOf(']');
      if (close < 0) return null;
      host = authority.slice(0, close + 1).toLowerCase();
    } else {
      host = authority.split(':')[0].toLowerCase();
    }
    if (!host) return null;
    if (/^[\d.]+$/.test(host) || host.charAt(0) === '[' || host.indexOf('.') < 0) {
      return scheme + '://' + host;
    }
    var labels = host.split('.');
    var keep = labels.length >= 3 && labels[labels.length - 1].length === 2 &&
      SHORT_SECOND_LEVEL.test(labels[labels.length - 2]) ? 3 : 2;
    return scheme + '://' + labels.slice(-keep).join('.');
  }

  // Third-party when any ancestor is another site, not only the top one: a frame of the site
  // inside a frame of another site is cross-site to the engine too. The frame's own origin is
  // the one it runs as, which for an about:blank or srcdoc child is the parent's, and for a
  // sandboxed or data: frame is opaque, which gets nothing.
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
    var ownOrigin;
    try { ownOrigin = typeof window.origin === 'string' ? window.origin : window.location.origin; } catch (ignored) { return true; }
    var own = siteOf(ownOrigin);
    if (own === null) return true;
    for (var i = 0; i < ancestors.length; i++) {
      var ancestor = siteOf(ancestors[i]);
      if (ancestor === null || ancestor !== own) return true;
    }
    return false;
  }

  // [openedByThirdParty]: a popup is its own top, so it would read as first-party; one a
  // third-party frame opened runs as that frame's origin, and is denied what the frame is.
  function harden(window, openedByThirdParty) {
    if (window[MARK]) return;
    try { defineProperty(window, MARK, { value: true }); } catch (ignored) { return; }
    hardenFrames(window);
    if (!openedByThirdParty && !isThirdParty(window)) return;
    var sources = sourcesOf(window);

    function denied(what) {
      var message = "Failed to read the '" + what + "' property from 'Window': Access is denied for this document.";
      try { return new window.DOMException(message, 'SecurityError'); } catch (ignored) { return new Error(message); }
    }
    function hide(fn, original, name) {
      try {
        apply(weakSet, sources, [fn, typeof original === 'function'
          ? apply(nativeToString, original, [])
          : 'function ' + (name || '') + '() { [native code] }']);
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
    each(['BroadcastChannel', 'SharedWorker'], function (kind) {
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
    if (window.StorageBucketManager) {
      rejecting(window.StorageBucketManager.prototype, 'open', 'storageBuckets');
      rejecting(window.StorageBucketManager.prototype, 'keys', 'storageBuckets');
      rejecting(window.StorageBucketManager.prototype, 'delete', 'storageBuckets');
    }
    // The old file system API keeps a per-site sandbox of its own; it answers through its error
    // callback, as Chrome's denial does.
    each([['webkitRequestFileSystem', 3], ['webkitResolveLocalFileSystemURL', 2]], function (entry) {
      var name = entry[0];
      var original = window[name];
      if (typeof original !== 'function') return;
      try {
        var fn = function () {
          var callback = arguments[entry[1]];
          var error = denied(name);
          if (typeof callback === 'function') window.setTimeout(function () { callback(error); }, 0);
        };
        defineProperty(window, name, { value: fn, writable: true, enumerable: true, configurable: true });
        hide(fn, original, name);
      } catch (ignored) {}
    });
    // A dedicated worker the frame starts is a realm of the same site with every one of those
    // APIs again, and the engine injects nothing into it.
    try { workerRealm(window, apply(nativeToString, workerRealm, []), undefined, true); } catch (ignored) {}
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

  /**
   * A worker of a third-party frame starts from a bootstrap that runs this function in its scope
   * and then loads the frame's script, with relative addresses still resolved against that
   * script: the worker is denied what the frame is, and a worker it starts in turn gets the same.
   * It is serialised into the bootstrap, so it uses nothing from outside its own body. With
   * [workersOnly] it only wraps the scope's Worker constructor: the frame's own denials are made
   * by harden. No bootstrap, no worker: the frame's script never runs in a realm left bare.
   */
  function workerRealm(scope, source, base, workersOnly) {
    'use strict';
    var apply = Reflect.apply;
    var construct = Reflect.construct;
    var defineProperty = Object.defineProperty;
    var getOwn = Object.getOwnPropertyDescriptor;
    var ownNames = Object.getOwnPropertyNames;
    var stringify = JSON.stringify;
    var nativeToString = Function.prototype.toString;
    var weakGet = WeakMap.prototype.get;
    var weakSet = WeakMap.prototype.set;
    var SOURCES = Symbol.for('wren.sources');
    var NativeURL = scope.URL;
    var NativeBlob = scope.Blob;
    var createObjectURL = NativeURL && NativeURL.createObjectURL;
    var trustedTypes = scope.trustedTypes;
    var sources = scope[SOURCES];
    if (!sources) {
      sources = new WeakMap();
      try {
        defineProperty(scope, SOURCES, { value: sources });
        var toString = scope.Function.prototype.toString;
        var masked = function toString() {
          var text = apply(weakGet, sources, [this]);
          return text !== undefined ? text : apply(nativeToString, this, []);
        };
        apply(weakSet, sources, [masked, apply(nativeToString, toString, [])]);
        defineProperty(scope.Function.prototype, 'toString', { value: masked, writable: true, configurable: true });
      } catch (ignored) {}
    }
    function hide(fn, original) {
      try { apply(weakSet, sources, [fn, apply(nativeToString, original, [])]); } catch (ignored) {}
    }
    function each(list, fn) {
      for (var i = 0; i < list.length; i++) fn(list[i], i);
    }
    function denied(what) {
      var message = "Failed to read the '" + what + "' property from 'WorkerGlobalScope': Access is denied for this document.";
      try { return new scope.DOMException(message, 'SecurityError'); } catch (ignored) { return new Error(message); }
    }

    var Native = scope.Worker;
    if (typeof Native === 'function' && NativeURL && NativeBlob && typeof createObjectURL === 'function') {
      var bootstraps = {};
      var Wrapped = function (url, options) {
        if (!new.target) {
          throw new TypeError("Failed to construct 'Worker': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
        }
        var target;
        try {
          var resolved = new NativeURL(String(url), base || scope.location.href).href;
          var isModule = !!(options && options.type === 'module');
          var key = (isModule ? 'module ' : 'classic ') + resolved;
          target = bootstraps[key];
          if (!target) {
            var load = isModule
              ? 'import(' + stringify(resolved) + ');'
              : 'importScripts(' + stringify(resolved) + ');';
            var body = '(' + source + ')(self, ' + stringify(source) + ', ' + stringify(resolved) + ', false);\n' + load;
            target = apply(createObjectURL, NativeURL, [new NativeBlob([body], { type: 'text/javascript' })]);
            bootstraps[key] = target;
          }
        } catch (ignored) {
          throw new scope.DOMException("Failed to construct 'Worker': the script could not be prepared.", 'SecurityError');
        }
        try {
          return construct(Native, [target, options], new.target);
        } catch (error) {
          // A frame that requires Trusted Types runs every worker address through its own
          // policy, which knows only the frame's own addresses and refuses the bootstrap; the
          // page's CSP names the policies it may create, so no other can vouch for it. Cloudflare's
          // Turnstile frame is one, and without its worker it reports the browser unsupported.
          // There the worker starts as the frame asked, without the denials: the frame's own
          // storage stays denied. Any other refusal stands.
          if (!trustedTypes || !trustedTypes.defaultPolicy) throw error;
          return construct(Native, [url, options], new.target);
        }
      };
      Wrapped.prototype = Native.prototype;
      try {
        defineProperty(Wrapped, 'name', { value: Native.name, configurable: true });
        defineProperty(Wrapped, 'length', { value: Native.length, configurable: true });
        defineProperty(Native.prototype, 'constructor', { value: Wrapped, writable: true, configurable: true });
        each(ownNames(Native), function (key) {
          if (key === 'prototype' || key === 'name' || key === 'length') return;
          try { defineProperty(Wrapped, key, getOwn(Native, key)); } catch (ignored) {}
        });
        hide(Wrapped, Native);
        defineProperty(scope, 'Worker', { value: Wrapped, writable: true, configurable: true });
      } catch (ignored) {}
    }
    if (workersOnly) return;

    // In a worker these accessors live on WorkerGlobalScope.prototype, not on the global itself;
    // the denial is defined on the global, in front of them.
    function throwing(name) {
      try {
        var native;
        for (var owner = scope; owner && !native; owner = Object.getPrototypeOf(owner)) native = getOwn(owner, name);
        if (!native) return;
        var getter = function () { throw denied(name); };
        defineProperty(scope, name, { get: getter, configurable: true, enumerable: native.enumerable });
        if (native.get) hide(getter, native.get);
      } catch (ignored) {}
    }
    function rejecting(Interface, names, what) {
      if (typeof Interface !== 'function') return;
      each(names, function (name) {
        try {
          var original = Interface.prototype[name];
          if (typeof original !== 'function') return;
          var fn = function () { return scope.Promise.reject(denied(what)); };
          defineProperty(Interface.prototype, name, { value: fn, writable: true, configurable: true });
          hide(fn, original);
        } catch (ignored) {}
      });
    }
    throwing('indexedDB');
    throwing('caches');
    rejecting(scope.StorageManager, ['getDirectory', 'persist', 'persisted', 'estimate'], 'storage');
    rejecting(scope.LockManager, ['request', 'query'], 'locks');
    rejecting(scope.StorageBucketManager, ['open', 'keys', 'delete'], 'storageBuckets');
    each(['BroadcastChannel', 'webkitRequestFileSystemSync', 'webkitResolveLocalFileSystemSyncURL'], function (name) {
      var original = scope[name];
      if (typeof original !== 'function') return;
      var fn = function () { throw denied(name); };
      try {
        if (original.prototype) {
          fn.prototype = original.prototype;
          defineProperty(fn, 'name', { value: original.name, configurable: true });
          defineProperty(original.prototype, 'constructor', { value: fn, writable: true, configurable: true });
        }
        hide(fn, original);
        defineProperty(scope, name, { value: fn, writable: true, configurable: true });
      } catch (ignored) {}
    });
    if (base) resolveAgainst(base);

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
  }

  function hardenFrames(window) {
    // A popup from window.open() starts as a blank document of the opener's own origin that the
    // opener scripts directly; the engine injects nothing into it. It gets what a frame gets.
    var nativeOpen = window.open;
    if (typeof nativeOpen === 'function') {
      var open = function open() {
        var opened = apply(nativeOpen, this, arguments);
        try {
          if (opened) harden(opened, isThirdParty(window));
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
          defineProperty(proto, name, { get: getter, set: native.set, enumerable: native.enumerable, configurable: true });
          apply(weakSet, sourcesOf(window), [getter, apply(nativeToString, get, [])]);
        } catch (ignored) {}
      });
    });
  }

  harden(window);
})();
