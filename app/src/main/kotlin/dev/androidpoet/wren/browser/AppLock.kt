/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.androidpoet.wren.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the whole app behind the device's own credential. Unlocked for as long as the app stays
 * in the foreground; leaving it re-locks, so a handed-over or pocketed phone shows nothing.
 */
@Singleton
class AppLock @Inject constructor(
  private val settings: SettingsStore,
) : DefaultLifecycleObserver {

  private var unlocked = false

  /** True while nothing stands between the user and the content. */
  val isOpen: Boolean
    get() = !settings.appLock || unlocked

  val isArmed: Boolean
    get() = settings.appLock

  /**
   * Below Android 13 the only way to keep a locked app out of the recents preview is a secure
   * window, which also forbids screenshots; from 13 on the preview alone can be disabled.
   */
  val needsSecureWindow: Boolean
    get() = settings.appLock && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

  init {
    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
  }

  fun shieldRecents(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      activity.setRecentsScreenshotEnabled(!settings.appLock)
    }
  }

  override fun onStop(owner: LifecycleOwner) {
    unlocked = false
  }

  fun canAuthenticate(activity: FragmentActivity): Boolean = BiometricManager.from(activity)
    .canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

  /**
   * Runs the gate if it is armed. [onLocked] fires first so the caller can hide its content;
   * exactly one of [onUnlocked] or [onFailed] follows.
   */
  fun gate(
    activity: FragmentActivity,
    title: String,
    onLocked: () -> Unit,
    onUnlocked: () -> Unit,
    onFailed: () -> Unit,
  ) {
    if (!settings.appLock || unlocked || !canAuthenticate(activity)) {
      onUnlocked()
      return
    }
    onLocked()
    val prompt = BiometricPrompt(
      activity,
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          unlocked = true
          onUnlocked()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          onFailed()
        }
      },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
      .build()
    prompt.authenticate(info)
  }
}

/**
 * Standard gate wiring for a screen: ask on every start, hide the content while the prompt is
 * up and before the system captures the recents preview, leave the app when the prompt fails.
 * Attach once, from onCreate.
 */
fun FragmentActivity.applyAppLock(appLock: AppLock, content: View) {
  lifecycle.addObserver(
    object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) {
        appLock.shieldRecents(this@applyAppLock)
        appLock.gate(
          activity = this@applyAppLock,
          title = getString(dev.androidpoet.wren.R.string.unlock_title),
          onLocked = { content.visibility = View.INVISIBLE },
          onUnlocked = { content.visibility = View.VISIBLE },
          onFailed = { finishAffinity() },
        )
      }

      override fun onResume(owner: LifecycleOwner) {
        if (appLock.isOpen) content.visibility = View.VISIBLE
      }

      override fun onPause(owner: LifecycleOwner) {
        if (appLock.isArmed) content.visibility = View.INVISIBLE
      }
    },
  )
}
