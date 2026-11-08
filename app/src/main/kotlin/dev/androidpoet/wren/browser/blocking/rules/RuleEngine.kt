/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

import dev.androidpoet.wren.browser.blocking.CompactHostSet

/**
 * Every pattern rule from the filter lists, indexed for one question per request: is this
 * address blocked?
 *
 * Each rule is filed under one token of its pattern - a run of letters and digits that any
 * matching address must contain whole - chosen as the rarest across the whole set. A request
 * is tokenised the same way and only the rules filed under its tokens are tried, so a page's
 * hundreds of requests each touch a handful of rules instead of tens of thousands.
 */
class RuleEngine private constructor(
  private val blocks: TokenIndex,
  private val blocksWithoutToken: Array<FilterRule>,
  private val blocksByPageDomain: Map<String, Array<FilterRule>>,
  private val exceptions: TokenIndex,
  private val exceptionsWithoutToken: Array<FilterRule>,
  private val exceptionsByPageDomain: Map<String, Array<FilterRule>>,
  private val exemptPages: CompactHostSet,
  private val genericHideExemptPages: CompactHostSet,
  private val importantBlocks: Array<FilterRule>,
  private val redirects: Array<FilterRule>,
  val size: Int,
) {

  /**
   * The untokenised rules that name the page's domain: "every gif on this site", and the like.
   * Most pages have none, and a page's set is fixed for as long as it is open, so the caller
   * looks it up once per page rather than once per request.
   */
  fun pageRulesFor(pageHost: String?): PageRules {
    if (pageHost == null || (blocksByPageDomain.isEmpty() && exceptionsByPageDomain.isEmpty())) {
      return PageRules.EMPTY
    }
    var blocks: ArrayList<FilterRule>? = null
    var exceptions: ArrayList<FilterRule>? = null
    var start = 0
    while (true) {
      val key = if (start == 0) pageHost else pageHost.substring(start)
      blocksByPageDomain[key]?.let { blocks = (blocks ?: ArrayList()).apply { addAll(it) } }
      exceptionsByPageDomain[key]?.let {
        exceptions = (exceptions ?: ArrayList()).apply { addAll(it) }
      }
      val dot = pageHost.indexOf('.', start)
      if (dot < 0) break
      start = dot + 1
    }
    if (blocks == null && exceptions == null) return PageRules.EMPTY
    return PageRules(
      blocks?.toTypedArray() ?: PageRules.NONE,
      exceptions?.toTypedArray() ?: PageRules.NONE,
    )
  }

  /** Shape of the index, for the benchmark: untokenised block/exception rules, bucket sizes. */
  internal fun describe(): String {
    val sizes = blocks.bucketSizes()
    return "untokenised blocks=${blocksWithoutToken.size} " +
      "exceptions=${exceptionsWithoutToken.size}; " +
      "block buckets=${sizes.size} max=${sizes.maxOrNull() ?: 0} " +
      "top=${sizes.sortedDescending().take(8)}\n" +
      "untokenised: " + (blocksWithoutToken + exceptionsWithoutToken).joinToString(" | ")
  }

  /** How many rules a request would have examined, for the benchmark. */
  internal fun rulesExamined(request: RequestContext, pageRules: PageRules): Int {
    var n = blocksWithoutToken.size + pageRules.blocks.size
    for (i in 0 until request.tokenCount) n += blocks.get(request.tokens[i])?.size ?: 0
    return n
  }

  /** True when a `$document` exception turns blocking off for pages on [pageHost]. */
  fun isPageExempt(pageHost: String): Boolean = exemptPages.matchesSuffixOf(pageHost)

  /** True where a list turned generic hiding off (`$generichide`); specific rules still apply. */
  fun isGenericHideExempt(pageHost: String): Boolean =
    genericHideExemptPages.matchesSuffixOf(pageHost)

  /**
   * The final word on a request. [alreadyBlocked] carries the host list's verdict, which the
   * exceptions here can still overturn; a block rule marked `$important` cannot be overturned.
   */
  fun shouldBlock(
    request: RequestContext,
    typeMask: Int,
    thirdParty: Boolean,
    pageHost: String?,
    alreadyBlocked: Boolean,
    pageRules: PageRules = pageRulesFor(pageHost),
  ): Boolean = decide(request, typeMask, thirdParty, pageHost, alreadyBlocked, pageRules) != null

  /**
   * The rule that blocks this request, [HOST_BLOCK] when the host set did and no exception
   * objects, or null to let it through. An `$important` rule beats every exception, also when
   * the host set made the first call.
   */
  fun decide(
    request: RequestContext,
    typeMask: Int,
    thirdParty: Boolean,
    pageHost: String?,
    alreadyBlocked: Boolean,
    pageRules: PageRules = pageRulesFor(pageHost),
  ): FilterRule? {
    var block: FilterRule = HOST_BLOCK
    if (!alreadyBlocked) {
      block = firstMatch(
        blocks,
        blocksWithoutToken,
        pageRules.blocks,
        request,
        typeMask,
        thirdParty,
        pageHost,
      ) ?: return null
      if (block.isImportant) return withRedirect(block, request, typeMask, thirdParty, pageHost)
    }
    val exception = firstMatch(
      exceptions,
      exceptionsWithoutToken,
      pageRules.exceptions,
      request,
      typeMask,
      thirdParty,
      pageHost,
    ) ?: return withRedirect(block, request, typeMask, thirdParty, pageHost)
    if (exception.isImportant) return null
    // An ordinary exception yields to any important block, not only to the one found first:
    // `$important` is what a list author writes precisely to beat an `@@` elsewhere.
    for (rule in importantBlocks) {
      if (rule.appliesTo(typeMask, thirdParty, pageHost) &&
        rule.pattern.matches(request.url, request.hostStart, request.hostEnd)
      ) {
        return rule
      }
    }
    return null
  }

  /**
   * The request is blocked, by the host set or by a rule of its own; a redirect rule for the
   * same URL still decides what the page gets back, because the stand-in is what keeps the
   * page working. Only blocked requests pay for this scan.
   */
  private fun withRedirect(
    block: FilterRule,
    request: RequestContext,
    typeMask: Int,
    thirdParty: Boolean,
    pageHost: String?,
  ): FilterRule {
    if (block.redirect != null) return block
    for (rule in redirects) {
      if (rule.appliesTo(typeMask, thirdParty, pageHost) &&
        rule.pattern.matches(request.url, request.hostStart, request.hostEnd)
      ) {
        return rule
      }
    }
    return block
  }

  private fun firstMatch(
    index: TokenIndex,
    untokenised: Array<FilterRule>,
    forPage: Array<FilterRule>,
    request: RequestContext,
    typeMask: Int,
    thirdParty: Boolean,
    pageHost: String?,
  ): FilterRule? {
    val url = request.url
    for (rule in untokenised) {
      if (rule.appliesTo(typeMask, thirdParty, pageHost) &&
        rule.pattern.matches(url, request.hostStart, request.hostEnd)
      ) {
        return rule
      }
    }
    for (rule in forPage) {
      if (rule.appliesTo(typeMask, thirdParty, pageHost) &&
        rule.pattern.matches(url, request.hostStart, request.hostEnd)
      ) {
        return rule
      }
    }
    val tokens = request.tokens
    val starts = request.tokenStarts
    for (i in 0 until request.tokenCount) {
      val bucket = index.get(tokens[i]) ?: continue
      val tokenStart = starts[i]
      for (rule in bucket) {
        if (!rule.appliesTo(typeMask, thirdParty, pageHost)) continue
        val offset = rule.tokenOffset
        val hit = if (offset >= 0) {
          // The token was found here, so the pattern can only start this far before it.
          val at = tokenStart - offset
          at >= 0 && rule.pattern.matchesAligned(url, at, request.hostStart, request.hostEnd)
        } else {
          rule.pattern.matches(url, request.hostStart, request.hostEnd)
        }
        if (hit) return rule
      }
    }
    return null
  }

  /** Accumulates list lines, then files every rule under its best token. */
  class Builder {
    private val rules = ArrayList<FilterRule>()
    private val sources = ArrayList<String>()
    private val exemptHosts = HashSet<String>()
    private val genericHideHosts = HashSet<String>()
    private val badFilters = HashSet<String>()

    fun add(line: String): Builder {
      FilterRule.documentExceptionHost(line)?.let {
        exemptHosts.add(it)
        return this
      }
      FilterRule.genericHideExceptionHost(line)?.let {
        genericHideHosts.add(it)
        return this
      }
      FilterRule.badFilterTarget(line)?.let {
        badFilters.add(it)
        return this
      }
      FilterRule.parse(line)?.let {
        rules.add(it)
        sources.add(line.trimSpaces())
      }
      return this
    }

    fun addAll(lines: Sequence<String>): Builder {
      lines.forEach { add(it) }
      return this
    }

    fun build(): RuleEngine {
      if (badFilters.isNotEmpty()) {
        val kept = rules.indices.filter { sources[it] !in badFilters }
        val survivors = kept.map(rules::get)
        val survivorSources = kept.map(sources::get)
        rules.clear()
        rules.addAll(survivors)
        sources.clear()
        sources.addAll(survivorSources)
      }
      val candidates = rules.map { Tokeniser.candidateTokens(it.pattern) }
      val frequency = HashMap<Int, Int>()
      for (tokens in candidates) {
        for (token in tokens) frequency[token.hash] = (frequency[token.hash] ?: 0) + 1
      }
      val blockBuckets = HashMap<Int, MutableList<FilterRule>>()
      val exceptionBuckets = HashMap<Int, MutableList<FilterRule>>()
      val blockRest = ArrayList<FilterRule>()
      val exceptionRest = ArrayList<FilterRule>()
      val blockByDomain = HashMap<String, MutableList<FilterRule>>()
      val exceptionByDomain = HashMap<String, MutableList<FilterRule>>()
      rules.forEachIndexed { i, rule ->
        if (rule.redirectOnly) return@forEachIndexed
        val tokens = candidates[i]
        var best = 0
        var bestFrequency = Int.MAX_VALUE
        var bestOffset = -1
        for (token in tokens) {
          val f = frequency[token.hash] ?: 0
          if (f < bestFrequency) {
            bestFrequency = f
            best = token.hash
            bestOffset = if (token.isFixed) token.offset else -1
          }
        }
        rule.tokenOffset = bestOffset
        val buckets = if (rule.isException) exceptionBuckets else blockBuckets
        val rest = if (rule.isException) exceptionRest else blockRest
        val byDomain = if (rule.isException) exceptionByDomain else blockByDomain
        val domains = rule.domains
        when {
          best != 0 -> buckets.getOrPut(best) { ArrayList() }.add(rule)
          // A rule with no usable token but a domain list only ever applies on those sites.
          domains != null -> for (domain in domains) {
            byDomain.getOrPut(domain) { ArrayList() }.add(rule)
          }
          else -> rest.add(rule)
        }
      }
      return RuleEngine(
        blocks = TokenIndex.of(blockBuckets),
        blocksWithoutToken = blockRest.toTypedArray(),
        blocksByPageDomain = blockByDomain.mapValues { it.value.toTypedArray() },
        exceptions = TokenIndex.of(exceptionBuckets),
        exceptionsWithoutToken = exceptionRest.toTypedArray(),
        exceptionsByPageDomain = exceptionByDomain.mapValues { it.value.toTypedArray() },
        exemptPages = CompactHostSet.of(exemptHosts),
        genericHideExemptPages = CompactHostSet.of(genericHideHosts),
        importantBlocks = rules.filter { it.isImportant && !it.isException }.toTypedArray(),
        redirects = rules.filter { it.redirect != null }.toTypedArray(),
        size = rules.size + exemptHosts.size + genericHideHosts.size,
      )
    }
  }

  companion object {
    val EMPTY: RuleEngine = Builder().build()

    /** Stands for "the host set blocked it" where a rule is expected. */
    val HOST_BLOCK = FilterRule(
      pattern = UrlPattern.parse(""),
      types = RequestType.ALL,
      party = FilterRule.ANY_PARTY,
      domains = null,
      excludedDomains = null,
      isException = false,
      isImportant = false,
    )

    fun parse(lines: Sequence<String>): RuleEngine = Builder().addAll(lines).build()
  }
}

