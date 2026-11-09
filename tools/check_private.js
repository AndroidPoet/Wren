#!/usr/bin/env node
/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/**
 * Checks that assets/private.js removes WebRTC, the motion sensors and device enumeration from
 * a private or Tor page, in the page's own realm and in a child frame's. Run with:
 * node tools/check_private.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/private.js'), 'utf8');

function realm() {
  const context = vm.createContext({});
  vm.runInContext(`
    window = this;
    Date = Date; Math = Math; Object = Object; Reflect = Reflect; Symbol = Symbol; Promise = Promise; Intl = Intl; Function = Function; Number = Number; String = String; Array = Array;
    listened = [];
    EventTarget = class EventTarget { addEventListener(type) { listened.push([this, String(type)]); } }
    MediaDevices = class MediaDevices { enumerateDevices() { return Promise.resolve([{ kind: 'videoinput' }]); } }
    HTMLIFrameElement = class HTMLIFrameElement { constructor(child) { this._child = child; } get contentWindow() { return this._child; } get contentDocument() { return this._child && { defaultView: this._child }; } }
    RTCPeerConnection = function () {};
    webkitRTCPeerConnection = function () {};
    DeviceMotionEvent = function () {};
    DeviceOrientationEvent = function () {};
    Accelerometer = function () {};
    Gyroscope = function () {};
    ondevicemotion = null;
  `, context);
  return context;
}

const context = realm();
vm.runInContext(source, context);
// The window is an EventTarget; the vm context stands in for both.
vm.runInContext('addEventListener = EventTarget.prototype.addEventListener', context);
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
run('addEventListener("deviceorientation", function () {})');
run('addEventListener("DeviceOrientationAbsolute", function () {})');
run('window.addEventListener("deviceorientationabsolute", function () {})');
run('addEventListener("click", function () {})');
check('sensorListenersDropped', run('listened.length') === 1 && run('listened[0][1]') === 'click');
check('otherTargetsUntouched',
  run('listened.length = 0; new EventTarget().addEventListener("devicemotion", function () {}); listened.length') === 1);
// A type whose string form changes between reads is read once.
check('doubleCoercionBlocked',
  run('listened.length = 0; var n = 0; addEventListener({ toString: function () { return n++ ? "devicemotion" : "click"; } }, function () {}); listened.length === 1 && listened[0][1] === "click"'));
check('onDeviceMotionStaysNull', run('ondevicemotion = function () {}; ondevicemotion') === null);
check('onDeviceOrientationStaysNull', run('ondeviceorientation = function () {}; ondeviceorientation') === null);

// A child frame reached through its element is hardened with its own natives, and its
// wrapper refuses a sensor listener aimed at the parent window.
const child = realm();
context.childRealm = child;
run('frame = new HTMLIFrameElement(childRealm)');
check('childRealmStartsNative', vm.runInContext('typeof RTCPeerConnection', child) === 'function');
check('childRealmRtcGoneOnAccess', run('frame.contentWindow.RTCPeerConnection') === undefined);
check('childRealmSensorsGone', vm.runInContext('typeof Gyroscope', child) === 'undefined');
check('childWrapperRefusesParentWindow',
  run('listened.length = 0; frame.contentWindow.EventTarget.prototype.addEventListener.call(window, "devicemotion", function () {}); listened.length') === 0);
check('childDocumentPathHardens',
  (() => { const g = realm(); child.grandchildRealm = g; vm.runInContext('new HTMLIFrameElement(grandchildRealm).contentDocument.defaultView', child); return vm.runInContext('typeof DeviceMotionEvent', g) === 'undefined'; })());

let failed = 0;
new (run('MediaDevices'))().enumerateDevices().then((devices) => {
  check('noMediaDevicesListed', Array.isArray(devices) && devices.length === 0);
  for (const [name, ok] of checks) {
    console.log((ok ? 'ok   ' : 'FAIL ') + name);
    if (!ok) failed++;
  }
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all ' + checks.length + ' checks passed');
});
