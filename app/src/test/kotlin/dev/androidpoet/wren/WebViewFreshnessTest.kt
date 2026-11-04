/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.WebViewFreshness
import org.junit.Test

class WebViewFreshnessTest {

  @Test
  fun `the date predicts the shipped Chromium within a release`() {
    // M120 shipped 2023-12-05, M137 on 2025-05-27.
    assertThat(WebViewFreshness.expectedMajor(1_701_734_400_000L)).isIn(119..121)
    assertThat(WebViewFreshness.expectedMajor(1_748_304_000_000L)).isIn(136..138)
  }

  @Test
  fun `a WebView a year behind is stale, a recent one is not`() {
    val nov2026 = 1_793_750_400_000L
    val current = WebViewFreshness.expectedMajor(nov2026)

    assertThat(WebViewFreshness.isStale("${current - 2}.0.6000.1", nov2026)).isFalse()
    assertThat(WebViewFreshness.isStale("${current - 20}.0.4000.1", nov2026)).isTrue()
  }

  @Test
  fun `an unreadable version is never called stale`() {
    assertThat(WebViewFreshness.isStale(null, 0L)).isFalse()
    assertThat(WebViewFreshness.isStale("unknown", 0L)).isFalse()
  }
}
