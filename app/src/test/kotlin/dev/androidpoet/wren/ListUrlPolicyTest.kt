/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.androidpoet.wren.browser.blocking.ListUrlPolicy
import org.junit.Test

class ListUrlPolicyTest {

  @Test
  fun `public https redirect is accepted`() {
    assertThat(ListUrlPolicy.acceptsRedirect("https://cdn.example.org/list.txt")).isTrue()
  }

  @Test
  fun `http redirect is refused`() {
    assertThat(ListUrlPolicy.acceptsRedirect("http://cdn.example.org/list.txt")).isFalse()
  }

  @Test
  fun `redirects into the phone's own networks are refused`() {
    val urls = listOf(
      "https://localhost/x",
      "https://router.local/x",
      "https://127.0.0.1/x",
      "https://10.0.0.5/x",
      "https://172.16.3.1/x",
      "https://192.168.1.1/x",
      "https://169.254.169.254/x",
      "https://[::1]/x",
      "https://[fe80::1]/x",
      "https://[fd12::1]/x",
    )
    for (url in urls) {
      assertWithMessage(url).that(ListUrlPolicy.acceptsRedirect(url)).isFalse()
    }
  }

  @Test
  fun `public literal addresses are not local`() {
    assertThat(ListUrlPolicy.isLocal("8.8.8.8")).isFalse()
    assertThat(ListUrlPolicy.isLocal("172.32.0.1")).isFalse()
    assertThat(ListUrlPolicy.isLocal("[2606:4700::1111]")).isFalse()
  }
}
