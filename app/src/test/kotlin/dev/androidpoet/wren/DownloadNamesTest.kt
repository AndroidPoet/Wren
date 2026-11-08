/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.download.DownloadNames
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

  @Test
  fun `a name the server states is kept without a guessed extension`() {
    assertThat(
      DownloadNames.choose(
        "https://github.com/x/releases/download/v1/SHA256SUMS",
        "attachment; filename=SHA256SUMS",
        "application/octet-stream",
      ),
    ).isEqualTo("SHA256SUMS")
    assertThat(
      DownloadNames.choose(
        "https://a.com/dl?id=1",
        "attachment; filename=\"report final.pdf\"",
        null,
      ),
    ).isEqualTo("report final.pdf")
    assertThat(
      DownloadNames.choose(
        "https://a.com/dl",
        "attachment; filename=\"plain.txt\"; filename*=UTF-8''na%C3%AFve%20r%C3%A9sum%C3%A9.txt",
        "text/plain",
      ),
    ).isEqualTo("naïve résumé.txt")
  }

  @Test
  fun `without a stated name the address and type are guessed from`() {
    assertThat(DownloadNames.choose("https://a.com/files/photo.JPG", null, "image/jpeg"))
      .isEqualTo("photo.JPG")
    assertThat(DownloadNames.choose("https://a.com/files/sums", "attachment", "text/plain"))
      .isEqualTo("sums.txt")
    assertThat(
      DownloadNames.choose("https://a.com/x", "attachment; filename=\"../../etc/passwd\"", null),
    )
      .isEqualTo("_.._etc_passwd")
  }
}
