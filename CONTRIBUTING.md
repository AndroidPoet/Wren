# Contributing

Bug reports, fixes and filter-rule problems are welcome as issues and pull requests.

## Building

JDK 17, the Android SDK and Rust with the Android targets and cargo-ndk (see the README's Build
section; the filter engine in `native/` is Rust). Then:

```sh
./gradlew :app:assembleDebug
./gradlew spotlessApply testDebugUnitTest
```

CI runs `spotlessCheck` and the unit tests on every pull request; run them before you push.
Every source file carries the GPL-3.0-or-later header, which `spotlessApply` adds for you.

## Ground rules

- No analytics, crash reporting, ad SDKs or Google Play Services. Wren talks only to the pages
  you open, your search engine, the filter-list hosts and, when it's on, Tor.
- New network requests need a reason in the pull request and a line in the README's list.
- Keep `core-*` modules free of any dependency on `app`.

By contributing you agree that your work is licensed under GPL-3.0-or-later.
