/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.privacy.HttpsUpgrader
import org.junit.Test

class HttpsUpgraderTest {

  @Test
  fun `ordinary http addresses are upgraded`() {
    assertThat(HttpsUpgrader.upgrade("http://example.com/a?b=c"))
      .isEqualTo("https://example.com/a?b=c")
  }

  @Test
  fun `https addresses need no upgrade`() {
    assertThat(HttpsUpgrader.upgrade("https://example.com/")).isNull()
  }

  @Test
  fun `non http schemes are left alone`() {
    assertThat(HttpsUpgrader.upgrade("about:blank")).isNull()
    assertThat(HttpsUpgrader.upgrade("mailto:a@example.com")).isNull()
  }

  @Test
  fun `loopback is never upgraded`() {
    assertThat(HttpsUpgrader.upgrade("http://localhost:8080/")).isNull()
    assertThat(HttpsUpgrader.upgrade("http://127.0.0.1/")).isNull()
  }

  @Test
  fun `addresses on the local network are never upgraded`() {
    assertThat(HttpsUpgrader.upgrade("http://192.168.1.10/")).isNull()
    assertThat(HttpsUpgrader.upgrade("http://10.0.0.5/admin")).isNull()
    assertThat(HttpsUpgrader.upgrade("http://172.16.0.1/")).isNull()
    assertThat(HttpsUpgrader.upgrade("http://169.254.1.1/")).isNull()
  }

  @Test
  fun `a public address that merely starts with 172 is still upgraded`() {
    // 172.32 is outside the private range; only 172.16-31 is reserved.
    assertThat(HttpsUpgrader.upgrade("http://172.32.0.1/")).isEqualTo("https://172.32.0.1/")
  }

  @Test
  fun `onion and local suffixes are left alone`() {
    assertThat(HttpsUpgrader.upgrade("http://example.onion/")).isNull()
    assertThat(HttpsUpgrader.upgrade("http://printer.local/")).isNull()
  }
}
