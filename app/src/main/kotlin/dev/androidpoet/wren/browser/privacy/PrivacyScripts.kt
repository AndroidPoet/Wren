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

  private val seeds = EnumMap<Mode, Int>(Mode::class.java).apply {
    Mode.entries.forEach { put(it, SecureRandom().nextInt()) }
  }

  private fun seedOf(mode: Mode): Int = seeds.getValue(mode)

  private val fingerprintSource: String by lazy { read("fingerprint.js") }

  private val fingerprintCache = EnumMap<Mode, Pair<Int, String>>(Mode::class.java)

  private fun fingerprintScript(mode: Mode): String {
    val seed = seedOf(mode)
    fingerprintCache[mode]?.let { if (it.first == seed) return it.second }
    val script = fingerprintSource
      .replace("__SEED__", seed.toString())
      .replace("__TOR__", (mode == Mode.TOR).toString())
    fingerprintCache[mode] = seed to script
    return script
  }

  /** Ends every farbling identity. Call with no WebView alive, or follow with [updateFor]. */
  fun rotateSeed() = Mode.entries.forEach(::rotateSeed)

  /** Ends one identity: the Tor one when Tor comes on, the private one when its last tab closes. */
  fun rotateSeed(mode: Mode) {
    seeds[mode] = SecureRandom().nextInt()
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
  private val torScript: String by lazy { read("tor.js") }

  /** WebRTC, motion sensors and device lists removed, for private and Tor pages; see the asset. */
  private val privateScript: String by lazy { read("private.js") }

  private val gpcScript: String by lazy { read("gpc.js") }

  private class Handlers {
    var tor: ScriptHandler? = null
    var private: ScriptHandler? = null
    var fingerprint: ScriptHandler? = null
    var fingerprintSeed: Int = 0
    var fingerprintMode: Mode? = null
    var autoconsent: ScriptHandler? = null
    var referrer: ScriptHandler? = null
    var gpc: ScriptHandler? = null
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

    val stale = installed.fingerprintSeed != seedOf(mode) || installed.fingerprintMode != mode
    if (installed.fingerprint != null && stale) {
      // A rotated seed or another kind of page means a new script; the old one comes off first.
      runCatching { installed.fingerprint?.remove() }
      installed.fingerprint = null
    }
    installed.fingerprint = sync(webView, installed.fingerprint, settings.blockFingerprinting) {
      fingerprintScript(mode)
    }
    installed.fingerprintSeed = seedOf(mode)
    installed.fingerprintMode = mode
    installed.autoconsent = sync(webView, installed.autoconsent, settings.answerCookiePopups) {
      autoconsentScript
    }
    installed.referrer = sync(
      webView,
      installed.referrer,
      settings.trimReferrers,
    ) { referrerScript }
    installed.gpc = sync(webView, installed.gpc, settings.sendDoNotTrack) { gpcScript }
    installed.tor = sync(webView, installed.tor, settings.torMode) { torScript }
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
