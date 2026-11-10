/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import androidx.webkit.Profile
import androidx.webkit.WebViewFeature
import timber.log.Timber

/**
 * Keeps a session profile's disk footprint as small as the engine allows. Tor Browser holds its
 * cache in memory; a WebView profile's cache is on disk, and the engine offers no memory-only
 * mode, but it does take a quota. Zero asks for the smallest cache it will run with, so a device
 * seized mid-session holds little of what the Tor or private session fetched, and the wipe when
 * the session ends has little to remove. Documented in README and REVIEW as the partial answer.
 */
object ProfileCache {

  fun minimise(profile: Profile) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.HTTP_CACHE_MANAGER)) return
    runCatching {
      val cache = profile.getHttpCache()
      if (cache.quotaBytes != 0L) cache.setQuotaBytes(0L)
    }.onFailure { Timber.w(it, "could not shrink the %s cache", profile.name) }
  }
}
