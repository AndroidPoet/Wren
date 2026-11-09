/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.network

import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Refuses, before a single packet leaves, any connection that would not go through the Tor
 * tunnel while Tor mode is on. OkHttp only connects where the proxy selector points, but the
 * selector is process-global state that any code can replace; this check reads the route as
 * it is about to be used, so a wrong selector costs a failed request instead of a leak.
 *
 * Android's own HttpURLConnection is never used for the same reason in reverse: its bundled
 * OkHttp 2 appends a direct connection after every proxy the selector names, so a slow Tor
 * circuit became a plain connection from the real address.
 */
class TorRouteGuard(private val torMode: () -> Boolean) : EventListener() {
  override fun dnsStart(call: Call, domainName: String) {
    if (torMode() && !isLoopbackLiteral(domainName)) {
      throw IOException("Tor mode: refusing to resolve $domainName locally")
    }
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
    if (!allows(proxy, inetSocketAddress)) {
      throw IOException("Tor mode: refusing a connection that is not through Tor")
    }
  }

  /** True when the connection may be made: Tor mode is off, or it goes to the local tunnel. */
  fun allows(proxy: Proxy, target: InetSocketAddress): Boolean {
    if (!torMode()) return true
    val address = target.address ?: return false
    return proxy.type() == Proxy.Type.HTTP && address.isLoopbackAddress
  }

  private fun isLoopbackLiteral(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}
