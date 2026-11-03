/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.print.PrintManager
import android.provider.MediaStore
import android.webkit.WebView
import androidx.core.content.getSystemService
import timber.log.Timber
import java.io.File

/**
 * Turns the current page into something the user keeps: a printout or PDF through the system
 * print pipeline, or a self-contained web archive on disk that reopens without a network.
 */
object PageSaver {

  /** Hands the page to the system print UI, which itself offers "Save as PDF". */
  fun print(context: Context, webView: WebView, documentName: String) {
    val printManager = context.getSystemService<PrintManager>() ?: return
    val adapter = webView.createPrintDocumentAdapter(documentName)
    runCatching { printManager.print(documentName, adapter, null) }
      .onFailure { Timber.w(it, "print failed") }
  }

  /**
   * Saves the page as an MHTML archive in the public Downloads folder. WebView can only write an
   * archive to a plain file path, so on scoped-storage devices it is written to the cache first
   * and then published into MediaStore.
   */
  fun saveArchive(context: Context, webView: WebView, fileName: String, onDone: (Boolean) -> Unit) {
    val safeName = fileName.ifBlank { "page" }.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".mht"

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      val target = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        safeName,
      )
      webView.saveWebArchive(target.absolutePath, false) { onDone(it != null) }
      return
    }

    val cacheFile = File(context.cacheDir, safeName)
    webView.saveWebArchive(cacheFile.absolutePath, false) { result ->
      onDone(result != null && publish(context, cacheFile, safeName))
      cacheFile.delete()
    }
  }

  private fun publish(context: Context, source: File, displayName: String): Boolean = runCatching {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, displayName)
      put(MediaStore.Downloads.MIME_TYPE, "multipart/related")
      put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return false
    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
    resolver.update(
      uri,
      ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
      null,
      null,
    )
    true
  }.onFailure { Timber.w(it, "could not publish archive") }.getOrDefault(false)
}
