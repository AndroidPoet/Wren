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
 */
(function () {
  'use strict';

  function remove(name) {
    try {
      Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false });
    } catch (ignored) {}
  }

  ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCIceCandidate',
    'RTCSessionDescription'].forEach(remove);

  ['DeviceMotionEvent', 'DeviceOrientationEvent', 'Accelerometer', 'LinearAccelerationSensor',
    'GravitySensor', 'Gyroscope', 'Magnetometer', 'AbsoluteOrientationSensor',
    'RelativeOrientationSensor', 'AmbientLightSensor'].forEach(remove);

  var SENSOR_EVENTS = { devicemotion: true, deviceorientation: true, deviceorientationabsolute: true };

  if (window.EventTarget && EventTarget.prototype.addEventListener) {
    var nativeAdd = EventTarget.prototype.addEventListener;
    var add = function (type) {
      // A bare addEventListener(...) call reaches the window with no receiver at all.
      var target = this === undefined || this === null ? window : this;
      if (target === window && SENSOR_EVENTS[String(type).toLowerCase()]) return undefined;
      return nativeAdd.apply(this, arguments);
    };
    try { add.toString = function () { return String(nativeAdd); }; } catch (ignored) {}
    try {
      Object.defineProperty(EventTarget.prototype, 'addEventListener', {
        value: add, writable: true, configurable: true
      });
    } catch (ignored) {}
  }

  Object.keys(SENSOR_EVENTS).forEach(function (event) {
    try {
      Object.defineProperty(window, 'on' + event, {
        get: function () { return null; }, set: function () {}, configurable: true
      });
    } catch (ignored) {}
  });

  if (window.MediaDevices && MediaDevices.prototype.enumerateDevices) {
    try {
      Object.defineProperty(MediaDevices.prototype, 'enumerateDevices', {
        value: function () { return Promise.resolve([]); }, writable: true, configurable: true
      });
    } catch (ignored) {}
  }
})();
