/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

import androidx.webkit.CustomHeader
import androidx.webkit.Profile
import androidx.webkit.WebViewFeature
import timber.log.Timber

/**
 * The `Sec-GPC` and `DNT` signals on every request a profile makes, not only on the documents
 * the app loads itself: a page's own fetches, frames and images are where the ad tech that
 * honours Global Privacy Control actually listens. Set on the profile, so it covers the
 * default, private and Tor profiles alike, on engines that support custom headers; elsewhere
 * the app-started loads keep carrying them and the page sees the JavaScript properties.
 */
object PrivacyHeaders {

  private const val GPC = "Sec-GPC"
  private const val DNT = "DNT"
  private val EVERYWHERE = setOf("*")

  val isSupported: Boolean
    get() = WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)

  /** Adds or removes the signals on [profile] to match [enabled]. */
  fun apply(profile: Profile, enabled: Boolean) {
    if (!isSupported) return
    runCatching {
      for (name in listOf(GPC, DNT)) {
        val present = profile.hasCustomHeader(name)
        if (enabled && !present) profile.addCustomHeader(CustomHeader(name, "1", EVERYWHERE))
        if (!enabled && present) profile.clearCustomHeader(name)
      }
    }.onFailure { Timber.w(it, "could not set the privacy headers") }
  }
}