/** The untokenised rules that apply on one page; see [RuleEngine.pageRulesFor]. */
class PageRules(val blocks: Array<FilterRule>, val exceptions: Array<FilterRule>) {
  companion object {
    val NONE: Array<FilterRule> = emptyArray()
    val EMPTY = PageRules(NONE, NONE)
  }
}

/** A request address prepared once for however many rules look at it. */
class RequestContext(rawUrl: String) {
  val url: String = run {
    val bounded = if (rawUrl.length > MAX_URL_LENGTH) {
      rawUrl.substring(
        0,
        MAX_URL_LENGTH,
      )
    } else {
      rawUrl
    }
    // Most addresses are already lowercase; a copy is only paid for when there is a capital.
    if (needsLowercase(bounded)) bounded.lowercase() else bounded
  }
  val hostStart: Int
  val hostEnd: Int

  /** The host in lowercase, without credentials, port or trailing dot; empty without a scheme. */
  val host: String
  val tokens = IntArray(MAX_TOKENS)

  /** Where each of [tokens] begins in [url]. */
  val tokenStarts = IntArray(MAX_TOKENS)
  val tokenCount: Int

  init {
    val scheme = url.indexOf("://")
    var start = if (scheme < 0) 0 else scheme + 3
    var authorityEnd = url.length
    for (i in start until url.length) {
      val c = url[i]
      if (c == '/' || c == '?' || c == '#') {
        authorityEnd = i
        break
      }
    }
    if (authorityEnd > start) {
      val at = url.lastIndexOf('@', authorityEnd - 1)
      if (at >= start) start = at + 1
    }
    var end = authorityEnd
    for (i in start until authorityEnd) {
      if (url[i] == ':') {
        end = i
        break
      }
    }
    hostStart = start
    hostEnd = end
    var trimmed = end
    while (trimmed > start && url[trimmed - 1] == '.') trimmed--
    host = if (scheme < 0 || trimmed <= start) "" else url.substring(start, trimmed)
    tokenCount = Tokeniser.tokenise(url, tokens, tokenStarts)
  }

