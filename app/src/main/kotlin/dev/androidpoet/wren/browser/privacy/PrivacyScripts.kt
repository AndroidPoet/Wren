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
   * Regenerated per process. Farbling has to be stable while a page is open - a page comparing
   * two reads of its own canvas must see the same answer - but must not survive a restart, or it
   * would become the identifier it exists to prevent.
   */
  private val sessionSeed: Int = SecureRandom().nextInt()

  private val fingerprintScript: String by lazy {
    read("fingerprint.js").replace("__SEED__", sessionSeed.toString())
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

  /** WebRTC removed and the clock moved to UTC; see the asset. */
  private val torScript: String by lazy { read("tor.js") }

  private val gpcScript: String by lazy { read("gpc.js") }

  private class Handlers {
    var tor: ScriptHandler? = null
    var fingerprint: ScriptHandler? = null
    var autoconsent: ScriptHandler? = null
    var referrer: ScriptHandler? = null
    var gpc: ScriptHandler? = null
  }

  private val handlers = WeakHashMap<WebView, Handlers>()

  fun warmUp() {
    fingerprintScript
    autoconsentScript
  }

  /** Brings the installed scripts in line with the current settings. */
  fun updateFor(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    val installed = handlers.getOrPut(webView) { Handlers() }

    installed.fingerprint = sync(webView, installed.fingerprint, settings.blockFingerprinting) {
      fingerprintScript
    }
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
