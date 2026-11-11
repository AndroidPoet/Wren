/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

import android.app.Application
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.settings.SettingsStore
import org.json.JSONObject
import timber.log.Timber
import java.security.SecureRandom
import java.util.EnumMap
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the document-start scripts that enforce privacy settings.
 *
 * Each one is added and removed as its setting changes. A document-start script is fixed for the
 * life of a document, so a change lands on the next load - which is what every browser does for
 * a content-blocking change.
 */
@Singleton
class PrivacyScripts @Inject constructor(
  private val application: Application,
  private val settings: SettingsStore,
) {

  /**
   * Regenerated per process, and again whenever the user ends an identity: a burn, a new Tor
   * identity. Farbling has to be stable while a page is open - a page comparing two reads of
   * its own canvas must see the same answer - but must not outlive the session it belongs to,
   * or it would become the identifier it exists to prevent.
   */
  /**
   * One seed per kind of page. A shared seed would give the same site the same canvas in a
   * normal tab, a private tab and a Tor tab, which is a join between the three identities.
   */
  enum class Mode { NORMAL, PRIVATE, TOR }

  private val seeds = EnumMap<Mode, Long>(Mode::class.java).apply {
    Mode.entries.forEach { put(it, SecureRandom().nextLong()) }
  }

  private fun seedOf(mode: Mode): Long = seeds.getValue(mode)

  /**
   * Where the Tor clock's 100 ms buckets round, one value per identity. Drawn in each frame,
   * as it was, every frame rounded one instant its own way, and a page reading the clock in a
   * few hundred frames could average the rounding back to the true time.
   */
  private var torClockSecret: Int = SecureRandom().nextInt()

  private val fingerprintSource: String by lazy { read("fingerprint.js") }

  private val fingerprintCache = EnumMap<Mode, Pair<Long, String>>(Mode::class.java)

  private fun fingerprintScript(mode: Mode): String {
    val seed = seedOf(mode)
    fingerprintCache[mode]?.let { if (it.first == seed) return it.second }
    // Two words of key for the script's keyed hash: a site learns its own per-site noise and
    // nothing of the key, so nothing of another site's noise.
    val script = fingerprintSource
      .replace("__SEED__", (seed ushr 32).toInt().toString())
      .replace("__SEED2__", seed.toInt().toString())
      .replace("__TOR__", (mode == Mode.TOR).toString())
    fingerprintCache[mode] = seed to script
    return script
  }

  /** Ends every farbling identity. Call with no WebView alive, or follow with [updateFor]. */
  fun rotateSeed() = Mode.entries.forEach(::rotateSeed)

  /** Ends one identity: the Tor one when Tor comes on, the private one when its last tab closes. */
  fun rotateSeed(mode: Mode) {
    seeds[mode] = SecureRandom().nextLong()
    if (mode == Mode.TOR) torClockSecret = SecureRandom().nextInt()
  }

  private val autoconsentScript: String by lazy { read("autoconsent.js") }

  private val referrerScript: String by lazy {
    // A meta referrer policy is the only referrer control a WebView host actually has; the
    // header is set by the engine, not by us, for everything the page itself requests.
    """
    (function () {
      try {
        var meta = document.createElement('meta');
        meta.name = 'referrer';
        meta.content = 'strict-origin';
        (document.head || document.documentElement).appendChild(meta);
      } catch (ignored) {}
    })();
    """.trimIndent()
  }

  /** The clock moved to UTC and coarsened, the screen reported as the viewport; see the asset. */
  private val torSource: String by lazy { read("tor.js") }

  private var torCache: Pair<Int, String>? = null

  private fun torScript(): String {
    val secret = torClockSecret
    torCache?.let { if (it.first == secret) return it.second }
    val script = torSource.replace("__CLOCK__", secret.toString())
    torCache = secret to script
    return script
  }

  /** WebRTC, motion sensors and device lists removed, for private and Tor pages; see the asset. */
  private val privateScript: String by lazy { read("private.js") }

  private val gpcScript: String by lazy { read("gpc.js") }

  /** Third-party frames get no storage, as Chrome gives them none with its cookies blocked. */
  private val partitionScript: String by lazy { read("partition.js") }

  /** Link `ping` trackers removed as the link is tapped; see the asset. */
  private val pingScript: String by lazy { read("ping.js") }

  /** Keeps the scripts out of bot-check frames and sites with protection off; see the asset. */
  private val guardSource: String by lazy { read("guard.js") }

  /**
   * The condition the scripts below are wrapped in, or null to run them everywhere. Under Tor
   * they are part of the identity a site must not see past, so nothing is exempt there.
   */
  private fun guardFor(mode: Mode): String? {
    if (mode == Mode.TOR) return null
    val off = settings.protectionOffHosts().sorted()
      .joinToString(",", "[", "]") { JSONObject.quote(it) }
    return guardSource.replace("__OFF__", off)
  }

  private fun guarded(script: String, guard: String?): String =
    if (guard == null) script else "if (!(\n$guard\n)) {\n$script\n}"

  private class Handlers {
    var tor: ScriptHandler? = null
    var torClock: Int = 0
    var private: ScriptHandler? = null
    var fingerprint: ScriptHandler? = null
    var fingerprintSeed: Long = 0
    var fingerprintMode: Mode? = null
    var autoconsent: ScriptHandler? = null
    var referrer: ScriptHandler? = null
    var gpc: ScriptHandler? = null
    var partition: ScriptHandler? = null
    var pings: ScriptHandler? = null
    var guard: String? = null
  }

  private val handlers = WeakHashMap<WebView, Handlers>()

  fun warmUp() {
    fingerprintScript(Mode.NORMAL)
    autoconsentScript
  }

  /** Brings the installed scripts in line with the current settings and the tab's privacy. */
  fun updateFor(webView: WebView, isPrivate: Boolean) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    val installed = handlers.getOrPut(webView) { Handlers() }
    val mode = when {
      settings.torMode -> Mode.TOR
      isPrivate -> Mode.PRIVATE
      else -> Mode.NORMAL
    }

    val guard = guardFor(mode)
    if (installed.guard != guard) {
      // The exempt sites changed: every guarded script carries the old list and comes off.
      with(installed) {
        listOf(fingerprint, autoconsent, referrer, gpc, partition, pings)
          .forEach { runCatching { it?.remove() } }
        fingerprint = null
        autoconsent = null
        referrer = null
        gpc = null
        partition = null
        pings = null
        this.guard = guard
      }
    }
    val stale = installed.fingerprintSeed != seedOf(mode) || installed.fingerprintMode != mode
    if (installed.fingerprint != null && stale) {
      // A rotated seed or another kind of page means a new script; the old one comes off first.
      runCatching { installed.fingerprint?.remove() }
      installed.fingerprint = null
    }
    // Under Tor this script is what blanks the canvas and WebGL and pins the hardware answers;
    // a switch turned off for a normal site must not hand Tor pages the device's real ones.
    val resistFingerprinting = settings.blockFingerprinting || settings.torMode
    installed.fingerprint = sync(webView, installed.fingerprint, resistFingerprinting) {
      guarded(fingerprintScript(mode), guard)
    }
    installed.fingerprintSeed = seedOf(mode)
    installed.fingerprintMode = mode
    installed.autoconsent = sync(webView, installed.autoconsent, settings.answerCookiePopups) {
      guarded(autoconsentScript, guard)
    }
    installed.referrer = sync(
      webView,
      installed.referrer,
      settings.trimReferrers,
    ) { guarded(referrerScript, guard) }
    installed.gpc = sync(webView, installed.gpc, settings.sendDoNotTrack && !settings.torMode) {
      guarded(gpcScript, guard)
    }
    installed.partition = sync(
      webView,
      installed.partition,
      settings.blockThirdPartyCookies || settings.torMode,
    ) { guarded(partitionScript, guard) }
    installed.pings = sync(webView, installed.pings, settings.stripTrackingParameters) {
      guarded(pingScript, guard)
    }
    if (installed.tor != null && installed.torClock != torClockSecret) {
      // A new identity rounds its clock its own way; the old script comes off first.
      runCatching { installed.tor?.remove() }
      installed.tor = null
    }
    installed.tor = sync(webView, installed.tor, settings.torMode) { torScript() }
    installed.torClock = torClockSecret
    installed.private = sync(webView, installed.private, isPrivate || settings.torMode) {
      privateScript
    }
  }

  private fun sync(
    webView: WebView,
    current: ScriptHandler?,
    wanted: Boolean,
    script: () -> String,
  ): ScriptHandler? = when {
    wanted && current == null -> runCatching {
      WebViewCompat.addDocumentStartJavaScript(webView, script(), ALLOWED_ORIGINS)
    }.onFailure { Timber.w(it, "could not install a privacy script") }.getOrNull()

    !wanted && current != null -> {
      runCatching { current.remove() }
      null
    }

    else -> current
  }

  private fun read(asset: String): String =
    application.assets.open(asset).bufferedReader().use { it.readText() }

  private companion object {
    val ALLOWED_ORIGINS = setOf("*")
  }
}
