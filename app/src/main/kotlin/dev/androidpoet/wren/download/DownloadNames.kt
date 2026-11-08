/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.webkit.URLUtil
import java.net.URLDecoder

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
  private val INVISIBLE =
    Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
  private val SEPARATORS = Regex("[/\\\\:]")
  private val EXTENDED_FILENAME = Regex("filename\\*\\s*=\\s*[Uu][Tt][Ff]-8''([^;]+)")
  private val PLAIN_FILENAME =
    Regex("filename\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^;]+))")

  /**
   * The name a download is saved under. A `filename` the server states is kept as it is;
   * [URLUtil.guessFileName] would add an extension from the type and turn `SHA256SUMS` into
   * `SHA256SUMS.bin`. Without one, the address and type are guessed from as the system does.
   */
  fun choose(url: String, contentDisposition: String?, mimeType: String?): String {
    val stated = statedFileName(contentDisposition)?.let(::sanitize)
    if (stated != null && stated != FALLBACK) return stated
    val type = mimeType?.substringBefore(';')?.trim()?.ifEmpty { null }
    return sanitize(URLUtil.guessFileName(url, contentDisposition, type))
  }

  /** The `filename*=UTF-8''…` form wins over `filename="…"`, as RFC 6266 says it should. */
  private fun statedFileName(disposition: String?): String? {
    if (disposition.isNullOrBlank()) return null
    EXTENDED_FILENAME.find(disposition)?.let { match ->
      val encoded = match.groupValues[1].trim().replace("+", "%2B")
      runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()?.let { return it }
    }
    val match = PLAIN_FILENAME.find(disposition) ?: return null
    val quoted = match.groupValues[1]
    val name = if (quoted.isNotEmpty()) quoted.replace("\\\"", "\"") else match.groupValues[2]
    return name.trim().ifEmpty { null }
  }

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
