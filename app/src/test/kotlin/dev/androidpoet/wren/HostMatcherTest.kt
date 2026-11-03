/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.blocking.HostMatcher
import org.junit.Test

/**
 * This is the code that decides whether a page loads or breaks, so the near-misses matter more
 * than the hits.
 */
class HostMatcherTest {

  @Test
  fun `host is extracted without full uri parsing`() {
    assertThat(HostMatcher.hostOf("https://ads.example.com/a/b?c=d")).isEqualTo("ads.example.com")
    assertThat(HostMatcher.hostOf("http://Example.COM:8080/x")).isEqualTo("example.com")
    assertThat(HostMatcher.hostOf("https://user:pw@example.com/x")).isEqualTo("example.com")
    assertThat(HostMatcher.hostOf("https://example.com./x")).isEqualTo("example.com")
  }

  @Test
  fun `non urls yield no host`() {
    assertThat(HostMatcher.hostOf("about:blank")).isNull()
    assertThat(HostMatcher.hostOf("")).isNull()
  }

  @Test
  fun `subdomains of a blocked host match`() {
    assertThat(HostMatcher.matches("stats.g.doubleclick.net", "doubleclick.net")).isTrue()
    assertThat(HostMatcher.matches("doubleclick.net", "doubleclick.net")).isTrue()
  }

  @Test
  fun `a lookalike host does not match`() {
    // The bug a naive endsWith would introduce.
    assertThat(HostMatcher.matches("notdoubleclick.net", "doubleclick.net")).isFalse()
    assertThat(HostMatcher.matches("doubleclick.net.evil.com", "doubleclick.net")).isFalse()
  }

  @Test
  fun `candidates walk up the labels, most specific first`() {
    assertThat(HostMatcher.candidates("a.b.example.com"))
      .containsExactly("a.b.example.com", "b.example.com", "example.com")
      .inOrder()
  }

  @Test
  fun `a bare host is its own only candidate`() {
    assertThat(HostMatcher.candidates("localhost")).containsExactly("localhost")
  }

  @Test
  fun `same party covers sibling subdomains`() {
    assertThat(HostMatcher.isSameParty("cdn.example.com", "www.example.com")).isTrue()
    assertThat(HostMatcher.isSameParty("example.com", "example.com")).isTrue()
  }

  @Test
  fun `different sites are third party`() {
    assertThat(HostMatcher.isSameParty("doubleclick.net", "news.example.com")).isFalse()
  }

  @Test
  fun `country code second level suffixes do not make strangers first party`() {
    assertThat(HostMatcher.registrableDomain("www.dailymail.co.uk")).isEqualTo("dailymail.co.uk")
    assertThat(HostMatcher.isSameParty("i.dailymail.co.uk", "www.dailymail.co.uk")).isTrue()
    assertThat(HostMatcher.isSameParty("ads.tracker.co.uk", "www.dailymail.co.uk")).isFalse()
    assertThat(HostMatcher.isSameParty("cdn.news.com.au", "www.news.com.au")).isTrue()
    assertThat(HostMatcher.isSameParty("pixel.other.com.au", "www.news.com.au")).isFalse()
    // A two-letter TLD with an ordinary second label keeps the usual two-label rule.
    assertThat(HostMatcher.registrableDomain("www.example.de")).isEqualTo("example.de")
    assertThat(HostMatcher.registrableDomain("co.uk")).isEqualTo("co.uk")
  }
}
