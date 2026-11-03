/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * OkHttp captures the process default selector when a client is built. Consulting it per request
 * instead lets the browser swap the route for the whole process - onto Tor and back - without
 * rebuilding a client that lives for the life of the app.
 */
object DeferredProxySelector : ProxySelector() {

  override fun select(uri: URI?): List<Proxy> = current()?.select(uri) ?: listOf(Proxy.NO_PROXY)

  override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
    current()?.connectFailed(uri, sa, ioe)
  }

  private fun current(): ProxySelector? = getDefault()?.takeUnless { it === this }
}
