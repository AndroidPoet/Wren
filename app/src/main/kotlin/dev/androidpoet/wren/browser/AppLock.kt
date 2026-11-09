/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.Toast
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
class AppLock internal constructor(
  private val settings: SettingsStore,
  /** A [BiometricManager] `canAuthenticate` result code for the activity's device. */
  private val authenticatorStatus: (FragmentActivity) -> Int,
) : DefaultLifecycleObserver {

  @Inject
  constructor(settings: SettingsStore) : this(
    settings,
    { activity ->
      BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
    },
  )

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

  fun canAuthenticate(activity: FragmentActivity): Boolean =
    authenticatorStatus(activity) == BiometricManager.BIOMETRIC_SUCCESS

  /**
   * Runs the gate if it is armed. [onLocked] fires first so the caller can hide its content;
   * exactly one of [onUnlocked] or [onFailed] follows. If the device lost its screen lock since
   * the gate was armed there is nothing left to ask for: the lock disarms, [onDisarmed] reports
   * it, and the screen opens, rather than opening silently while the setting still says locked.
   */
  fun gate(
    activity: FragmentActivity,
    title: String,
    onLocked: () -> Unit,
    onUnlocked: () -> Unit,
    onFailed: () -> Unit,
    onDisarmed: () -> Unit = {},
  ) {
    if (!settings.appLock || unlocked) {
      onUnlocked()
      return
    }
    if (authenticatorStatus(activity) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
      // The device has no screen lock any more: there is nothing left to ask for, and a lock
      // that can never open is worse than one honestly reported as gone.
      settings.appLock = false
      shieldRecents(activity)
      onDisarmed()
      onUnlocked()
      return
    }
    // Any other trouble - sensor busy, hardware unavailable, an unknown status - keeps the
    // gate closed: the prompt is still shown and its own error path ends in [onFailed].
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
          onDisarmed = {
            Toast.makeText(
              this@applyAppLock,
              dev.androidpoet.wren.R.string.app_lock_disarmed,
              Toast.LENGTH_LONG,
            ).show()
          },
        )
      }

      override fun onResume(owner: LifecycleOwner) {
        if (appLock.isOpen) content.visibility = View.VISIBLE
      }

      override fun onPause(owner: LifecycleOwner) {
        if (appLock.isArmed) {
          content.visibility = View.INVISIBLE
          // Sheets and dialogs are windows of their own above the hidden content; the lock
          // has to cover them too, and the simplest cover is their absence.
          supportFragmentManager.fragments
            .filterIsInstance<androidx.fragment.app.DialogFragment>()
            .forEach { it.dismissAllowingStateLoss() }
        }
      }
    },
  )
}
