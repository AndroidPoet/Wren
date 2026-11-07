/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.BuildConfig
import dev.androidpoet.wren.browser.blocking.rules.BlockEngine
import dev.androidpoet.wren.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the filter lists' cosmetic side onto each page: scriptlets through a document-start
 * script that carries the whole table (they must run before the page's own code), and
 * element-hiding selectors through messages: the page asks by host for its specific rules, then
 * reports the ids and classes its DOM contains and receives the generic rules keyed by those.
 *
 * A document-start script is fixed for the lifetime of the document, so a change to the
 * lists or the settings only takes effect on the next load, as in every browser.
 */
@Singleton
class CosmeticFilter @Inject constructor(
  private val application: Application,
  private val settings: SettingsStore,
  private val blocklist: Blocklist,
) {

  private class Installed(val handler: ScriptHandler, val version: Int)

  /** A document-start script belongs to one WebView, so every tab needs its own handle. */
  private val handlers = WeakHashMap<WebView, Installed>()
  private val listeners = WeakHashMap<WebView, Boolean>()

  private val configured = WeakHashMap<WebView, String?>()

  private val mainHandler = Handler(Looper.getMainLooper())

  private val library: String by lazy { readAsset(LIBRARY_ASSET) }
  private val injector: String by lazy { readAsset(INJECTOR_ASSET) }

  @Volatile
  private var cachedScript: Pair<Int, String>? = null

  init {
    blocklist.addEngineListener(::onEngineReady)
  }

  private fun onEngineReady(engine: BlockEngine) {
    scriptFor(engine)
    mainHandler.post {
      for ((view, pageUrl) in configured.entries.toList()) updateFor(view, pageUrl)
    }
  }

  /** Installs the reply channel the page uses to ask for its selectors. Once per WebView. */
  fun install(webView: WebView) {
    if (listeners.containsKey(webView)) return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    runCatching {
      WebViewCompat.addWebMessageListener(
        webView,
        CHANNEL,
        ALLOWED_ORIGINS,
      ) { _, message, _, _, reply -> respond(message.data.orEmpty(), reply) }
      listeners[webView] = true
    }.onFailure { Timber.w(it, "could not install the cosmetic channel") }
  }

  private fun respond(raw: String, reply: JavaScriptReplyProxy) {
    val message = runCatching { JSONObject(raw) }.getOrNull()
    val host = (message?.optString("h") ?: raw).trim().lowercase()
    if (host.isEmpty() || host.length > 253) return
    if (!settings.blockAds || settings.isAllowlisted(host)) return
    val cosmetics = blocklist.engineOrNull?.cosmetics ?: return
    val payload = if (message != null && (message.has("i") || message.has("c"))) {
      cosmetics.surveyPayloadFor(
        ids = message.optJSONArray("i").tokens(),
        classes = message.optJSONArray("c").tokens(),
      )
    } else {
      cosmetics.hidingPayloadFor(host)
    }
    runCatching { reply.postMessage(payload) }
      .onFailure { Timber.w(it, "could not send cosmetic rules") }
  }

  private fun JSONArray?.tokens(): List<String> {
    if (this == null || length() == 0) return emptyList()
    val out = ArrayList<String>(minOf(length(), MAX_SURVEY_TOKENS))
    for (i in 0 until minOf(length(), MAX_SURVEY_TOKENS)) {
      val token = optString(i, null) ?: continue
      if (token.isNotEmpty() && token.length <= MAX_TOKEN_LENGTH) out.add(token)
    }
    return out
  }

  /** Adds, refreshes or removes the document-start script to match the current settings. */
  fun updateFor(webView: WebView, pageUrl: String?) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    install(webView)
    configured[webView] = pageUrl

    val host = pageUrl?.let(HostMatcher::hostOf)
    val wanted = settings.blockAds && (host == null || !settings.isAllowlisted(host))
    val installed = handlers[webView]
    val engine = blocklist.engineOrNull

    if (wanted && engine != null && (installed == null || installed.version != engine.version)) {
      installed?.let { runCatching { it.handler.remove() } }
      runCatching {
        WebViewCompat.addDocumentStartJavaScript(webView, scriptFor(engine), ALLOWED_ORIGINS)
      }.onFailure { Timber.w(it, "could not install cosmetic filtering") }
        .getOrNull()
        ?.let { handlers[webView] = Installed(it, engine.version) }
    } else if (!wanted && installed != null) {
      runCatching { installed.handler.remove() }
      handlers.remove(webView)
    }
  }

  /**
   * One closure per engine version: the reply channel taken off `window` under its random
   * name, the library, the scriptlet table (parsed from a JSON string, which the engine does
   * faster than an object literal of this size), then the injector. Nothing is left on the
   * page's global object, so a page cannot tell this browser from any other Chromium WebView.
   */
  private fun scriptFor(engine: BlockEngine): String {
    cachedScript?.let { if (it.first == engine.version) return it.second }
    val table = JSONObject.quote(engine.cosmetics.scriptletTableJson())
    val name = JSONObject.quote(CHANNEL)
    val script = buildString(library.length + table.length + injector.length + 256) {
      append("(function () {\n")
      append("var DEBUG = ").append(BuildConfig.DEBUG).append(";\n")
      append("var CHANNEL = null;\n")
      append("try { CHANNEL = window[").append(name).append("] || null; ")
      append("delete window[").append(name).append("]; } catch (e) {}\n")
      append(library).append('\n')
      append("var SCRIPTLET_TABLE = JSON.parse(").append(table).append(");\n")
      append(injector)
      append("\n})();\n")
    }
    cachedScript = engine.version to script
    return script
  }

  private fun readAsset(name: String): String =
    application.assets.open(name).bufferedReader().use { it.readText() }

  private companion object {
    const val LIBRARY_ASSET = "scriptlets.js"
    const val INJECTOR_ASSET = "cosmetic_filter.js"

    /**
     * The message channel's global name. Random per process and deleted from `window` by the
     * document-start script as soon as it has a reference, so no page sees a name that says
     * which browser it is in, nor one that stays the same across sessions.
     */
    val CHANNEL: String = "w" + UUID.randomUUID().toString().replace("-", "")
    const val MAX_SURVEY_TOKENS = 4000
    const val MAX_TOKEN_LENGTH = 128
    val ALLOWED_ORIGINS = setOf("*")
  }
}
