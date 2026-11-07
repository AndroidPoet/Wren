/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.UserAgents
import org.junit.Test

class UserAgentsTest {

  private val deviceUa = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_arm64 " +
    "Build/BE2A.250530.026.F3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
    "Chrome/133.0.6943.137 Mobile Safari/537.36"

  @Test
  fun test_chromeMajor_prefersThePackageVersion() {
    assertThat(UserAgents.chromeMajor("141.0.7390.42", deviceUa)).isEqualTo(141)
  }

  @Test
  fun test_chromeMajor_fallsBackToTheUserAgent() {
    assertThat(UserAgents.chromeMajor(null, deviceUa)).isEqualTo(133)
    assertThat(UserAgents.chromeMajor("", deviceUa)).isEqualTo(133)
  }

  @Test
  fun test_chromeMajor_returnsNullWhenNothingNamesOne() {
    assertThat(UserAgents.chromeMajor(null, "Mozilla/5.0 (X11)")).isNull()
    assertThat(UserAgents.chromeMajor("beta", null)).isNull()
  }

  @Test
  fun test_mobile_namesNoDeviceOrWebView() {
    val ua = UserAgents.mobile(133)

    assertThat(ua).isEqualTo(
      "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/133.0.0.0 Mobile Safari/537.36",
    )
    assertThat(ua).doesNotContain("wv")
    assertThat(ua).doesNotContain("Version/4.0")
    assertThat(ua).doesNotContain("Build/")
  }

  @Test
  fun test_desktop_carriesTheSameEngineVersion() {
    assertThat(UserAgents.desktop(133)).contains("Chrome/133.0.0.0 Safari/537.36")
    assertThat(UserAgents.desktop(133)).doesNotContain("Mobile")
  }

  @Test
  fun test_brandList_followsChromiumsGreaseAlgorithm() {
    // major 133: chars[133 % 11 = 1] = "(" and chars[2] = ":", version "99", order {0,2,1}.
    val brands = UserAgents.brandList(133, "133.0.6943.137")
    assertThat(brands.map { it.name })
      .containsExactly("Not(A:Brand", "Google Chrome", "Chromium").inOrder()
    assertThat(brands[0].major).isEqualTo("99")
    assertThat(brands[1].full).isEqualTo("133.0.6943.137")
    // major 134: ":" and "-", version "24", order {1,0,2}.
    val next = UserAgents.brandList(134, "134.0.0.0")
    assertThat(next.map { it.name })
      .containsExactly("Chromium", "Not:A-Brand", "Google Chrome").inOrder()
    assertThat(next[1].major).isEqualTo("24")
  }

  @Test
  fun test_fullVersion_usesTheEngineVersionOnlyForTheSameMajor() {
    assertThat(UserAgents.fullVersion(133, "133.0.6943.137")).isEqualTo("133.0.6943.137")
    assertThat(UserAgents.fullVersion(133, "141.0.7390.42")).isEqualTo("133.0.0.0")
    assertThat(UserAgents.fullVersion(133, null)).isEqualTo("133.0.0.0")
    assertThat(UserAgents.fullVersion(133, "beta")).isEqualTo("133.0.0.0")
  }
}
