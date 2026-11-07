/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

/**
 * The name a download is saved under comes from the page, through the URL or
 * `Content-Disposition`. Android keeps it inside the Downloads folder, so this is about what the
 * user reads, not where the file lands: a right-to-left override can make `exe` look like `pdf`,
 * and control characters garble the notification.
 */
object DownloadNames {

  private const val MAX_LENGTH = 200
  private const val MAX_EXTENSION = 16
  private const val FALLBACK = "download"

  /** Characters that steer or hide text: C0/C1 controls, zero-width and bidi marks, BOM. */
  private val INVISIBLE = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
  private val SEPARATORS = Regex("[/\\\\:]")

  fun sanitize(raw: String?): String {
    val cleaned = raw.orEmpty()
      .replace(INVISIBLE, "")
      .replace(SEPARATORS, "_")
      .trim()
      .trim('.', ' ')
    if (cleaned.isEmpty()) return FALLBACK
    if (cleaned.length <= MAX_LENGTH) return cleaned
    val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXTENSION }
    return if (extension == null) {
      cleaned.take(MAX_LENGTH)
    } else {
      cleaned.take(MAX_LENGTH - extension.length - 1).trimEnd('.', ' ') + "." + extension
    }
  }
}
