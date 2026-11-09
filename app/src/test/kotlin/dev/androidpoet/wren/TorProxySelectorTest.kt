/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.tor.TorProxySelector
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class TorProxySelectorTest {
  @Test
  fun `every address goes to the http tunnel on IPv4 loopback`() {
    val selector = TorProxySelector(9150)

    val proxies = selector.select(URI("https://example.com/"))

    assertThat(proxies).hasSize(1)
    assertThat(proxies[0].type()).isEqualTo(Proxy.Type.HTTP)
    val address = proxies[0].address() as InetSocketAddress
    assertThat(address.address.hostAddress).isEqualTo("127.0.0.1")
    assertThat(address.port).isEqualTo(9150)
    assertThat(selector.select(URI("http://10.0.0.1/"))).isEqualTo(proxies)
  }
}
