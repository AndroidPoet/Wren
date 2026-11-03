/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

/**
 * One network rule from an EasyList-style filter list: an address pattern plus the conditions
 * under which it applies. Rules the engine cannot honour faithfully (popups, CSP injection,
 * redirects, regexes) parse to null rather than to an approximation, so a list never blocks
 * something its author did not ask for.
 */
class FilterRule(
  val pattern: UrlPattern,
  val types: Int,
  val party: Int,
  val domains: Array<String>?,
  val excludedDomains: Array<String>?,
  val isException: Boolean,
  val isImportant: Boolean,
) {

  /**
   * Where the token this rule is filed under starts inside the pattern text, when nothing
   * before it can stretch: lets the engine test the pattern at one aligned position of the
   * address instead of searching the whole address for it. -1 when unknown or not fixed.
   */
  var tokenOffset: Int = -1
    internal set

  /** True when the rule's conditions hold for this request; the pattern is checked separately. */
  fun appliesTo(typeMask: Int, thirdParty: Boolean, pageHost: String?): Boolean {
    if (types and typeMask == 0) return false
    if (party == THIRD_PARTY && !thirdParty) return false
    if (party == FIRST_PARTY && thirdParty) return false
    if (domains != null && (pageHost == null || !isUnder(pageHost, domains))) return false
    if (excludedDomains != null && pageHost != null && isUnder(pageHost, excludedDomains)) {
      return false
    }
    return true
  }

  override fun toString(): String = (if (isException) "@@" else "") + pattern.toString()

  companion object {
    const val ANY_PARTY = 0
    const val THIRD_PARTY = 1
    const val FIRST_PARTY = 2

    private val UNSUPPORTED_OPTIONS = hashSetOf(
      "popup", "popunder", "generichide", "ghide", "genericblock", "elemhide", "ehide",
      "specifichide", "shide", "csp", "redirect", "redirect-rule", "rewrite", "removeparam",
      "queryprune", "header", "method", "denyallow", "to", "all", "inline-script",
      "inline-font", "badfilter", "empty", "mp4", "object-subrequest", "webrtc", "cname",
      "strict1p", "strict3p", "permissions", "replace", "urltransform", "urlskip", "ipaddress",
      "reason", "document", "doc",
    )

    fun isUnder(host: String, domains: Array<String>): Boolean {
      for (domain in domains) {
        if (host.length < domain.length) continue
        if (!host.endsWith(domain)) continue
        val boundary = host.length - domain.length - 1
        if (boundary < 0 || host[boundary] == '.') return true
      }
      return false
    }

    /** True for lines that are comments, headers or cosmetic rules: not network rules at all. */
    fun isNetworkRule(line: String): Boolean {
      if (line.isEmpty() || line[0] == '!' || line[0] == '[') return false
      val hash = line.indexOf('#')
      if (hash < 0) return true
      // Cosmetic separators are ##, #@#, #?#, #$#, #%# and their combinations.
      val next = line.getOrNull(hash + 1) ?: return true
      if (next == '#') return false
      return !(next in "@?$%" && line.getOrNull(hash + 2) == '#')
    }

    /**
     * The host a `@@||host^$document` exception exempts as a whole, or null when the line is
     * anything else. Such rules turn blocking off for the page, which the engine handles by
     * host rather than by pattern.
     */
    fun documentExceptionHost(raw: String): String? {
      val line = raw.trim()
      if (!line.startsWith("@@||")) return null
      val dollar = line.lastIndexOf('$')
      if (dollar < 0) return null
      val options = line.substring(dollar + 1).split(',')
      if (options.none { it == "document" || it == "doc" }) return null
      if (options.any { it != "document" && it != "doc" && it != "important" }) return null
      val body = line.substring(4, dollar).removeSuffix("^").lowercase()
      if (body.isEmpty() || body.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) {
        return null
      }
      return body
    }

    fun parse(raw: String): FilterRule? {
      val line = raw.trim()
      if (!isNetworkRule(line)) return null
      var body = line
      var exception = false
      if (body.startsWith("@@")) {
        exception = true
        body = body.substring(2)
      }
      var options: String? = null
      val dollar = body.lastIndexOf('$')
      if (dollar >= 0) {
        options = body.substring(dollar + 1)
        body = body.substring(0, dollar)
      }
      if (body.length > 1 && body.startsWith("/") && body.endsWith("/")) return null
      var included: MutableList<String>? = null
      var excluded: MutableList<String>? = null
      var party = ANY_PARTY
      var positiveTypes = 0
      var negativeTypes = 0
      var important = false
      if (options != null) {
        for (option in options.split(',')) {
          val negated = option.startsWith("~")
          val named = if (negated) option.substring(1) else option
          val eq = named.indexOf('=')
          val key = if (eq >= 0) named.substring(0, eq) else named
          val value = if (eq >= 0) named.substring(eq + 1) else ""
          when (key) {
            "third-party", "3p" -> party = if (negated) FIRST_PARTY else THIRD_PARTY
            "first-party", "1p" -> party = if (negated) THIRD_PARTY else FIRST_PARTY
            "important" -> important = true
            "match-case" -> Unit
            "domain", "from" -> {
              if (value.isEmpty()) return null
              for (entry in value.split('|')) {
                val negatedEntry = entry.startsWith("~")
                val domain = (if (negatedEntry) entry.substring(1) else entry).lowercase()
                if (domain.isEmpty() || domain.contains('*')) continue
                if (negatedEntry) {
                  excluded = (excluded ?: ArrayList()).also { it.add(domain) }
                } else {
                  included = (included ?: ArrayList()).also { it.add(domain) }
                }
              }
              // Every entry was one the engine cannot express: the rule would apply everywhere.
              if (included == null && excluded == null) return null
              if (included == null && !value.contains('~')) return null
            }
            in UNSUPPORTED_OPTIONS -> return null
            else -> {
              val type = RequestType.fromOption(key) ?: return null
              if (negated) {
                negativeTypes = negativeTypes or type
              } else {
                positiveTypes = positiveTypes or type
              }
            }
          }
        }
      }
      val types = when {
        positiveTypes != 0 -> positiveTypes and negativeTypes.inv()
        negativeTypes != 0 -> RequestType.ALL and negativeTypes.inv()
        else -> RequestType.ALL
      }
      if (types == 0) return null
      val pattern = UrlPattern.parse(body)
      val unconditional = types == RequestType.ALL && party == ANY_PARTY && included == null
      if (pattern.text.isEmpty() && unconditional) return null
      return FilterRule(
        pattern = pattern,
        types = types,
        party = party,
        domains = included?.toTypedArray(),
        excludedDomains = excluded?.toTypedArray(),
        isException = exception,
        isImportant = important,
      )
    }
  }
}
