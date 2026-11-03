/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.blocking.CompactHostSet
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class CompactHostSetTest {

  private val set =
    CompactHostSet.of(listOf("doubleclick.net", "cdn.taboola.com", "example.co.uk"))

  @Test
  fun `a host and its subdomains match`() {
    assertThat(set.matchesSuffixOf("doubleclick.net")).isTrue()
    assertThat(set.matchesSuffixOf("stats.g.doubleclick.net")).isTrue()
    assertThat(set.matchesSuffixOf("cdn.taboola.com")).isTrue()
  }

  @Test
  fun `matching stays on label boundaries`() {
    assertThat(set.matchesSuffixOf("notdoubleclick.net")).isFalse()
    assertThat(set.matchesSuffixOf("doubleclick.net.evil.com")).isFalse()
    assertThat(set.matchesSuffixOf("taboola.com")).isFalse()
    assertThat(set.matchesSuffixOf("net")).isFalse()
    assertThat(set.matchesSuffixOf("")).isFalse()
  }

  @Test
  fun `the empty set matches nothing`() {
    assertThat(CompactHostSet.EMPTY.matchesSuffixOf("doubleclick.net")).isFalse()
    assertThat(CompactHostSet.EMPTY.size).isEqualTo(0)
  }

  @Test
  fun `duplicates collapse and a union holds both sides`() {
    val merged =
      CompactHostSet.union(set, CompactHostSet.of(listOf("doubleclick.net", "adnxs.com")))
    assertThat(merged.size).isEqualTo(4)
    assertThat(merged.matchesSuffixOf("ib.adnxs.com")).isTrue()
    assertThat(merged.matchesSuffixOf("example.co.uk")).isTrue()
  }

  @Test
  fun `a written set reads back identically`() {
    val bytes = ByteArrayOutputStream().also(set::write).toByteArray()
    val loaded = CompactHostSet.load(ByteArrayInputStream(bytes))
    assertThat(loaded.size).isEqualTo(3)
    assertThat(loaded.matchesSuffixOf("ad.doubleclick.net")).isTrue()
    assertThat(loaded.matchesSuffixOf("google.com")).isFalse()
  }

  @Test
  fun `garbage is rejected rather than trusted`() {
    val result = runCatching { CompactHostSet.load(ByteArrayInputStream("nope".toByteArray())) }
    assertThat(result.isFailure).isTrue()
  }

  @Test
  fun `the bundled file was compiled with the same hash`() {
    // Hashes of the shipped file come from tools/compile_hosts.py; the Kotlin side must agree.
    val file = File("src/main/assets/hosts.bin")
    val bundled = file.inputStream().buffered().use(CompactHostSet::load)
    assertThat(bundled.size).isGreaterThan(40_000)
    assertThat(bundled.matchesSuffixOf("securepubads.g.doubleclick.net")).isTrue()
    assertThat(bundled.matchesSuffixOf("cdn.taboola.com")).isTrue()
    assertThat(bundled.matchesSuffixOf("www.wikipedia.org")).isFalse()
    assertThat(bundled.matchesSuffixOf("fonts.gstatic.com")).isFalse()
  }
}
