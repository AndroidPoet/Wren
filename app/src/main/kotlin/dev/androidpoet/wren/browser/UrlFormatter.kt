/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.util.Patterns
import dev.androidpoet.wren.core.model.SearchEngine
import java.net.URLEncoder

/**
 * Decides whether omnibox text is an address or a search. Getting this wrong in either direction
 * is the most visible bug a browser can have, so the rules are deliberately conservative: only
 * text that clearly names a host is treated as an address.
 */
object UrlFormatter {

  const val NEW_TAB_URL = "about:blank"

  private val SCHEME_WITH_AUTHORITY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
  private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

  /**
   * Schemes that are legal without `//`. Everything else that looks like `word:something` is far
   * more likely to be `localhost:8080` or a typed search than a URI.
   */
  private val AUTHORITYLESS_SCHEMES = setOf(
    "mailto", "tel", "sms", "intent", "market", "geo", "callto",
    "about", "data", "javascript", "file", "content",
  )

  private val NON_WEB_SCHEMES = setOf("mailto", "tel", "sms", "intent", "market", "geo", "callto")

  /** True for the only schemes another app may hand this browser to load. */
  fun isWebUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")
  }

  fun isScriptOrDataUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("javascript:") || lower.startsWith("data:")
  }

  fun toUrlOrSearch(input: String, engine: SearchEngine): String {
    val text = input.trim()
    if (text.isEmpty()) return NEW_TAB_URL
    if (SCHEME_WITH_AUTHORITY.containsMatchIn(text)) return text
    if (schemeOf(text) in AUTHORITYLESS_SCHEMES) return text
    if (isProbablyUrl(text)) return "https://$text"
    return search(text, engine)
  }

  fun search(query: String, engine: SearchEngine): String = search(query, engine.queryTemplate)

  /** Runs a search against a raw template that contains a single %s for the encoded query. */
  fun search(query: String, template: String): String =
    template.replace("%s", URLEncoder.encode(query, "UTF-8"))

  /** Address-or-search using a raw search template rather than a built-in engine. */
  fun toUrlOrSearch(input: String, searchTemplate: String): String {
    val text = input.trim()
    if (text.isEmpty()) return NEW_TAB_URL
    if (SCHEME_WITH_AUTHORITY.containsMatchIn(text)) return text
    if (schemeOf(text) in AUTHORITYLESS_SCHEMES) return text
    if (isProbablyUrl(text)) return "https://$text"
    return search(text, searchTemplate)
  }

  fun isProbablyUrl(text: String): Boolean {
    if (text.any { it.isWhitespace() }) return false
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
    if (host.equals("localhost", ignoreCase = true) || host.startsWith("localhost:")) return true
    if (!host.contains('.')) return false
    return Patterns.WEB_URL.matcher(text).matches()
  }

  fun isNonWebScheme(url: String): Boolean = schemeOf(url) in NON_WEB_SCHEMES

  /** The host, minus a leading `www.`, for the compact omnibox label. */
  fun prettyHost(url: String): String {
    if (url.isBlank() || url == NEW_TAB_URL) return ""
    val withoutScheme = url.substringAfter("://", url)
    val host = withoutScheme.substringBefore('/').substringBefore('?')
    return host.removePrefix("www.").ifBlank { url }
  }

  fun isSecure(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

  private fun schemeOf(text: String): String? =
    if (SCHEME_PREFIX.containsMatchIn(text)) text.substringBefore(':').lowercase() else null
}
