/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import android.app.Application
import android.content.Intent
import android.net.VpnService
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class TorVpnStatus { OFF, ON, FAILED }

/**
 * The experimental Tor VPN's switch. It rides on Tor mode: it can only run while Tor mode is on
 * and is taken down with it, so there is one Tor client and one place it is turned on.
 */
@Singleton
class TorVpn @Inject constructor(
  private val application: Application,
  private val settings: SettingsStore,
) {

  private val _status = MutableStateFlow(TorVpnStatus.OFF)
  val status: StateFlow<TorVpnStatus> = _status.asStateFlow()

  val isEnabled: Boolean get() = settings.torVpn

  /** The system's consent screen when this app may not run a VPN yet, or null. */
  fun consentIntent(): Intent? = runCatching { VpnService.prepare(application) }.getOrNull()

  /** The user's switch; turning it on needs [consentIntent] answered first. */
  fun setEnabled(enabled: Boolean) {
    settings.torVpn = enabled
    sync()
  }

  /** Starts or stops the service to match the switch and Tor mode. */
  fun sync(torOn: Boolean = settings.torMode) {
    if (torOn && settings.torVpn && consentIntent() == null) {
      send(
        TorVpnService.ACTION_START,
      )
    } else {
      stop()
    }
  }

  fun stop() {
    if (_status.value == TorVpnStatus.OFF) return
    send(TorVpnService.ACTION_STOP)
  }

  internal fun onStarted() {
    _status.value = TorVpnStatus.ON
  }

  internal fun onStopped(failed: Boolean) {
    _status.value = if (failed) TorVpnStatus.FAILED else TorVpnStatus.OFF
  }

  private fun send(action: String) {
    // Android refuses to start services for an app in the background; the next sync from a
    // screen brings it up.
    runCatching {
      application.startService(Intent(application, TorVpnService::class.java).setAction(action))
    }.onFailure { Timber.w(it, "tor vpn: %s refused", action) }
  }
}
