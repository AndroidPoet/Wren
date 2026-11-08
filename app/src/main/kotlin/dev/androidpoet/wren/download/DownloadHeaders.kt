/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.net.Uri

/** Headers a download carries on the page's behalf. */
object DownloadHeaders {

  /**
   * The referer for a download started from [pageUrl]. With trimming on, the page's origin
   * alone, the same `strict-origin` policy its own requests follow: a download host should not
   * learn the full address, with its path and query, of the page that linked to it.
   */
  fun referer(pageUrl: String?, trim: Boolean, targetUrl: String? = null): String? {
    if (pageUrl.isNullOrBlank()) return null
    if (!pageUrl.startsWith("http://", ignoreCase = true) &&
      !pageUrl.startsWith("https://", ignoreCase = true)
    ) {
      return null
    }
    // strict-origin sends nothing at all on a downgrade: an https page's address must not
    // travel in the clear to an http download host.
    if (trim && targetUrl != null &&
      pageUrl.startsWith("https://", ignoreCase = true) &&
      targetUrl.startsWith("http://", ignoreCase = true)
    ) {
      return null
    }
    if (!trim) return pageUrl
    val uri = Uri.parse(pageUrl)
    val host = uri.host ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "${uri.scheme}://$host$port/"
  }
}
