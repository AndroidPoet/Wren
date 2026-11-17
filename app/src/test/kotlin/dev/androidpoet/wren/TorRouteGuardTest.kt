/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.core.network.TorRouteGuard
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class TorRouteGuardTest {
  private val tunnel = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9050)
  private val outside =
    InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443)

  @Test
  fun `with Tor off every route is allowed`() {
    val guard = TorRouteGuard { false }

    assertThat(guard.allows(Proxy.NO_PROXY, outside)).isTrue()
    assertThat(guard.allows(Proxy(Proxy.Type.HTTP, tunnel), tunnel)).isTrue()
  }

  @Test
  fun `under Tor only the local http tunnel is allowed`() {
    val guard = TorRouteGuard { true }

    assertThat(guard.allows(Proxy(Proxy.Type.HTTP, tunnel), tunnel)).isTrue()
    assertThat(guard.allows(Proxy.NO_PROXY, outside)).isFalse()
    assertThat(guard.allows(Proxy(Proxy.Type.SOCKS, tunnel), outside)).isFalse()
    assertThat(guard.allows(Proxy(Proxy.Type.HTTP, outside), outside)).isFalse()
    assertThat(
      guard.allows(
        Proxy(Proxy.Type.HTTP, tunnel),
        InetSocketAddress.createUnresolved("x.example", 443),
      ),
    ).isFalse()
  }

  @Test
  fun test_allows_underTorLocalSocksWithUnresolvedTarget_isAllowed() {
    val guard = TorRouteGuard { true }
    val named = InetSocketAddress.createUnresolved("x.onion", 80)

    val allowed = guard.allows(Proxy(Proxy.Type.SOCKS, tunnel), named)

    assertThat(allowed).isTrue()
  }

  @Test
  fun test_allows_underTorSocksElsewhereOrResolvedTarget_isRefused() {
    val guard = TorRouteGuard { true }
    val named = InetSocketAddress.createUnresolved("x.onion", 80)

    val remoteProxy = guard.allows(Proxy(Proxy.Type.SOCKS, outside), named)
    val resolvedTarget = guard.allows(Proxy(Proxy.Type.SOCKS, tunnel), outside)

    assertThat(remoteProxy).isFalse()
    assertThat(resolvedTarget).isFalse()
  }
}
