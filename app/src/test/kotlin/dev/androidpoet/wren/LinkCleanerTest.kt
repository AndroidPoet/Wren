/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.privacy.LinkCleaner
import org.junit.Test

/**
 * Removing the wrong parameter breaks the page, which is worse than leaving a tracker behind, so
 * the "leaves this alone" cases carry most of the weight here.
 */
class LinkCleanerTest {

  @Test
  fun `campaign tags are removed`() {
    assertThat(LinkCleaner.clean("https://example.com/a?utm_source=x&utm_medium=y"))
      .isEqualTo("https://example.com/a")
  }

  @Test
  fun `click identifiers are removed`() {
    assertThat(LinkCleaner.clean("https://example.com/?fbclid=abc"))
      .isEqualTo("https://example.com/")
    assertThat(LinkCleaner.clean("https://example.com/?gclid=abc"))
      .isEqualTo("https://example.com/")
  }

  @Test
  fun `email and snapchat click tokens are removed`() {
    assertThat(LinkCleaner.clean("https://example.com/?oft_id=1&oft_k=2&page=3"))
      .isEqualTo("https://example.com/?page=3")
    assertThat(LinkCleaner.clean("https://example.com/?ScCid=abc"))
      .isEqualTo("https://example.com/")
  }

  @Test
  fun `real parameters survive alongside tracking ones`() {
    assertThat(LinkCleaner.clean("https://example.com/search?q=kotlin&utm_source=news&page=2"))
      .isEqualTo("https://example.com/search?q=kotlin&page=2")
  }

  @Test
  fun `a url with no tracking is returned untouched`() {
    val url = "https://example.com/search?q=kotlin&page=2"
    assertThat(LinkCleaner.clean(url)).isEqualTo(url)
    assertThat(LinkCleaner.hasTrackingParameters(url)).isFalse()
  }

  @Test
  fun `parameters that merely look like tracking are kept`() {
    // Not on the list, so not our business - guessing here is how pages get broken.
    val url = "https://example.com/?source=newsletter&campaign=spring&id=42"
    assertThat(LinkCleaner.clean(url)).isEqualTo(url)
  }

  @Test
  fun `the fragment is preserved`() {
    assertThat(LinkCleaner.clean("https://example.com/a?utm_source=x#section-2"))
      .isEqualTo("https://example.com/a#section-2")
    assertThat(LinkCleaner.clean("https://example.com/a?q=1&fbclid=z#top"))
      .isEqualTo("https://example.com/a?q=1#top")
  }

  @Test
  fun `urls without a query are untouched`() {
    assertThat(LinkCleaner.clean("https://example.com/a/b")).isEqualTo("https://example.com/a/b")
    assertThat(LinkCleaner.clean("https://example.com/a#frag"))
      .isEqualTo("https://example.com/a#frag")
  }

  @Test
  fun `a parameter whose value contains a tracking name is kept`() {
    val url = "https://example.com/?redirect=https%3A%2F%2Fx.com%3Futm_source%3Dy"
    assertThat(LinkCleaner.clean(url)).isEqualTo(url)
  }

  @Test
  fun `tracking names are caught however they are cased or encoded`() {
    assertThat(LinkCleaner.clean("https://a.com/?UTM_Source=x&id=1"))
      .isEqualTo("https://a.com/?id=1")
    assertThat(LinkCleaner.clean("https://a.com/?utm%5Fmedium=x&id=1"))
      .isEqualTo("https://a.com/?id=1")
    assertThat(LinkCleaner.clean("https://a.com/?fbclid=x&gad_source=1&ysclid=2&id=1"))
      .isEqualTo("https://a.com/?id=1")
  }

  @Test
  fun `a fragment that looks like a query is not touched`() {
    assertThat(LinkCleaner.clean("https://a.com/?utm_source=x#/route?utm_source=keep"))
      .isEqualTo("https://a.com/#/route?utm_source=keep")
  }
}
