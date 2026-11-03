/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

import dev.androidpoet.wren.browser.blocking.HostMatcher

/**
 * Rewrites `http://` to `https://` before the request leaves the device.
 *
 * An http request is readable and rewritable by anything between the phone and the server, so
 * upgrading first and falling back only on failure is strictly better than the reverse. Hosts
 * that have no chance of serving TLS are left alone rather than being sent on a doomed round
 * trip: a development server on the LAN, or a `.onion` address that is already encrypted by the
 * transport it rides on.
 */
object HttpsUpgrader {

  private val LOOPBACK = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]")
  private val NEVER_UPGRADED_SUFFIXES = setOf(".onion", ".local", ".localhost", ".test", ".invalid")

  /** The https form of [url], or null when it should be left as it is. */
  fun upgrade(url: String): String? {
    if (!url.startsWith("http://", ignoreCase = true)) return null
    val host = HostMatcher.hostOf(url) ?: return null
    if (!isUpgradable(host)) return null
    return "https://" + url.substring("http://".length)
  }

  private fun isUpgradable(host: String): Boolean {
    if (host in LOOPBACK) return false
    if (NEVER_UPGRADED_SUFFIXES.any { host.endsWith(it) }) return false
    if (isPrivateAddress(host)) return false
    return true
  }

  /**
   * Addresses on the local network, which almost never have a certificate anyone would accept.
   */
  private fun isPrivateAddress(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size != 4 || octets.any { part -> part.toIntOrNull() == null }) return false
    val first = octets[0].toInt()
    val second = octets[1].toInt()
    return when {
      first == 10 -> true
      first == 192 && second == 168 -> true
      first == 172 && second in 16..31 -> true
      first == 169 && second == 254 -> true
      else -> false
    }
  }
}
