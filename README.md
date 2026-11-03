<p align="center"><img src="art/logo.svg" width="96" alt=""></p>

# Wren

A private Android browser that blocks trackers and ads out of the box. Free, open source, and
with no account, no telemetry and no servers of its own: the only network traffic is the pages you
open, your search engine's suggestions, filter-list updates, speed-dial site icons (can be
turned off) and, if you turn it on, Tor.

## What it does

- **Tracker and ad blocking**: EasyList, EasyPrivacy and uBlock Origin rules, compiled into a
  pattern engine with cosmetic filtering and scriptlets. Lists update from Settings, and you can
  add your own.
- **Per-site panel** showing what was blocked, grouped by the company that owns it.
- **Clear everything**: closes every tab and deletes history, cookies and cache. Optional wipe
  on exit.
- **Private tabs** on their own WebView profile: no history, cookies or cache written.
- **Tor mode** through Guardian Project's tor-android.
- **Privacy defaults**: HTTPS upgrade, tracking parameters stripped from links, fingerprinting
  and WebRTC leak protection, cookie banners declined and hidden on common consent platforms, third-party cookies blocked,
  `DNT` and `Sec-GPC` sent, form data never saved, location and camera/mic requests denied.
- **App lock** with the device's own screen lock.
- **Search**: DuckDuckGo by default; Brave, Startpage, Mojeek, Wikipedia and Google available.
  `!bangs` work in the address bar.
- **The rest of a browser**: tabs, history, bookmarks, downloads, reader mode, find in page,
  desktop mode, QR sharing.

## Module layout

| Module | Contains |
| --- | --- |
| `app` | Browser UI, tab management, WebView policy, settings |
| `core-model` | `HistoryEntry`, `Bookmark`, `Suggestion`, `SearchEngine`, `TabSnapshot` |
| `core-network` | Retrofit + Sandwich client for DuckDuckGo autocomplete |
| `core-database` | Room database, DAOs and entity mappers for history and bookmarks |
| `core-data` | Repositories that join network and database |
| `core-test` | Shared test fixtures |
| `benchmark` | Macrobenchmark / baseline profile generation |

`core-*` modules never depend on `app`; `app` talks only to `core-data`.

## Design notes

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

## Build

```bash
./gradlew :app:assembleDebug     # build
./gradlew testDebugUnitTest      # unit tests
./gradlew spotlessApply          # format + license headers
```

Requires JDK 17+ (Android Studio's JBR works) and `compileSdk 37`. `minSdk` is 24.

## Credits

The project scaffolding started from [skydoves/Pokedex](https://github.com/skydoves/Pokedex)
(Apache-2.0). The bundled filter lists come from EasyList, uBlock Origin and StevenBlack/hosts.
See `NOTICE` for each licence.

## License

GNU General Public License v3.0 or later. See `LICENSE`.
