/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.webkit.URLUtil

/**
 * Whether a download is an Android app. An app file installs code rather than opening as
 * content, so the browser asks before fetching one, however the download was started.
 */
object AndroidAppFile {

  const val MIME_TYPE = "application/vnd.android.package-archive"

  fun matches(url: String, contentDisposition: String?, mimeType: String?): Boolean =
    mimeType?.substringBefore(';')?.trim().equals(MIME_TYPE, ignoreCase = true) ||
      URLUtil.guessFileName(url, contentDisposition, mimeType).endsWith(".apk", ignoreCase = true)
}
