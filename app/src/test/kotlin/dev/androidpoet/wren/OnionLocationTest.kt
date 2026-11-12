/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.tor.OnionLocation
import org.junit.Test

class OnionLocationTest {

  private val v3 = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"

  @Test
  fun `a v3 onion address over http or https is offered as sent`() {
    assertThat(OnionLocation.validOnion("https://$v3.onion/")).isEqualTo("https://$v3.onion/")
    assertThat(
      OnionLocation.validOnion(" http://www.$v3.onion/path?q=1 "),
    ).isEqualTo("http://www.$v3.onion/path?q=1")
  }

  @Test
  fun `anything that is not a v3 onion web address is refused`() {
    assertThat(OnionLocation.validOnion("https://example.com/")).isNull()
    assertThat(OnionLocation.validOnion("https://expyuzz4wqqyqhjn.onion/")).isNull()
    assertThat(OnionLocation.validOnion("javascript:alert(1)//$v3.onion")).isNull()
    assertThat(OnionLocation.validOnion("ftp://$v3.onion/")).isNull()
    assertThat(
      OnionLocation.validOnion("https://${v3.uppercase().replace('2', '1')}.onion/"),
    ).isNull()
    assertThat(OnionLocation.validOnion("not a url")).isNull()
  }
}
