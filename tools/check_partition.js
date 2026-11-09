/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Runs partition.js in a frame-shaped realm and checks that a third-party frame gets no
// storage while a first-party one, and the top page, keep theirs.
//   node tools/check_partition.js

'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/partition.js'), 'utf8');

function realm(origin, ancestors) {
  const context = vm.createContext({});
  vm.runInContext(`
    window = this;
    Object = Object; Symbol = Symbol; Promise = Promise; Function = Function; Error = Error; WeakMap = WeakMap;
    DOMException = class DOMException extends Error { constructor(m, n) { super(m); this.name = n; } };
    location = { origin: ${JSON.stringify(origin)}, ancestorOrigins: ${JSON.stringify(ancestors)} };
    top = ${ancestors.length ? '{}' : 'this'};
    localStorage = { secret: 1 }; sessionStorage = {}; indexedDB = { open() { return 'req'; } }; caches = { open() {} }; cookieStore = {};
    Navigator = class Navigator { get storage() { return navigator._s; } };
    StorageManager = class StorageManager { getDirectory() { return Promise.resolve('dir'); } estimate() { return Promise.resolve({}); } };
    LockManager = class LockManager { request() { return Promise.resolve('lock'); } };
    ServiceWorkerContainer = class ServiceWorkerContainer { register() { return Promise.resolve('reg'); } };
    navigator = new Navigator(); navigator._s = new StorageManager();
    BroadcastChannel = class BroadcastChannel { constructor(n) { this.n = n; } };
    Document = class Document { get cookie() { return 'a=1'; } set cookie(v) { this._c = v; } };
    document = new Document();
    HTMLIFrameElement = class HTMLIFrameElement { constructor(child) { this._child = child; } get contentWindow() { return this._child; } get contentDocument() { return this._child && { defaultView: this._child }; } }
  `, context);
  return context;
}

const checks = [];
function check(name, ok) { checks.push([name, ok]); }
const threw = (ctx, code) => { try { vm.runInContext(code, ctx); return false; } catch (e) { return e.name === 'SecurityError'; } };
const rejected = async (ctx, code) => { try { await vm.runInContext(code, ctx); return false; } catch (e) { return e.name === 'SecurityError'; } };

(async () => {
  const third = realm('https://tracker.example', ['https://site-a.example']);
  vm.runInContext(source, third);
  check('thirdPartyLocalStorageDenied', threw(third, 'localStorage'));
  check('thirdPartySessionStorageDenied', threw(third, 'sessionStorage'));
  check('thirdPartyIndexedDbDenied', threw(third, 'indexedDB'));
  check('thirdPartyCachesDenied', threw(third, 'caches'));
  check('thirdPartyCookieStoreDenied', threw(third, 'cookieStore'));
  check('thirdPartyOpfsDenied', await rejected(third, 'navigator.storage.getDirectory()'));
  check('thirdPartyLocksDenied', await rejected(third, 'navigator.locks ? 1 : new LockManager().request()'));
  check('thirdPartyServiceWorkerDenied', await rejected(third, 'new ServiceWorkerContainer().register("sw.js")'));
  check('thirdPartyBroadcastChannelDenied', threw(third, 'new BroadcastChannel("x")'));
  check('thirdPartyCookieEmpty', vm.runInContext('document.cookie = "b=2"; document.cookie', third) === '');
  check('deniedGetterLooksNative', /\[native code\]/.test(vm.runInContext('Object.getOwnPropertyDescriptor(window, "localStorage").get.toString()', third)));
  check('broadcastChannelLooksNative', vm.runInContext('BroadcastChannel.name', third) === 'BroadcastChannel' &&
    vm.runInContext('BroadcastChannel.prototype.constructor === BroadcastChannel', third));

  const first = realm('https://cdn.site-a.example', ['https://www.site-a.example']);
  vm.runInContext(source, first);
  check('sameSiteFrameKeepsStorage', vm.runInContext('localStorage.secret', first) === 1);
  check('sameSiteFrameKeepsCookie', vm.runInContext('document.cookie', first) === 'a=1');

  const nested = realm('https://tracker.example', ['https://widget.site-a.example', 'https://site-a.example']);
  vm.runInContext(source, nested);
  check('nestedThirdPartyDenied', threw(nested, 'localStorage'));

  const topPage = realm('https://site-a.example', []);
  vm.runInContext(source, topPage);
  check('topPageKeepsStorage', vm.runInContext('localStorage.secret', topPage) === 1);

  const uk = realm('https://a.co.uk', ['https://b.co.uk']);
  vm.runInContext(source, uk);
  check('publicSuffixPairIsCrossSite', threw(uk, 'localStorage'));
  const ukSame = realm('https://img.shop.co.uk', ['https://shop.co.uk']);
  vm.runInContext(source, ukSame);
  check('publicSuffixSameSiteAllowed', vm.runInContext('localStorage.secret', ukSame) === 1);

  // An about:blank child of a third-party frame inherits the denial through the frame accessor.
  const blank = realm('https://tracker.example', ['https://site-a.example']);
  third.childRealm = blank;
  vm.runInContext('new HTMLIFrameElement(childRealm).contentWindow', third);
  check('blankChildOfThirdPartyDenied', threw(blank, 'localStorage'));

  let failed = 0;
  for (const [name, ok] of checks) {
    console.log((ok ? 'ok   ' : 'FAIL ') + name);
    if (!ok) failed++;
  }
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all ' + checks.length + ' checks passed');
})();
