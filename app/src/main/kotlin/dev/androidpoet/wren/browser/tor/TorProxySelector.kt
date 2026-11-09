/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import dev.androidpoet.wren.settings.SettingsStore
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Sends every connection the app itself opens - filter lists, favicons, suggestions, the native
 * player - through Tor's local HTTP CONNECT port. There is deliberately no direct fallback: a
 * request that cannot go through Tor must not go at all.
 */
class TorProxySelector(port: Int) : ProxySelector() {

  private val proxies = listOf(
    Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, port)),
  )

  override fun select(uri: URI?): List<Proxy> = proxies

  override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

  companion object {
    /**
     * Tor listens on 127.0.0.1. Android's `InetAddress.getLoopbackAddress()` is `::1`, where
     * nothing listens: a client that then gave up on the proxy, as the platform's
     * HttpURLConnection does, went on to connect directly.
     */
    val LOOPBACK: InetAddress = InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1))

    /**
     * Whether a connection opened right now would leave from the real address: Tor mode is
     * on, but the tunnel is not yet the process proxy. That window is Tor's own start-up,
     * while its port is being chosen; callers skip the request and try again next time.
     */
    fun wouldLeakFor(settings: SettingsStore): Boolean =
      settings.torMode && ProxySelector.getDefault() !is TorProxySelector
  }
}

/** Reads Tor's `status/bootstrap-phase` reply, which carries a `PROGRESS=n` field. */
object TorBootstrap {
  private val progress = Regex("""PROGRESS=(\d{1,3})""")

  fun progressOf(phase: String?): Int? =
    phase?.let(progress::find)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
}
