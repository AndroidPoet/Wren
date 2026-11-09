/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

/**
 * Whether a download is an Android app. An app file installs code rather than opening as
 * content, so the browser asks before fetching one, however the download was started.
 */
object AndroidAppFile {

  const val MIME_TYPE = "application/vnd.android.package-archive"

  fun matches(url: String, contentDisposition: String?, mimeType: String?): Boolean =
    mimeType?.substringBefore(';')?.trim().equals(MIME_TYPE, ignoreCase = true) ||
      // The very name the file will be saved under: `filename*=`, `inline; filename=` and a
      // mismatched type all end up in it, where URLUtil's guess would have missed them.
      isAppName(DownloadNames.choose(url, contentDisposition, mimeType))

  /**
   * Whether a file name is one Android, or a file manager, would offer to install. The name a
   * page supplies late - a `download="update.apk"` on a blob or data link - is checked here
   * before the file is published.
   */
  fun isAppName(fileName: String): Boolean =
    APP_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }

  private val APP_SUFFIXES = listOf(".apk", ".apks", ".xapk", ".apkm", ".aab")
}
