/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.tor.TorBootstrap
import dev.androidpoet.wren.browser.tor.TorProxySelector
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class TorProxyTest {

  @Test
  fun `every scheme goes through the local tunnel port`() {
    val selector = TorProxySelector(9151)
    for (uri in listOf("https://example.org/", "http://example.org/", "ws://example.org/")) {
      val proxies = selector.select(URI(uri))
      assertThat(proxies).hasSize(1)
      val proxy = proxies.single()
      assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP)
      val address = proxy.address() as InetSocketAddress
      assertThat(address.address.isLoopbackAddress).isTrue()
      assertThat(address.port).isEqualTo(9151)
    }
  }

  @Test
  fun `never offers a direct route`() {
    assertThat(TorProxySelector(9151).select(URI("https://example.org/")))
      .doesNotContain(Proxy.NO_PROXY)
  }

  @Test
  fun `reads the bootstrap percentage`() {
    val phase = "NOTICE BOOTSTRAP PROGRESS=45 TAG=requesting_descriptors " +
      "SUMMARY=\"Loading relay descriptors\""
    assertThat(TorBootstrap.progressOf(phase)).isEqualTo(45)
    assertThat(TorBootstrap.progressOf("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
      .isEqualTo(100)
  }

  @Test
  fun `tolerates a missing or malformed phase`() {
    assertThat(TorBootstrap.progressOf(null)).isNull()
    assertThat(TorBootstrap.progressOf("")).isNull()
    assertThat(TorBootstrap.progressOf("PROGRESS=abc")).isNull()
  }
}
