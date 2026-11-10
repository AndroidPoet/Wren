/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * What a page must not reach from a private or Tor tab.
 *
 * WebRTC negotiates its own UDP sockets past any proxy and hands the page the real address, so
 * it is removed outright. The motion and orientation sensors fire without a permission prompt
 * in a WebView; their readings identify the device by its calibration noise and reveal how it
 * is being held and moved, so they are removed too, both the constructors and the window
 * events. Media devices are not listed: even without labels, the count of cameras and
 * microphones is a fingerprint. Tor Browser disables all three.
 *
 * Applied per realm: a child frame's window carries every native until the engine injects
 * this script into it, so the frame accessors hand the child through the same treatment.
 */
(function () {
  'use strict';

  var defineProperty = Object.defineProperty;
  var getOwn = Object.getOwnPropertyDescriptor;
  var MARK = Symbol.for('wren.private');
  // Natives used at call time, taken before any page script can replace them.
  var apply = Reflect.apply;
  var weakSet = WeakMap.prototype.set;
  var homeRealm = window;
  var nativeToString = Function.prototype.toString;

  function each(list, fn) {
    for (var i = 0; i < list.length; i++) fn(list[i], i);
  }

  var REMOVED = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCIceCandidate',
    'RTCSessionDescription', 'DeviceMotionEvent', 'DeviceOrientationEvent', 'Accelerometer',
    'LinearAccelerationSensor', 'GravitySensor', 'Gyroscope', 'Magnetometer',
    'AbsoluteOrientationSensor', 'RelativeOrientationSensor', 'AmbientLightSensor'];

  var SENSOR_EVENTS = { devicemotion: true, deviceorientation: true, deviceorientationabsolute: true };

  function isWindow(value) {
    try { return value != null && typeof value === 'object' && value.window === value; } catch (ignored) { return false; }
  }

  function harden(window) {
    if (window[MARK]) return;
    try { defineProperty(window, MARK, { value: true }); } catch (ignored) { return; }

    each(REMOVED, function (name) {
      try {
        defineProperty(window, name, { value: undefined, writable: false, configurable: false });
      } catch (ignored) {}
    });

    var EventTarget = window.EventTarget;
    if (EventTarget && EventTarget.prototype.addEventListener) {
      var nativeAdd = EventTarget.prototype.addEventListener;
      var add = function (type, listener, options) {
        // The type is read once and the same string handed on: a value whose toString answers
        // differently on a second read must not pass under one name and register under another.
        var name = String(type);
        // A bare addEventListener(...) call reaches the window with no receiver at all, and a
        // wrapper borrowed from a child frame may be called on this window: any realm's window.
        var target = this === undefined || this === null ? window : this;
        if (isWindow(target) && SENSOR_EVENTS[name.toLowerCase()]) return undefined;
        return arguments.length > 2 ? apply(nativeAdd, this, [name, listener, options]) : apply(nativeAdd, this, [name, listener]);
      };
      try { add.toString = function () { return String(nativeAdd); }; } catch (ignored) {}
      try {
        defineProperty(EventTarget.prototype, 'addEventListener', {
          value: add, writable: true, configurable: true
        });
      } catch (ignored) {}
    }

    each(Object.keys(SENSOR_EVENTS), function (event) {
      try {
        defineProperty(window, 'on' + event, {
          get: function () { return null; }, set: function () {}, configurable: true
        });
      } catch (ignored) {}
    });

    // WebGPU names the graphics adapter and its limits: a hardware fingerprint on its own, and
    // one Tor Browser ships switched off. Private tabs match it; tor.js does the same for Tor.
    var Navigator = window.Navigator;
    if (Navigator && Navigator.prototype && 'gpu' in Navigator.prototype) {
      try {
        defineProperty(Navigator.prototype, 'gpu', { get: function () { return undefined; }, configurable: true });
      } catch (ignored) {}
    }

    var MediaDevices = window.MediaDevices;
    if (MediaDevices && MediaDevices.prototype.enumerateDevices) {
      try {
        defineProperty(MediaDevices.prototype, 'enumerateDevices', {
          value: function () { return window.Promise.resolve([]); }, writable: true, configurable: true
        });
      } catch (ignored) {}
    }

    hardenFrames(window);
  }

  function hardenFrames(window) {
    // A popup from window.open() starts as a blank document of the opener's own origin that the
    // opener scripts directly; the engine injects nothing into it. It gets what a frame gets.
    var nativeOpen = window.open;
    if (typeof nativeOpen === 'function') {
      var open = function open() {
        var opened = apply(nativeOpen, this, arguments);
        try {
          if (opened) harden(opened);
        } catch (ignored) {
          // Another origin: the engine injects into that document itself.
        }
        return opened;
      };
      try {
        defineProperty(window, 'open', { value: open, writable: true, enumerable: true, configurable: true });
      // The wrapper is a function of this realm: the map this realm's toString reads must know it.
      var sources = window[Symbol.for('wren.sources')] || homeRealm[Symbol.for('wren.sources')];
      if (sources) apply(weakSet, sources, [open, apply(nativeToString, nativeOpen, [])]);
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
          defineProperty(proto, name, {
            get: function () {
              var value = apply(get, this, []);
              try {
                var child = name === 'contentWindow' ? value : value && value.defaultView;
                if (child) harden(child);
              } catch (ignored) {}
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
