/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.content.res.AssetManager
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The Public Suffix List, for telling whether two hosts belong to the same party. Without it
 * `victim.github.io` and `tracker.github.io` look like one site - the list's private section
 * is what says every `github.io` tenant is its own registrable domain.
 *
 * Loaded once from `assets/public_suffixes.txt` on a background thread; until then callers
 * fall back to [HostMatcher]'s two-label guess.
 */
object PublicSuffixes {
  @Volatile private var rules: Set<String>? = null
  private val cache = ConcurrentHashMap<String, Int>()

  val isLoaded: Boolean get() = rules != null

  fun load(assets: AssetManager) {
    if (rules != null) return
    runCatching { assets.open(ASSET).use(::load) }
  }

  fun load(input: InputStream) {
    val loaded = HashSet<String>(16_384)
    input.bufferedReader().forEachLine { line ->
      val rule = line.trim()
      if (rule.isNotEmpty() && !rule.startsWith("#")) loaded.add(rule)
    }
    rules = loaded
    cache.clear()
  }

  /**
   * Where the registrable domain begins in [host] (lowercase, no trailing dot), by the list's
   * algorithm: the longest matching rule wins, an exception rule beats a wildcard, and a host
   * with no rule has a one-label suffix. Null until the list is loaded. A host that is itself
   * a public suffix answers 0, so it is only ever the same party as itself.
   */
  fun registrableDomainStart(host: String): Int? {
    val rules = rules ?: return null
    cache[host]?.let { return it }
    val answer = compute(rules, host)
    if (cache.size >= CACHE_LIMIT) cache.clear()
    cache[host] = answer
    return answer
  }

  private fun compute(rules: Set<String>, host: String): Int {
    var start = 0
    var previous = -1
    while (true) {
      val candidate = host.substring(start)
      if ("!$candidate" in rules) return start
      val nextDot = host.indexOf('.', start)
      val wildcard = nextDot >= 0 && ("*." + host.substring(nextDot + 1)) in rules
      if (candidate in rules || wildcard) return if (previous < 0) 0 else previous
      if (nextDot < 0) break
      previous = start
      start = nextDot + 1
    }
    // No rule: the last label is the suffix.
    return if (previous < 0) 0 else previous
  }

  internal fun unloadForTests() {
    rules = null
    cache.clear()
  }

  private const val ASSET = "public_suffixes.txt"
  private const val CACHE_LIMIT = 4096
}
