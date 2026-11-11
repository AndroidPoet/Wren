/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Evaluates guard.js in frame-shaped realms: true keeps Wren's page scripts out.
//   node tools/check_guard.js

'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const guard = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/guard.js'), 'utf8');

function skips(href, ancestors, off, windowOrigin) {
  const url = new URL(href);
  const context = vm.createContext({
    URL,
    location: { hostname: url.hostname, pathname: url.pathname, ancestorOrigins: ancestors || [] },
    origin: windowOrigin === undefined ? url.origin : windowOrigin,
  });
  return vm.runInContext(guard.replace('__OFF__', JSON.stringify(off || [])), context);
}

const checks = [
  ['cloudflareChallengeFrameSkipped', skips('https://challenges.cloudflare.com/cdn-cgi/x', ['https://stackoverflow.com']) === true],
  ['hcaptchaSubdomainSkipped', skips('https://newassets.hcaptcha.com/captcha/v1', ['https://site.example']) === true],
  ['recaptchaOnGoogleSkipped', skips('https://www.google.com/recaptcha/api2/anchor', ['https://site.example']) === true],
  ['googleSearchNotSkipped', skips('https://www.google.com/search?q=x') === false],
  ['dataDomeSkipped', skips('https://geo.captcha-delivery.com/captcha/', ['https://site.example']) === true],
  ['lookalikeHostNotSkipped', skips('https://evilhcaptcha.com/', ['https://site.example']) === false],
  ['suffixTrickNotSkipped', skips('https://challenges.cloudflare.com.evil.example/') === false],
  ['ordinaryPageNotSkipped', skips('https://news.example/') === false],
  ['protectionOffTopSkipped', skips('https://shop.example/', [], ['shop.example']) === true],
  ['protectionOffFrameSkipped', skips('https://cdn.other.example/w', ['https://shop.example'], ['shop.example']) === true],
  ['otherSiteFrameNotSkipped', skips('https://shop.example/w', ['https://news.example'], ['shop.example']) === false],
  ['blankFrameOfChallengeSkipped', skips('about:blank', ['https://challenges.cloudflare.com', 'https://stackoverflow.com'], [], 'https://challenges.cloudflare.com') === true],
];

let failed = 0;
for (const [name, ok] of checks) {
  console.log((ok ? 'ok   ' : 'FAIL ') + name);
  if (!ok) failed++;
}
if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
console.log('all ' + checks.length + ' checks passed');
