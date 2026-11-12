/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import android.content.Intent
import android.net.VpnService
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.settings.SettingsStore
import timber.log.Timber
import javax.inject.Inject

/**
 * Experimental: sends the other apps on the device through the Tor client this app already
 * runs for Tor mode. Every route, IPv4 and IPv6, points into the interface, so nothing an app
 * sends can go around it while it is up; DNS is answered inside it. This app is left out, as
 * it must be: Tor's own connections to the relays have to reach the network directly, and the
 * browser's pages already travel through Tor's tunnel port.
 *
 * Fail-closed while up: if Tor is down, connections die at the local SOCKS port. When the VPN
 * itself is off, apps go direct, unless Android's "Block connections without VPN" is set for
 * this app.
 */
@AndroidEntryPoint
class TorVpnService : VpnService() {

  @Inject lateinit var settings: SettingsStore

  @Inject lateinit var torVpn: TorVpn

  private var worker: Thread? = null
  private var workerPort = 0

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      halt()
      stopSelf()
      return START_NOT_STICKY
    }
    // ACTION_START from the app, or Android's own start for an always-on VPN.
    val port = settings.torSocksPort
    if (!settings.torMode || !settings.torVpn || port == 0 || !TorTunnel.isAvailable) {
      halt()
      stopSelf()
      return START_NOT_STICKY
    }
    if (worker?.isAlive == true && workerPort == port) return START_STICKY
    halt()
    launch(port)
    return START_STICKY
  }

  private fun launch(port: Int) {
    val tun = runCatching {
      Builder()
        .setSession(getString(R.string.tor_vpn_session))
        .setMtu(MTU)
        .addAddress(TUN_ADDRESS, TUN_PREFIX)
        .addRoute("0.0.0.0", 0)
        .addAddress(TUN_ADDRESS6, TUN_PREFIX6)
        .addRoute("::", 0)
        .addDnsServer(DNS_ADDRESS)
        .addDisallowedApplication(packageName)
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
        .establish()
    }.onFailure { Timber.w(it, "tor vpn: interface refused") }.getOrNull()
    if (tun == null) {
      // No consent (revoked, or another VPN took over): nothing to run.
      torVpn.onStopped(failed = true)
      stopSelf()
      return
    }
    val fd = tun.detachFd()
    workerPort = port
    torVpn.onStarted()
    worker = Thread({
      val code = TorTunnel.run(fd, port, MTU)
      if (code != 0) Timber.w("tor vpn: tunnel ended with %d", code)
      // Only the thread still current may report: a restart replaces it before it returns.
      if (worker === Thread.currentThread()) {
        worker = null
        torVpn.onStopped(failed = code != 0)
        stopSelf()
      }
    }, "tor-vpn").apply { start() }
  }

  /** Stops the tunnel and waits for its thread, so a restart never meets the old one. */
  private fun halt() {
    val running = worker ?: return
    worker = null
    TorTunnel.stop()
    running.join(HALT_WAIT_MS)
    torVpn.onStopped(failed = false)
  }

  override fun onRevoke() {
    // The user turned it off in system settings, or another VPN replaced it.
    settings.torVpn = false
    halt()
    super.onRevoke()
  }

  override fun onDestroy() {
    halt()
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "dev.androidpoet.wren.tor.VPN_START"
    const val ACTION_STOP = "dev.androidpoet.wren.tor.VPN_STOP"
    private const val MTU = 1500
    private const val TUN_ADDRESS = "10.111.222.1"
    private const val TUN_PREFIX = 30
    private const val DNS_ADDRESS = "10.111.222.2"
    private const val TUN_ADDRESS6 = "fd66:7265:6e00::1"
    private const val TUN_PREFIX6 = 126
    private const val HALT_WAIT_MS = 4000L
  }
}
