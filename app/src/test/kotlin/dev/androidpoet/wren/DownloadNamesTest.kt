/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.download.DownloadNames
import org.junit.Test

class DownloadNamesTest {

  @Test
  fun `ordinary name is unchanged`() {
    assertThat(DownloadNames.sanitize("report 2026.pdf")).isEqualTo("report 2026.pdf")
  }

  @Test
  fun `right to left override and zero width marks are dropped`() {
    assertThat(DownloadNames.sanitize("invoice\u202efdp.exe")).isEqualTo("invoicefdp.exe")
    assertThat(DownloadNames.sanitize("a\u200bb\u0000c.txt")).isEqualTo("abc.txt")
  }

  @Test
  fun `path separators and leading dots are neutralised`() {
    assertThat(DownloadNames.sanitize("../../etc/passwd")).isEqualTo("_.._etc_passwd")
    assertThat(DownloadNames.sanitize(".hidden")).isEqualTo("hidden")
  }

  @Test
  fun `empty name falls back`() {
    assertThat(DownloadNames.sanitize("\u202e\u202e")).isEqualTo("download")
    assertThat(DownloadNames.sanitize(null)).isEqualTo("download")
  }

  @Test
  fun `long name is capped but keeps its extension`() {
    val name = DownloadNames.sanitize("x".repeat(500) + ".tar.gz")

    assertThat(name).hasLength(200)
    assertThat(name).endsWith(".gz")
  }
}
