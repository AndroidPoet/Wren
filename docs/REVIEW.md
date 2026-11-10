# Wren privacy review against Tor Browser's design requirements

Date: 2026-11-09, reviewed at Wren 1.0.6 (commit 73838cc) and updated for the 1.0.7, 1.0.8 and
1.0.9 fixes marked **(1.0.7)** / **(1.0.8)** / **(1.0.9)**. Blaze 1.1.8 to 1.1.11 carry the same engine.

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
| Memory-only cache | Partial | `cacheMode=LOAD_DEFAULT` | The Tor profile's cache is written to disk and deleted with the profile. Tor Browser keeps it in memory. A device seized mid-session shows cached pages |
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
| Security levels (safer / safest) | Partial **(1.0.7)** | Safest switch on the Tor sheet: JavaScript off on every Tor page from its first byte (1.0.8) and web fonts blocked by file extension (`ContentBlocker`; the engine does not pass `Sec-Fetch-Dest` to the interceptor, so a font served without an extension loads), media already click-to-play. No JIT switch exists in the engine |
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
5. **Memory-only cache under Tor** and **CSS `device-width`** remain engine-level gaps.

Things that cannot be fixed from an app and are stated in README and PRIVACY: DNS prefetch
through the phone's resolver, no first-party circuit isolation, no JIT switch,
`X-Requested-With` on subresources, and the engine's version being the system's.
