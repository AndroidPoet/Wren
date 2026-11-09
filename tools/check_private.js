#!/usr/bin/env node
/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/**
 * Checks that assets/private.js removes WebRTC, the motion sensors and device enumeration from
 * a private or Tor page. Run with: node tools/check_private.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/private.js'), 'utf8');

const listened = [];
class EventTarget { addEventListener(type) { listened.push(type); } }
class MediaDevices { enumerateDevices() { return Promise.resolve([{ kind: 'videoinput' }]); } }
const context = vm.createContext({});
context.window = context;
context.EventTarget = EventTarget;
context.MediaDevices = MediaDevices;
context.RTCPeerConnection = function () {};
context.webkitRTCPeerConnection = function () {};
context.DeviceMotionEvent = function () {};
context.DeviceOrientationEvent = function () {};
context.Accelerometer = function () {};
context.Gyroscope = function () {};
context.ondevicemotion = null;
vm.runInContext(source, context);
// The window is an EventTarget; the vm context stands in for both.
context.addEventListener = EventTarget.prototype.addEventListener;
const run = (code) => vm.runInContext(code, context);

const checks = [];
function check(name, ok) { checks.push([name, ok]); }

check('rtcPeerConnectionGone', run('RTCPeerConnection') === undefined);
check('webkitRtcPeerConnectionGone', run('webkitRTCPeerConnection') === undefined);
check('rtcCannotBeRestored',
  run('try { RTCPeerConnection = function () {}; } catch (e) {} RTCPeerConnection') === undefined);
check('deviceMotionEventGone', run('DeviceMotionEvent') === undefined);
check('deviceOrientationEventGone', run('DeviceOrientationEvent') === undefined);
check('accelerometerGone', run('Accelerometer') === undefined);
check('gyroscopeGone', run('Gyroscope') === undefined);

run('addEventListener("devicemotion", function () {})');
run('addEventListener("DeviceOrientation", function () {})');
run('addEventListener("deviceorientationabsolute", function () {})');
run('addEventListener("click", function () {})');
check('sensorListenersDropped', listened.length === 1 && listened[0] === 'click');
check('otherTargetsUntouched',
  (() => { listened.length = 0; run('new EventTarget().addEventListener("devicemotion", function () {})'); return listened.length === 1; })());
check('onDeviceMotionStaysNull', run('ondevicemotion = function () {}; ondevicemotion') === null);
check('onDeviceOrientationStaysNull', run('ondeviceorientation = function () {}; ondeviceorientation') === null);

let failed = 0;
new MediaDevices().enumerateDevices().then((devices) => {
  check('noMediaDevicesListed', Array.isArray(devices) && devices.length === 0);
  for (const [name, ok] of checks) {
    console.log((ok ? 'ok   ' : 'FAIL ') + name);
    if (!ok) failed++;
  }
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all ' + checks.length + ' checks passed');
});
