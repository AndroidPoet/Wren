/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * Keeps internet pages off the phone's own network. A page can make the engine fetch
 * `http://192.168.1.1/` or `http://127.0.0.1:8080/` and read the timing or the result: a port
 * scan of the phone and of everything behind its router, run from inside the firewall, which
 * real sites have done to their visitors. Chrome 142 asks before such a request and Brave
 * refuses localhost; Wren refuses all of it. Only what a page requests for itself is covered: a
 * navigation to a router page is the user's own, and a page already on the local network may
 * talk to it. The engine resolves names after this point, so a public hostname that resolves to a
 * private address is not seen here; literal addresses, `localhost` and mDNS `.local` names are.
 */
object LocalNetwork {

  /** True when [requestUrl] points at the local network and [pageUrl] does not. */
  fun isCrossing(pageUrl: String, requestUrl: String): Boolean {
    val target = hostOf(requestUrl) ?: return false
    if (!isLocal(target)) return false
    val page = hostOf(pageUrl) ?: return false
    return !isLocal(page)
  }

  /** Loopback, any-address, private, carrier-NAT, link-local and unique-local ranges; local names. */
  fun isLocal(host: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]")
    if (name.isEmpty()) return false
    if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
    return when {
      name.all { it.isDigit() || it == '.' } -> isLocalV4(name)
      ':' in name && name.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' } ->
        isLocalV6(name)
      else -> false
    }
  }

  /** The refusal: empty, uncacheable, and over before anything is sent. */
  fun refusal(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
  )

  // The engine hands over canonical URLs, so an IPv4 address is always four decimal octets.
  private fun isLocalV4(name: String): Boolean {
    val octets = name.split('.')
    if (octets.size != 4) return false
    val a = octets[0].toIntOrNull() ?: return false
    val b = octets[1].toIntOrNull() ?: return false
    if (octets.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return false
    return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
      (a == 192 && b == 168) || (a == 100 && b in 64..127)
  }

  // Only ever called with a literal, which the resolver answers without a lookup.
  private fun isLocalV6(name: String): Boolean {
    val address = runCatching { InetAddress.getByName(name) }.getOrNull() ?: return false
    // ::ffff:a.b.c.d comes back as the IPv4 address it wraps.
    if (address is Inet4Address) return isLocalV4(address.hostAddress.orEmpty())
    if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress
    ) {
      return true
    }
    return (address.address[0].toInt() and 0xfe) == 0xfc
  }

  // HostMatcher stops at the first colon, which an IPv6 literal has inside; those go the slow way.
  private fun hostOf(url: String): String? {
    val quick = HostMatcher.hostOf(url) ?: return null
    if (!quick.startsWith('[')) return quick
    return runCatching { URI(url).host }.getOrNull()
  }
}
