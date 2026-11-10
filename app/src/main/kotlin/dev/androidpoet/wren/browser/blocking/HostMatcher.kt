/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

/**
 * Host arithmetic for the blocker. Pure string work, deliberately free of Android types so the
 * rules can be tested directly - this is the code that decides whether a page loads or breaks.
 */
object HostMatcher {

  /**
   * Pulls the host out of a URL without paying for full URI parsing on every subresource: one
   * scan for the authority, one substring, and a lowercase pass only when there is a capital.
   */
  fun hostOf(url: String): String? {
    val scheme = url.indexOf("://")
    if (scheme < 0) return null
    var start = scheme + 3
    var authorityEnd = url.length
    for (i in start until url.length) {
      val c = url[i]
      if (c == '/' || c == '?' || c == '#') {
        authorityEnd = i
        break
      }
    }
    if (authorityEnd <= start) return null
    // Credentials sit before the last '@' of the authority, the port after the first ':'.
    val at = url.lastIndexOf('@', authorityEnd - 1)
    if (at >= start) start = at + 1
    var end = authorityEnd
    for (i in start until authorityEnd) {
      if (url[i] == ':') {
        end = i
        break
      }
    }
    while (end > start && url[end - 1] == '.') end--
    if (end <= start) return null
    var plain = true
    for (i in start until end) {
      val c = url[i]
      if (c in 'A'..'Z' || c.code > 127 || c.isWhitespace()) {
        plain = false
        break
      }
    }
    if (plain) return url.substring(start, end)
    return url.substring(start, end).lowercase().takeIf { it.isNotBlank() }
  }

  /**
   * True when [host] is [blocked] or a subdomain of it. Matching on label boundaries matters:
   * a naive `endsWith` would let `notdoubleclick.net` match `doubleclick.net`.
   */
  fun matches(host: String, blocked: String): Boolean =
    host == blocked || host.endsWith(".$blocked")

  /**
   * Every candidate to look up for [host], most specific first: `a.b.example.com` yields
   * itself, `b.example.com`, then `example.com`. Lets the blocklist be a flat hash set and still
   * match subdomains in a couple of lookups rather than a scan.
   */
  fun candidates(host: String): List<String> {
    val labels = host.split('.')
    if (labels.size < 2) return listOf(host)
    return (0..labels.size - 2).map { labels.subList(it, labels.size).joinToString(".") }
  }

  /**
   * The registrable domain, used to decide whether a request is first-party. It comes from
   * the Public Suffix List once that is loaded ([PublicSuffixes]); before that, a guess that
   * knows the `co.uk` / `com.au` shape, so no `.co.uk` tracker counts as first-party on every
   * `.co.uk` site.
   */
  fun registrableDomain(host: String): String {
    val start = registrableDomainStart(host)
    return if (start == 0) host else host.substring(start)
  }

  /** Where the registrable domain begins in [host]; the same answer as [registrableDomain]. */
  private fun registrableDomainStart(host: String): Int {
    // An address is its own site: the last two numbers of 10.0.2.2 name nothing.
    if (isIpAddress(host)) return 0
    PublicSuffixes.registrableDomainStart(host)?.let { return it }
    val dot1 = host.lastIndexOf('.')
    if (dot1 < 0) return 0
    val dot2 = host.lastIndexOf('.', dot1 - 1)
    if (dot2 < 0) return 0
    val tldLength = host.length - dot1 - 1
    if (tldLength == 2 && isSecondLevelSuffix(host, dot2 + 1, dot1)) {
      return host.lastIndexOf('.', dot2 - 1) + 1
    }
    return dot2 + 1
  }

  private fun isIpAddress(host: String): Boolean =
    host.indexOf(':') >= 0 || host.all { it == '.' || it in '0'..'9' }

  private fun isSecondLevelSuffix(host: String, from: Int, to: Int): Boolean {
    val length = to - from
    for (suffix in SECOND_LEVEL_SUFFIXES) {
      if (suffix.length == length && host.regionMatches(from, suffix, 0, length)) return true
    }
    return false
  }

  /** Labels that sit directly under a country code and act as a suffix: `co.uk`, `com.au`. */
  private val SECOND_LEVEL_SUFFIXES = arrayOf(
    "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "mil", "nom",
  )

  /** Compares the two registrable domains in place: this runs once per subresource. */
  fun isSameParty(requestHost: String, pageHost: String): Boolean {
    val requestStart = registrableDomainStart(requestHost)
    val pageStart = registrableDomainStart(pageHost)
    val length = requestHost.length - requestStart
    if (length != pageHost.length - pageStart) return false
    return requestHost.regionMatches(requestStart, pageHost, pageStart, length)
  }
}
