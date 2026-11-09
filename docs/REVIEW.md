# Wren privacy review against Tor Browser's design requirements

Date: 2026-11-09, reviewed at Wren 1.0.6 (commit 73838cc) and updated for the 1.0.7 fixes marked
**(1.0.7)**. Blaze 1.1.8 / 1.1.9 carry the same engine.

The yardstick is *The Design and Implementation of the Tor Browser* (torproject.org, design
document), the only published requirement list for a browser with a Tor mode. Each requirement
is scored against the code, not against the README. PrivacyTests.org's categories are used for
the parts of privacy that have nothing to do with Tor.

Scores: **Met**, **Partial**, **Missing**, **Engine** (impossible or not controllable from an
app on Android's system WebView).

## 1. Proxy obedience: nothing may bypass Tor

| Requirement | Score | Where | Note |
|---|---|---|---|
| Page traffic through the proxy | Met | `TorController.installProxy`, `ProxyController` | HTTP tunnel on 127.0.0.1, verified with check.torproject.org |
| The app's own fetches through the proxy | Met | `network/SafeHttp`, `network/TorRouteGuard` | Downloads, icons, filter lists. Guard refuses any non-tunnel route before a packet leaves. Fixed in 1.0.6; every earlier version leaked here |
| Fail closed when Tor is down | Met | `TorController.fail`, `shutDown(sealed)` | Proxy pointed at a closed port |
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
| Profile destroyed on Tor off and on new identity | Met | `TorProfile.wipe/destroyAll` | Also on a cold start after process death (1.0.6) |
| No history written | Met | `BrowserActivity` history gate | |
| No session restore under Tor | Met | `restoreSessionIfEmpty` | |
| Form data and autofill off | Met | `saveFormData=false`, `importantForAutofill` | |
| Memory-only cache | Partial | `cacheMode=LOAD_DEFAULT` | The Tor profile's cache is written to disk and deleted with the profile. Tor Browser keeps it in memory. A device seized mid-session shows cached pages |
| Keyboard does not learn from Tor and private typing | Met **(1.0.7)** | `WrenWebView`, `activity_browser.xml` | `IME_FLAG_NO_PERSONALIZED_LEARNING` on the omnibox and find field always, and on every Tor and private WebView |
| Screenshots and recents blocked | Met | `FLAG_SECURE`, `inheritSecureFlag` | Every sheet included since 1.0.6 |
| Downloads kept inside the app | Partial | `DownloadsFolder` | Files go to the public Downloads folder by design; the record carries `viaTor` |

## 3. Cross-origin identifier unlinkability

| Requirement | Score | Where | Note |
|---|---|---|---|
| Third-party cookies off | Met **(1.0.7)** | `WebViewConfigurator.applyCookiePolicy` | Default off; forced off under Tor whatever the setting says |
| Storage and cache partitioned by top site | Engine | Chromium | Chromium partitions network state and storage by top-level site in current WebView builds. Not independently verified on device; worth one PrivacyTests-style run |
| Circuit isolation by first party | **Missing** | `IsolateDestAddr` | Circuits are isolated by destination host, not by the site in the address bar: a tracker embedded on two sites uses one circuit for both. A WebView cannot pass per-tab SOCKS credentials; this is the largest structural gap against Tor Browser and is documented in README and PRIVACY |
| HTTP auth, favicons, blobs isolated | Partial | `PrivacyCleaner`, `FaviconStore` | Cleared on exit and new identity; not partitioned per site during a session |
| `window.name` reset across sites | Engine | Chromium | Done by the engine since Chromium 86 |
| TLS session tickets, HTTP/2 reuse, Alt-Svc | Engine | Chromium | Partitioned by network isolation key in the engine; cannot be disabled from an app |

## 4. Fingerprinting

| Vector | Score | Where | Note |
|---|---|---|---|
| Canvas | Met (different design) | `fingerprint.js` | Per-site, per-session noise seeded by content hash. Tor Browser returns blank; noise is the Brave approach and keeps sites working |
| Audio | Met | `fingerprint.js` | Noise on `getChannelData`, analyser reads |
| WebGL | Met | `fingerprint.js` | Noise on `getParameter`, `readPixels` |
| Time zone and clock | Met | `tor.js` | UTC through `Date` and `Intl`, not in workers |
| Locale and Accept-Language | Met | `tor.js`, `TorProfile` | en-US |
| User agent and client hints | Met | `userAgentFor`, `setUserAgentMetadata` | Chrome's reduced UA, Android 10 / K |
| `hardwareConcurrency`, `deviceMemory`, `maxTouchPoints`, battery, storage estimate | Met | `fingerprint.js` | |
| Speech voices, Network Information | Met | `tor.js` | |
| Screen size and letterboxing | Met **(1.0.7)** | `Letterbox`, `tor.js` | Under Tor the WebView is sized to 100 CSS px steps and centred; `screen.*`, `outer*` report the viewport, `screenX/Y` zero. `devicePixelRatio` stays real, as in Tor Browser for Android |
| Fonts | **Missing** | | System fonts are measurable. Tor Browser ships a fixed font set. A WebView cannot replace the font stack; a partial defence is a `@font-face` default stylesheet under Tor that names only a few fonts |
| Keyboard layout and keystroke timing | Partial | `tor.js` | `KeyboardEvent.code` is real; keystroke timing is on the 100 ms grid (1.0.7) |
| Performance timer precision | Met **(1.0.7)** | `tor.js` | `performance.now`, `Date.now`, `new Date()`, `Event.timeStamp` and animation-frame timestamps floored to 100 ms, Tor Browser's precision |
| Motion and orientation sensors | Met **(1.0.7)** | `private.js` | Constructors removed, `devicemotion`/`deviceorientation` listeners on the window dropped, `on*` handlers inert, under Tor and in private tabs |
| Media device enumeration | Met **(1.0.7)** | `private.js` | `enumerateDevices` answers an empty list under Tor and in private tabs |
| Web Workers | Engine | | No document-start injection into workers; a page that measures the time zone, canvas or concurrency inside a worker sees the truth. README states this |

## 5. Long-term unlinkability: New identity

| Requirement | Score | Where |
|---|---|---|
| Pages stopped before the circuits change | Met | `onTorNewIdentity` discards WebViews first (1.0.6) |
| Tabs closed | Met | `clearBrowsingData` |
| Cookies, storage, cache, permissions, HTTP auth cleared | Met | `TorProfile.rotate`, `PrivacyCleaner` |
| HSTS and TLS state cleared | Met | profile deletion takes its network state |
| Fingerprint seed rotated | Met | `PrivacyScripts.rotateSeed` |
| New circuits | Met | `SIGNAL NEWNYM` plus a new tunnel port so no old connection survives |
| Obvious, one-tap | Met | Tor sheet |

## 6. Other security

| Requirement | Score | Note |
|---|---|---|
| Security levels (safer / safest) | Met **(1.0.7)** | Safest switch on the Tor sheet: JavaScript off on every Tor page and web fonts blocked (`ContentBlocker`), media already click-to-play. No JIT switch exists in the engine |
| Update checks over Tor | Met | There are none: GitHub, F-Droid and Obtainium carry updates |
| Outdated engine visible | Met | `warnIfWebViewStale` |
| Censorship circumvention | Met | obfs4, Snowflake, meek bridges (1.0.6); Snowflake and meek not yet exercised on a device with working DNS |

## 7. PrivacyTests.org categories (outside Tor)

| Category | Score | Note |
|---|---|---|
| State partitioning | Engine, default good | Third-party cookies blocked by default; storage and cache partitioned by the engine. Not run through the actual test harness yet |
| Navigation tracking (query parameters) | Met | `LinkCleaner`: utm_*, fbclid, gclid, dclid, gbraid, wbraid, msclkid, twclid, ttclid, yclid, igshid and more; bounce redirectors and AMP unwrapped |
| Referrer | Met | `strict-origin` by default, optional trim to origin |
| HTTPS | Met | Upgrade, HTTPS-only mode, mixed content blocked |
| Global Privacy Control and DNT | Met | `Sec-GPC` and `DNT` headers plus the `navigator` properties (`gpc.js`) |
| Tracker and ad blocking | Met | EasyList, EasyPrivacy, hosts, cosmetic and scriptlet rules |
| WebRTC IP leak | Met in private tabs **(1.0.7)**, Engine in normal tabs | WebRTC removed in private and Tor tabs. In a normal tab a page can collect ICE candidates; Chromium's mDNS obfuscation covers local addresses |
| Fingerprinting resistance (screen, fonts) | Partial | Fonts remain; screen closed under Tor (section 4) |
| Miscellaneous (IP via prefetch, link rel=preconnect) | Partial | Page preload off in private and Tor |

## 8. What to build next, in order of payoff

Items 1 to 6 of the original list (third-party cookies, keyboard learning, external-app
confirmation, sensors, letterboxing and timers, WebRTC in private tabs, the Safest level)
shipped in 1.0.7. What remains:

1. **Run the PrivacyTests harness** against a build to confirm the engine-level partitioning claims instead of assuming them.
2. **Verify Snowflake and meek** on a device with working DNS.
3. **Fonts.** A `@font-face` default stylesheet under Tor naming a few fonts would narrow what a page can measure; it cannot replace the system font stack.
4. **Keyboard layout and keystroke timing.** `KeyboardEvent.code` is real; timing is now coarse through `Event.timeStamp`.

Things that cannot be fixed from an app and are stated in README and PRIVACY: DNS prefetch
through the phone's resolver, no first-party circuit isolation, no worker shims, no JIT switch,
`X-Requested-With` on subresources, and the engine's version being the system's.
