/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/**
 * What a file picker handed back, filtered before it reaches the page. The system warns that
 * the host, not WebView, must make sure a picker cannot answer with the browser's own files:
 * a `file:` path or a `content:` authority of this very app would be readable by this process.
 */
object FileChooserUris {

  fun accept(data: Intent?, ownPackage: String): Array<Uri>? {
    if (data == null) return null
    val picked = buildList {
      data.data?.let(::add)
      data.clipData?.let { clip -> addAll(clip.uris()) }
    }
    val safe = picked.filter { uri -> isSafe(uri, ownPackage) }
    return safe.takeIf { it.isNotEmpty() }?.toTypedArray()
  }

  fun isSafe(uri: Uri, ownPackage: String): Boolean {
    if (!uri.scheme.equals("content", ignoreCase = true)) return false
    val authority = uri.authority?.lowercase() ?: return false
    return authority != ownPackage.lowercase() && !authority.startsWith(
      ownPackage.lowercase() + ".",
    )
  }

  private fun ClipData.uris(): List<Uri> = (0 until itemCount).mapNotNull { getItemAt(it).uri }
}
