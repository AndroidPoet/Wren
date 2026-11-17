/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.settings.SettingsStore
import dev.androidpoet.wren.ui.browser.BrowserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Experimental: sends the other apps on the device through the Tor client this app already
 * runs for Tor mode. Every route, IPv4 and IPv6, points into the interface, so nothing an app
 * sends can go around it while it is up; DNS is answered inside it. This app is left out, as
 * it must be: Tor's own connections to the relays have to reach the network directly, and the
 * browser's pages already travel through Tor's tunnel port.
 *
 * The interface belongs to the service, not to the packet engine. It goes up as soon as both
 * switches are on and stays up until one goes off; packets are only carried while Tor is
 * connected. While Tor starts, after it fails, and between two runs of the engine, nobody reads
 * the interface, so the other apps are offline rather than direct. When the VPN itself is off,
 * apps go direct, unless Android's "Block connections without VPN" is set for this app.
 */
@AndroidEntryPoint
class TorVpnService : VpnService() {

  @Inject lateinit var settings: SettingsStore

  @Inject lateinit var torVpn: TorVpn

  @Inject lateinit var torController: TorController

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /**
   * Every start, stop and restart runs here, one at a time and off the main thread: stopping
   * the engine waits for its thread. The fields below are touched only from it.
   */
  private val lifecycle = Executors.newSingleThreadScheduledExecutor { Thread(it, "tor-vpn-life") }
  private var tun: ParcelFileDescriptor? = null
  private var worker: Thread? = null
  private var workerPort = 0
  private var failures = 0

  @Volatile
  private var destroyed = false

  override fun onCreate() {
    super.onCreate()
    // Tor leaving or reaching its connected state decides whether packets are carried.
    scope.launch {
      torController.state
        .map { it.isOn }
        .distinctUntilChanged()
        .drop(1)
        .collect { onLifecycle(::reconcile) }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      onLifecycle(::shutDown)
      return START_NOT_STICKY
    }
    // ACTION_START from the app, or Android's own start for an always-on VPN.
    promote()
    onLifecycle(::reconcile)
    return START_STICKY
  }

  /** Brings the interface and the engine to what the switches and Tor's state ask for. */
  private fun reconcile() {
    if (destroyed) return
    if (!settings.torMode || !settings.torVpn || !TorTunnel.isAvailable) {
      shutDown()
      return
    }
    val held = tun ?: establish()
    if (held == null) {
      // No consent (revoked, or another VPN took over): nothing to run.
      torVpn.onStopped(failed = true)
      stopSelf()
      return
    }
    tun = held
    val port = settings.torSocksPort
    if (!torController.state.value.isOn || port == 0) {
      // Tor's port may be closed or, worse, taken by another app: nothing is sent to it.
      haltWorker()
      torVpn.onHolding()
      return
    }
    if (worker?.isAlive == true && workerPort == port) return
    haltWorker()
    forward(held, port)
  }

  private fun establish(): ParcelFileDescriptor? = runCatching {
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

  /** Runs the engine on a copy of the interface's descriptor; the service keeps its own. */
  private fun forward(held: ParcelFileDescriptor, port: Int) {
    val fd = runCatching { held.dup().detachFd() }
      .onFailure { Timber.w(it, "tor vpn: interface could not be shared") }
      .getOrNull()
    if (fd == null) {
      torVpn.onHolding()
      return
    }
    workerPort = port
    torVpn.onStarted()
    val started = SystemClock.elapsedRealtime()
    worker = Thread({
      val code = TorTunnel.run(fd, port, MTU)
      if (code != 0) Timber.w("tor vpn: tunnel ended with %d", code)
      val ended = Thread.currentThread()
      onLifecycle { onWorkerEnded(ended, SystemClock.elapsedRealtime() - started) }
    }, "tor-vpn").apply { start() }
  }

  /** The engine returned on its own: the interface stays, and it is started again a few times. */
  private fun onWorkerEnded(ended: Thread, ranMs: Long) {
    // A thread stopped on purpose was already replaced or dropped.
    if (worker !== ended) return
    worker = null
    torVpn.onHolding()
    if (ranMs >= STEADY_MS) failures = 0
    if (++failures > MAX_RESTARTS || destroyed) return
    runCatching { lifecycle.schedule(::reconcile, RESTART_DELAY_MS, TimeUnit.MILLISECONDS) }
  }

  /**
   * Stops the engine and waits for its thread, so a restart never meets the old one. The stop
   * is repeated: a thread that has not reached the engine yet would miss a single one.
   */
  private fun haltWorker() {
    val running = worker ?: return
    worker = null
    val deadline = SystemClock.elapsedRealtime() + HALT_WAIT_MS
    while (running.isAlive && SystemClock.elapsedRealtime() < deadline) {
      TorTunnel.stop()
      running.join(HALT_POLL_MS)
    }
  }

  private fun shutDown() {
    haltWorker()
    runCatching { tun?.close() }
    tun = null
    failures = 0
    torVpn.onStopped(failed = false)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun onLifecycle(task: () -> Unit) {
    // Refused only after onDestroy, whose own shutdown is already queued.
    runCatching { lifecycle.execute(task) }
  }

  /**
   * Android asks a VPN to run in the foreground, and the notification is the one place outside
   * the Tor sheet that says other apps are being routed. Refused for an app in the background;
   * the system's own binding still keeps the service up.
   */
  private fun promote() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.tor_vpn_session),
        NotificationManager.IMPORTANCE_LOW,
      ).apply { setShowBadge(false) }
      getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, BrowserActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_tor)
      .setContentTitle(getString(R.string.tor_vpn_session))
      .setContentText(getString(R.string.tor_vpn_notification))
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(open)
      .build()
    runCatching {
      ServiceCompat.startForeground(
        this,
        FOREGROUND_ID,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
          0
        },
      )
    }.onFailure { Timber.w(it, "tor vpn: not promoted to the foreground") }
  }

  override fun onRevoke() {
    // The user turned it off in system settings, or another VPN replaced it.
    settings.torVpn = false
    onLifecycle(::shutDown)
    super.onRevoke()
  }

  override fun onDestroy() {
    destroyed = true
    scope.cancel()
    onLifecycle {
      haltWorker()
      runCatching { tun?.close() }
      tun = null
      torVpn.onStopped(failed = false)
    }
    lifecycle.shutdown()
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "dev.androidpoet.wren.tor.VPN_START"
    const val ACTION_STOP = "dev.androidpoet.wren.tor.VPN_STOP"
    private const val CHANNEL_ID = "tor_vpn"
    private const val FOREGROUND_ID = 7002
    private const val MTU = 1500
    private const val TUN_ADDRESS = "10.111.222.1"
    private const val TUN_PREFIX = 30
    private const val DNS_ADDRESS = "10.111.222.2"
    private const val TUN_ADDRESS6 = "fd66:7265:6e00::1"
    private const val TUN_PREFIX6 = 126
    private const val HALT_WAIT_MS = 4000L
    private const val HALT_POLL_MS = 100L
    private const val MAX_RESTARTS = 3
    private const val RESTART_DELAY_MS = 2000L
    private const val STEADY_MS = 30_000L
  }
}
