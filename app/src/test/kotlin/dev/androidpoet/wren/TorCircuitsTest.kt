/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.tor.TorCircuits
import dev.androidpoet.wren.browser.tor.TorStreamEvent
import org.junit.Test

class TorCircuitsTest {

  private val guard = "\$A0B1C2D3E4F5A6B7C8D9E0F1A2B3C4D5E6F7A8B9~GuardOne"
  private val middle = "\$1111111111111111111111111111111111111111~Middle"
  private val exit = "\$2222222222222222222222222222222222222222~ExitNode"

  private val circuits = """
    7 BUILT $guard,$middle,$exit BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL TIME_CREATED=2026-11-12T06:00:00.000000
    9 EXTENDED $guard,$middle PURPOSE=GENERAL
    12 BUILT $guard,$middle,${"$"}3333333333333333333333333333333333333333~Other PURPOSE=GENERAL
  """.trimIndent()

  private val streams = """
    41 SUCCEEDED 7 www.example.com:443
    42 SUCCEEDED 12 cdn.example.net:443
    43 NEW 0 www.example.com:443
    44 SUCCEEDED 12 WWW.EXAMPLE.COM:443
  """.trimIndent()

  @Test
  fun `streams to the page host name its circuits, newest first`() {
    assertThat(
      TorCircuits.circuitIdsFor("www.example.com", streams),
    ).containsExactly("12", "7").inOrder()
  }

  @Test
  fun `a stream not yet attached and another host do not count`() {
    assertThat(TorCircuits.circuitIdsFor("example.com", streams)).isEmpty()
    assertThat(TorCircuits.circuitIdsFor("www.example.com", "")).isEmpty()
  }

  @Test
  fun `a connected stream reported by address is matched by the name it was opened for`() {
    val resolved = "51 SUCCEEDED 7 116.202.120.181:443\n52 SUCCEEDED 12 93.184.215.14:443"
    val names = mapOf("51" to "check.torproject.org")

    assertThat(
      TorCircuits.circuitIdsFor("check.torproject.org", resolved, names),
    ).containsExactly("7")
    assertThat(TorCircuits.circuitIdsFor("check.torproject.org", resolved)).isEmpty()
  }

  @Test
  fun `stream events read as id, status, circuit and host`() {
    val opened = "51 NEW 0 check.torproject.org:443 SOURCE_ADDR=127.0.0.1:40000 PURPOSE=USER"

    assertThat(TorCircuits.streamEvent(opened))
      .isEqualTo(TorStreamEvent("51", "NEW", "0", "check.torproject.org"))
    assertThat(TorCircuits.streamEvent("51 SUCCEEDED 7 116.202.120.181:443"))
      .isEqualTo(TorStreamEvent("51", "SUCCEEDED", "7", "116.202.120.181"))
    assertThat(TorCircuits.streamEvent("garbage")).isNull()
  }

  @Test
  fun `a built circuit reads as its relays in order, an unbuilt one as nothing`() {
    val path = TorCircuits.pathOf("7", circuits)

    assertThat(
      path?.map { it.nickname },
    ).containsExactly("GuardOne", "Middle", "ExitNode").inOrder()
    assertThat(path?.first()?.fingerprint).isEqualTo("A0B1C2D3E4F5A6B7C8D9E0F1A2B3C4D5E6F7A8B9")
    assertThat(TorCircuits.pathOf("9", circuits)).isNull()
    assertThat(TorCircuits.pathOf("1", circuits)).isNull()
  }

  @Test
  fun `the consensus r line gives the address and nothing else does`() {
    val ns = "r ExitNode IiIiIiIiIiIiIiIiIiIiIiIiIiI= abc 2026-11-12 05:00:00 192.0.2.7 9001 0\n" +
      "s Exit Fast Running Stable Valid"

    assertThat(TorCircuits.addressOf(ns)).isEqualTo("192.0.2.7")
    assertThat(TorCircuits.addressOf("")).isNull()
  }
}
