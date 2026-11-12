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
 * A frame the page creates starts as a fresh window the engine has not injected into yet, so
 * the frame accessors give each child window the same treatment the moment the page reaches
 * for it. Workers are realms the engine never injects into: WebAssembly stays available there.
 */
(function () {
  'use strict';

  var WEBGL = { webgl: 1, webgl2: 1, 'experimental-webgl': 1 };

  function harden(win) {
    try {
      if (!win || win.__wrenSafer) return;
      Object.defineProperty(win, '__wrenSafer', { value: true });
      try { delete win.WebAssembly; } catch (e) {}
      if (win.WebAssembly) {
        try { Object.defineProperty(win, 'WebAssembly', { value: undefined, configurable: true }); } catch (e) {}
      }
      ['HTMLCanvasElement', 'OffscreenCanvas'].forEach(function (name) {
        var proto = win[name] && win[name].prototype;
        if (!proto || !proto.getContext) return;
        var getContext = proto.getContext;
        var replacement = {
          getContext: function (kind) {
            if (WEBGL[String(kind).toLowerCase()]) return null;
            return Reflect.apply(getContext, this, arguments);
          },
        }.getContext;
        Object.defineProperty(proto, 'getContext', {
          value: replacement, writable: true, configurable: true, enumerable: false,
        });
      });
      ['WebGLRenderingContext', 'WebGL2RenderingContext'].forEach(function (name) {
        try { delete win[name]; } catch (e) {}
      });
      ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement'].forEach(function (name) {
        var proto = win[name] && win[name].prototype;
        if (!proto) return;
        ['contentWindow', 'contentDocument'].forEach(function (property) {
          var descriptor = Object.getOwnPropertyDescriptor(proto, property);
          if (!descriptor || !descriptor.get) return;
          var get = descriptor.get;
          Object.defineProperty(proto, property, {
            configurable: true,
            enumerable: descriptor.enumerable,
            get: {
              [property]: function () {
                var value = Reflect.apply(get, this, []);
                try {
                  harden(property === 'contentWindow' ? value : value && value.defaultView);
                } catch (e) {}
                return value;
              },
            }[property],
          });
        });
      });
    } catch (e) {}
  }

  harden(window);
})();
