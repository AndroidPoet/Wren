/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import dev.androidpoet.wren.network.SafeHttp
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tor Browser's Onion-Location: a site served over HTTPS can name its onion service in an
 * `Onion-Location` response header, and the browser offers to go there. WebView does not hand
 * the page's response headers to the app, so the header is read from one HEAD request per site
 * and Tor identity, sent through Tor like the page itself (the tunnel port isolates by
 * destination, so it travels the circuit the page already used) and carrying the page's user
 * agent. Nothing is asked with Tor off, of onion sites, or of pages without HTTPS, for which
 * Tor Browser ignores the header too.
 */
@Singleton
class OnionLocation @Inject constructor(
  private val http: SafeHttp,
  private val settings: SettingsStore,
) {

  /** Host to the onion address it named, or "" for none; the sites already asked. */
  private val answers = ConcurrentHashMap<String, String>()

  /** Sites being asked right now; a second page finishing meanwhile does not ask again. */
  private val asking = ConcurrentHashMap.newKeySet<String>()

  /** Sites whose onion address was already offered; each is offered once. */
  private val offered = ConcurrentHashMap.newKeySet<String>()

  /**
   * The onion address [pageUrl]'s site names, the first time one of its pages asks; null after
   * that, for sites that name none, and when the answer could not be had.
   */
  suspend fun offerFor(pageUrl: String, userAgent: String): String? {
    val host = runCatching { URI(pageUrl).host?.lowercase() }.getOrNull() ?: return null
    if (host in offered) return null
    return lookup(pageUrl, userAgent)?.also { offered += host }
  }

  /** The onion address [pageUrl]'s site names, asked once per site, or null. */
  suspend fun lookup(pageUrl: String, userAgent: String): String? {
    if (!settings.torMode) return null
    val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || isOnion(host)) return null
    answers[host]?.let { return it.ifEmpty { null } }
    if (!asking.add(host)) return null
    val answer = withContext(Dispatchers.IO) {
      runCatching {
        val request = Request.Builder().url(pageUrl).head().header("User-Agent", userAgent).build()
        http.client.newCall(request).execute().use { it.header(HEADER) }
      }.onFailure { Timber.d(it, "onion-location check failed") }
    }
    asking.remove(host)
    // A failed request is not an answer: the site is asked again on a later visit.
    if (answer.isFailure) return null
    val found = answer.getOrNull()?.let(::validOnion)
    answers[host] = found.orEmpty()
    return found
  }

  /** Forgotten with the identity, so a new one does not reveal the old one's sites. */
  fun forget() {
    answers.clear()
    offered.clear()
  }

  companion object {
    const val HEADER = "Onion-Location"

    /** The header value when it is an http(s) address on a v3 onion service, else null. */
    fun validOnion(value: String): String? {
      val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
      val scheme = uri.scheme?.lowercase()
      val host = uri.host?.lowercase() ?: return null
      if (scheme != "http" && scheme != "https") return null
      val label = host.removeSuffix(".onion").substringAfterLast('.')
      if (!host.endsWith(".onion") || label.length != V3_LENGTH) return null
      if (!label.all { it in 'a'..'z' || it in '2'..'7' }) return null
      return uri.toString()
    }

    fun isOnion(host: String): Boolean = host == "onion" || host.endsWith(".onion")

    private const val V3_LENGTH = 56
  }
}
