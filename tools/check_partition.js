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

function realm(origin, ancestors, windowOrigin) {
  const context = vm.createContext({});
  vm.runInContext(`
    window = this;
    Object = Object; Symbol = Symbol; Promise = Promise; Function = Function; Error = Error; WeakMap = WeakMap;
    DOMException = class DOMException extends Error { constructor(m, n) { super(m); this.name = n; } };
    location = { origin: ${JSON.stringify(origin)}, ancestorOrigins: ${JSON.stringify(ancestors)} };
    ${windowOrigin === undefined ? '' : 'origin = ' + JSON.stringify(windowOrigin) + ';'}
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

  // The site is schemeful: an http top with an https frame of the same host is cross-site.
  const schemes = realm('https://site-a.example', ['http://site-a.example']);
  vm.runInContext(source, schemes);
  check('schemeMismatchIsCrossSite', threw(schemes, 'localStorage'));
  // Every ancestor counts: the site inside another site inside the site is third-party.
  const chain = realm('https://site-a.example', ['https://site-b.example', 'https://site-a.example']);
  vm.runInContext(source, chain);
  check('ownSiteBehindForeignFrameIsCrossSite', threw(chain, 'localStorage'));
  // Address hosts are whole sites of their own; an IPv6 literal keeps its brackets.
  const v6 = realm('https://[2001:db8::2]', ['https://[2001:db8::1]']);
  vm.runInContext(source, v6);
  check('distinctIpv6HostsAreCrossSite', threw(v6, 'localStorage'));
  const v6Port = realm('https://[2001:db8::1]:8443', ['https://[2001:db8::1]']);
  vm.runInContext(source, v6Port);
  check('ipv6PortDoesNotSplitSite', vm.runInContext('localStorage.secret', v6Port) === 1);
  // A sandboxed frame runs as an opaque origin whatever its address says: nothing for it.
  const sandboxed = realm('https://site-a.example', ['https://site-a.example'], 'null');
  vm.runInContext(source, sandboxed);
  check('opaqueOriginIsDenied', threw(sandboxed, 'localStorage'));
  // The origin a frame runs as wins over its address: an about:blank child of the site is the site.
  const inherited = realm('null', ['https://site-a.example'], 'https://site-a.example');
  vm.runInContext(source, inherited);
  check('inheritedOriginIsFirstParty', vm.runInContext('localStorage.secret', inherited) === 1);

  // An about:blank child of a third-party frame inherits the denial through the frame accessor.
  const blank = realm('https://tracker.example', ['https://site-a.example']);
  third.childRealm = blank;
  vm.runInContext('new HTMLIFrameElement(childRealm).contentWindow', third);
  check('blankChildOfThirdPartyDenied', threw(blank, 'localStorage'));
  // The child's shims are functions of the parent's realm; read from either realm they are native.
  check('childRealmShimReadsNative', /\[native code\]/.test(vm.runInContext('Object.getOwnPropertyDescriptor(window, "localStorage").get.toString()', blank)) &&
    /\[native code\]/.test(vm.runInContext('Function.prototype.toString.call(Object.getOwnPropertyDescriptor(window, "localStorage").get)', blank)));

  // Storage buckets and the old file system API are the same per-site storage under other names.
  const buckets = realm('https://tracker.example', ['https://site-a.example']);
  vm.runInContext(`
    StorageBucketManager = class StorageBucketManager { open() { return Promise.resolve({}); } keys() { return Promise.resolve([]); } };
    webkitRequestFileSystem = function (type, size, ok) { ok({ root: 'fs' }); };
    setTimeout = function (fn) { fn(); };`, buckets);
  vm.runInContext(source, buckets);
  check('thirdPartyStorageBucketsDenied', await rejected(buckets, 'new StorageBucketManager().open("id")') &&
    await rejected(buckets, 'new StorageBucketManager().keys()'));
  check('thirdPartyFileSystemDenied', vm.runInContext(`var got = 'none';
    webkitRequestFileSystem(0, 1, function () { got = 'granted'; }, function (e) { got = e.name; }); got`, buckets) === 'SecurityError');

  // A worker of a third-party frame starts from a bootstrap that denies the same storage in the
  // worker's scope, then loads the frame's script by its absolute address.
  function workerWorld(ctx) {
    vm.runInContext(`
      blobs = []; started = [];
      URL = class URL { constructor(u, b) { this.href = /^[a-z]+:/.test(u) ? u : b.replace(/[^/]*$/, '') + u; }
        static createObjectURL(blob) { blobs.push(blob.parts.join('')); return 'blob:https://tracker.example/' + blobs.length; } };
      Blob = class Blob { constructor(parts) { this.parts = parts; } };
      Worker = class Worker { constructor(url, options) { started.push(url); } };
      location.href = 'https://tracker.example/frame/index.html';`, ctx);
  }
  const framed = realm('https://tracker.example', ['https://site-a.example']);
  workerWorld(framed);
  vm.runInContext(source, framed);
  vm.runInContext('new Worker("job.js")', framed);
  check('thirdPartyWorkerStartsFromBootstrap', vm.runInContext('started[0]', framed) === 'blob:https://tracker.example/1');
  // The stub here is a class, so "native" means: reads as the constructor it replaced.
  check('workerLooksNative', vm.runInContext('Worker.name === "Worker" && /^class Worker/.test(Worker.toString())', framed));
  check('workerNeedsNew', (() => {
    try { vm.runInContext('Worker("job.js")', framed); return false; } catch (e) { return e.name === 'TypeError'; }
  })());
  const bootstrap = vm.runInContext('blobs[0]', framed);
  const scope = vm.createContext({});
  vm.runInContext(`
    self = this; WorkerGlobalScope = function () {};
    Object = Object; Symbol = Symbol; Promise = Promise; Function = Function; Error = Error; WeakMap = WeakMap;
    DOMException = class DOMException extends Error { constructor(m, n) { super(m); this.name = n; } };
    // As in Blink: a worker's storage accessors sit on the prototype chain, not on the global.
    Object.setPrototypeOf(self, Object.create(Object.getPrototypeOf(self), {
      indexedDB: { get() { return { open() {} }; }, configurable: true, enumerable: true },
      caches: { get() { return {}; }, configurable: true, enumerable: true },
    }));
    StorageManager = class StorageManager { getDirectory() { return Promise.resolve('dir'); } };
    LockManager = class LockManager { request() { return Promise.resolve('lock'); } };
    BroadcastChannel = class BroadcastChannel {};
    imported = []; importScripts = function () { for (var i = 0; i < arguments.length; i++) imported.push(arguments[i]); };
    location = { href: 'blob:https://tracker.example/1' };
    blobs = []; started = [];
    URL = class URL { constructor(u, b) { if (b && typeof b === 'object') b = b.href; this.href = /^[a-z]+:/.test(u) ? u : b.replace(/[^/]*$/, '') + u; }
      static createObjectURL(blob) { blobs.push(blob.parts.join('')); return 'blob:https://tracker.example/w' + blobs.length; } };
    Blob = class Blob { constructor(parts) { this.parts = parts; } };
    Worker = class Worker { constructor(url) { started.push(url); } };`, scope);
  vm.runInContext(bootstrap, scope);
  check('workerIndexedDbDenied', threw(scope, 'indexedDB'));
  check('workerCachesDenied', threw(scope, 'caches'));
  check('workerOpfsDenied', await rejected(scope, 'new StorageManager().getDirectory()'));
  check('workerLocksDenied', await rejected(scope, 'new LockManager().request("x")'));
  check('workerBroadcastChannelDenied', threw(scope, 'new BroadcastChannel("x")'));
  check('workerLoadsFrameScriptByAddress', vm.runInContext('imported[0]', scope) === 'https://tracker.example/frame/job.js');
  vm.runInContext('importScripts("lib.js")', scope);
  check('workerRelativeImportsResolveAgainstScript', vm.runInContext('imported[1]', scope) === 'https://tracker.example/frame/lib.js');
  vm.runInContext('new Worker("nested.js")', scope);
  check('nestedWorkerStartsFromBootstrap', vm.runInContext('started[0]', scope) === 'blob:https://tracker.example/w1' &&
    /nested\.js/.test(vm.runInContext('blobs[0]', scope)));

  // A first-party frame's worker is the page's own business.
  const own = realm('https://cdn.site-a.example', ['https://www.site-a.example']);
  workerWorld(own);
  vm.runInContext(source, own);
  vm.runInContext('new Worker("job.js")', own);
  check('firstPartyWorkerUntouched', vm.runInContext('started[0]', own) === 'job.js');

  let failed = 0;
  for (const [name, ok] of checks) {
    console.log((ok ? 'ok   ' : 'FAIL ') + name);
    if (!ok) failed++;
  }
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all ' + checks.length + ' checks passed');
})();
