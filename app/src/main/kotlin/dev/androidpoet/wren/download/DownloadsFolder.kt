/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import timber.log.Timber
import java.io.File

/**
 * Copies a finished file the app wrote itself (a blob a page built, a saved web archive) into the
 * shared Downloads folder, and returns a content:// address other apps may open.
 *
 * Writing straight into Downloads is not an option on any version: Android 10 and later allow it
 * only through MediaStore, and on Android 9 even WebView's own archive writer is refused there. So
 * the file is always written privately first and published here.
 */
object DownloadsFolder {

  fun publish(context: Context, source: File, displayName: String, mimeType: String): String? =
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        publishToMediaStore(context, source, displayName, mimeType)
      } else {
        publishToFolder(context, source, displayName, mimeType)
      }
    }.onFailure { Timber.w(it, "could not publish %s", displayName) }.getOrNull()

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun publishToMediaStore(
    context: Context,
    source: File,
    displayName: String,
    mimeType: String,
  ): String? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, displayName)
      put(MediaStore.Downloads.MIME_TYPE, mimeType)
      put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return null
    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
    resolver.update(
      uri,
      ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
      null,
      null,
    )
    return uri.toString()
  }

  /**
   * Android 9 and older: a plain file in the public folder, which needs the storage grant the
   * browser asks for before any save starts, then registered with the system download list so it
   * shows up there and gets an address other apps can open.
   */
  @Suppress("DEPRECATION")
  private fun publishToFolder(
    context: Context,
    source: File,
    displayName: String,
    mimeType: String,
  ): String? {
    val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    folder.mkdirs()
    val target = uniqueFile(folder, displayName)
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    val manager = context.getSystemService<DownloadManager>() ?: return null
    val id = manager.addCompletedDownload(
      target.name,
      target.name,
      true,
      mimeType,
      target.absolutePath,
      target.length(),
      true,
    )
    return manager.getUriForDownloadedFile(id)?.toString()
  }

  /** "page.mht", then "page-1.mht", and so on: a second save must not overwrite the first. */
  private fun uniqueFile(folder: File, displayName: String): File {
    val base = displayName.substringBeforeLast('.')
    val extension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var candidate = File(folder, displayName)
    var n = 1
    while (candidate.exists()) candidate = File(folder, "$base-${n++}$extension")
    return candidate
  }
}
