/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.BuildConfig
import dev.androidpoet.wren.core.network.DeferredProxySelector
import dev.androidpoet.wren.settings.SettingsStore
import dev.androidpoet.wren.ui.tor.TorSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.freehaven.tor.control.TorControlCommands
import org.torproject.jni.TorService
import timber.log.Timber
import java.io.File
import java.net.ProxySelector
import java.net.ServerSocket
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

enum class TorStatus { OFF, STARTING, ON, STOPPING, FAILED }

data class TorState(
  val status: TorStatus = TorStatus.OFF,
  val progress: Int = 0,
  /** True once every WebView request is routed to the local Tor port. */
  val proxyInstalled: Boolean = false,
  val error: String? = null,
) {
  val isOn: Boolean get() = status == TorStatus.ON
}

/**
 * Runs the bundled Tor client and puts it in front of everything the browser sends.
 *
 * WebView's proxy override is process-wide, so Tor is a mode for the whole browser rather than
 * a kind of tab. The order of operations is what keeps it honest: the proxy is installed before
 * Tor is asked to start, so a page that loads during bootstrap fails at the local port instead
 * of slipping out direct, and the proxy stays in place if Tor dies underneath it.
 */
@Singleton
class TorController @Inject constructor(
  private val application: Application,
  private val settings: SettingsStore,
) {

  private val _state = MutableStateFlow(TorState())
  val state: StateFlow<TorState> = _state.asStateFlow()

  val isEnabled: Boolean get() = settings.torMode

  /**
   * The proxy override puts Tor in front of every page; the profile keeps Tor pages' cookies,
   * cache and TLS state apart from clear-net browsing. Without either, "Tor mode" would be a
   * name for a leak, so both are required.
   */
  val isSupported: Boolean
    get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) &&
      WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

  /**
   * Runs on the main thread just before the route changes, with `true` when Tor is about to
   * front the pages and `false` when the proxy is about to come down. The browser screen tears
   * its pages down here, whatever lifecycle state it is in: a page that outlived the proxy
   * under it would carry on direct.
   */
  var onBeforeRouteChange: ((toTor: Boolean) -> Unit)? = null

  /**
   * Whether a page load may start: Tor is off, or the proxy fronts every request and Tor has
   * either built its circuits or given up. A load during bootstrap would only die at the local
   * port; after a failed start it dies there too, which is the point.
   */
  val isNetworkReady: Boolean
    get() = !settings.torMode || isReady(_state.value)

  fun isReady(state: TorState): Boolean =
    !settings.torMode || (state.proxyInstalled && state.status != TorStatus.STARTING)

  private val mainHandler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { mainHandler.post(it) }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private var service: TorService? = null
  private var bound = false
  private var receiverRegistered = false
  private var pollJob: Job? = null
  private var pendingStart = false
  private var stopBroadcastsSeen = 0
  private var systemProxySelector: ProxySelector? = null
  private var sealing = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      service = (binder as TorService.LocalBinder).service
    }

    override fun onServiceDisconnected(name: ComponentName) {
      service = null
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        TorService.ACTION_STATUS -> onStatus(intent.getStringExtra(TorService.EXTRA_STATUS))
        TorService.ACTION_ERROR -> fail(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor error")
      }
    }
  }

  fun startIfEnabled() {
    if (settings.torMode) start()
  }

  fun setEnabled(enabled: Boolean) {
    if (enabled == settings.torMode && enabled == (_state.value.status != TorStatus.OFF)) return
    onBeforeRouteChange?.invoke(enabled)
    settings.torMode = enabled
    if (enabled) start() else stop()
  }

  /**
   * Fresh circuits for everything that follows. NEWNYM alone leaves the tunnels already open
   * on their old circuits, and WebView keeps idle connections for minutes, so the tunnel port
   * is moved first: Tor closes the old listener with every tunnel on it, and the proxy
   * override pointed at the new port starts with an empty connection pool. False when Tor did
   * not take the change, in which case nothing may claim a new identity.
   */
  suspend fun newIdentity(): Boolean {
    val control = service?.torControlConnection ?: return false
    val fresh = withContext(Dispatchers.IO) { freePort() }
    val changed = withContext(Dispatchers.IO) {
      runCatching {
        control.setConf("HTTPTunnelPort", "127.0.0.1:$fresh IsolateDestAddr")
        control.signal(TorControlCommands.SIGNAL_NEWNYM)
      }.onFailure { Timber.w(it, "new identity failed") }.isSuccess
    }
    if (changed) {
      settings.torHttpPort = fresh
      installProxy(fresh)
    }
    return changed
  }

  private fun start() {
    when (_state.value.status) {
      TorStatus.STARTING, TorStatus.ON -> return
      TorStatus.STOPPING -> {
        pendingStart = true
        return
      }
      TorStatus.OFF, TorStatus.FAILED -> Unit
    }
    if (!isSupported) {
      // Without a proxy override nothing could be routed, so the mode cannot be honoured at all.
      settings.torMode = false
      _state.value = TorState(TorStatus.FAILED, error = "proxy override unsupported")
      return
    }
    _state.value = TorState(TorStatus.STARTING)
    scope.launch {
      val bridges = withContext(Dispatchers.IO) { runCatching { bridgePlan() } }
        .getOrElse { problem ->
          fail(problem.message ?: "bridges could not be configured")
          return@launch
        }
      val ports =
        withContext(Dispatchers.IO) { ensurePorts().also { writeTorrc(it, bridges.torrc) } }
      if (_state.value.status != TorStatus.STARTING) return@launch
      installProxy(ports.http)
      registerReceiver()
      // tor-android 0.4.9.14 dropped the setter; status goes to the app's own package by default.
      val intent = Intent(application, TorService::class.java)
      bound = application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      if (!bound) {
        fail("could not bind the Tor service")
        return@launch
      }
      bridges.transportPlugin?.let(::armTransports)
      pollBootstrap()
    }
  }

  /**
   * Hands Tor the pluggable-transport plugin over the control port, then lets it reach the
   * network. See [Bridges.torrcOptions] for why this is not a torrc line. Until the control
   * socket is up there is nothing to talk to, so this waits for it, bounded.
   */
  private fun armTransports(plugin: String) {
    scope.launch {
      val control = withTimeoutOrNull(CONTROL_WAIT_MS) {
        while (_state.value.status == TorStatus.STARTING) {
          service?.torControlConnection?.let { return@withTimeoutOrNull it }
          delay(POLL_INTERVAL_MS)
        }
        null
      }
      if (_state.value.status != TorStatus.STARTING) return@launch
      if (control == null) {
        fail("Tor's control port did not come up")
        return@launch
      }
      val armed = withContext(Dispatchers.IO) {
        runCatching {
          control.setConf(listOf("ClientTransportPlugin $plugin"))
          control.setConf("DisableNetwork", "0")
        }.onFailure { Timber.w(it, "bridge transport could not be set") }.isSuccess
      }
      if (!armed && _state.value.status == TorStatus.STARTING) fail("bridge transport refused")
    }
  }

  private fun stop() {
    if (_state.value.status == TorStatus.OFF) return
    pendingStart = false
    shutDown(sealed = false)
  }

  /**
   * The bridge choice changed. Tor reads bridges only at start, so a running client is taken
   * down and brought back; the mode stays on throughout, so the gap is sealed with a dead end
   * rather than opened to the real address. Off, nothing happens until Tor is next turned on.
   */
  fun applyBridges() {
    when (_state.value.status) {
      TorStatus.OFF -> if (settings.torMode) start()
      TorStatus.FAILED -> start()
      TorStatus.STOPPING -> pendingStart = settings.torMode
      TorStatus.STARTING, TorStatus.ON -> {
        pendingStart = true
        shutDown(sealed = true)
      }
    }
  }

  private fun shutDown(sealed: Boolean) {
    pollJob?.cancel()
    _state.update { TorState(TorStatus.STOPPING, proxyInstalled = it.proxyInstalled) }
    if (sealed) installProxy(DEAD_END_PORT) else clearProxy()
    stopBroadcastsSeen = 0
    unbind()
    application.stopService(Intent(application, TorService::class.java))
    mainHandler.postDelayed(::finishStop, STOP_TIMEOUT_MS)
  }

  private fun onStatus(status: String?) {
    when (status) {
      TorService.STATUS_ON -> if (_state.value.status == TorStatus.STARTING) {
        pollJob?.cancel()
        _state.update { it.copy(status = TorStatus.ON, progress = 100, error = null) }
        Timber.i("tor connected")
      }

      TorService.STATUS_STOPPING, TorService.STATUS_OFF -> when (_state.value.status) {
        TorStatus.STOPPING -> {
          // The service and its Tor thread each announce the end; the second one means the
          // ports are free again and a restart will not block on the run lock.
          stopBroadcastsSeen++
          if (stopBroadcastsSeen >= 2) finishStop()
        }
        TorStatus.STARTING, TorStatus.ON -> fail("Tor stopped")
        else -> Unit
      }
    }
  }

  private fun finishStop() {
    if (_state.value.status != TorStatus.STOPPING) return
    mainHandler.removeCallbacks(::finishStop)
    _state.update { TorState(TorStatus.OFF, proxyInstalled = it.proxyInstalled) }
    if (pendingStart) {
      pendingStart = false
      start()
    }
  }

  /** Leaves the proxy in place: with Tor gone, fail closed is the only acceptable failure. */
  private fun fail(message: String) {
    Timber.w("tor failed: %s", message)
    pollJob?.cancel()
    unbind()
    _state.update { it.copy(status = TorStatus.FAILED, error = message) }
    // The mode is still on, so nothing may leave direct. A dead end goes in front of WebView
    // until the user turns the mode off - even when the tunnel port was already the proxy: a
    // port Tor no longer holds is one any other app on the device may bind next.
    if (settings.torMode) {
      if (sealing) {
        // The override itself is what failed. The mode stays on and the network stays closed:
        // no load runs until the proxy fronts WebView, which it now never will, so the user
        // has to turn Tor off deliberately rather than find pages quietly going direct.
        _state.update { it.copy(error = "$message; pages stay blocked until Tor mode is off") }
      } else {
        sealing = true
        installProxy(DEAD_END_PORT)
        sealing = false
      }
    }
  }

  private fun pollBootstrap() {
    pollJob?.cancel()
    pollJob = scope.launch {
      while (isActive && _state.value.status == TorStatus.STARTING) {
        val progress = withContext(Dispatchers.IO) {
          val control = service?.torControlConnection ?: return@withContext null
          runCatching { control.getInfo(BOOTSTRAP_PHASE) }.getOrNull()
        }?.let(TorBootstrap::progressOf)
        if (progress != null && progress > _state.value.progress) {
          _state.update { it.copy(progress = progress) }
        }
        delay(POLL_INTERVAL_MS)
      }
    }
  }

  private fun installProxy(port: Int) {
    // Without this WebView would still send loopback and link-local addresses direct.
    val config = ProxyConfig.Builder()
      .addProxyRule("http://127.0.0.1:$port")
      .removeImplicitRules()
      .build()
    runCatching {
      ProxyController.getInstance().setProxyOverride(config, mainExecutor) {
        _state.update { it.copy(proxyInstalled = true) }
      }
    }.onFailure { fail("proxy override failed: ${it.message}") }
    if (systemProxySelector == null) systemProxySelector = ProxySelector.getDefault()
    ProxySelector.setDefault(TorProxySelector(port))
    DeferredProxySelector.routeChanged()
  }

  private fun clearProxy() {
    ProxySelector.setDefault(systemProxySelector)
    DeferredProxySelector.routeChanged()
    runCatching {
      ProxyController.getInstance().clearProxyOverride(mainExecutor) {
        _state.update { it.copy(proxyInstalled = false) }
      }
    }.onFailure {
      Timber.w(it, "could not clear the proxy override")
      _state.update { it.copy(proxyInstalled = false) }
    }
  }

  private fun registerReceiver() {
    if (receiverRegistered) return
    receiverRegistered = true
    val filter = IntentFilter().apply {
      addAction(TorService.ACTION_STATUS)
      addAction(TorService.ACTION_ERROR)
    }
    LocalBroadcastManager.getInstance(application).registerReceiver(receiver, filter)
  }

  private fun unbind() {
    if (!bound) return
    bound = false
    service = null
    runCatching { application.unbindService(connection) }
  }

  private data class Ports(val socks: Int, val http: Int)

  /** Fixed ports, chosen once and reused, so the proxy can be installed before Tor is up. */
  private fun ensurePorts(): Ports {
    var socks = settings.torSocksPort
    var http = settings.torHttpPort
    if (socks == 0 || !isFree(socks)) socks = freePort()
    if (http == 0 || http == socks || !isFree(http)) http = freePort()
    settings.torSocksPort = socks
    settings.torHttpPort = http
    return Ports(socks, http)
  }

  /**
   * `IsolateDestAddr` gives every destination host its own circuit, so one exit never sees
   * the whole session. WebView's proxy override carries no per-request credentials, which
   * rules out Tor Browser's per-site (first-party) isolation; per-destination is the
   * strongest isolation the tunnel port can offer, at the cost of more circuit builds on a
   * page's first load.
   */
  private fun writeTorrc(ports: Ports, bridgeOptions: String) {
    TorService.getTorrc(application).writeText(
      """
      SOCKSPort 127.0.0.1:${ports.socks} IsolateDestAddr
      HTTPTunnelPort 127.0.0.1:${ports.http} IsolateDestAddr
      ClientOnly 1
      AvoidDiskWrites 1
      """.trimIndent() + "\n" + bridgeOptions + debugLog(),
    )
  }

  /** Debug builds keep Tor's own log in the cache directory; release builds log nowhere. */
  private fun debugLog(): String = if (BuildConfig.DEBUG) {
    "Log notice file ${File(application.cacheDir, "tor.log").absolutePath}\n"
  } else {
    ""
  }

  private class BridgePlan(val torrc: String, val transportPlugin: String?)

  /**
   * The chosen bridges as torrc lines plus the transport plugin to set once Tor is up, or
   * neither. Built-in lines come from the bundled list, custom ones are parsed again here:
   * nothing reaches Tor that did not pass [Bridges.parse]. Transports run through the bundled
   * lyrebird binary, which the installer places in the native-library directory; an install
   * without it cannot use them.
   */
  private fun bridgePlan(): BridgePlan {
    val mode = settings.torBridgeMode
    val lines = when (mode) {
      BridgeMode.NONE -> return BridgePlan("", null)
      BridgeMode.CUSTOM -> Bridges.parseAll(settings.torCustomBridges)
      else -> Bridges.builtIn(application.assets, mode)
    }
    val binary = File(application.applicationInfo.nativeLibraryDir, LYREBIRD_LIBRARY)
    val plugin = Bridges.transportPlugin(lines, binary.absolutePath)
      // The plugin logs next to Tor's state in debug builds only: bridge addresses are in it.
      ?.let { if (BuildConfig.DEBUG) "$it -enableLogging -logLevel INFO" else it }
    if (plugin != null) {
      check(binary.isFile && (binary.canExecute() || binary.setExecutable(true, false))) {
        TorSheet.TRANSPORT_MISSING
      }
    }
    return BridgePlan(Bridges.torrcOptions(lines), plugin)
  }

  private fun freePort(): Int = ServerSocket(0, 1, TorProxySelector.LOOPBACK).use { it.localPort }

  private fun isFree(port: Int): Boolean = runCatching {
    ServerSocket(port, 1, TorProxySelector.LOOPBACK).close()
  }.isSuccess

  private companion object {
    /** Nothing listens here; a proxy pointed at it refuses every connection. */
    const val DEAD_END_PORT = 1
    const val LYREBIRD_LIBRARY = "liblyrebird.so"
    const val BOOTSTRAP_PHASE = "status/bootstrap-phase"
    const val POLL_INTERVAL_MS = 500L
    const val CONTROL_WAIT_MS = 20_000L
    const val STOP_TIMEOUT_MS = 4000L
  }
}
