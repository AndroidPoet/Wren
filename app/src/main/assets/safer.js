/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Tor Browser's "Safer" level, as far as a page script can carry it: WebAssembly is gone and a
 * canvas hands out no WebGL context, so the two engines a page most often reaches past plain
 * JavaScript are not there. JavaScript itself stays on (on https and onion pages); the native
 * side turns it off for pages that arrived without either.
 *
 * A frame or popup the page creates starts as a fresh window the engine has not injected into
 * yet, so the accessors a page reaches it through give each child window the same treatment
 * first. Two ways in stay open, because no script can stand in front of them: a child window
 * read by index (`frames[0]`), and workers, which the engine never injects into.
 *
 * The page runs in the same world and can replace any built-in, so everything used after the
 * first line is taken here, before a page script has run. Installed after tor.js, whose table
 * of native-looking sources the replacements are entered in.
 */
(function () {
  'use strict';

  var apply = Reflect.apply;
  var defineProperty = Object.defineProperty;
  var getOwn = Object.getOwnPropertyDescriptor;
  var NativeString = String;
  var toLowerCase = String.prototype.toLowerCase;
  var nativeToString = Function.prototype.toString;
  var weakSet = WeakMap.prototype.set;
  var weakHas = WeakSet.prototype.has;
  var weakAdd = WeakSet.prototype.add;
  var SOURCES = Symbol.for('wren.sources');

  var WEBGL = Object.create(null);
  WEBGL.webgl = WEBGL.webgl2 = WEBGL['experimental-webgl'] = 1;

  // The windows already treated. Kept here and not on the window, where a page could read it.
  var done = new WeakSet();

  // Makes `replacement` print as the native it stands for.
  function mask(win, replacement, native) {
    try {
      var sources = win[SOURCES];
      if (sources) apply(weakSet, sources, [replacement, apply(nativeToString, native, [])]);
    } catch (e) {}
  }

  function child(win) {
    try { if (win) harden(win); } catch (e) {
      // Another origin: the engine injects into that document itself.
    }
  }

  function withoutWebGL(win, name) {
    var proto = win[name] && win[name].prototype;
    var native = proto && proto.getContext;
    if (typeof native !== 'function') return;
    var replacement = {
      getContext: function (kind) {
        if (!arguments.length) return apply(native, this, []);
        // Converted once: an object could name one kind to this check and another to the engine.
        var text = NativeString(kind);
        if (WEBGL[apply(toLowerCase, text, [])]) return null;
        var args = [text];
        for (var i = 1; i < arguments.length; i++) args[i] = arguments[i];
        return apply(native, this, args);
      },
    }.getContext;
    defineProperty(proto, 'getContext', {
      value: replacement, writable: true, configurable: true, enumerable: true,
    });
    mask(win, replacement, native);
  }

  function accessor(win, proto, property) {
    var descriptor = getOwn(proto, property);
    if (!descriptor || !descriptor.get) return;
    var get = descriptor.get;
    var getter = {
      [property]: function () {
        var value = apply(get, this, []);
        child(property === 'contentWindow' ? value : value && value.defaultView);
        return value;
      },
    }[property];
    defineProperty(proto, property, {
      get: getter, set: descriptor.set, enumerable: descriptor.enumerable, configurable: true,
    });
    mask(win, getter, get);
  }

  function svgDocument(win, proto) {
    var native = proto.getSVGDocument;
    if (typeof native !== 'function') return;
    var replacement = {
      getSVGDocument: function () {
        var value = apply(native, this, arguments);
        child(value && value.defaultView);
        return value;
      },
    }.getSVGDocument;
    defineProperty(proto, 'getSVGDocument', {
      value: replacement, writable: true, configurable: true, enumerable: true,
    });
    mask(win, replacement, native);
  }

  function popups(win) {
    var native = win.open;
    if (typeof native !== 'function') return;
    var replacement = {
      open: function () {
        var opened = apply(native, this, arguments);
        child(opened);
        return opened;
      },
    }.open;
    defineProperty(win, 'open', {
      value: replacement, writable: true, configurable: true, enumerable: true,
    });
    mask(win, replacement, native);
  }

  function harden(win) {
    if (!win || apply(weakHas, done, [win])) return;
    // First, and on its own: whatever fails below, the page does not get WebAssembly.
    try { delete win.WebAssembly; } catch (e) {}
    try {
      if (win.WebAssembly) defineProperty(win, 'WebAssembly', { value: undefined, configurable: true });
    } catch (e) {}
    apply(weakAdd, done, [win]);
    var canvases = ['HTMLCanvasElement', 'OffscreenCanvas'];
    for (var c = 0; c < canvases.length; c++) {
      try { withoutWebGL(win, canvases[c]); } catch (e) {}
    }
    try { delete win.WebGLRenderingContext; } catch (e) {}
    try { delete win.WebGL2RenderingContext; } catch (e) {}
    var frames = ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement', 'HTMLEmbedElement'];
    for (var f = 0; f < frames.length; f++) {
      try {
        var proto = win[frames[f]] && win[frames[f]].prototype;
        if (!proto) continue;
        accessor(win, proto, 'contentWindow');
        accessor(win, proto, 'contentDocument');
        svgDocument(win, proto);
      } catch (e) {}
    }
    try { popups(win); } catch (e) {}
  }

  child(window);
})();
