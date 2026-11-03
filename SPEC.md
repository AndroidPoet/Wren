# Wren — open-source private browser

Goal: a complete, free, open-source Android browser that competes with DuckDuckGo Browser.
Starts from an existing privacy engine written by the same author. It must be a
standalone product with its own look, not a reskin.

## Kept (engine)
- Blocker: EasyList/EasyPrivacy/uBO pattern engine, cosmetic rules, scriptlets, filter-list refresh
- Privacy: HTTPS upgrade, link cleaning, fingerprint + WebRTC scripts, autoconsent,
  private tabs on their own WebView Profile, clear data, app lock
- Tor mode (Guardian Project tor-android, BSD)
- Tabs, history, bookmarks, downloads, reader mode, find in page, bangs, QR share

## Removed (premium / commercial features)
- Native video player, pop-out video, background playback service (media3)
- Media sniffing / "Play natively"
- Sponsored featured row + signed home-config backend
- Private YouTube player

## Decided (2026-11-03)
- Name Wren, applicationId dev.androidpoet.wren, GPL-3.0-or-later, version 1.0.0 (code 1)
- Own look: warm paper and ink, russet accent, wren launcher icon, flat home masthead
- Neutral speed dial: Wikipedia, OpenStreetMap, Internet Archive, Gutenberg, F-Droid,
  Mastodon, Hacker News, Codeberg
- fastlane/ metadata for F-Droid

## Still to do
1. Real-phone test, Tor test
2. Public GitHub repo + release tag, F-Droid and Play submission (owner confirms each)

## F-Droid audit (2026-10-31)
- Every dependency is FOSS (AndroidX, Hilt, Retrofit/Moshi/OkHttp, ZXing, Guardian Project tor-android + jtorctl). No GMS, Firebase or ad SDKs.
- No calls to owner servers remain: the home-config endpoint went with the sponsored row.
  Network use: the chosen search engine's suggest API, filter-list downloads (easylist.to,
  adblockplus.org, fanboy.co.nz, githubusercontent), and Tor when it's turned on.
- Default search is DuckDuckGo. That's fine for F-Droid. Product question: a DDG competitor
  may want a different default (Brave, Startpage or Mojeek are already in the list).
- Already at parity: "Clear everything" burn action, per-site blocked-tracker panel grouped by company.

## Device check (2026-10-31)
Debug APK on an API 36 google_apis emulator: it installed, opened https://example.com from a VIEW intent, and showed home, the overflow menu and Settings. No crash-buffer entries, and the process stayed alive. Home has no featured row and Settings has no player switches. Branding was renamed to Wren on 2026-11-02.
