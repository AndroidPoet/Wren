/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.download.AndroidAppFile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A long-pressed link knows only its address; a tapped download also has the server's headers.
 * Both have to be caught, since either one ends with an app file in Downloads.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAppFileTest {

  @Test
  fun `an apk address is an app with no headers at all`() {
    assertThat(AndroidAppFile.matches("https://example.com/get/app.apk", null, null)).isTrue()
    assertThat(AndroidAppFile.matches("https://example.com/get/APP.APK?v=2", null, null)).isTrue()
  }

  @Test
  fun `the server's type or filename marks an app behind a plain address`() {
    val url = "https://example.com/download?id=7"

    assertThat(AndroidAppFile.matches(url, null, "${AndroidAppFile.MIME_TYPE}; charset=binary"))
      .isTrue()
    assertThat(AndroidAppFile.matches(url, "attachment; filename=\"tool.apk\"", null)).isTrue()
  }

  @Test
  fun `ordinary files are not apps`() {
    assertThat(AndroidAppFile.matches("https://example.com/report.pdf", null, null)).isFalse()
    assertThat(AndroidAppFile.matches("https://example.com/photo.jpg", null, "image/jpeg"))
      .isFalse()
    assertThat(AndroidAppFile.matches("https://example.com/apk-guide.html", null, "text/html"))
      .isFalse()
  }

  @Test
  fun `a late file name can say app on its own`() {
    for (name in listOf("update.apk", "Update.APK", "bundle.apks", "pack.xapk", "app.aab")) {
      assertThat(AndroidAppFile.isAppName(name)).isTrue()
    }
    for (name in listOf("notes.pdf", "apk.txt", "archive.zip", "x.apk.png")) {
      assertThat(AndroidAppFile.isAppName(name)).isFalse()
    }
  }

  @Test
  fun `the saved name decides, however the server spelled it`() {
    assertThat(
      AndroidAppFile.matches(
        "https://x.example/get",
        "inline; filename=\"x.apk\"",
        "application/octet-stream",
      ),
    ).isTrue()
    assertThat(
      AndroidAppFile.matches(
        "https://x.example/get",
        "attachment; filename*=UTF-8''x.apk",
        "application/octet-stream",
      ),
    ).isTrue()
    assertThat(
      AndroidAppFile.matches(
        "https://x.example/get",
        "attachment; filename=\"x.apk\"",
        "text/plain",
      ),
    ).isTrue()
    assertThat(
      AndroidAppFile.matches(
        "https://x.example/get.apk",
        "attachment; filename=\"notes.txt\"",
        "text/plain",
      ),
    ).isFalse()
  }
}
