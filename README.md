<p align="center">
  <img src="art/social-preview.png" width="720" alt="Wren, a quiet way to browse">
</p>

<p align="center">
  <a href="https://github.com/AndroidPoet/Wren/actions/workflows/ci.yml"><img src="https://github.com/AndroidPoet/Wren/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-A8482A" alt="License: GPL-3.0-or-later"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-5E4A36" alt="Android 7.0+">
</p>

<p align="center">
  A private Android browser that blocks trackers and ads out of the box.<br>
  No account. No telemetry. No servers of its own.
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="23%" alt="">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="23%" alt="">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="23%" alt="">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="23%" alt="">
</p>

## Features

**Blocking**
- Tracker and ad blocking with EasyList, EasyPrivacy and uBlock Origin rules, compiled into a
  pattern engine with cosmetic filtering and scriptlets. Lists update from Settings, and you can
  add your own.
- A per-site panel showing what was blocked, grouped by the company that owns it.
- Cookie banners declined and hidden on common consent platforms.

**Privacy**
- **Clear everything**: one tap closes every tab and deletes history, cookies and cache, with an
  optional wipe on exit.
- **Private tabs** on their own WebView profile: no history, cookies or cache written.
- **Tor mode** through the Guardian Project's tor-android.
- **App lock** using the device's own screen lock.
- HTTPS upgrade, tracking parameters stripped from links, fingerprinting and WebRTC leak
  protection, third-party cookies blocked, `DNT` and `Sec-GPC` sent, form data never saved,
  location and camera/mic requests denied.

**Browsing**
- DuckDuckGo by default; Brave, Startpage, Mojeek, Wikipedia and Google available. `!bangs` work
  in the address bar.
- Tabs, history, bookmarks, downloads, reader mode, find in page, desktop mode and QR sharing.

## What leaves your phone

Wren has no backend. The only network traffic is:

| Traffic | When |
| --- | --- |
| The pages you open | Always |
| Search suggestions from your chosen engine | Only if turned on in Settings, then while typing |
| Filter-list downloads | Shortly after launch when a list is over a week old (so also on first launch), or when you tap Update now in Settings |
| Speed-dial site icons | Can be turned off |
| Safe Browsing checks (hashed address prefixes, to Google through Android) | Always, except in Tor mode |
| Passkey sign-ins, through your phone's password manager | Only when a site asks you to sign in with one |
| Tor | Only if you turn it on |

Addresses are looked up with your phone's own DNS. To encrypt those lookups, turn on Private DNS
in Android's network settings; in Tor mode, lookups go through Tor instead. The full
[privacy policy](PRIVACY.md) lists the same.

## What Wren is not

Wren is a shell around Android's system WebView, which is Chromium shipped and updated by Google
or your phone maker. Wren decides what is loaded and where it goes; it does not control the
engine. Before relying on it for anything sensitive, know the limits:

- **The engine is the system's.** Its version, security patches, site isolation, JIT, storage
  partitioning and WebRTC internals come from the system WebView, not from Wren. Wren warns when
  the engine is out of date, but only you can update it.
- **Tor mode is not Tor Browser.** It sends every tab and every request the app makes through
  Tor, refuses plain HTTP, pauses downloads and removes WebRTC from pages. It does not make you
  look like other Tor users: fingerprint protection adds noise that changes each time Wren starts
  rather than giving every user the same answers, and the engine version, screen size and fonts
  stay visible. All sites share the same circuits until you choose New identity, which closes
  every tab, clears data and asks Tor for new circuits.
- **Safe Browsing talks to Google.** Short hashed prefixes of addresses go to Google through
  Android, except in Tor mode. There is no switch for it yet.
- **Nobody independent has audited Wren.** The source is public and reviews of it are
  welcome, but reading the source shows what Wren intends to do, not that it is safe against a
  determined adversary.

If your safety depends on being anonymous, use [Tor Browser for
Android](https://www.torproject.org/download/#android). Wren is for everyday browsing with
trackers and ads out of the way.

## Download

Get the APK for your phone from [Releases](https://github.com/AndroidPoet/Wren/releases/latest):
`arm64-v8a` for most phones, or `universal` if you are not sure. Or build it from source (below).
[Obtainium](https://github.com/ImranR98/Obtainium) can watch that page and install updates for you.

**Check what you installed.** Every release ships a `SHA256SUMS` file, and every APK is signed
with this certificate:

```
CN=AndroidPoet, O=AndroidPoet
SHA-256: AD:76:AB:B2:F4:C5:4A:A9:E9:A9:9E:FA:F9:1F:AD:2B:2B:37:98:CF:87:26:5E:C2:DA:39:2C:57:B2:C8:C7:CE
```

Compare it after installing with [AppVerifier](https://github.com/soupslurpr/AppVerifier), or
before installing with `apksigner verify --print-certs wren-*.apk`. Android refuses an update
over Wren unless it is signed with this same key, so one checked install covers the ones after it.

## Build

```bash
./gradlew :app:assembleDebug     # build
./gradlew testDebugUnitTest      # unit tests
./gradlew spotlessApply          # format + license headers
```

Requires JDK 17+ (Android Studio's JBR works) and `compileSdk 37`. `minSdk` is 24.

## How it works

<details>
<summary>Module layout</summary>

| Module | Contains |
| --- | --- |
| `app` | Browser UI, tab management, WebView policy, settings |
| `core-model` | `HistoryEntry`, `Bookmark`, `Suggestion`, `SearchEngine`, `TabSnapshot` |
| `core-network` | Retrofit client for search-engine autocomplete |
| `core-database` | Room database, DAOs and entity mappers for history and bookmarks |
| `core-data` | Repositories that join network and database |
| `core-test` | Shared test fixtures |
| `benchmark` | Macrobenchmark / baseline profile generation |

`core-*` modules never depend on `app`; `app` talks only to `core-data`.

</details>

<details>
<summary>Design notes</summary>

**A small pool of live tabs.** Up to four tabs keep a live `WebView`; the rest are held as saved
state (`TabManager`). A tab still in the pool switches instantly and keeps loading, playing and
running while another is on screen; one that has aged out costs a re-render when it comes back.
Memory therefore tracks the pool, not the tab count, and under `onTrimMemory` the pool collapses
to the active tab alone. Low-RAM devices keep one, which is what every device used to do.

**Suggestions emit twice.** `SuggestionRepository` emits local history matches immediately, then
re-emits widened with remote results. The omnibox stays responsive when the network is slow, and
degrades to history-only when it is absent or disabled.

**History is per-URL, not per-visit.** One row per page with `visitCount` and `lastVisitedAt`; a
row-per-load table grows without bound and the omnibox only needs "when last" and "how often".
The speed dial folds those rows by host, so a site that redirects, challenges and lands still
occupies one tile rather than three.

**The address bar names the committed page.** `onPageCommitVisible` moves it, not `onPageStarted`,
so a load that is slow, failed or cancelled leaves the bar describing the page still on screen -
the alternative is an address bar that can be made to display a site the user is not on.

</details>

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the build,
checks and ground rules. Report privacy leaks and security bugs privately, as described in
[SECURITY.md](SECURITY.md).

## License

GNU General Public License v3.0 or later. See [LICENSE](LICENSE).
