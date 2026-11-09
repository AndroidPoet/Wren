/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.tor.BridgeLine
import dev.androidpoet.wren.browser.tor.Bridges
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class BridgesTest {

  private val builtIn = File("src/main/assets/bridges.txt").readText()
  private val fp = "D9A82D2F9C2F65A18407B1D2B764F130847F8B5D"

  @Test
  fun `every built-in line parses and renders back unchanged`() {
    for (section in listOf("obfs4", "snowflake", "meek")) {
      val lines = Bridges.sectionOf(builtIn, section).lines().filter { it.isNotBlank() }
      assertThat(lines).isNotEmpty()
      for (line in lines) {
        assertThat(Bridges.parse(line).render()).isEqualTo(line)
      }
    }
  }

  @Test
  fun `obfs4 line keeps transport, address, fingerprint and arguments`() {
    val parsed = Bridges.parse(
      "obfs4 37.218.245.14:38224 d9a82d2f9c2f65a18407b1d2b764f130847f8b5d " +
        "cert=bjRa+/Mz== iat-mode=0",
    )
    assertThat(parsed).isEqualTo(
      BridgeLine(
        transport = "obfs4",
        address = "37.218.245.14:38224",
        fingerprint = "D9A82D2F9C2F65A18407B1D2B764F130847F8B5D",
        arguments = listOf("cert" to "bjRa+/Mz==", "iat-mode" to "0"),
      ),
    )
  }

  @Test
  fun `accepts the Bridge keyword, a dollar fingerprint and an ipv6 address`() {
    val parsed = Bridges.parse(
      "Bridge obfs4 [2001:db8::1]:443 \$D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
        "cert=abc iat-mode=1",
    )
    assertThat(parsed.address).isEqualTo("[2001:db8::1]:443")
    assertThat(parsed.fingerprint).isEqualTo("D9A82D2F9C2F65A18407B1D2B764F130847F8B5D")
    assertThat(parsed.render()).doesNotContain("Bridge ")
    assertThat(parsed.render()).doesNotContain("$")
  }

  @Test
  fun `a plain bridge needs a fingerprint and takes no arguments`() {
    assertThat(Bridges.parse("1.2.3.4:443 $fp").transport).isNull()
    assertThrows(IllegalArgumentException::class.java) { Bridges.parse("1.2.3.4:443") }
    assertThrows(IllegalArgumentException::class.java) {
      Bridges.parse("1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x")
    }
  }

  @Test
  fun `nothing pasted can add a second torrc option`() {
    val attempts = listOf(
      "obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x\nUseBridges 0",
      "obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x\rLog debug file /sdcard/t",
      "obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=\"x y\"",
      "obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x SocksPort",
      "obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x\tfoo",
      "obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D",
    )
    for (attempt in attempts) {
      val error = assertThrows(IllegalArgumentException::class.java) { Bridges.parse(attempt) }
      assertThat(error.message).isNotEmpty()
    }
  }

  @Test
  fun `retired and unknown transports are refused`() {
    for (transport in listOf("obfs3", "scramblesuit", "fte", "OBFS4")) {
      assertThrows(IllegalArgumentException::class.java) {
        Bridges.parse("$transport 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D")
      }
    }
  }

  @Test
  fun `bad addresses and ports are refused`() {
    val addresses =
      listOf(
        "1.2.3.256:443",
        "1.2.3.4:0",
        "1.2.3.4:70000",
        "1.2.3.4",
        "a..b:443",
        "[::1]443",
        "-x.com:443",
      )
    for (address in addresses) {
      assertThrows(IllegalArgumentException::class.java) {
        Bridges.parse("obfs4 $address D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x")
      }
    }
  }

  @Test
  fun `parseAll skips comments and blank lines and names the bad line`() {
    val lines = Bridges.parseAll(
      """
      # from the Telegram bot

      obfs4 1.2.3.4:443 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=x iat-mode=0
      snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://a.example/ fronts=b.example
      """.trimIndent(),
    )
    assertThat(lines).hasSize(2)
    val error = assertThrows(IllegalArgumentException::class.java) {
      Bridges.parseAll("obfs4 1.2.3.4:443 $fp cert=x\nnonsense")
    }
    assertThat(error.message).startsWith("line 2:")
    assertThrows(IllegalArgumentException::class.java) { Bridges.parseAll("# only a comment\n\n") }
  }

  @Test
  fun `torrc options turn bridges on, hold the network and list every line`() {
    val lines = Bridges.parseAll(Bridges.sectionOf(builtIn, "obfs4")) +
      Bridges.parseAll(Bridges.sectionOf(builtIn, "snowflake"))
    val options = Bridges.torrcOptions(lines)
    assertThat(options.lines().first()).isEqualTo("UseBridges 1")
    assertThat(options.lines()).contains("DisableNetwork 1")
    assertThat(options).doesNotContain("ClientTransportPlugin")
    assertThat(options.lines().count { it.startsWith("Bridge obfs4 ") }).isEqualTo(7)
    assertThat(options.lines().count { it.startsWith("Bridge snowflake ") }).isEqualTo(2)
    assertThat(Bridges.torrcOptions(emptyList())).isEmpty()
  }

  @Test
  fun `the transport plugin names every transport once and the binary from the installer`() {
    val lines = Bridges.parseAll(Bridges.sectionOf(builtIn, "obfs4")) +
      Bridges.parseAll(Bridges.sectionOf(builtIn, "snowflake"))
    assertThat(Bridges.transportPlugin(lines, "/data/app/x/lib/arm64/liblyrebird.so"))
      .isEqualTo("obfs4,snowflake exec /data/app/x/lib/arm64/liblyrebird.so")
  }

  @Test
  fun `a plain bridge list needs no transport plugin and keeps the network on`() {
    val lines = Bridges.parseAll("1.2.3.4:443 $fp")
    assertThat(Bridges.transportPlugin(lines, "/x")).isNull()
    val options = Bridges.torrcOptions(lines)
    assertThat(options).doesNotContain("DisableNetwork")
    assertThat(options).contains("Bridge 1.2.3.4:443 $fp")
  }
}
