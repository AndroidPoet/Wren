/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.download.DownloadHeaders
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadHeadersTest {

  @Test
  fun test_referer_trimsToTheOriginWhenTrimmingIsOn() {
    val referer = DownloadHeaders.referer("https://user.example.com:8443/inbox?id=42#x", true)

    assertThat(referer).isEqualTo("https://user.example.com:8443/")
  }

  @Test
  fun test_referer_keepsTheFullAddressWhenTrimmingIsOff() {
    val page = "https://example.com/files?token=abc"

    assertThat(DownloadHeaders.referer(page, false)).isEqualTo(page)
  }

  @Test
  fun test_referer_sendsNothingForNonWebPages() {
    assertThat(DownloadHeaders.referer("about:blank", true)).isNull()
    assertThat(DownloadHeaders.referer("file:///sdcard/x.html", false)).isNull()
    assertThat(DownloadHeaders.referer(null, true)).isNull()
  }
}
