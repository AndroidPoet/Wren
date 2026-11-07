/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.TorProfile
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** Tor pages speak en-US for as long as Tor is on, and the device language comes back after. */
@RunWith(RobolectricTestRunner::class)
class TorProfileTest {

  private val device = LocaleList(Locale.GERMANY, Locale.US)
  private val profile = TorProfile(ApplicationProvider.getApplicationContext<Context>())

  @After
  fun restore() {
    profile.leaveEnglish()
  }

  @Test
  fun `tor makes the process speak en-US and leaving restores the device languages`() {
    LocaleList.setDefault(device)

    profile.enterEnglish()
    val whileOn = LocaleList.getDefault()
    profile.enterEnglish()
    profile.leaveEnglish()

    assertThat(whileOn.toLanguageTags()).isEqualTo("en-US")
    assertThat(LocaleList.getDefault()).isEqualTo(device)
    assertThat(Locale.getDefault()).isEqualTo(Locale.GERMANY)
    assertThat(profile.speaksEnglish).isFalse()
  }

  @Test
  fun `leaving without entering changes nothing`() {
    LocaleList.setDefault(device)

    profile.leaveEnglish()

    assertThat(LocaleList.getDefault()).isEqualTo(device)
  }
}
