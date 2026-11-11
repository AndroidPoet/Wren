/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Wren's scriptlet library: small functions the filter lists call by name to disarm the code
 * that detects blockers, replays ads, or fetches them another way. Names and arguments follow
 * the uBlock Origin conventions the lists are written in; implementations are Wren's own.
 *
 * Runs at document start, before any page script, inside one closure the app assembles from
 * this file, the scriptlet table and cosmetic_filter.js. Nothing here is attached to window:
 * a global with the browser's name in it would tell every page which browser it runs in.
 * The expression evaluates to the dispatcher the injector calls with the table.
 */
var runScriptlets = (function () {
  'use strict';

  var MAGIC = 'abort-' + Math.random().toString(36).slice(2);

  function toRegex(needle, flags) {
    if (!needle) return null;
    if (needle.length > 2 && needle.charAt(0) === '/' && needle.lastIndexOf('/') > 0) {
      var last = needle.lastIndexOf('/');
      try {
        return new RegExp(needle.slice(1, last), needle.slice(last + 1) || flags || '');
      } catch (e) { /* fall through to a literal match */ }
    }
    return new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), flags || '');
  }

  function matchesNeedle(text, needle) {
    if (!needle) return true;
    var negate = needle.charAt(0) === '!';
    if (negate) needle = needle.slice(1);
    var re = toRegex(needle);
    var hit = re ? re.test(String(text)) : true;
    return negate ? !hit : hit;
  }

  function parseValue(raw) {
    switch (raw) {
      case 'undefined': return undefined;
      case 'null': return null;
      case 'true': return true;
      case 'false': return false;
      case '': return '';
      case 'noopFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc': return function () { throw new Error(MAGIC); };
      case '[]': case 'emptyArr': return [];
      case '{}': case 'emptyObj': return {};
      case 'yes': return 'yes';
      case 'no': return 'no';
    }
    if (/^-?\d+(\.\d+)?$/.test(raw)) return Number(raw);
    if (raw.length > 1 && (raw.charAt(0) === "'" || raw.charAt(0) === '"') &&
        raw.charAt(raw.length - 1) === raw.charAt(0)) {
      return raw.slice(1, -1);
    }
    return raw;
  }

  var swallowing = false;
  // A nameless mark on a request the page must not see through; no string a page can look for.
  // Which XHRs were matched lives in a map the page cannot reach: a property on the request
  // object, even a Symbol, is listed by getOwnPropertySymbols and can be deleted before send().
  var blockedXhr = typeof WeakMap === 'function' ? new WeakMap() : null;
  var BLOCKED = typeof Symbol === 'function' ? Symbol() : '__' + MAGIC;
  function markBlocked(xhr, value) {
    if (blockedXhr) blockedXhr.set(xhr, value); else xhr[BLOCKED] = value;
  }
  function isBlocked(xhr) {
    return blockedXhr ? blockedXhr.get(xhr) === true : xhr[BLOCKED] === true;
  }
  function swallowMagicErrors() {
    if (swallowing) return;
    swallowing = true;
    window.addEventListener('error', function (e) {
      if (e && e.message && String(e.message).indexOf(MAGIC) >= 0) {
        e.stopImmediatePropagation();
        e.preventDefault();
      }
    }, true);
  }

  /** Walks `a.b.c` from `owner`, installing `install(obj, key)` at the leaf once it exists. */
  function trapChain(owner, chain, install) {
    var dot = chain.indexOf('.');
    if (dot < 0) {
      install(owner, chain);
      return;
    }
    var head = chain.slice(0, dot);
    var rest = chain.slice(dot + 1);
    var current = owner[head];
    if (current && (typeof current === 'object' || typeof current === 'function')) {
      trapChain(current, rest, install);
      return;
    }
    var descriptor = Object.getOwnPropertyDescriptor(owner, head);
    if (descriptor && descriptor.configurable === false) return;
    try {
      Object.defineProperty(owner, head, {
        configurable: true,
        enumerable: true,
        get: function () { return current; },
        set: function (value) {
          current = value;
          if (value && (typeof value === 'object' || typeof value === 'function')) {
            trapChain(value, rest, install);
          }
        }
      });
    } catch (e) { /* not configurable */ }
  }

  function defineConstant(obj, key, value) {
    var descriptor = Object.getOwnPropertyDescriptor(obj, key);
    if (descriptor && descriptor.configurable === false) {
      try { obj[key] = value; } catch (e) {}
      return;
    }
    try {
      Object.defineProperty(obj, key, {
        configurable: true,
        enumerable: descriptor ? descriptor.enumerable !== false : true,
        get: function () { return value; },
        set: function () {}
      });
    } catch (e) {}
  }

  function currentScriptMatches(needle) {
    var script = document.currentScript;
    if (!script) return false;
    if (!needle) return true;
    return matchesNeedle(script.textContent || '', needle);
  }

  function onReady(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn, { once: true });
    } else {
      fn();
    }
  }

  function observe(fn) {
    var run = function () {
      try { fn(); } catch (e) {}
    };
    onReady(function () {
      run();
      new MutationObserver(run).observe(document.documentElement, { childList: true, subtree: true, attributes: true });
    });
  }

  function propsMatch(url, method, props) {
    if (!props) return true;
    var parts = props.split(/\s+/);
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (!part) continue;
      var colon = part.indexOf(':');
      var key = colon > 0 ? part.slice(0, colon) : 'url';
      var needle = colon > 0 ? part.slice(colon + 1) : part;
      if (key === 'url' || key === 'propsToMatch') {
        if (!matchesNeedle(url, needle)) return false;
      } else if (key === 'method') {
        if (!matchesNeedle(method || 'GET', needle)) return false;
      }
    }
    return true;
  }

  function prune(obj, rawPaths, needles) {
    if (!obj || typeof obj !== 'object') return obj;
    if (needles) {
      var ok = needles.split(/\s+/).every(function (needle) {
        return !needle || hasPath(obj, needle.split('.'));
      });
      if (!ok) return obj;
    }
    rawPaths.split(/\s+/).forEach(function (path) {
      if (path) removePath(obj, path.split('.'));
    });
    return obj;
  }

  function hasPath(obj, tokens) {
    var node = obj;
    for (var i = 0; i < tokens.length; i++) {
      if (node === null || typeof node !== 'object') return false;
      var token = tokens[i];
      if (token === '[]' || token === '*') {
        var rest = tokens.slice(i + 1);
        var keys = Array.isArray(node) ? node.map(function (_, k) { return k; }) : Object.keys(node);
        return keys.some(function (k) { return rest.length === 0 || hasPath(node[k], rest); });
      }
      if (!(token in node)) return false;
      node = node[token];
    }
    return true;
  }

  function removePath(obj, tokens) {
    if (obj === null || typeof obj !== 'object') return;
    var token = tokens[0];
    var rest = tokens.slice(1);
    if (token === '[]' || token === '*' || token === '[-]') {
      var keys = Array.isArray(obj) ? obj.map(function (_, k) { return k; }) : Object.keys(obj);
      keys.forEach(function (k) {
        if (rest.length === 0) delete obj[k]; else removePath(obj[k], rest);
      });
      return;
    }
    if (!(token in obj)) return;
    if (rest.length === 0) delete obj[token]; else removePath(obj[token], rest);
  }

  function wrapFetchText(matcher, transform) {
    var original = window.fetch;
    if (typeof original !== 'function') return;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      var result = original.apply(this, arguments);
      if (!matcher(url, method)) return result;
      return result.then(function (response) {
        return response.clone().text().then(function (text) {
          var edited = transform(text);
          if (edited === text) return response;
          return new Response(edited, {
            status: response.status,
            statusText: response.statusText,
            headers: response.headers
          });
        }).catch(function () { return response; });
      });
    };
  }

  function wrapXhrText(matcher, transform) {
    var open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      if (matcher(String(url), method)) {
        var xhr = this;
        xhr.addEventListener('readystatechange', function () {
          if (xhr.readyState !== 4) return;
          try {
            if (xhr.responseType && xhr.responseType !== 'text' && xhr.responseType !== 'json') return;
            var text = xhr.responseType === 'json' ? JSON.stringify(xhr.response) : xhr.responseText;
            var edited = transform(text);
            if (edited === text) return;
            var value = xhr.responseType === 'json' ? JSON.parse(edited) : edited;
            Object.defineProperty(xhr, 'response', { configurable: true, get: function () { return value; } });
            Object.defineProperty(xhr, 'responseText', { configurable: true, get: function () { return edited; } });
          } catch (e) {}
        }, true);
      }
      return open.apply(this, arguments);
    };
  }

  function pruneJsonText(text, paths, needles) {
    try {
      var parsed = JSON.parse(text);
      prune(parsed, paths, needles);
      return JSON.stringify(parsed);
    } catch (e) {
      return text;
    }
  }

  function replaceText(text, pattern, replacement) {
    if (pattern === '' || pattern === undefined) return text;
    var re = toRegex(pattern, 'g');
    return re ? text.replace(re, replacement || '') : text;
  }

  function fakeXhr(xhr) {
    var fire = function (type) { xhr.dispatchEvent(new Event(type)); };
    Object.defineProperty(xhr, 'readyState', { configurable: true, get: function () { return 4; } });
    Object.defineProperty(xhr, 'status', { configurable: true, get: function () { return 200; } });
    Object.defineProperty(xhr, 'statusText', { configurable: true, get: function () { return 'OK'; } });
    Object.defineProperty(xhr, 'response', { configurable: true, get: function () { return ''; } });
    Object.defineProperty(xhr, 'responseText', { configurable: true, get: function () { return ''; } });
    Object.defineProperty(xhr, 'responseURL', { configurable: true, get: function () { return ''; } });
    setTimeout(function () { fire('readystatechange'); fire('load'); fire('loadend'); }, 1);
  }

  var lib = {};

  lib['set-constant'] = function (prop, raw) {
    if (!prop) return;
    var value = parseValue(raw === undefined ? '' : raw);
    trapChain(window, prop, function (obj, key) { defineConstant(obj, key, value); });
  };

  lib['abort-on-property-read'] = function (prop) {
    if (!prop) return;
    swallowMagicErrors();
    trapChain(window, prop, function (obj, key) {
      var value = obj[key];
      try {
        Object.defineProperty(obj, key, {
          configurable: true,
          get: function () { throw new ReferenceError(MAGIC); },
          set: function (v) { value = v; }
        });
      } catch (e) {}
    });
  };

  lib['abort-on-property-write'] = function (prop) {
    if (!prop) return;
    swallowMagicErrors();
    trapChain(window, prop, function (obj, key) {
      try {
        Object.defineProperty(obj, key, {
          configurable: true,
          get: function () { return undefined; },
          set: function () { throw new ReferenceError(MAGIC); }
        });
      } catch (e) {}
    });
  };

  lib['abort-current-script'] = function (prop, needle) {
    if (!prop) return;
    swallowMagicErrors();
    trapChain(window, prop, function (obj, key) {
      var value = obj[key];
      var descriptor = Object.getOwnPropertyDescriptor(obj, key);
      var getter = descriptor && descriptor.get;
      var setter = descriptor && descriptor.set;
      try {
        Object.defineProperty(obj, key, {
          configurable: true,
          get: function () {
            if (currentScriptMatches(needle)) throw new ReferenceError(MAGIC);
            return getter ? getter.call(this) : value;
          },
          set: function (v) {
            if (currentScriptMatches(needle)) throw new ReferenceError(MAGIC);
            if (setter) setter.call(this, v); else value = v;
          }
        });
      } catch (e) {}
    });
  };

  lib['json-prune'] = function (paths, needles) {
    if (!paths) return;
    var parse = JSON.parse;
    JSON.parse = function () {
      return prune(parse.apply(this, arguments), paths, needles);
    };
    if (window.Response && Response.prototype.json) {
      var json = Response.prototype.json;
      Response.prototype.json = function () {
        return json.apply(this, arguments).then(function (data) { return prune(data, paths, needles); });
      };
    }
  };

  lib['json-prune-fetch-response'] = function (paths, needles, _label, props) {
    if (!paths) return;
    wrapFetchText(function (url, method) { return propsMatch(url, method, props); },
      function (text) { return pruneJsonText(text, paths, needles); });
  };

  lib['json-prune-xhr-response'] = function (paths, needles, _label, props) {
    if (!paths) return;
    wrapXhrText(function (url, method) { return propsMatch(url, method, props); },
      function (text) { return pruneJsonText(text, paths, needles); });
  };

  lib['trusted-replace-fetch-response'] = function (pattern, replacement, props) {
    wrapFetchText(function (url, method) { return propsMatch(url, method, props); },
      function (text) { return replaceText(text, pattern, replacement); });
  };

  lib['trusted-replace-xhr-response'] = function (pattern, replacement, props) {
    wrapXhrText(function (url, method) { return propsMatch(url, method, props); },
      function (text) { return replaceText(text, pattern, replacement); });
  };

  function timerDefuser(name, needle, delay) {
    var original = window[name];
    var wanted = delay === undefined || delay === '' ? null : Number(delay);
    window[name] = function (fn, ms) {
      var text = typeof fn === 'function' ? fn.toString() : String(fn);
      var delayMatches = wanted === null || Number(ms) === wanted;
      if (delayMatches && matchesNeedle(text, needle)) return 0;
      return original.apply(this, arguments);
    };
  }

  lib['no-setTimeout-if'] = function (needle, delay) { timerDefuser('setTimeout', needle, delay); };
  lib['no-setInterval-if'] = function (needle, delay) { timerDefuser('setInterval', needle, delay); };

  function timerBooster(name, needle, delay, boost) {
    var original = window[name];
    var wanted = delay === undefined || delay === '' ? null : Number(delay);
    var factor = boost === undefined || boost === '' ? 0.05 : Number(boost);
    if (!(factor > 0)) factor = 0.05;
    window[name] = function (fn, ms) {
      var text = typeof fn === 'function' ? fn.toString() : String(fn);
      var delayMatches = wanted === null || Number(ms) === wanted;
      if (delayMatches && matchesNeedle(text, needle)) {
        arguments[1] = Number(ms) * factor;
      }
      return original.apply(this, arguments);
    };
  }

  lib['nano-setTimeout-booster'] = function (needle, delay, boost) { timerBooster('setTimeout', needle, delay, boost); };
  lib['nano-setInterval-booster'] = function (needle, delay, boost) { timerBooster('setInterval', needle, delay, boost); };

  lib['addEventListener-defuser'] = function (type, handler) {
    var original = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (eventType, listener) {
      var text = typeof listener === 'function' ? listener.toString() : String(listener);
      if (matchesNeedle(String(eventType), type) && matchesNeedle(text, handler)) return;
      return original.apply(this, arguments);
    };
  };

  lib['no-window-open-if'] = function (needle) {
    var original = window.open;
    window.open = function (url) {
      if (matchesNeedle(String(url || ''), needle)) return null;
      return original.apply(this, arguments);
    };
  };

  lib['no-fetch-if'] = function (props, body) {
    var original = window.fetch;
    if (typeof original !== 'function') return;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      if (!propsMatch(url, method, props)) return original.apply(this, arguments);
      var text = body === 'emptyObj' ? '{}' : body === 'emptyArr' ? '[]' : '';
      return Promise.resolve(new Response(text, { status: 200, statusText: 'OK' }));
    };
  };

  lib['no-xhr-if'] = function (props) {
    var open = XMLHttpRequest.prototype.open;
    var send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      markBlocked(this, propsMatch(String(url), method, props));
      return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      if (isBlocked(this)) { fakeXhr(this); return; }
      return send.apply(this, arguments);
    };
  };

  lib['remove-attr'] = function (attrs, selector) {
    if (!attrs) return;
    var names = attrs.split(/\|/);
    var query = selector || names.map(function (n) { return '[' + n + ']'; }).join(',');
    observe(function () {
      document.querySelectorAll(query).forEach(function (el) {
        names.forEach(function (n) { el.removeAttribute(n); });
      });
    });
  };

  lib['remove-class'] = function (classes, selector) {
    if (!classes) return;
    var names = classes.split(/\|/);
    var query = selector || names.map(function (n) { return '.' + n; }).join(',');
    observe(function () {
      document.querySelectorAll(query).forEach(function (el) {
        names.forEach(function (n) { el.classList.remove(n); });
      });
    });
  };

  // A page that enforces Trusted Types rejects plain strings on script text; a policy that
  // passes the text through is what the page's own code would use.
  var scriptPolicy;
  function scriptText(text) {
    try {
      if (window.trustedTypes && trustedTypes.createPolicy) {
        if (!scriptPolicy) {
          scriptPolicy = trustedTypes.createPolicy('nodeText', { createScript: function (s) { return s; } });
        }
        return scriptPolicy.createScript(text);
      }
    } catch (ignored) {}
    return text;
  }

  function nodeTextEditor(nodeName, needle, replacement) {
    if (!nodeName) return;
    var tag = nodeName.toUpperCase();
    var pattern = toRegex(needle, 'g');
    var edit = function (node) {
      if (node.nodeType !== 1 || node.tagName !== tag) return;
      var text = node.textContent;
      if (!pattern || !pattern.test(text)) return;
      pattern.lastIndex = 0;
      var edited = replacement === undefined ? '' : text.replace(pattern, replacement);
      try {
        node.textContent = tag === 'SCRIPT' ? scriptText(edited) : edited;
      } catch (ignored) {}
    };
    new MutationObserver(function (records) {
      records.forEach(function (record) { record.addedNodes.forEach(edit); });
    }).observe(document, { childList: true, subtree: true });
  }

  lib['remove-node-text'] = function (nodeName, needle) { nodeTextEditor(nodeName, needle); };
  lib['replace-node-text'] = function (nodeName, needle, replacement) { nodeTextEditor(nodeName, needle, replacement || ''); };

  lib['noeval'] = function () { window.eval = function () {}; };
  lib['noeval-if'] = function (needle) {
    var original = window.eval;
    window.eval = function (code) {
      if (matchesNeedle(String(code), needle)) return;
      return original.apply(this, arguments);
    };
  };

  lib['nowebrtc'] = function () {
    var Dummy = function () {};
    Dummy.prototype = {
      createDataChannel: function () { return { close: function () {} }; },
      createOffer: function () { return Promise.resolve(); },
      createAnswer: function () { return Promise.resolve(); },
      setLocalDescription: function () { return Promise.resolve(); },
      setRemoteDescription: function () { return Promise.resolve(); },
      addIceCandidate: function () { return Promise.resolve(); },
      addEventListener: function () {},
      close: function () {}
    };
    window.RTCPeerConnection = Dummy;
    window.webkitRTCPeerConnection = Dummy;
  };

  function detectorDummy() {
    var Dummy = function () { return this; };
    var noop = function () { return this; };
    Dummy.prototype = { check: noop, clearEvent: noop, emitEvent: noop, on: noop, onDetected: noop, onNotDetected: noop, setOption: noop };
    return Dummy;
  }

  lib['nobab'] = function () {
    var D = detectorDummy();
    window.blockAdBlock = new D();
    window.BlockAdBlock = D;
  };
  lib['nofab'] = function () {
    var D = detectorDummy();
    window.fuckAdBlock = new D();
    window.FuckAdBlock = D;
  };
  lib['popads-dummy'] = function () {
    ['PopAds', 'popns'].forEach(function (name) {
      try {
        Object.defineProperty(window, name, { configurable: true, get: function () { throw new ReferenceError(MAGIC); }, set: function () {} });
      } catch (e) {}
    });
    swallowMagicErrors();
  };

  lib['set-cookie'] = function (name, value, path) {
    if (!name) return;
    try {
      document.cookie = encodeURIComponent(name) + '=' + encodeURIComponent(String(parseValue(value === undefined ? '' : value))) +
        '; path=' + (path || '/') + '; max-age=31536000';
    } catch (e) {}
  };

  // The storage is looked up per call: in a frame that may not use storage (a third-party one
  // under partition.js, or a sandboxed one) reading it throws, and at load time that would take
  // every other scriptlet in the frame down with it.
  function storageSetter(name) {
    return function (key, value) {
      if (!key) return;
      try {
        var storage = window[name];
        var v = value === undefined ? '' : value;
        var parsed = v === '$remove$' ? null : parseValue(v);
        if (parsed === null && v === '$remove$') storage.removeItem(key);
        else storage.setItem(key, typeof parsed === 'string' ? parsed : JSON.stringify(parsed));
      } catch (e) {}
    };
  }
  lib['set-local-storage-item'] = storageSetter('localStorage');
  lib['set-session-storage-item'] = storageSetter('sessionStorage');

  lib['remove-cookie'] = function (needle) {
    var clear = function () {
      document.cookie.split(';').forEach(function (entry) {
        var name = entry.split('=')[0].trim();
        if (name && matchesNeedle(name, needle)) {
          document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
          document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=' + location.hostname;
        }
      });
    };
    clear();
    window.addEventListener('beforeunload', clear);
  };

  lib['no-cookie-if'] = function (needle) {
    var descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    if (!descriptor || !descriptor.set) return;
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: function () { return descriptor.get.call(document); },
      set: function (value) {
        var name = String(value).split('=')[0].trim();
        if (matchesNeedle(name, needle)) return;
        descriptor.set.call(document, value);
      }
    });
  };

  lib['href-sanitizer'] = function (selector, source) {
    if (!selector) return;
    observe(function () {
      document.querySelectorAll(selector).forEach(function (a) {
        var target = null;
        if (!source || source === 'text') {
          target = (a.textContent || '').trim();
        } else if (source.charAt(0) === '?') {
          try { target = new URL(a.href).searchParams.get(source.slice(1)); } catch (e) {}
        } else if (source.charAt(0) === '[' && source.charAt(source.length - 1) === ']') {
          target = a.getAttribute(source.slice(1, -1));
        }
        if (target && /^https?:\/\//.test(target) && a.href !== target) a.href = target;
      });
    });
  };

  var aliases = {
    'set': 'set-constant',
    'aopr': 'abort-on-property-read',
    'aopw': 'abort-on-property-write',
    'acs': 'abort-current-script',
    'acis': 'abort-current-script',
    'abort-current-inline-script': 'abort-current-script',
    'nostif': 'no-setTimeout-if',
    'setTimeout-defuser': 'no-setTimeout-if',
    'nosiif': 'no-setInterval-if',
    'nano-stb': 'nano-setTimeout-booster',
    'nano-sib': 'nano-setInterval-booster',
    'aeld': 'addEventListener-defuser',
    'nowoif': 'no-window-open-if',
    'window.open-defuser': 'no-window-open-if',
    'prevent-fetch': 'no-fetch-if',
    'prevent-xhr': 'no-xhr-if',
    'ra': 'remove-attr',
    'rc': 'remove-class',
    'rmnt': 'remove-node-text',
    'rpnt': 'replace-node-text',
    'cookie-remover': 'remove-cookie',
    'trusted-rpfr': 'trusted-replace-fetch-response',
    'trusted-rpnt': 'replace-node-text',
    'trusted-replace-node-text': 'replace-node-text',
    'trusted-set': 'set-constant',
    'trusted-set-constant': 'set-constant',
    'trusted-set-cookie': 'set-cookie',
    'trusted-set-local-storage-item': 'set-local-storage-item',
    'trusted-set-session-storage-item': 'set-session-storage-item',
    'trusted-prevent-xhr': 'no-xhr-if',
    'trusted-prevent-fetch': 'no-fetch-if',
    'prevent-setTimeout': 'no-setTimeout-if',
    'prevent-setInterval': 'no-setInterval-if',
    'prevent-addEventListener': 'addEventListener-defuser',
    'prevent-window-open': 'no-window-open-if',
    'prevent-eval-if': 'noeval-if',
    'adjust-setTimeout': 'nano-setTimeout-booster',
    'adjust-setInterval': 'nano-setInterval-booster',
    'norafif': 'no-requestAnimationFrame-if',
    'prevent-refresh': 'refresh-defuser'
  };

  lib['set-attr'] = function (selector, attr, value) {
    if (!selector || !attr) return;
    var allowed = value === undefined || value === '' || value === 'true' || value === 'false' ||
      /^\d+$/.test(value) || value === '[]' || value === '{}';
    if (!allowed) return;
    var v = value === undefined ? '' : value;
    observe(function () {
      document.querySelectorAll(selector).forEach(function (el) {
        if (el.getAttribute(attr) !== v) el.setAttribute(attr, v);
      });
    });
  };

  lib['refresh-defuser'] = function (delay) {
    var wanted = delay === undefined || delay === '' ? null : Number(delay);
    observe(function () {
      document.querySelectorAll('meta[http-equiv="refresh" i]').forEach(function (meta) {
        var content = meta.getAttribute('content') || '';
        var seconds = parseFloat(content);
        if (wanted !== null && seconds !== wanted) return;
        meta.remove();
      });
    });
  };

  lib['no-requestAnimationFrame-if'] = function (needle) {
    var native = window.requestAnimationFrame;
    if (typeof native !== 'function') return;
    var negate = needle && needle.charAt(0) === '!';
    var re = toRegex(negate ? needle.slice(1) : needle);
    window.requestAnimationFrame = function (callback) {
      var hit = re ? re.test(String(callback)) : true;
      if (negate ? !hit : hit) return 0;
      return native.apply(this, arguments);
    };
  };

  lib['disable-newtab-links'] = function () {
    document.addEventListener('click', function (event) {
      var target = event.target;
      while (target && target.tagName !== 'A') target = target.parentElement;
      if (target && target.getAttribute('target') === '_blank') event.preventDefault();
    }, true);
  };

  lib['set-cookie-reload'] = function (name, value, path) {
    if (!name) return;
    var present = document.cookie.split(';').some(function (c) { return c.trim().indexOf(name + '=') === 0; });
    lib['set-cookie'](name, value, path);
    if (!present) {
      var now = document.cookie.split(';').some(function (c) { return c.trim().indexOf(name + '=') === 0; });
      if (now) location.reload();
    }
  };

  function splitArgs(call) {
    var args = [];
    var current = '';
    for (var i = 0; i < call.length; i++) {
      var c = call.charAt(i);
      if (c === '\\' && call.charAt(i + 1) === ',') { current += ','; i++; continue; }
      if (c === ',') { args.push(current.trim()); current = ''; continue; }
      current += c;
    }
    args.push(current.trim());
    return args.map(function (arg) {
      if (arg.length > 1 && (arg.charAt(0) === "'" || arg.charAt(0) === '"') && arg.charAt(arg.length - 1) === arg.charAt(0)) {
        return arg.slice(1, -1);
      }
      return arg;
    });
  }

  function callsFor(table, host) {
    var calls = [];
    var excepted = [];
    var labels = host.split('.');
    for (var i = 0; i < labels.length - 1; i++) {
      var domain = labels.slice(i).join('.');
      if (table.d[domain]) calls = calls.concat(table.d[domain]);
      if (table.x[domain]) excepted = excepted.concat(table.x[domain]);
    }
    if (excepted.indexOf('') >= 0) return [];
    return calls.filter(function (call) { return excepted.indexOf(call) < 0; });
  }

  return function (table) {
    var calls = callsFor(table, location.hostname);
    for (var i = 0; i < calls.length; i++) {
      var args = splitArgs(calls[i]);
      var raw = args[0].replace(/\.js$/, '');
      var name = aliases[raw] || raw;
      var fn = lib[name];
      if (!fn) continue;
      try { fn.apply(null, args.slice(1)); } catch (e) {}
    }
    return calls.length;
  };
})();