  private companion object {
    const val MAX_URL_LENGTH = 8192
    const val MAX_TOKENS = 128

    fun needsLowercase(text: String): Boolean {
      for (c in text) if (c in 'A'..'Z' || c.code > 127) return true
      return false
    }
  }
}

/** Splits addresses and patterns into the letter-and-digit runs the index is keyed on. */
internal object Tokeniser {

  fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

  /** Writes each token's hash into [out] and its start into [starts]; returns how many fit. */
  fun tokenise(text: String, out: IntArray, starts: IntArray): Int {
    var count = 0
    var hash = 0
    var tokenStart = -1
    for (i in text.indices) {
      val c = text[i]
      if (isTokenChar(c)) {
        if (tokenStart < 0) tokenStart = i
        hash = hash * 31 + c.code
      } else if (tokenStart >= 0) {
        out[count] = if (hash == 0) 1 else hash
        starts[count] = tokenStart
        count++
        if (count == out.size) return count
        hash = 0
        tokenStart = -1
      }
    }
    if (tokenStart >= 0 && count < out.size) {
      out[count] = if (hash == 0) 1 else hash
      starts[count] = tokenStart
      count++
    }
    return count
  }

  /** A token a rule may be filed under: its hash, where it starts in the pattern text, and
   * whether nothing before it can stretch (no `*`), so a match has a fixed alignment. */
  class Candidate(val hash: Int, val offset: Int, val isFixed: Boolean)

