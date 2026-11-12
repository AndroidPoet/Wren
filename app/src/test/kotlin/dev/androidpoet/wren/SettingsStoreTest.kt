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
import dev.androidpoet.wren.settings.TorSecurityLevel
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

  @Test
  fun `safest is off by default and only active while pages are on Tor`() {
    val settings = store()

    assertThat(settings.torSafest).isFalse()
    settings.torSafest = true
    assertThat(settings.torSafestActive).isFalse()
    settings.torMode = true
    assertThat(settings.torSafestActive).isTrue()
  }

  @Test
  fun `safer blocks script only on Tor pages that arrived without https`() {
    val settings = store()
    settings.torSecurityLevel = TorSecurityLevel.SAFER

    assertThat(settings.torLevelBlocksScript("http://example.com/")).isFalse()
    settings.torMode = true
    assertThat(settings.torLevelBlocksScript("http://example.com/")).isTrue()
    assertThat(settings.torLevelBlocksScript("https://example.com/")).isFalse()
    assertThat(
      settings.torLevelBlocksScript(
        "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
      ),
    ).isFalse()
    assertThat(settings.torLevelBlocksScript("about:blank")).isFalse()
    assertThat(settings.torSafestActive).isFalse()
  }

  @Test
  fun `the old safest switch carries over until a level is chosen`() {
    val settings = store()
    settings.torSafest = true

    assertThat(settings.torSecurityLevel).isEqualTo(TorSecurityLevel.SAFEST)
    settings.torMode = true
    assertThat(settings.torLevelBlocksScript("https://example.com/")).isTrue()
    settings.torSecurityLevel = TorSecurityLevel.STANDARD
    assertThat(settings.torSafest).isFalse()
    assertThat(settings.torLevelBlocksScript("http://example.com/")).isFalse()
  }
}
