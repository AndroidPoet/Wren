#!/usr/bin/env node
/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/**
 * Checks that assets/tor.js hides the device's time zone from every Date entry point, coarsens
 * every clock, reports the screen as the viewport, and gives a child frame's realm the same
 * treatment. Run under a zone that is not UTC: TZ=Asia/Kolkata node tools/check_tor.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/tor.js'), 'utf8');

// A realm shaped like a page's window: the clocks and the screen as WebView exposes them.
function realm(width, height) {
  const context = vm.createContext({});
  vm.runInContext(`
    window = this;
    Date = Date; Math = Math; Object = Object; Reflect = Reflect; Symbol = Symbol; Promise = Promise; Intl = Intl; Function = Function; Number = Number; String = String; Array = Array;
    Performance = class Performance { now() { return 123456.789; } get timeOrigin() { return 1794200000123.4; } }
    PerformanceEntry = class PerformanceEntry { get startTime() { return 5555.5; } get duration() { return 12.25; } toJSON() { return { startTime: 5555.5, duration: 12.25 }; } }
    Event = class Event { get timeStamp() { return 98765.4321; } }
    IdleDeadline = class IdleDeadline { timeRemaining() { return 33.3; } }
    Screen = class Screen { get width() { return ${width}; } get height() { return ${height}; } get availWidth() { return ${width}; } get availHeight() { return ${height - 37}; } get availTop() { return 37; } get availLeft() { return 0; } }
    Navigator = class Navigator { get language() { return 'hi-IN'; } }
    HTMLIFrameElement = class HTMLIFrameElement { constructor(child) { this._child = child; } get contentWindow() { return this._child; } get contentDocument() { return this._child && { defaultView: this._child }; } }
    performance = new Performance();
    screen = new Screen();
    navigator = new Navigator();
    requestAnimationFrame = (callback) => { callback(16.6667); return 1; };
    innerWidth = 400; innerHeight = 700; outerWidth = ${width}; outerHeight = ${height}; screenX = 0; screenY = 37;
  `, context);
  return context;
}

const context = realm(412, 915);
vm.runInContext(source, context);
const run = (code) => vm.runInContext(code, context);

const checks = [];
function check(name, ok) { checks.push([name, ok]); }
const onGrid = (v) => typeof v === 'number' && v % 100 === 0;
const nearby = (v, real) => onGrid(v) && v >= Math.floor(real / 100) * 100 && v <= Math.floor(real / 100) * 100 + 100;

const nativeOffset = new Date(2026, 0, 1).getTimezoneOffset();
check('harnessRunsOutsideUtc', nativeOffset !== 0);
check('getTimezoneOffsetIsZero', run('new Date().getTimezoneOffset()') === 0);
check('localIsoStringParsesAsUtc', run('Date.parse("2026-01-01T00:00:00")') === Date.UTC(2026, 0, 1));
check('localIsoObjectParsesAsUtc',
  run('Date.parse({ toString: function () { return "2026-01-01T00:00:00"; } })') === Date.UTC(2026, 0, 1));
check('localIsoObjectConstructsAsUtc',
  run('new Date({ toString: function () { return "2026-01-01T00:00:00"; } }).getTime()') === Date.UTC(2026, 0, 1));
check('valueOfObjectConstructsFromNumber',
  run('new Date({ valueOf: function () { return 86400000; } }).getTime()') === 86400000);
check('componentsConstructAsUtc', run('new Date(2026, 0, 1, 0, 0).getTime()') === Date.UTC(2026, 0, 1));
check('zonedStringUntouched', run('Date.parse("2026-01-01T00:00:00+05:30")') === Date.UTC(2025, 11, 31, 18, 30));
check('toStringShowsGmt', /GMT\+0000/.test(run('new Date(0).toString()')));
check('intlResolvesUtc', run('new Intl.DateTimeFormat().resolvedOptions().timeZone') === 'UTC');

// Legacy spellings without a zone are UTC too; ones with a zone keep it.
check('spaceSeparatedParsesAsUtc', run('Date.parse("2026-01-01 00:00:00")') === Date.UTC(2026, 0, 1));
check('slashDateParsesAsUtc', run('Date.parse("2026/01/01")') === Date.UTC(2026, 0, 1));
check('monthNameParsesAsUtc', run('new Date("Jan 1 2026 00:00").getTime()') === Date.UTC(2026, 0, 1));
check('gmtOffsetStringKept',
  run('Date.parse("Thu Jan 01 2026 00:00:00 GMT+0530 (India Standard Time)")') === Date.UTC(2025, 11, 31, 18, 30));
check('estStringKept', run('Date.parse("1 January 2026 00:00 EST")') === Date.UTC(2026, 0, 1, 5));
check('invalidStaysInvalid', isNaN(run('Date.parse("garbage")')) && isNaN(run('new Date("garbage").getTime()')));

// Clocks come back on a 100 ms grid, one edge of their bucket, and stay monotonic.
check('performanceNowIsCoarse', nearby(run('performance.now()'), 123456.789));
check('timeOriginIsCoarse', onGrid(run('performance.timeOrigin')));
check('dateNowIsCoarse', onGrid(run('Date.now()')));
check('newDateIsCoarse', onGrid(run('new Date().getTime()')));
check('dateCallIsCoarse', onGrid(run('Date.parse(Date())')) && /GMT\+0000/.test(run('Date()')));
check('explicitDateIsExact', run('new Date(1234567891).getTime()') === 1234567891);
check('eventTimeStampIsCoarse', nearby(run('new Event().timeStamp'), 98765.4321));
check('frameTimestampIsCoarse', nearby(run('var t = -1; requestAnimationFrame(function (ts) { t = ts; }); t'), 16.6667));
check('entryStartTimeIsCoarse', nearby(run('new PerformanceEntry().startTime'), 5555.5));
check('entryJsonIsCoarse', nearby(run('new PerformanceEntry().toJSON().startTime'), 5555.5));
check('idleDeadlineIsCoarse', onGrid(run('new IdleDeadline().timeRemaining()')));
check('dateNowMonotonic', run('var a = Date.now(), ok = true; for (var i = 0; i < 2000; i++) { var b = Date.now(); if (b < a) ok = false; a = b; } ok'));
// A page that swaps Math.floor for a recorder must not see the raw inputs.
check('floorSwapSeesNothing', run('var seen = []; var f = Math.floor; Math.floor = function (v) { seen.push(v); return f(v); }; performance.now(); Date.now(); Math.floor = f; seen.length') === 0);

// The screen is the viewport, from the prototype as much as from the instance.
check('screenWidthIsViewport', run('screen.width') === 400);
check('screenHeightIsViewport', run('screen.height') === 700);
check('availHeightIsViewport', run('screen.availHeight') === 700);
check('availTopIsZero', run('screen.availTop') === 0);
check('deleteDoesNotRestore', run('delete screen.width; screen.width') === 400);
check('prototypeGetterIsViewport', run('Object.getOwnPropertyDescriptor(Screen.prototype, "width").get.call(screen)') === 400);
check('outerWidthIsInner', run('outerWidth') === 400);
check('outerHeightIsInner', run('outerHeight') === 700);
check('screenYIsZero', run('screenY') === 0);
check('screenFollowsViewport', run('innerWidth = 300; screen.width') === 300);
check('languageIsEnglish', run('navigator.language') === 'en-US');

// A child frame reached through its element gets the same treatment, with its own natives.
const child = realm(412, 915);
context.childRealm = child;
run('frame = new HTMLIFrameElement(childRealm)');
const childNativeOffset = vm.runInContext('new Date(2026, 0, 1).getTimezoneOffset()', child);
check('childRealmStartsNative', childNativeOffset !== 0);
check('childRealmHardenedOnAccess', run('frame.contentWindow.Date.parse("2026-01-01 00:00:00")') === Date.UTC(2026, 0, 1));
check('childRealmOffsetIsZero', vm.runInContext('new Date().getTimezoneOffset()', child) === 0);
check('childRealmScreenIsViewport', vm.runInContext('screen.width', child) === 400);
check('childRealmClockIsCoarse', onGrid(vm.runInContext('performance.now()', child)));
const grandchild = realm(412, 915);
child.grandchildRealm = grandchild;
check('grandchildViaDocumentHardened',
  vm.runInContext('new HTMLIFrameElement(grandchildRealm).contentDocument.defaultView.Date.parse("2026/01/01")', child) === Date.UTC(2026, 0, 1));

let failed = 0;
for (const [name, ok] of checks) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name);
  if (!ok) failed++;
}
if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
console.log('all ' + checks.length + ' checks passed');