  /**
   * Tokens of a pattern that every matching address must contain whole. A run touching an
   * unanchored edge or a `*` may be only part of an address token, so it does not qualify.
   */
  fun candidateTokens(pattern: UrlPattern): List<Candidate> {
    val text = pattern.text
    val result = ArrayList<Candidate>()
    var i = 0
    var starSeen = false
    while (i < text.length) {
      if (!isTokenChar(text[i])) {
        if (text[i] == '*') starSeen = true
        i++
        continue
      }
      var j = i
      var hash = 0
      while (j < text.length && isTokenChar(text[j])) {
        hash = hash * 31 + text[j].code
        j++
      }
      val leftBound = if (i == 0) pattern.anchor != UrlPattern.NONE else text[i - 1] != '*'
      val rightBound = if (j == text.length) pattern.endAnchored else text[j] != '*'
      if (leftBound && rightBound) {
        result.add(Candidate(if (hash == 0) 1 else hash, offset = i, isFixed = !starSeen))
      }
      i = j
    }
    return result
  }
}

/** An open-addressed int-keyed map, so a lookup per token boxes nothing. */
internal class TokenIndex private constructor(
  private val keys: IntArray,
  private val values: Array<Array<FilterRule>?>,
) {
  private val mask = keys.size - 1

  fun get(token: Int): Array<FilterRule>? {
    if (keys.isEmpty()) return null
    var slot = mix(token) and mask
    while (true) {
      val key = keys[slot]
      if (key == token) return values[slot]
      if (key == 0) return null
      slot = (slot + 1) and mask
    }
  }

  fun bucketSizes(): List<Int> = values.mapNotNull { it?.size }

  companion object {
    fun of(buckets: Map<Int, List<FilterRule>>): TokenIndex {
      if (buckets.isEmpty()) return TokenIndex(IntArray(0), arrayOfNulls(0))
      var capacity = 16
      while (capacity < buckets.size * 2) capacity = capacity shl 1
      val keys = IntArray(capacity)
      val values = arrayOfNulls<Array<FilterRule>>(capacity)
      val mask = capacity - 1
      for ((token, rules) in buckets) {
        var slot = mix(token) and mask
        while (keys[slot] != 0) slot = (slot + 1) and mask
        keys[slot] = token
        values[slot] = rules.toTypedArray()
      }
      return TokenIndex(keys, values)
    }

    private fun mix(token: Int): Int {
      var h = token * -0x61c88647
      h = h xor (h ushr 16)
      return h
    }
  }
}
