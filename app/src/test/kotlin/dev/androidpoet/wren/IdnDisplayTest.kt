/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.IdnDisplay
import org.junit.Test

class IdnDisplayTest {

  @Test
  fun `ascii hosts pass through untouched`() {
    assertThat(IdnDisplay.forDisplay("example.com")).isEqualTo("example.com")
    assertThat(IdnDisplay.forDisplay("")).isEqualTo("")
  }

  @Test
  fun `single-script labels are shown in their letters`() {
    assertThat(IdnDisplay.forDisplay("xn--bcher-kva.de")).isEqualTo("bücher.de")
    assertThat(IdnDisplay.forDisplay("xn--fiqs8s")).isEqualTo("中国")
    assertThat(IdnDisplay.forDisplay("xn--wgbh1c")).isEqualTo("مصر")
    assertThat(IdnDisplay.forDisplay("xn--e1afmkfd.xn--p1ai")).isEqualTo("пример.рф")
    assertThat(IdnDisplay.forDisplay("xn--mgbh0fb.xn--kgbechtv")).isEqualTo("مثال.إختبار")
    assertThat(IdnDisplay.forDisplay("xn--p1b6ci4b4b3a")).isEqualTo("उदाहरण")
  }

  @Test
  fun `japanese may mix han and kana`() {
    assertThat(IdnDisplay.forDisplay("xn--r8jz45g.jp")).isEqualTo("例え.jp")
    assertThat(IdnDisplay.forDisplay("xn--eckwd4c7cu47r2wf.jp")).isEqualTo("ドメイン名例.jp")
  }

  @Test
  fun `a cyrillic label made of latin lookalikes stays punycode`() {
    // аррӏе.com
    assertThat(IdnDisplay.forDisplay("xn--80ak6aa92e.com")).isEqualTo("xn--80ak6aa92e.com")
    // The same letters under a Cyrillic top-level domain are not pretending to be Latin.
    assertThat(IdnDisplay.forDisplay("xn--80ak6aa92e.xn--p1ai")).isEqualTo("аррӏе.рф")
  }

  @Test
  fun `a label mixing scripts stays punycode`() {
    // аpple.com: one Cyrillic letter among Latin ones.
    assertThat(IdnDisplay.forDisplay("xn--pple-43d.com")).isEqualTo("xn--pple-43d.com")
  }

  @Test
  fun `symbols, lookalike singletons and invisible characters stay punycode`() {
    assertThat(IdnDisplay.forDisplay("xn--n3h.com")).isEqualTo("xn--n3h.com") // ☃
    assertThat(IdnDisplay.forDisplay("xn--paypa-r4a.com")).isEqualTo("xn--paypa-r4a.com") // paypaı
    assertThat(IdnDisplay.forDisplay("xn--oogle-qmc.com")).isEqualTo("xn--oogle-qmc.com") // ɡoogle
  }

  @Test
  fun `only the punycode labels are decoded`() {
    assertThat(IdnDisplay.forDisplay("mail.xn--bcher-kva.de")).isEqualTo("mail.bücher.de")
    assertThat(IdnDisplay.forDisplay("xn--invalid-.com")).isEqualTo("xn--invalid-.com")
  }
}
