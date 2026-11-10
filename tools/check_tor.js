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
const CLOCK = 1234567;
const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/tor.js'), 'utf8')
  .replace('__CLOCK__', String(CLOCK));

// A realm shaped like a page's window: the clocks and the screen as WebView exposes them.
function realm(width, height) {
  const context = vm.createContext({ hostURL: URL, hostBlob: Blob });
  vm.runInContext(`
    window = this;
    Date = Date; Math = Math; Object = Object; Reflect = Reflect; Symbol = Symbol; Promise = Promise; Intl = Intl; Function = Function; Number = Number; String = String; Array = Array;
    Performance = class Performance { now() { return 123456.789; } get timeOrigin() { return 1794200000123.4; } }
    PerformanceEntry = class PerformanceEntry { get startTime() { return 5555.5; } get duration() { return 12.25; } toJSON() { return { startTime: 5555.5, duration: 12.25 }; } }
    Event = class Event { get timeStamp() { return 98765.4321; } }
    Animation = class Animation { constructor() { this._t = 1234.567; } get currentTime() { return this._t; } set currentTime(v) { this._t = v; } get startTime() { return 4321.987; } };
    LargestContentfulPaint = class LargestContentfulPaint extends PerformanceEntry { get renderTime() { return 2345.678; } };
    HTMLVideoElement = class HTMLVideoElement { requestVideoFrameCallback(cb) { cb(3456.789, { presentationTime: 3457.123, mediaTime: 1.5 }); return 1; } };
    PerformanceResourceTiming = class PerformanceResourceTiming extends PerformanceEntry { get responseEnd() { return 7777.75; } toJSON() { return { startTime: 5555.5, duration: 12.25, responseEnd: 7777.75 }; } }
    PerformanceTiming = class PerformanceTiming { get navigationStart() { return 1794200000123.4; } }
    AudioContext = class AudioContext { getOutputTimestamp() { return { contextTime: 1.2345678, performanceTime: 4444.4444 }; } }
    DOMException = class DOMException extends Error { constructor(m, n) { super(m); this.name = n; } };
    IdleDeadline = class IdleDeadline { timeRemaining() { return 33.3; } }
    ExpiredDeadline = class ExpiredDeadline extends IdleDeadline { timeRemaining() { return 0; } };
    Temporal = { Now: { timeZoneId() { return 'Asia/Calcutta'; }, instant() { return { ms: 1794200000123 }; }, zonedDateTimeISO(z) { return { zone: z || 'Asia/Calcutta' }; }, plainDateTimeISO(z) { return { zone: z || 'Asia/Calcutta' }; }, plainDateISO(z) { return { zone: z || 'Asia/Calcutta' }; }, plainTimeISO(z) { return { zone: z || 'Asia/Calcutta' }; } },
                 Instant: { fromEpochMilliseconds(ms) { return { ms: ms, toZonedDateTimeISO(z) { return { zone: z, ms: ms, toPlainDateTime() { return { zone: z, ms: ms }; }, toPlainDate() { return { zone: z }; }, toPlainTime() { return { zone: z }; } }; } }; } } };
    Screen = class Screen { get width() { return ${width}; } get height() { return ${height}; } get availWidth() { return ${width}; } get availHeight() { return ${height - 37}; } get availTop() { return 37; } get availLeft() { return 0; } }
    Navigator = class Navigator { get language() { return 'hi-IN'; } }
    KeyboardEvent = class KeyboardEvent { constructor(k, c) { this._k = k; this._c = c; } get key() { return this._k; } get code() { return this._c; } };
    MouseEvent = class MouseEvent { constructor(x, y) { this._x = x; this._y = y; } get clientX() { return this._x; } get clientY() { return this._y; } get screenX() { return this._x + 6; } get screenY() { return this._y + 243; } };
    Touch = class Touch { constructor(x) { this._x = x; } get clientX() { return this._x; } get clientY() { return 0; } get screenX() { return this._x + 6; } get screenY() { return 243; } };
    __workerBodies = {};
    URL = class URL extends hostURL {
      static createObjectURL(blob) { const url = 'blob:https://example.org/' + Math.random().toString(16).slice(2); __workerBodies[url] = blob.__text; return url; }
    };
    Blob = class Blob extends hostBlob { constructor(parts, opts) { super(parts, opts); this.__text = parts.join(''); } }; JSON = JSON; TypeError = TypeError; WeakMap = WeakMap;
    location = { href: 'https://example.org/app/page.html' };
    __modules = [];
    Worklet = class Worklet { addModule(url) { __modules.push(String(url)); return Promise.resolve(); } };
    AudioWorklet = class AudioWorklet extends Worklet {};
    PaintWorklet = class PaintWorklet extends Worklet {};
    Worker = class Worker { constructor(url, options) { this.url = String(url); this.options = options; } static get builtIn() { return 1; } };
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
check('setYearIsUtc', run('var d = new Date(0); d.setYear(2000); d.getTime()') === Date.UTC(2000, 0, 1) &&
  run('var d = new Date(0); d.setYear(99); d.getUTCFullYear()') === 1999 &&
  isNaN(run('var d = new Date(0); d.setYear("x")')));
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
check('resourceTimingIsCoarse', nearby(run('new PerformanceResourceTiming().responseEnd'), 7777.75) &&
  nearby(run('new PerformanceResourceTiming().toJSON().responseEnd'), 7777.75));
check('animationTimesAreCoarse', nearby(run('new Animation().currentTime'), 1234.567) && nearby(run('new Animation().startTime'), 4321.987));
check('animationTimeStillSettable', run('var a = new Animation(); a.currentTime = 500; a._t') === 500);
check('paintTimeIsCoarse', nearby(run('new LargestContentfulPaint().renderTime'), 2345.678));
check('videoFrameTimesAreCoarse', run('var got; new HTMLVideoElement().requestVideoFrameCallback(function (n, m) { got = [n, m.presentationTime, m.mediaTime]; }); got').every(
  (v, i) => i === 2 ? v === 1.5 : onGrid(v)));
check('legacyTimingIsCoarse', onGrid(run('new PerformanceTiming().navigationStart')));
check('outputTimestampIsCoarse', nearby(run('new AudioContext().getOutputTimestamp().performanceTime'), 4444.4444) &&
  run('new AudioContext().getOutputTimestamp().contextTime * 1000 % 100 === 0'));
check('idleDeadlineIsCoarse', onGrid(run('new IdleDeadline().timeRemaining()')));
check('expiredDeadlineStaysZero', run('new ExpiredDeadline().timeRemaining()') === 0);
check('temporalZoneIsUtc', run('Temporal.Now.timeZoneId()') === 'UTC' && run('Temporal.Now.zonedDateTimeISO().zone') === 'UTC' &&
  run('Temporal.Now.plainDateTimeISO().zone') === 'UTC' && run('Temporal.Now.zonedDateTimeISO("Europe/Paris").zone') === 'Europe/Paris');
check('temporalInstantIsCoarse', onGrid(run('Temporal.Now.instant().ms')) && onGrid(run('Temporal.Now.zonedDateTimeISO().ms')));
check('dateNowMonotonic', run('var a = Date.now(), ok = true; for (var i = 0; i < 2000; i++) { var b = Date.now(); if (b < a) ok = false; a = b; } ok'));
// A page that swaps Math.floor for a recorder must not see the raw inputs.
// One rounding per identity: every realm rounds the same instant the same way, so reading the
// clock in many frames averages nothing back. Drawn per realm, half of these would differ.
check('everyRealmRoundsAlike', (() => {
  for (let i = 0; i < 16; i++) {
    const other = realm(412, 915);
    vm.runInContext(source, other);
    if (vm.runInContext('performance.now()', other) !== run('performance.now()')) return false;
  }
  return true;
})());
check('workerBootstrapCarriesClock', Object.values(run('new Worker("clock.js"); __workerBodies')).some((b) => b && b.indexOf(', ' + CLOCK + ');') >= 0));
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
// Keys read as a US layout: the physical key behind a character stays unknown.
check('keyCodeIsUsLayout', run('new KeyboardEvent("z", "KeyY").code') === 'KeyZ' &&
  run('new KeyboardEvent("@", "Digit0").code') === 'Digit2' && run('new KeyboardEvent("Enter", "NumpadEnter").code') === 'NumpadEnter');

// Pointer and touch events report screen coordinates equal to client coordinates.
check('mouseScreenXIsClientX', run('new MouseEvent(10, 20).screenX') === 10 && run('new MouseEvent(10, 20).screenY') === 20);
check('touchScreenXIsClientX', run('new Touch(42).screenX') === 42);
check('unmappedKeyCodeIsEmpty', run('new KeyboardEvent("\u00e9", "Digit2").code') === '');

// Everything locale-sensitive answers as en-US unless the page names a locale.
check('intlLocaleIsEnglish', run('new Intl.NumberFormat().resolvedOptions().locale') === 'en-US' &&
  run('Intl.DateTimeFormat().resolvedOptions().locale') === 'en-US' && run('new Intl.Collator().resolvedOptions().locale') === 'en-US');
check('emptyLocaleListIsEnglish', run('new Intl.NumberFormat([]).resolvedOptions().locale') === 'en-US' &&
  run('new Intl.DateTimeFormat([]).resolvedOptions().locale') === 'en-US' && run('(1234.5).toLocaleString([])') === '1,234.5');
check('unsupportedLocaleIsEnglish', run('new Intl.Collator("qaa").resolvedOptions().locale') === 'en-US' &&
  run('new Intl.DateTimeFormat("zxx").resolvedOptions().locale') === 'en-US');
check('malformedLocaleStillThrows', run('try { new Intl.NumberFormat("not a tag!"); "ok" } catch (e) { e.name }') === 'RangeError');
check('intlNamedLocaleKept', run('new Intl.NumberFormat("de-DE").resolvedOptions().locale') === 'de-DE');
check('toLocaleStringIsEnglish', run('(1234567.5).toLocaleString()') === '1,234,567.5' &&
  run('new Date(Date.UTC(2026, 0, 2, 15)).toLocaleString()') === '1/2/2026, 3:00:00 PM');
check('intlConstructorLooksNative', run('Intl.NumberFormat.name') === 'NumberFormat' && run('Intl.NumberFormat.prototype.constructor === Intl.NumberFormat') &&
  run('Intl.NumberFormat.supportedLocalesOf') !== undefined);

// Shims read as native code through Function.prototype.toString, including toString itself.
check('shimToStringLooksNative', /\[native code\]/.test(run('Function.prototype.toString.call(Date.prototype.getTimezoneOffset)')) &&
  /\[native code\]/.test(run('Date.toString()')) && /\[native code\]/.test(run('Function.prototype.toString.call(Function.prototype.toString)')) &&
  !run('Object.prototype.hasOwnProperty.call(Date.prototype.getTimezoneOffset, "toString")'));
check('pageFunctionToStringIntact', run('(function page() { return 1; }).toString()') === 'function page() { return 1; }');

// A worker is started from a bootstrap that installs this script before the page's own.
check('workerSurvivesSwappedCreateObjectURL', (() => {
  const url = run('var kept = URL.createObjectURL; URL.createObjectURL = function () { throw new Error("gone"); }; var u; try { u = new Worker("w.js").url; } finally { URL.createObjectURL = kept; } u');
  return /^blob:/.test(url);
})());
check('workerFailsClosedWhenBootstrapCannotBeBuilt',
  run('try { new Worker({ toString() { throw new Error("no"); } }); "constructed" } catch (e) { e.name }') === 'SecurityError');
check('workerStartsFromBootstrap', run('Worker.name') === 'Worker' && run('Worker.builtIn') === 1 &&
  /^blob:/.test(run('new Worker("w.js").url')) && run('new Worker("w.js").url === new Worker("./w.js").url') &&
  run('new Worker("w.js").url !== new Worker("w.js", { type: "module" }).url'));
check('workerBootstrapCarriesScript', (() => {
  const url = run('new Worker("worker.js").url');
  const body = run('__workerBodies[' + JSON.stringify(url) + ']');
  return typeof body === 'string' && body.indexOf('Symbol.for(\'wren.tor\')') > 0 && body.indexOf('(self, true, ') > 0 &&
    body.indexOf('importScripts("https://example.org/app/worker.js")') > 0 && body.indexOf('resolveAgainst') > 0 &&
    body.indexOf(', "https://example.org/app/worker.js", ' + CLOCK + ');') > 0;
})());
// An audio worklet loads the shims before the page's first module, once per worklet; a worklet
// of another kind is left alone. The bootstrap then runs in a worklet-shaped realm.
let workletOrder = null;
run('var aw = new AudioWorklet(); aw.addModule("a.js").then(function () { return aw.addModule("b.js"); }).then(function () { new PaintWorklet().addModule("p.js"); })');
const workletDone = new Promise((resolve) => setTimeout(() => { workletOrder = run('__modules.slice()'); resolve(); }, 50));
check('workerWithoutNewThrows', (() => { try { run('Worker("w.js")'); return false; } catch (e) { return e instanceof TypeError || /new/.test(String(e)); } })());

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

workletDone.then(() => {
check('audioWorkletBootstrapsFirst', workletOrder.length === 4 && /^blob:/.test(workletOrder[0]) &&
  /a\.js$/.test(workletOrder[1]) && /b\.js$/.test(workletOrder[2]) && /p\.js$/.test(workletOrder[3]));
const workletBody = run('__workerBodies[' + JSON.stringify(workletOrder[0]) + ']');
const scope = vm.createContext({});
vm.runInContext('globalThis.Date = Date; Intl = Intl; Math = Math; Object = Object; Reflect = Reflect; Symbol = Symbol; Function = Function; JSON = JSON; WeakMap = WeakMap; String = String; Array = Array; Number = Number; Promise = Promise;', scope);
let workletRan = true;
try { vm.runInContext(workletBody, scope); } catch (e) { workletRan = false; console.log('  worklet bootstrap threw: ' + e); }
check('audioWorkletRealmIsUtc', workletRan && vm.runInContext('new Date().getTimezoneOffset()', scope) === 0 &&
  vm.runInContext('Intl.DateTimeFormat().resolvedOptions().timeZone', scope) === 'UTC');
let failed = 0;
for (const [name, ok] of checks) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name);
  if (!ok) failed++;
}
if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
console.log('all ' + checks.length + ' checks passed');
});
