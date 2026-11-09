#!/usr/bin/env node
/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/**
 * Checks that assets/tor.js hides the device's time zone from every Date entry point,
 * including arguments that are coerced to strings by the native Date. Run under a zone that
 * is not UTC, for example: TZ=Asia/Kolkata node tools/check_tor.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/tor.js'), 'utf8');

const context = vm.createContext({});
context.window = context;
vm.runInContext(source, context);
const run = (code) => vm.runInContext(code, context);

const checks = [];
function check(name, ok) { checks.push([name, ok]); }

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

let failed = 0;
for (const [name, ok] of checks) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name);
  if (!ok) failed++;
}
if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
console.log('all ' + checks.length + ' checks passed');
