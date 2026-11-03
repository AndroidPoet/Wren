/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

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
    Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), port)),
  )

  override fun select(uri: URI?): List<Proxy> = proxies

  override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

/** Reads Tor's `status/bootstrap-phase` reply, which carries a `PROGRESS=n` field. */
object TorBootstrap {
  private val progress = Regex("""PROGRESS=(\d{1,3})""")

  fun progressOf(phase: String?): Int? =
    phase?.let(progress::find)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
}
