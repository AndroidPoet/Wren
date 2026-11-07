/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.FileChooserUris
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileChooserUrisTest {

  private val own = "dev.androidpoet.wren"

  private fun intentWith(vararg uris: String): Intent = Intent().apply {
    data = Uri.parse(uris.first())
    if (uris.size > 1) {
      clipData = ClipData.newRawUri("files", Uri.parse(uris[1])).also { clip ->
        uris.drop(2).forEach { clip.addItem(ClipData.Item(Uri.parse(it))) }
      }
    }
  }

  @Test
  fun `a document from another provider is accepted`() {
    val document = "content://com.android.providers.media.documents/document/image%3A12"

    val uris = FileChooserUris.accept(intentWith(document), own)

    assertThat(uris?.map { it.toString() }).containsExactly(document)
  }

  @Test
  fun `a file path is refused`() {
    val cookies = "file:///data/data/$own/app_webview/Default/Cookies"

    assertThat(FileChooserUris.accept(intentWith(cookies), own)).isNull()
  }

  @Test
  fun `this app's own authorities are refused`() {
    assertThat(FileChooserUris.accept(intentWith("content://$own/cookies"), own)).isNull()
    assertThat(FileChooserUris.accept(intentWith("content://$own.androidx-startup/x"), own))
      .isNull()
  }

  @Test
  fun `a multiple selection keeps only the safe entries`() {
    val uris = FileChooserUris.accept(
      intentWith("content://a.provider/1", "content://b.provider/2", "file:///etc/hosts"),
      own,
    )

    assertThat(uris?.map { it.toString() })
      .containsExactly("content://a.provider/1", "content://b.provider/2")
  }

  @Test
  fun `no answer stays no answer`() {
    assertThat(FileChooserUris.accept(null, own)).isNull()
  }
}
