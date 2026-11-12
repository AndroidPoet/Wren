# Wren privacy review against Tor Browser's design requirements

Date: 2026-11-09, reviewed at Wren 1.0.6 (commit 73838cc) and updated for the 1.0.7, 1.0.8,
1.0.9 and 1.0.13 fixes marked **(1.0.7)** / **(1.0.8)** / **(1.0.9)** / **(1.0.13)**; section 9 is
the 1.0.13 round. Blaze 1.1.8 to 1.1.15 carry the same engine.

The yardstick is *The Design and Implementation of the Tor Browser* (torproject.org, design
document), the only published requirement list for a browser with a Tor mode. Each requirement
is scored against the code, not against the README. PrivacyTests.org's categories are used for
the parts of privacy that have nothing to do with Tor.

Scores: **Met**, **Partial**, **Missing**, **Engine** (impossible or not controllable from an
app on Android's system WebView).

## 1. Proxy obedience: nothing may bypass Tor

| Requirement | Score | Where | Note |
|---|---|---|---|
| Page traffic through the proxy | Met | `TorController.installProxy`, `ProxyController` | HTTP tunnel on 127.0.0.1, verified with check.torproject.org. Socket audit 2026-11-10 (`ss -tnp` sampled through a clean cold start with Tor on and a Wikipedia page load): the process holds sockets to Tor relays and loopback only (each peer checked against the Tor relay search); with Tor off the same start opens only the home-tile icon sites and the filter-list hosts. Same result for the debug build and for the signed 1.0.12 release build rebuilt from its tag |
| The app's own fetches through the proxy | Met | `network/SafeHttp`, `network/TorRouteGuard` | Downloads, icons, filter lists. Guard refuses any non-tunnel route before a packet leaves. Fixed in 1.0.6; every earlier version leaked here |
| Fail closed when Tor is down | Met | `TorController.fail`, `shutDown(sealed)` | Proxy pointed at a closed port |
| Fail closed when the Tor profile is missing | Met **(1.0.8)** | `BrowserActivity.createWebView` | A Tor WebView whose profile cannot be attached gets `blockNetworkLoads`; before 1.0.8 it ran on the clear-net profile over Tor |
| Tor off: nothing of the Tor profile fetches direct | Met **(1.0.8)** | `ServiceWorkerBlocker.install(allowed)`, `TorProfile.destroyAll` | Pages are discarded before the proxy comes down (1.0.6); a service worker of a profile that could not yet be deleted is answered with a block; each stale profile is deleted or emptied one by one. A pooled connection that is not through Tor cancels the call that acquired it (1.0.9; throwing from the listener alone did not fail the call) |
| Tor on: nothing started direct continues | Met **(1.0.8)** | `DownloadController.stopDirectDownloads`, `BlobDownloader.cancelAll` | System downloads (asked of the DownloadManager itself, 1.0.9) and blob spools are cancelled; pages are closed, not reloaded over Tor; a clear-net service worker is answered with a block while Tor is on, a private one when the route changes (1.0.9) |
| Downloads: route checked per hop | Met **(1.0.8)** | `InAppDownloader.fetch` | Every redirect hop asks the guard again |
| Name resolution through Tor | Partial | engine | Page loads resolve at the exit (CONNECT by host). `<link rel="dns-prefetch">` is resolved by the engine through the phone's DNS; no app switch exists |
| WebRTC disabled | Met | `assets/private.js` | Removed from Tor and private pages (1.0.7: private tabs too, plus ICE candidate and session description constructors); not from Web Workers (no document-start hook there). Workers cannot open WebRTC in Chromium, so the practical leak is closed |
| Safe Browsing, passkeys, page preload off under Tor | Met | `WebViewConfigurator` | All three would reach Google or a credential provider outside the proxy |
| External protocol handlers blocked | Met | `BrowserActivity.openExternally` | `intent:`, `market:`, `tel:` open another app only from a tap in the main frame, and under Tor a dialog names the app and warns that it fetches outside Tor before anything leaves |
| Local network unreachable from pages | Met | Tor itself | `ClientRejectInternalAddresses` is on by default; the exit refuses RFC 1918 |
| No plugins | Met | engine | WebView has none |

## 2. State separation and disk avoidance

| Requirement | Score | Where | Note |
|---|---|---|---|
| Tor pages in their own profile | Met | `TorProfile` ("tor-N") | Cookies, storage, cache, service workers, HSTS and TLS session cache are per profile in Chromium |
| Profile destroyed on Tor off and on new identity | Met | `TorProfile.wipe/destroyAll` | Also on a cold start after process death (1.0.6). A profile still loaded is emptied with `deleteBrowsingData` (storage, workers, caches, HSTS) (1.0.8) |
| Clear-net pages never carried into Tor | Met **(1.0.8)** | `syncPagesWithRoute` | Tor on closes the open tabs; a page reloaded over Tor would join the two sessions by address |
| Saved state restored only over the route | Met **(1.0.8)** | `restoreActiveTab`, `switchToTab` (1.0.9) | `restoreState` waits for `whenNetworkReady` like a typed address |
| No history written | Met | `BrowserActivity` history gate | |
| No session restore under Tor | Met | `restoreSessionIfEmpty` | |
| Form data and autofill off | Met | `saveFormData=false`, `importantForAutofill` | |
| Memory-only cache | Partial **(1.0.13)** | `ProfileCache.minimise` | The Tor and private profiles' HTTP cache quota is set to 0, which the engine (`HttpCache.setQuotaBytes`, WebView 151+) takes as the smallest cache it will run with; it still touches disk and is deleted with the profile. Tor Browser keeps it in memory |
| Keyboard does not learn from Tor and private typing | Met **(1.0.7)** | `WrenWebView`, `activity_browser.xml` | `IME_FLAG_NO_PERSONALIZED_LEARNING` on the omnibox and find field always, and on every Tor and private WebView |
| Screenshots and recents blocked | Met | `FLAG_SECURE`, `inheritSecureFlag`, `showSecure`, `AppLock.shieldRecents` | Every sheet since 1.0.6; every AlertDialog of the browser window since 1.0.8; the page's own alert/confirm/prompt/beforeunload dialogs and the settings, library and downloads windows while Tor is on since 1.0.9 |
| Downloads kept inside the app | Partial | `DownloadsFolder` | Files go to the public Downloads folder by design; the record carries `viaTor` |

## 3. Cross-origin identifier unlinkability

| Requirement | Score | Where | Note |
|---|---|---|---|
| Third-party cookies off | Met **(1.0.7)** | `WebViewConfigurator.applyCookiePolicy` | Default off; forced off under Tor whatever the setting says |
| Storage and cache partitioned by top site | Met **(1.0.9)** | `assets/partition.js` | Verified on device with the PrivacyTests supercookie pages: the engine blocks a third-party frame's cookies but gives it its site's localStorage, IndexedDB, Cache API, OPFS, BroadcastChannel and Web Locks, readable first-party on the tracker's own site (1.0.8 and earlier). A frame whose site differs from the top page's now gets Chrome's SecurityError from each of them; same-site frames and top pages are untouched. HTTP cache and HSTS remain the engine's |
| Circuit isolation by first party | **Missing** | `IsolateDestAddr` | Circuits are isolated by destination host, not by the site in the address bar: a tracker embedded on two sites uses one circuit for both. A WebView cannot pass per-tab SOCKS credentials; this is the largest structural gap against Tor Browser and is documented in README and PRIVACY |
| HTTP auth, favicons, blobs isolated | Partial | `PrivacyCleaner`, `FaviconStore` | Cleared on exit and new identity; not partitioned per site during a session. The HTTP auth username field no longer feeds the keyboard's dictionary (1.0.8). A popup's transport WebView joins the opener's profile instead of the default one (1.0.9) |
| Fingerprint seed per identity | Met **(1.0.8)** | `PrivacyScripts.Mode` | Normal, private and Tor pages carry different seeds; one seed across all three was a join between the identities |
| `window.name` reset across sites | Engine | Chromium | Done by the engine since Chromium 86 |
| TLS session tickets, HTTP/2 reuse, Alt-Svc | Engine | Chromium | Partitioned by network isolation key in the engine; cannot be disabled from an app |

## 4. Fingerprinting

| Vector | Score | Where | Note |
|---|---|---|---|
| Canvas | Met **(1.0.8)** | `fingerprint.js` | Under Tor every read and export is blank, Tor Browser's answer: noise in the low bits can be masked away by a reader that drops them, and every Tor user must give the same answer. Outside Tor, per-site, per-session noise seeded by content hash (the Brave approach) keeps sites working. WebGL2 `getBufferSubData`, `VideoFrame.copyTo` and the byte analyser reads follow the same rule (1.0.9). Shims answer `Function.prototype.toString` through a shared per-realm map rather than an own `toString`, so the hook is not visible as an own property (1.0.9) |
| Audio | Met | `fingerprint.js` | Noise on `getChannelData`, analyser reads |
| WebGL | Met | `fingerprint.js` | Noise on `getParameter`, `readPixels` |
| Time zone and clock | Met | `tor.js` | UTC through `Date`, `Intl` and `Temporal`, including strings written without a zone ("2026-01-01 00:00", "Jan 1 2026") (1.0.8); in workers too (1.0.9). Every locale-sensitive API answers en-US unless the page names a locale (1.0.9) |
| Shims in child frames | Partial **(1.0.8)** | `tor.js`, `private.js`, `fingerprint.js` | A frame's window is hardened when the page reaches it through `contentWindow` or `contentDocument`; the `window[0]` / `frames[0]` path cannot be hooked and leaves a same-origin frame's natives bare until the engine injects the script at its document start |
| Locale and Accept-Language | Met | `tor.js`, `TorProfile` | en-US |
| User agent and client hints | Met | `userAgentFor`, `setUserAgentMetadata` | Chrome's reduced UA, Android 10 / K |
| `hardwareConcurrency`, `deviceMemory`, `maxTouchPoints`, battery, storage estimate | Met | `fingerprint.js` | |
| Speech voices, Network Information | Met | `tor.js` | |
| Screen size and letterboxing | Partial **(1.0.7)** | `Letterbox`, `tor.js` | Under Tor the WebView is sized to 100 CSS px steps and centred; `screen.*` (on the prototype, not deletable, 1.0.8), `outer*` report the viewport, `screenX/Y` zero, and `MouseEvent`/`Touch` `screenX/Y` equal `clientX/Y` (1.0.9). `devicePixelRatio` stays real, as in Tor Browser for Android. CSS `device-width` media queries are answered by the engine with the real screen |
| Fonts | **Missing** | | System fonts are measurable. Tor Browser ships a fixed font set. A WebView cannot replace the font stack; a partial defence is a `@font-face` default stylesheet under Tor that names only a few fonts |
| Keyboard layout and keystroke timing | Met **(1.0.9)** | `tor.js` | `KeyboardEvent.code` is derived from `key` as on a US layout, empty for a character the US layout cannot type; `navigator.keyboard` removed; keystroke timing is on the 100 ms grid (1.0.7) |
| Performance timer precision | Met **(1.0.7)** | `tor.js` | `performance.now`, `timeOrigin`, `Date.now`, `new Date()`, `Event.timeStamp`, performance entries, idle deadlines, animation and audio clocks on a 100 ms grid with Tor Browser's per-bucket jitter (1.0.8), so repeated reads cannot be averaged back to the true clock |
| Motion and orientation sensors | Met **(1.0.7)** | `private.js` | Constructors removed, `devicemotion`/`deviceorientation` listeners on the window dropped, `on*` handlers inert, under Tor and in private tabs |
| Media device enumeration | Met **(1.0.7)** | `private.js` | `enumerateDevices` answers an empty list under Tor and in private tabs |
| Web Workers | Met **(1.0.9)** | `tor.js` `hardenWorkers` | `Worker` and `SharedWorker` are started from a blob bootstrap that installs the same shim in the worker (UTC, coarse clocks, en-US, blank OffscreenCanvas and WebGL reads, four cores) and then loads the page's script, with relative addresses resolved against the script's real URL. Verified on device: a worker reports offset 0, UTC string, 4 cores, en-US and a zero canvas. A worker created inside a worker goes through the same bootstrap, resolved against the parent script's real address |

## 5. Long-term unlinkability: New identity

| Requirement | Score | Where |
|---|---|---|
| Pages stopped before the circuits change | Met | `onTorNewIdentity` discards WebViews first (1.0.6) |
| Tabs closed | Met | `clearBrowsingData` |
| Cookies, storage, cache, permissions, HTTP auth cleared | Met | `TorProfile.rotate`, `PrivacyCleaner` |
| HSTS and TLS state cleared | Met | profile deletion takes its network state |
| Fingerprint seed rotated | Met | `PrivacyScripts.rotateSeed(TOR)`; the clear-net profile and its seed are left alone (1.0.8) |
| New circuits | Met | `SIGNAL NEWNYM` plus a new tunnel port so no old connection survives |
| Obvious, one-tap | Met | Tor sheet |

## 6. Other security

| Requirement | Score | Note |
|---|---|---|
| Security levels (standard / safer / safest) | Partial **(round 12)** | Three levels on the Tor sheet. Safest (1.0.7): JavaScript off on every Tor page from its first byte (1.0.8) and web fonts blocked by file extension (`ContentBlocker`; the engine does not pass `Sec-Fetch-Dest` to the interceptor, so a font served without an extension loads), media already click-to-play. Safer (round 12, `assets/safer.js`): WebAssembly and WebGL removed from Tor pages and the frames they create, JavaScript off on pages without HTTPS (onion sites count as secure). Workers keep WebAssembly: the engine never injects into them. No JIT switch exists in the engine |
| Update checks over Tor | Met | There are none: GitHub, F-Droid and Obtainium carry updates |
| Outdated engine visible | Met | `warnIfWebViewStale` |
| Censorship circumvention | Met | obfs4, Snowflake, meek bridges (1.0.6). All three built-in sets bootstrap to 100% and `check.torproject.org` answers `IsTor: true` through them (Snowflake in about four minutes, meek in under twenty seconds, on an API 36 emulator with public DNS, 2026-11-10). Packet capture under Snowflake: Lyrebird opens TLS to the CDN front, UDP to one listed STUN server and WebRTC to the volunteer proxy; the app and Tor open nothing outside loopback |

## 7. PrivacyTests.org categories (outside Tor)

| Category | Score | Note |
|---|---|---|
| State partitioning | Met **(1.0.9)** | Third-party cookies blocked by default; every other storage API denied to cross-site frames by `partition.js`, verified against the PrivacyTests supercookie pages on device |
| Navigation tracking (query parameters) | Met | `LinkCleaner`: utm_*, fbclid, gclid, dclid, gbraid, wbraid, msclkid, twclid, ttclid, yclid, igshid and more; bounce redirectors and AMP unwrapped |
| Referrer | Met | `strict-origin` by default, optional trim to origin |
| HTTPS | Met | Upgrade, HTTPS-only mode, mixed content blocked |
| Global Privacy Control and DNT | Met | `Sec-GPC` and `DNT` headers plus the `navigator` properties (`gpc.js`). The per-profile headers need WebView 142 or newer (Chromium commit 61ccfbb41e, "available in M142"); on WebView 133 a page's own `fetch` was seen without them, only the address-bar loads carry them there |
| Tracker and ad blocking | Met | EasyList, EasyPrivacy, hosts, cosmetic and scriptlet rules |
| WebRTC IP leak | Met in private tabs **(1.0.7)**, Engine in normal tabs | WebRTC removed in private and Tor tabs. In a normal tab a page can collect ICE candidates; Chromium's mDNS obfuscation covers local addresses |
| Fingerprinting resistance (screen, fonts) | Partial | Fonts remain, as in Tor Browser for Android, which ships no bundled fonts either (`tor-browser-build/projects/browser/config` enables the fonts project only off Android); screen closed under Tor (section 4) |
| Miscellaneous (IP via prefetch, link rel=preconnect) | Partial | Page preload off in private and Tor |

## 8. What to build next, in order of payoff

Items 1 to 6 of the original list (third-party cookies, keyboard learning, external-app
confirmation, sensors, letterboxing and timers, WebRTC in private tabs, the Safest level)
shipped in 1.0.7. What remains:

1. ~~Run the PrivacyTests harness~~ Done in 1.0.9: the engine did not partition third-party storage; `partition.js` now denies it.
2. ~~Verify Snowflake and meek~~ Done 2026-11-10: both bootstrap and route a page through Tor; the earlier failure was the emulator's resolver, cured by `-dns-server 8.8.8.8,1.1.1.1`.
3. **Fonts.** A `@font-face` default stylesheet under Tor naming a few fonts would narrow what a page can measure; it cannot replace the system font stack. Tor Browser for Android does not bundle fonts either (checked 2026-11-10), so this is parity, not a gap behind it.
4. ~~Keyboard layout~~ Done in 1.0.9.
5. **Memory-only cache under Tor** (reduced to the engine's smallest quota in 1.0.13, not memory-only) and **CSS `device-width`** remain engine-level gaps.

Things that cannot be fixed from an app and are stated in README and PRIVACY: DNS prefetch
through the phone's resolver, no first-party circuit isolation, no JIT switch,
`X-Requested-With` on subresources (Chromium main still tests, in `AwNetworkConfigurationTest`, that the header is the package name on main-frame and iframe loads by default; androidx.webkit now documents the allow-list API as deprecated and ineffective, the origin trial to remove the header having ended, so 1.0.13 dropped the call), and the engine's version being the system's.

## 9. Round 9 (1.0.13): audit against Brave, DuckDuckGo, Vanadium, Cromite, IronFox

Two passes on 2026-11-10: a Codex source audit of commit 56db2a7, and a feature comparison with
Brave, DuckDuckGo Private Browser, Vanadium, Cromite, IronFox, Firefox Focus and Tor Browser for
Android, each claim checked against the androidx.webkit 1.18 API before it was acted on.

| Item | Status | Where | Notes |
| --- | --- | --- | --- |
| First-party fetches refused everywhere | Fixed | `FilterRule.parse` | uBlock filters carries `*$script,1p,domain=~kat.ai…kat.*…kickass.*`; the parser dropped the `kat.*` entries it cannot express and kept the exclusions, so since the 1.0.4 list snapshot every same-site request without an extension and with `Accept: */*` (fetch, XMLHttpRequest, sendBeacon, pings) was answered 403 on every site. A rule that names sites is now dropped when none of the names survive. Found when the ping test below never reached the server |
| Private tab without a profile | Fixed | `BrowserActivity.createWebView` | On an engine without `MULTI_PROFILE` a private tab ran on the shared cookie jar: signed in where the user expected to be a stranger. It now gets no network, as a Tor tab without its profile does |
| HTTPS-only and downloads | Fixed | `DownloadController.enqueue` | A long-pressed `http://` download, and a download listener's URL, left in cleartext under HTTPS-only and Tor; the in-app downloader refused only http redirects. The first request is upgraded too |
| Mixed content | Fixed | `WebViewConfigurator` | `MIXED_CONTENT_COMPATIBILITY_MODE` let images, audio and video load over plain http on an https page. `NEVER_ALLOW` always; Chrome upgrades those or drops them, and the engine offers no upgrade |
| Cosmetic channel answered for any host | Fixed | `CosmeticFilter.respond` | A page could ask for another host's rules and learn which lists are on and which sites are allowlisted. The frame's own origin, as the engine reports it, is used; an opaque origin gets no answer |
| Blob chunk bound | Fixed | `BlobDownloader.onChunk` | The 512 MiB total was checked after a chunk was decoded and written; bounded per message and on the total before either |
| Media Integrity API | Fixed | `WebViewConfigurator` | The default lets a site obtain a Play token that names the app. Disabled, as in Cromite |
| Enterprise authentication app links | Fixed | `WebViewConfigurator` | A managed device's policy could hand a sign-in redirect to an app without the gesture and the Tor prompt every other hand-off gets. Off |
| Local network from internet pages | Added | `LocalNetwork` | Subresource requests from an internet page to loopback, 0.0.0.0/8, RFC 1918, 100.64/10, link-local, fc00::/7, `localhost` and `.local` are refused (Chrome 142 prompts; Brave refuses localhost). A name that resolves to a private address is not seen, as in Brave. Verified on the emulator: from https://example.com the previous build opened four TLS connections to a listener on the host's address, 1.0.13 none; the engine itself already refuses them from plain-http pages |
| Hyperlink auditing | Added | `assets/ping.js` | `ping` attributes removed in the capture phase of click and auxclick, in every frame; tied to "Remove tracking from links". Firefox and Tor Browser never send pings; Chromium has no switch. Verified on the emulator: a real click on a `ping` link sends two POSTs with the setting off and none with it on |
| Certificate errors | Improved | `WrenWebViewClient.onReceivedSslError` | Still cancelled, never offered through; the error page now says expired, wrong name, untrusted issuer or invalid date instead of one sentence. The engine reports an expired or not-yet-valid certificate as a date error, so that is the text those get; verified on the emulator with expired, wrong-host and self-signed test hosts |
| Safe Browsing | Switch | Settings | Off in Tor mode as before; a user choice outside it, as Brave, DuckDuckGo and Cromite give |
| Tor and private cache | Partial | `ProfileCache` | HTTP cache quota 0 on both profiles where `HTTP_CACHE_MANAGER` exists (WebView 151+): the engine's smallest cache, not a memory-only one |
| New identity ordering | Improved | `BrowserActivity` | Pages are destroyed before storage is cleared (a live page's timer could write back during the wipe); a Tor profile's service worker is gated on the identity it belongs to, so the old identity's worker is blocked the moment the identity changes, before the profile deletion completes |
| Startup prefetch | Fixed | `SpeculativeLoader` | The startup prefetches' cancellation signals were dropped, so `cancel()` could not reach them; kept, and cancelled when Tor is switched on |
| Autocomplete client | Fixed | `core-network/NetworkModule` | Gained the route guard the other client had; a Tor toggle between the readiness check and the connect is refused rather than sent direct |
| App lock | Improved | `AppLock`, `SecureWindows` | An address another app sends while locked waits for the unlock instead of loading behind it; page dialogs are cancelled when the lock comes down |
| WebGPU in private tabs | Added | `assets/private.js` | `navigator.gpu` removed, as under Tor |
| External app hand-off | Fixed | `BrowserActivity.launchExternal` | A target that demands a permission threw `SecurityException`, which would have crashed the app; treated as no app |
| `X-Requested-With` | Documented | README, PRIVACY | The allow-list API is deprecated and ineffective; the dead call is gone and the docs say the header is permanent on subresources |

Recorded and not changed:

- `partition.js` decides same-site by the last two labels (three for short second-level suffixes), not by the Public Suffix List, so two tenants of `github.io` or `blogspot.com` are one site to it. The native side has the list (`PublicSuffixes`); a document-start script cannot afford it in every frame. The engine's own cookie blocking, not the script, is the boundary that matters for that case.
- Reader mode simplifies the page in place; the page's scripts, timers and handlers stay alive behind it. An inert reader needs a second, script-free WebView.
- The filter engine classifies a request by its extension before its headers, never infers `ping`, skips main documents, applies host lists to third parties only, and drops regex rules and unsupported modifiers; `shouldInterceptRequest` also sees only the first URL of a redirect chain. These are the engine's and the design's limits.
- A child frame reached through `frames[0]` rather than `contentWindow` may show its natives before the engine injects the shims; modelled in a harness, not reproduced in a WebView.
- Left against the compared browsers: Onion-Location, a Safer level between Standard and Safest, per-site forget-on-close (Brave's Shred), CNAME-cloaking classification (DuckDuckGo), and IDN display beyond punycode.

## 10. Round 10 (1.0.14): audit of the Tor route, the downloader, the filter engine and the injected scripts

Two Codex passes on 2026-11-10 over commit f14fec4, one on the Kotlin side and one on the
document-start scripts (`fingerprint.js`, `tor.js`, `partition.js`, `private.js`, `ping.js`, the
analytics shim), plus the two items left from round 9's comparison that an app can build:
per-site forgetting (Brave's Shred) and IDN display. Each uBlock Origin claim was checked against
its source before it was acted on.

| Item | Status | Where | Notes |
| --- | --- | --- | --- |
| Forget this site | Added | `PrivacyCleaner.forgetSite`, site panel | One button under the site's name: signs out, closes the site's tabs, deletes its cookies, storage and cache (`WebStorageCompat.deleteBrowsingDataForSite` on WebView 125+, cookie expiry plus `deleteOrigin` per matching origin before that), its history rows and icons, for the profile the tab is on. Per-site settings stay. Verified on the emulator: a page that set a cookie and localStorage reads both back empty after Forget, and its tabs are gone |
| IDN display | Added | `IdnDisplay` | Address bar, error page, site panel, tabs and suggestions decode `xn--` labels when the result cannot pass for something else: one script per label (Han with kana, hangul or bopomofo allowed), ASCII digits only, no Latin look-alike letters, combining marks only on letters outside Latin, Cyrillic and Greek, none of Chrome's denied code points, and a label of Latin-looking Cyrillic or Greek letters only under a TLD of that script. `xn--80ak6aa92e.com` (аррӏе) stays punycode; `.рф` shows. Verified on the emulator: `https://xn--80aswg.xn--p1ai` shows as `сайт.рф` in all three places |
| IP hosts as sites | Fixed | `HostMatcher.registrableDomainStart` | `10.0.2.2` was "the site `2.2`" to the omnibox bolding and to Forget; an address is now its own site |
| Tor downloads pinned to the proxy | Fixed | `InAppDownloader.clientFor` | A Tor download used the shared client's proxy selector; its calls are now made on a client whose proxy is the Tor HTTP port, and a direct download is refused while Tor mode is on, whatever its own flag says |
| Downloads across a Tor toggle | Fixed | `TorController.addRouteListener`, `DownloadController.dropOffRoute` | Switching Tor on cancels every direct in-app and system-manager download and every blob transfer; switching it off cancels the Tor ones. Before, a direct transfer started seconds earlier ran on next to the Tor tabs |
| New identity vs the old profile | Fixed | `TorProfile.retireIdentity`, `BrowserActivity` | Pages are destroyed and the identity retired before the circuit changes, and a private tab under Tor is gated on the identity it was created in, so nothing started before the change can reach the new circuit with the old state |
| Engine without proxy override | Fixed | `TorController.start` | Tor mode used to silently turn itself off; it now stays on with a failed state and the Tor sheet says the engine cannot do it. A browser that quietly reverts to direct is the one failure a Tor user cannot see |
| Filter-list ETags under Tor | Fixed | `FilterListManager` | A list fetched over Tor sent the ETag from the clear-net fetch, which ties the two routes together at the list server. No validators are sent or stored under Tor |
| Private tab without a profile | Fixed | `BrowserActivity.createWebView` | Round 9 gave it no network, but on failure it still fell through to attach the Tor profile when Tor was on. It does not any more |
| Page dialogs behind the lock or off screen | Fixed | `WrenWebChromeClient` | `alert`, `confirm` and `prompt` are shown only for the tab on screen and never while the app lock is down |
| `data:` URL downloads | Fixed | `InAppDownloader.MAX_DATA_URL_LENGTH` | A page could hand the downloader a gigabyte-long `data:` URL to decode in memory; 32 MiB cap, refused with the download-failed toast |
| Favicon decoding | Fixed | `FaviconStore.download` | Bounds are read first, sides over 2048 px refused, the rest sampled down; a 64 MiB icon no longer allocates its full bitmap |
| Long addresses | Fixed | `RuleEngine.RequestContext` | Only the first 512 tokens of an address were tested, so a tracker path pushed past that met no rule; uBlock Origin's tokenizer has no such cap (checked in `urlTokenizer.setURL`). Every token up to a 256 KiB address is seen |
| `site.*` rules | Fixed | `FilterRule.isEntity` | Round 9 dropped rules whose only site names were entities; they are now matched as uBlock does: the host minus its public suffix against the entity. `domain=~shop.*` and `domain=kat.*` both work, under every suffix |
| `match-case` | Fixed | `FilterRule.parse` | Patterns and addresses are lowercased, so a case-sensitive rule would have matched more than written; dropped |
| Cosmetic channel message type | Fixed | `CosmeticFilter` | Only string messages are read; an ArrayBuffer message no longer reaches the parser |
| Page-replaceable intrinsics | Fixed | all document-start scripts | The shims called `.call`, `.apply`, `.forEach`, `.fill`, `.then`, `WeakMap.get` and `JSON.stringify` at run time; a page that replaced any of those on the prototype saw the raw values pass through or steered the shim. Every one is captured at document start (`Reflect.apply` with a captured function, a plain loop instead of `forEach`), in the worker bootstrap too. The harness spies on all of them and sees nothing |
| Fingerprint seed | Fixed | `fingerprint.js`, `PrivacyScripts` | The per-site noise was seeded by a 32-bit multiply-xor of the seed and the site, which a page could invert from its own noise and use to predict another site's. The seed is now 64 bits from `SecureRandom`, mixed with the site through HalfSipHash-2-4; a site learns its own stream and nothing else |
| WebGL readbacks | Fixed | `fingerprint.js` | `readPixels` and `getBufferSubData` noised the whole destination array from index 0 whatever offset and length the call wrote, left `Float32Array` destinations untouched, and noised vertex and index buffers that are not rendering results. Now: exactly the written range, floats included (noise relative to the value, a fixed step smaller than a float's spacing rounded away), pixel-pack buffers only, and under Tor a read of one byte is blank too. `getParameter` coerces its argument before comparing it, as the engine does |
| Audio | Fixed | `fingerprint.js` | Analyser and `AudioBuffer` reads moved one sample in a few hundred, so the flat stretch between touches gave the native values away; every sample now moves, by an amount relative to its value |
| Blob download channel | Fixed | `BlobDownloader.EVENT_NAME` | The event name was random per process, which told a page how long the process had lived and set two sites apart within it. One fixed name |
| `Date.prototype.setYear` under Tor | Fixed | `tor.js` | The legacy setter worked in local time while every getter said UTC, so the zone offset came back through it. UTC now, with the two-digit-year rule kept |
| Worker bootstrap under Tor | Fixed | `tor.js` | A page that broke `URL.createObjectURL` or `Blob` after load made the bootstrap throw, and the worker was then started from the page's own script with the real clocks. The natives are captured at document start, and when the bootstrap still cannot be built the constructor throws a `SecurityError` instead of starting anything |
| Timing getters under Tor | Fixed | `tor.js` | `PerformanceResourceTiming`, `PerformanceNavigationTiming` and the legacy `performance.timing` fields, their `toJSON`, and `AudioContext.getOutputTimestamp` are coarsened to the same 100 ms grid as `now()`; before only `startTime` and `duration` were |
| Same-site in `partition.js` | Fixed | `partition.js` | Schemeful now (an https frame of an http page is cross-site), every ancestor is compared rather than the top only, IPv6 hosts keep their brackets, and the frame's own origin is `window.origin` when it is a string, so an `about:blank` child of the site is the site and a sandboxed frame (`null`) gets nothing |
| Analytics shim callbacks | Fixed | `shims/analytics.js` | `ga` and `gtag` callbacks (`hitCallback`, `event_callback`) are called, a queued `ga.q` is drained and `dataLayer.push` answers; a site waiting on the callback before it navigated used to hang |
| Link pings | Improved | `ping.js` | Also strips in the bubble phase, so a `ping` added by a capture-phase handler of the page is removed before the navigation |

Recorded and not changed:

- Dedicated and service workers get no hardening outside Tor mode; the shims run in documents, and WebView has no worker-script injection. Under Tor the bootstrap wraps dedicated and shared workers but not service workers, which the engine starts without a document.
- The worker bootstrap starts from a `blob:` URL, so a page's `worker-src` CSP that names its own origin only refuses it, and two `SharedWorker` calls from different documents name different blob URLs; the engine's own CSP and worker identity cannot be imitated from a script.
- `noeval-if` makes direct `eval` indirect, as uBlock Origin's does.
- The shims are detectable: `Symbol.for('wren.*')` markers, a wrapped `toString`, and the noise itself. Tor Browser's are too; what is kept from a page is the native answer, not the fact of the shim.
- A page handler that calls `stopPropagation` in the capture phase on the document can keep `ping.js` from seeing a click on a link added by that handler; the engine offers no earlier hook.
- Addresses beyond 256 KiB are not inspected by the filter engine.
- The emulator's WebGL renderer string (SwiftShader behind ANGLE) carries no driver version, so the renderer trim leaves it whole; on a device the version suffix is dropped as before.
- Onion-Location, a Safer level between Standard and Safest, and CNAME-cloaking classification remain, from round 9's comparison.

## 11. Round 11 (1.0.15): second audit of the app side and the injected scripts

Two audit passes on 2026-11-10 over commit c0eb007, one on the Kotlin side and one on the
document-start scripts. Codex had no credits that day, so both passes were run by Claude agents
with the same brief as round 10. Each finding came with a proof of concept, and each fix was
checked against that proof of concept, the harnesses in `tools/` or the emulator. Where a finding
named another browser's behaviour (Chrome's intent handling and third-party storage denial,
uBlock Origin's cosmetic sheet), that behaviour was the target.

| Item | Status | Where | Notes |
| --- | --- | --- | --- |
| `intent:` links that name Wren | Fixed | `BrowserActivity.openExternally` | An `intent:` link whose package was Wren, or which Android resolved to Wren, came back through `onNewIntent` as a new normal tab: a private page could reopen itself with the normal profile's cookies and a history row. As in Chrome, such an intent now loads in the current tab, and when Wren is one of several answers it is left out of the chooser. Verified on the emulator: the link opens in the same tab and the tab count does not change |
| Fingerprint protection under Tor | Fixed | `PrivacyScripts.updateFor` | The canvas, WebGL and audio shims followed the global Block fingerprinting switch even in Tor mode; they are always on under Tor now, and the switch's summary says so |
| Popups and `window.open` | Fixed | all document-start scripts, `WrenWebChromeClient` | A popup starts as a blank document of the opener's origin that the engine never injects into. Every script now wraps `window.open` and hardens the returned window as it hardens a blank frame; `partition.js` treats a popup opened by a third-party frame as that frame. A new window created through `onCreateWindow` gets the scripts of its own transport before it loads. Verified on the emulator: a popup reports the fingerprint marker and native-looking canvas functions |
| Shims in child realms read as native | Fixed | `sourcesOf` in each script | A blank frame's or popup's shims are functions of the parent's realm, so their own `toString` was the parent's, which did not know them, and printed their source. A child realm now shares its parent's source map. A new harness check fails without the change; on the emulator `toDataURL.toString()` reads native from the top page, a popup and a blank frame |
| Exported pixels read through page-controlled properties | Fixed | `fingerprint.js` | Canvas export, `getImageData`, WebGL readbacks, `VideoFrame.copyTo` and audio reads used `length`, `width`, `height`, `buffer` and `data` from the objects themselves, which a page can redefine. All are read through native getters captured at document start, and a canvas export that cannot build its noisy copy fails with the engine's own `SecurityError` instead of returning the clean pixels |
| WebGL readbacks, continued | Fixed | `fingerprint.js` | The written range of `readPixels` now follows the pack parameters (alignment, row length, skipped rows and pixels on WebGL 2) and the format's component count. Pixel-pack buffers are tracked through `bindBufferBase`, `bindBufferRange` and `copyBufferSubData`, and `getBufferSubData` coerces its target |
| Device locale under Tor | Fixed | `tor.js` | A locale tag the page passed to `Intl` was used as given, and the engine fell back to the device's locale behind it. `en-US` is appended to every requested list, so the fallback is English as in Tor Browser |
| Audio worklet under Tor | Fixed | `tor.js` | An `AudioWorklet` ran with the real zone and clocks. Before the page's first module the worklet now loads one that installs the Tor shims in its scope; when that module cannot load, the page's module is refused with an `AbortError` |
| Worker canvas and limits under Tor | Fixed | `tor.js` | In a worker, `OffscreenCanvas.convertToBlob`, `navigator.hardwareConcurrency`, `navigator.deviceMemory`, the storage quota and the WebGL renderer string now answer as in the page |
| Clock rounding across realms | Fixed | `tor.js`, `PrivacyScripts` | Each document and worker drew its own rounding point, so 200 frames read together gave the true time to within a few milliseconds. One secret per Tor identity is injected into every realm and copied into the worker bootstrap, so every realm rounds an instant the same way; a new identity draws a new one |
| More high-resolution times under Tor | Fixed | `tor.js` | Event timing, largest-contentful-paint, element timing, long-animation-frame and script timing entries, `Animation.startTime` and `currentTime`, and `requestVideoFrameCallback` metadata are coarsened to the same 100 ms grid |
| Third-party workers and Storage Buckets | Fixed | `partition.js` | A third-party frame was denied storage, but a dedicated worker it started had IndexedDB, the Cache API, the origin-private file system, Web Locks and `BroadcastChannel` again, and Storage Buckets were open in the frame itself. The worker now starts from a bootstrap that makes the same denials in its scope and then loads the frame's script with relative addresses resolved against it; nested workers get the same. `storageBuckets` and the old `webkitRequestFileSystem` are denied in the frame. Verified on the emulator with a frame on another site: every one of those APIs fails with `SecurityError` in the frame's worker, and its relative `importScripts` still loads |
| gtag callbacks | Fixed | `shims/analytics.js` | The page's own gtag snippet pushes an `arguments` object, which the shim did not look inside, so a page waiting on `event_callback` still hung. Arguments objects and arrays are now read one level deep, and calls queued before the shim loaded are answered |
| Link pings in closed shadow roots | Fixed | `ping.js` | A click inside a closed shadow root reaches the window retargeted to its host, so the link carrying the ping was not on the path, and a page that replaced `composedPath` after load hid every ping. The natives are captured at document start and every shadow root a script attaches gets the same listeners. Verified on the emulator: the ping attribute is gone both in a closed root and after `composedPath` was replaced |
| Filter engine on long addresses | Fixed | `UrlPattern`, `RuleEngine.firstMatch` | A long address that repeated a rule's token made a wildcard rule rescan the rest of the address at every repeat. Each rule is now tried once per request where it is not aligned, and a wildcard rule remembers where its prefix last failed, so the work is linear |
| IDN letters from phonetic notation | Fixed | `IdnDisplay` | Latin-script letters outside the ordinary alphabet blocks (small capitals, click letters like `ǀ`, `ɩ`) could stand in for ASCII. A Latin label is shown decoded only when its letters come from the Latin-1, Latin Extended-A/B or Latin Extended Additional ranges; `gᴏᴏgle` stays punycode, Vietnamese and Polish names still show |
| Device-to-device transfer | Fixed | `data_extraction_rules.xml` | `allowBackup="false"` does not cover the Android 12+ device transfer, which copied every database and preference to a new phone. Both cloud backup and device transfer now exclude all app data |
| Per-site settings from private and Tor tabs | Fixed | `SettingsStore` | A site's JavaScript, desktop, allow-list or keep-signed-in choice made in a private or Tor tab was written to disk and outlived the session. It is now kept in memory only, and dropped when the private session ends, Tor is switched either way, a new identity is requested or browsing data is cleared |
| Long-press in a private tab | Fixed | `BrowserActivity.showLongPressMenu` | Open in new tab and Open image opened normal tabs. A private tab now offers Open in private tab only, and Open image stays private |
| HTTP auth prompts | Fixed | `BrowserActivity.promptHttpAuth` | Shown for the on-screen tab only and never behind the app lock; any other request is cancelled |
| Service workers and the local-network fence | Fixed | `ServiceWorkerBlocker` | A service worker's requests skipped the public-to-private check that page requests get; they are now refused the same way |
| Private downloads | Fixed | `DownloadController.enqueueSecured` | A private tab's download without cookies went to Android's download manager, which keeps its own record; it is now saved by the in-app downloader |
| Tor download records | Fixed | `DownloadController.forgetTor` | The downloads list kept Tor downloads after New identity, Tor off and Clear everything; those rows are deleted at each of the three |
| Prefetches when Tor turns on | Fixed | `WrenApp` route listener | Startup prefetches and preconnects queued before Tor came on were not cancelled; they are now |
| `.onion` names and DNS | Fixed | `SpeculativeLoader` | Typing an onion address with Tor off sent a DNS query for it from the speculative preconnect. Onion hosts are never pre-resolved or warmed, as RFC 7686 asks |
| Downloads without a tap | Fixed | `BrowserActivity.enqueueDownload` | A page could start any number of downloads, from a background tab too. Downloads from a tab that is not on screen, or behind the app lock, are refused; a second download from the same tab within 10 seconds asks first. Verified on the emulator: the second of two quick downloads shows "Allow another download?" |
| Undo close across a Tor switch | Fixed | `BrowserActivity.offerUndoClose` | The Undo snackbar for a closed tab outlived a Tor route change and Clear everything, and could bring a tab back into the other route. It is dismissed on both and checks the route before it restores |

Recorded and not changed:

- The cosmetic filter's stylesheet is in `document.adoptedStyleSheets`, where a page can read which selectors are applied and remove the sheet. uBlock Origin uses a user-origin sheet that pages cannot see; WebView has no API for one.
- A closed shadow root declared in the markup (`<template shadowrootmode="closed">`) is built by the parser, not by `attachShadow`, so `ping.js` does not see inside it.
- In a third-party frame's worker and in a Tor worker, `self.location` is the bootstrap's `blob:` address. Relative `importScripts`, `fetch`, XHR, `WebSocket`, `EventSource` and `new URL(…, location)` are resolved against the real script, but a script that reads `location.href` itself sees the blob.
- An AudioWorklet's `currentFrame` and `currentTime` count samples at the real rate; they are the audio clock, and coarsening them would break playback.
- A WebGL 2 `COPY_READ_BUFFER` that holds pixel data copied without a prior `readPixels` into a pixel-pack buffer is not noised.
- A child realm's shims are still functions of the parent's realm, so `Object.getPrototypeOf(fn) === frame.Function.prototype` is false for them in a blank frame or popup.

## 12. Round 12 (1.0.16): Tor Browser parity and an experimental Tor VPN

| Item | Result | Where | Note |
|---|---|---|---|
| Plain-http pages under Tor | Fixed | `TorController.installProxy` | Tor's tunnel port speaks only CONNECT; WebView sends a plain-http request to an http proxy whole, and the port closed on it (`ERR_EMPTY_RESPONSE`). Wren had papered over it by forcing every http address to https under Tor, which made http onion sites, most of them, unreachable. http now goes to its own SOCKS5 port (`socks5` hands Tor the name), https stays on the tunnel port, and the forced https keeps Tor Browser's HTTPS-Only shape: clear-web pages only, onion sites exempt. Verified on the emulator: `http://example.com/` and the Tor Project's http onion load with 200 |
| Onion-Location | Met | `OnionLocation` | WebView gives the app no response headers, so the header is read from one HEAD request per site and identity, sent through Tor with the page's user agent; https pages only, as in Tor Browser. The offer is a snackbar, never an automatic move. Verified: torproject.org offers its onion. The `<meta http-equiv>` form is not read |
| Circuit display | Met | `TorController.circuitFor`, `TorCircuits` | The Tor sheet shows the site's circuit, relay names and addresses. Tor reports a connected stream by the address the exit resolved, so names come from STREAM events; the site's last circuit is remembered because WebView closes idle connections within seconds. No countries: tor-android ships no GeoIP table. Bridges show without an address |
| New circuit for this site | Met | `TorController.newCircuitFor` | Closes the circuits the site's streams use and its last one, then reloads. Verified: middle and exit change, the guard stays, as in Tor Browser |
| Tor VPN for other apps | Experimental | `TorVpnService`, `native/src/tunnel.rs` | `VpnService` plus tun2proxy (MIT) in the native library: TCP to a dedicated SOCKS port, DNS answered from a pool of stand-in addresses so the exit resolves names, IPv4 and IPv6 routed into the interface, Wren itself excluded. UDP other than DNS fails. Runs only while Tor mode is on and stops with it. Verified on the emulator: another browser with no proxy reaches check.torproject.org as a Tor exit |

Recorded and not changed:

- Turning Tor off and on again in one process can abort inside `libtor.so` (`hs_circuitmap_init` and `tor_cond_wait` assertions): tor-android does not restart Tor cleanly in the same process. Reproduced on the unchanged 1.0.15 release, so it is not from this round. Running Tor in its own process would fix it.
- First-party circuit isolation is still missing; see section 3.

## 13. Round 13 (1.0.16): breakage and ad-leak pass

Measured on an API 36 emulator (WebView 133) with the arm64 debug build: 107 popular sites loaded with blocking on, every site that came back thin reloaded with blocking off, and the six that differed loaded on, off, on again. None stayed broken by the blocker: booking.com, eBay, Fox News, Indeed and Walmart differed run to run in both modes, and Twitch rendered in 31 s with blocking on against 46 to 51 s off. The rest were bot checks ("Just a moment...", "Too Many Requests") or network failures on the emulator in both modes. An ad-leak probe on 22 ad-heavy sites (news, Fandom, Speedtest, w3schools, Yahoo, Reddit) found no ad or tag script served from the network: every Google Publisher Tag and Tag Manager response was Wren's stand-in.

| Item | Result | Where | Note |
|---|---|---|---|
| `no-fetch-if` with a fetch property | Fixed | `scriptlets.js` `propsMatch` | Only `url` and `method` were compared; any other key (`mode:no-cors`, 8 bundled rules) matched every fetch, so a site's own API and login calls got empty answers. Other keys are now read from the fetch's init or Request and skipped only when the call did not set them, as uBlock does |
| `[-]` in json-prune paths | Fixed | `scriptlets.js` `removePath` | Was treated as `[]`, deleting one key from each entry; it now removes the matching array entry whole, so a feed's sponsored item goes (39 bundled rules) |
| `#@#` exception against the fallback selectors | Fixed | `cosmetic_filter.js` | The built-in AdSense/GPT/Taboola selectors went in before the site's exceptions arrived and stayed; the sheet is now rebuilt without them |
| `$generichide` and `$document` page exceptions | Fixed | `CosmeticFilter.respond`, `cosmetic_filter.js` | Parsed but never used for hiding. A `$generichide` page now gets its site rules only and no generic survey; a `$document` page gets nothing and the page script takes back what it applied |
| Procedural cosmetic rules | Added | `CosmeticRules`, `cosmetic_filter.js` `procedural` | About 1,300 site-specific uBlock and Adblock Plus rules (`:has-text` 433, `:style` 170, `:upward` 50, `:xpath`, `:remove`, `:remove-attr`, `:remove-class`, `:matches-css`, `:matches-attr`, `:matches-path`, `:matches-media`, `:min-text-length`, `:-abp-has`, `:-abp-contains`, procedural `:has` and `:not`) were dropped at parse time. They now reach the page as their own list and run after parsing and on every DOM change, hiding through one random attribute and a sheet of their own. A rule with an operator not implemented (`:matches-prop`, `:others`, `:shadow`) is dropped whole, and `:style` declarations with `url(`, braces or escapes are refused. Generic procedural rules are not run, as in uBlock. Checked in WebView 133 against a synthetic page for every operator, content added later included; NDTV hides five sponsored blocks |
| Plain-http downloads under Tor | Fixed | `InAppDownloader.clientFor` | Went to the CONNECT-only tunnel port and failed; they now use the SOCKS port pages use for http, which hands Tor the name |

Recorded and not changed:

- A request with no telling extension or Accept header is matched as script, fetch and font at once, so a `$script` rule on an API host also stops its fetches. WebView gives no `Sec-Fetch-Dest` to settle it.
- The native adapter matches every request as GET and drops `$removeparam` and `$csp` actions; `$ping` and `$websocket` rules have no request path in WebView.
- `partition.js` decides same-site with a short list of second-level labels, not the bundled public suffix list: `a.me.de` and `b.me.de` count as two sites, `x.github.io` and `y.github.io` as one.
- The Kotlin fallback engine (used only when the native library cannot load) misses first-party `||host^` rules and `,badfilter` on host-set entries.
