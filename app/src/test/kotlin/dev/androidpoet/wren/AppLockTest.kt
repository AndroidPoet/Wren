/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.AppLock
import dev.androidpoet.wren.settings.SettingsStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The lock must never open silently while the setting still says it is armed. */
@RunWith(RobolectricTestRunner::class)
class AppLockTest {

  private val settings = SettingsStore(
    ApplicationProvider.getApplicationContext<Context>()
      .getSharedPreferences("app-lock-test", Context.MODE_PRIVATE),
  )
  private val activity: FragmentActivity =
    Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

  private fun gate(lock: AppLock): Triple<Boolean, Boolean, Boolean> {
    var locked = false
    var unlocked = false
    var disarmed = false
    lock.gate(
      activity = activity,
      title = "Unlock",
      onLocked = { locked = true },
      onUnlocked = { unlocked = true },
      onFailed = {},
      onDisarmed = { disarmed = true },
    )
    return Triple(locked, unlocked, disarmed)
  }

  @Test
  fun `lock off opens without a prompt`() {
    settings.appLock = false

    val (locked, unlocked, disarmed) = gate(AppLock(settings) { true })

    assertThat(locked).isFalse()
    assertThat(unlocked).isTrue()
    assertThat(disarmed).isFalse()
  }

  @Test
  fun `armed lock without an authenticator disarms itself and says so`() {
    settings.appLock = true

    val (locked, unlocked, disarmed) = gate(AppLock(settings) { false })

    assertThat(locked).isFalse()
    assertThat(unlocked).isTrue()
    assertThat(disarmed).isTrue()
    assertThat(settings.appLock).isFalse()
  }

  @Test
  fun `armed lock with an authenticator hides the content first`() {
    settings.appLock = true

    val (locked, _, disarmed) = gate(AppLock(settings) { true })

    assertThat(locked).isTrue()
    assertThat(disarmed).isFalse()
    assertThat(settings.appLock).isTrue()
  }
}
