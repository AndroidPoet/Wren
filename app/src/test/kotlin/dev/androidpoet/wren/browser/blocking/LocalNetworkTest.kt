/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {

  @Test
  fun `loopback, private, link-local, carrier NAT and any-address ranges are local`() {
    listOf(
      "127.0.0.1", "127.8.8.8", "0.0.0.0", "10.0.0.5", "172.16.0.1", "172.31.255.254",
      "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255",
      "[::1]", "::1", "[::]", "[fe80::1]", "[fc00::1]", "[fd12:3456::1]", "[::ffff:192.168.0.1]",
      "localhost", "LOCALHOST", "dev.localhost", "printer.local",
    ).forEach { assertTrue(it, LocalNetwork.isLocal(it)) }
  }

  @Test
  fun `public addresses and names are not local`() {
    listOf(
      "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "100.63.0.1", "100.128.0.1",
      "[2606:4700::1111]", "[2001:db8::1]", "example.com", "local.example.com",
      "localhost.example.com",
      "mylocal", "",
    ).forEach { assertFalse(it, LocalNetwork.isLocal(it)) }
  }

  @Test
  fun `a public page reaching the local network is a crossing`() {
    assertTrue(LocalNetwork.isCrossing("https://shop.example/cart", "http://127.0.0.1:8080/probe"))
    assertTrue(LocalNetwork.isCrossing("https://shop.example/", "http://192.168.1.1/"))
    assertTrue(LocalNetwork.isCrossing("https://shop.example/", "http://[::1]:9000/"))
    assertTrue(LocalNetwork.isCrossing("https://shop.example/", "ws://localhost:3000/socket"))
  }

  @Test
  fun `local pages, public targets and pages without a host are left alone`() {
    assertFalse(LocalNetwork.isCrossing("http://192.168.1.1/admin", "http://192.168.1.1/api"))
    assertFalse(LocalNetwork.isCrossing("http://localhost:3000/", "http://127.0.0.1:8080/"))
    assertFalse(LocalNetwork.isCrossing("https://shop.example/", "https://cdn.example/a.js"))
    assertFalse(LocalNetwork.isCrossing("about:blank", "http://127.0.0.1/"))
    assertFalse(LocalNetwork.isCrossing("", "http://127.0.0.1/"))
  }
}
