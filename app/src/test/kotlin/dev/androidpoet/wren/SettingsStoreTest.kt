/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.settings.SettingsStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The defaults are a promise the app makes on first launch, so they are pinned rather than left
 * to whoever edits the store next.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

  private fun store(): SettingsStore {
    val context: Context = ApplicationProvider.getApplicationContext()
    return SettingsStore(context.getSharedPreferences("test", Context.MODE_PRIVATE))
  }

  @Test
  fun `search defaults to duckduckgo`() {
    assertThat(store().searchEngine).isEqualTo(SearchEngine.DUCKDUCKGO)
  }

  @Test
  fun `privacy defaults are on`() {
    val settings = store()
    assertThat(settings.blockThirdPartyCookies).isTrue()
    assertThat(settings.sendDoNotTrack).isTrue()
  }

  @Test
  fun `algorithmic darkening is off, matching Chrome`() {
    assertThat(store().darkenWebPages).isFalse()
  }

  @Test
  fun `rendering defaults do not cripple pages`() {
    val settings = store()
    assertThat(settings.javaScriptEnabled).isTrue()
    assertThat(settings.desktopMode).isFalse()
  }

  @Test
  fun `an unknown stored engine falls back to the default rather than crashing`() {
    val settings = store()
    assertThat(SearchEngine.fromNameOrDefault("ALTAVISTA")).isEqualTo(SearchEngine.DUCKDUCKGO)
    assertThat(SearchEngine.fromNameOrDefault(null)).isEqualTo(SearchEngine.DUCKDUCKGO)
    settings.searchEngine = SearchEngine.MOJEEK
    assertThat(settings.searchEngine).isEqualTo(SearchEngine.MOJEEK)
  }
}
