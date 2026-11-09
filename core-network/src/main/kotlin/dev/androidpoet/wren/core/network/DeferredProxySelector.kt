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

  private val clients = java.util.concurrent.CopyOnWriteArrayList<okhttp3.OkHttpClient>()

  /** Clients whose open connections must not outlive a change of route. */
  fun track(client: okhttp3.OkHttpClient) {
    clients += client
  }

  /**
   * The process route changed (Tor turned on or off). OkHttp reuses a pooled connection for
   * the same host without asking the selector again, so a socket opened direct before Tor
   * came up would carry the next request too: every pooled connection goes, and so does
   * every call in flight.
   */
  fun routeChanged() {
    for (client in clients) client.dispatcher.cancelAll()
    // Closing a socket is network I/O to Android, which throws for it on the main thread; the
    // pool is emptied on its own thread, and the route guard refuses any connection that a
    // request might reuse before that finishes.
    evictor.execute { for (client in clients) client.connectionPool.evictAll() }
  }

  private val evictor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "route-evict").apply { isDaemon = true }
  }
}
