/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import java.net.URI

/**
 * Where a filter list may be fetched from. The list a user typed is their own choice, but a
 * redirect is the server's, so every hop must stay on https and off the phone's own networks:
 * a public list URL must not be able to point the fetch at a router page or a LAN service.
 */
object ListUrlPolicy {

  const val MAX_REDIRECTS = 5

  val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

  fun acceptsRedirect(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return !isLocal(host)
  }

  /** Loopback, link-local and private ranges, by name and by literal address. */
  fun isLocal(host: String): Boolean {
    val h = host.trim('[', ']').lowercase()
    if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "0.0.0.0") return true
    if (h == "::1" || h == "::" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) {
      return h.contains(':')
    }
    val octets = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
    if (octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 10 || a == 127 || a == 0 ||
      (a == 172 && b in 16..31) ||
      (a == 192 && b == 168) ||
      (a == 169 && b == 254) ||
      (a == 100 && b in 64..127)
  }
}
