/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.SpeculativeLoader
import dev.androidpoet.wren.settings.SettingsStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * The DNS warm-up resolves through the system resolver, outside any proxy WebView is given, so
 * under Tor it would hand every guessed hostname to the network in the clear. These pin the gate.
 */
@RunWith(RobolectricTestRunner::class)
class SpeculativeLoaderTest {

  private val resolved = mutableListOf<String>()
  private val settings = SettingsStore(
    ApplicationProvider.getApplicationContext<Context>()
      .getSharedPreferences("speculative-test", Context.MODE_PRIVATE),
  )
  private val loader = SpeculativeLoader(settings, Executor { it.run() }, resolved::add)

  @Test
  fun `tor mode sends no dns lookup for a warmed origin`() {
    settings.torMode = true

    loader.warmOrigin("https://example.org/path")

    assertThat(resolved).isEmpty()
  }

  @Test
  fun `tor mode sends no dns lookup for a speculated page`() {
    settings.torMode = true

    loader.speculate("https://example.org/page", isPrivate = false)

    assertThat(resolved).isEmpty()
  }

  @Test
  fun `preload off sends no dns lookup`() {
    settings.preloadPages = false

    loader.warmOrigin("https://example.org/")

    assertThat(resolved).isEmpty()
  }

  @Test
  fun `clearnet warms the host it is given`() {
    loader.warmOrigin("https://example.org/path")

    assertThat(resolved).containsExactly("example.org")
  }
}
