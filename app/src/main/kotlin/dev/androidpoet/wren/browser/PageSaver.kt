/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.Context
import android.print.PrintManager
import android.webkit.WebView
import androidx.core.content.getSystemService
import dev.androidpoet.wren.download.DownloadsFolder
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
   * archive to a plain file path, and is refused in Downloads itself, so the archive goes to the
   * cache first and [DownloadsFolder] publishes it.
   */
  fun saveArchive(context: Context, webView: WebView, fileName: String, onDone: (Boolean) -> Unit) {
    val safeName = fileName.ifBlank { "page" }.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".mht"
    val cacheFile = File(context.cacheDir, safeName)
    webView.saveWebArchive(cacheFile.absolutePath, false) { result ->
      val published = result != null &&
        DownloadsFolder.publish(context, cacheFile, safeName, "multipart/related") != null
      cacheFile.delete()
      onDone(published)
    }
  }
}
