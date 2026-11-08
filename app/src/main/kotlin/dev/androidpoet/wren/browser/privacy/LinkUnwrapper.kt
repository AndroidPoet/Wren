/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

import dev.androidpoet.wren.browser.blocking.HostMatcher
import java.net.URLDecoder

/**
 * Takes the detour out of a link before the first request leaves.
 *
 * Two kinds. A bounce redirector (`google.com/url?q=`, `l.facebook.com/l.php?u=`,
 * Outlook's safelinks) exists to record the click before sending it on; Brave calls skipping
 * it debouncing. An AMP cache address (`google.com/amp/s/…`, `*.cdn.ampproject.org/c/s/…`)
 * serves a publisher's page from Google's servers; DuckDuckGo calls going to the publisher
 * instead de-AMPing. Both are rewritten only when the destination inside them is a plain
 * web address, and at most a few hops deep, so a crafted link cannot send the rewrite into a
 * loop. Everything else is left alone: a redirector that signs or encrypts its target cannot
 * be unwrapped, and guessing would break the link.
 */
object LinkUnwrapper {

  private class Redirector(val host: String, val pathPrefix: String, val parameters: List<String>)

  private val REDIRECTORS = listOf(
    Redirector("google.com", "/url", listOf("q", "url")),
    Redirector("www.google.com", "/url", listOf("q", "url")),
    Redirector("l.facebook.com", "/l.php", listOf("u")),
    Redirector("lm.facebook.com", "/l.php", listOf("u")),
    Redirector("l.messenger.com", "/l.php", listOf("u")),
    Redirector("l.instagram.com", "/", listOf("u")),
    Redirector("out.reddit.com", "/", listOf("url")),
    Redirector("www.youtube.com", "/redirect", listOf("q")),
    Redirector("youtube.com", "/redirect", listOf("q")),
    Redirector("t.umblr.com", "/redirect", listOf("z")),
    Redirector("exit.sc", "/", listOf("url")),
    Redirector("steamcommunity.com", "/linkfilter/", listOf("url", "u")),
    Redirector("href.li", "/", emptyList()),
    Redirector("slack-redir.net", "/link", listOf("url")),
    Redirector("www.linkedin.com", "/redir/redirect", listOf("url")),
    Redirector("disq.us", "/url", listOf("url")),
  )

  private const val SAFELINKS_SUFFIX = ".safelinks.protection.outlook.com"
  private const val AMP_CACHE_SUFFIX = ".cdn.ampproject.org"
  private const val MAX_HOPS = 3

  /** The address a bounce redirector or AMP cache would eventually have sent the user to. */
  fun unwrap(url: String): String {
    var current = url
    repeat(MAX_HOPS) {
      val next = hop(current) ?: return current
      if (next == current) return current
      current = next
    }
    return current
  }

  private fun hop(url: String): String? {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    val host = HostMatcher.hostOf(url)?.lowercase() ?: return null
    val pathStart = url.indexOf('/', url.indexOf("://") + 3)
    val path = if (pathStart >= 0) url.substring(pathStart) else "/"
    deAmp(host, path)?.let { return it }
    if (host.endsWith(SAFELINKS_SUFFIX)) return webTarget(parameter(path, "url"))
    for (redirector in REDIRECTORS) {
      if (host != redirector.host || !path.startsWith(redirector.pathPrefix)) continue
      if (redirector.parameters.isEmpty()) {
        // href.li/?https://… carries the target as the whole query.
        return webTarget(path.substringAfter('?', "").substringBefore('#'))
      }
      for (name in redirector.parameters) {
        webTarget(parameter(path, name))?.let { return it }
      }
    }
    return null
  }

  /**
   * `google.com/amp/s/host/path` and `host-com.cdn.ampproject.org/c/s/host/path` both carry
   * the publisher's address in the path, after the `s/` that marks it as https.
   */
  private fun deAmp(host: String, path: String): String? {
    val marker = when {
      host == "google.com" || host == "www.google.com" || host.endsWith(".google.com") ->
        if (path.startsWith("/amp/")) "/amp/" else return null
      host.endsWith(AMP_CACHE_SUFFIX) -> when {
        path.startsWith("/c/") -> "/c/"
        path.startsWith("/v/") -> "/v/"
        else -> return null
      }
      else -> return null
    }
    var rest = path.substring(marker.length)
    val secure = rest.startsWith("s/")
    if (secure) rest = rest.substring(2)
    if (rest.isEmpty() || rest.startsWith("/")) return null
    val decoded = runCatching { URLDecoder.decode(rest, "UTF-8") }.getOrNull() ?: rest
    return webTarget((if (secure) "https://" else "http://") + decoded)
  }

  private fun parameter(path: String, name: String): String? {
    val query = path.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
      if (pair.substringBefore('=') != name) continue
      val raw = pair.substringAfter('=', "")
      return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: raw
    }
    return null
  }

  private fun webTarget(candidate: String?): String? {
    if (candidate.isNullOrEmpty()) return null
    val decoded = if (candidate.startsWith("http", ignoreCase = true)) {
      candidate
    } else {
      runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrNull() ?: return null
    }
    if (!decoded.startsWith("https://", ignoreCase = true) &&
      !decoded.startsWith("http://", ignoreCase = true)
    ) {
      return null
    }
    // A target with no host, or with whitespace or control characters, is not a link.
    if (HostMatcher.hostOf(decoded) == null) return null
    if (decoded.any { it <= ' ' }) return null
    return decoded
  }
}
