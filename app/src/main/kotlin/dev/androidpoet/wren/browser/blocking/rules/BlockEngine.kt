/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

import dev.androidpoet.wren.browser.blocking.CompactHostSet

/** Everything the blocker consults, built as one unit so a list refresh swaps it whole. */
class BlockEngine(
  val hosts: CompactHostSet,
  val rules: RuleEngine,
  val cosmetics: CosmeticRules,
  val version: Int,
) {
  val size: Int get() = hosts.size + rules.size + cosmetics.size

  companion object {
    val EMPTY = BlockEngine(CompactHostSet.EMPTY, RuleEngine.EMPTY, CosmeticRules.EMPTY, 0)
  }
}

/**
 * Reads filter lists of any common shape into one [BlockEngine]: hosts files, bare domains and
 * EasyList syntax can be mixed line by line, so a user's custom subscription needs no format
 * setting.
 */
class EngineBuilder {
  private val hosts = HashSet<String>()
  private val hostSets = ArrayList<CompactHostSet>()
  private val rules = RuleEngine.Builder()
  private val cosmetics = CosmeticRules.Builder()
  private var lines = 0

  fun addHostSet(set: CompactHostSet): EngineBuilder {
    hostSets.add(set)
    return this
  }

  fun addLines(lines: Sequence<String>): EngineBuilder {
    lines.forEach(::addLine)
    return this
  }

  fun addLine(raw: String) {
    val line = raw.trimSpaces()
    if (line.isEmpty() || line[0] == '[') return
    if (line[0] == '!') {
      if (line.startsWith("!#")) directive(line)
      return
    }
    if (!conditions.all { it }) return
    lines++
    if (CosmeticRules.isCosmeticLine(line)) {
      cosmetics.add(line)
      return
    }
    if (line[0] == '#') return
    plainHostOf(line)?.let {
      hosts.add(it)
      return
    }
    hostsFileEntry(line)?.let {
      hosts.add(it)
      return
    }
    // What is left of a hosts file after the entries above (loopback names, IPv6 aliases,
    // lines with several fields) is never a network rule; a pattern has no whitespace and
    // always carries some punctuation.
    if (line.any { it.isSpace() } || line.all { it.isWordChar() || it == '-' }) return
    rules.add(line)
  }

  /**
   * uBlock's preprocessor: `!#if cond` ... `!#else` ... `!#endif`. Lists wrap engine-specific
   * rules in these; Wren reads as a Chromium-based mobile uBlock, the closest truth. An
   * `!#include` is resolved when the list is fetched, so here it is a comment.
   */
  private val conditions = ArrayList<Boolean>()

  private fun directive(line: String) {
    when {
      line.startsWith("!#if ") -> conditions.add(evaluate(line.substring(5)))
      line.startsWith("!#else") -> if (conditions.isNotEmpty()) {
        conditions[conditions.lastIndex] = !conditions.last()
      }
      line.startsWith("!#endif") -> if (conditions.isNotEmpty()) {
        conditions.removeAt(conditions.lastIndex)
      }
    }
  }

  fun build(version: Int): BlockEngine {
    conditions.clear()
    var all = CompactHostSet.of(hosts)
    for (set in hostSets) all = CompactHostSet.union(all, set)
    return BlockEngine(all, rules.build(), cosmetics.build(), version)
  }

  companion object {
    /**
     * The host of a `||host^` rule with no options beyond third-party, which the host set
     * expresses exactly (it is only consulted for third-party requests). Anything more specific
     * stays a pattern rule.
     */
    fun plainHostOf(line: String): String? {
      if (!line.startsWith("||")) return null
      var body = line.substring(2)
      var options = ""
      val dollar = body.indexOf('$')
      if (dollar >= 0) {
        options = body.substring(dollar + 1)
        body = body.substring(0, dollar)
      }
      body = body.removeSuffix("^")
      if (options.isNotEmpty() && options != "third-party" && options != "3p") return null
      return body.lowercase().takeIf(::isHostName)
    }

    /** The list conditions that hold here; everything else (firefox, safari, adguard) is false. */
    private val TRUE_CONDITIONS = setOf("env_chromium", "env_mobile", "ext_ublock", "true")

    /** `a || b`, `!a`, `(a && b)`: enough of the grammar for the lists Wren ships and fetches. */
    fun evaluate(expression: String): Boolean {
      val cleaned = expression.replace("(", " ").replace(")", " ")
      if (cleaned.contains("||")) return cleaned.split("||").any { evaluate(it) }
      if (cleaned.contains("&&")) return cleaned.split("&&").all { evaluate(it) }
      val term = cleaned.trimSpaces()
      if (term.startsWith("!")) return !evaluate(term.substring(1))
      return term in TRUE_CONDITIONS
    }

    /** The host of a hosts-file line (`0.0.0.0 host`) or a bare domain line. */
    fun hostsFileEntry(line: String): String? {
      // A `#` opens a comment only at the start of the line or after whitespace. Glued to text
      // it is part of a cosmetic rule the caller did not recognise, and that line names a site
      // to hide something on, never a host to block.
      val hash = line.indexOf('#')
      val content = when {
        hash < 0 -> line
        hash == 0 || line[hash - 1].isSpace() -> line.substring(0, hash).trim()
        else -> return null
      }
      if (content.isEmpty()) return null
      val parts = content.split(' ', '\t').filter { it.isNotEmpty() }
      val host = when (parts.size) {
        1 -> parts[0]
        2 -> if (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1") {
          parts[1]
        } else {
          return null
        }
        else -> return null
      }.lowercase().removeSuffix(".")
      if (host == "localhost" || host == "localhost.localdomain" || host == "broadcasthost") {
        return null
      }
      if (host == "local" || host == "ip6-localhost" || host == "ip6-loopback") return null
      if (host.startsWith("ip6-") || host == "0.0.0.0") return null
      return host.takeIf(::isHostName)
    }

    /** Dotted labels of letters, digits, `-` and `_`, none empty, not a bare IPv4 address. */
    private fun isHostName(host: String): Boolean {
      if (host.isEmpty()) return false
      var dots = 0
      var afterDot = true
      var lastLabelDigits = true
      for (c in host) {
        if (c == '.') {
          if (afterDot) return false
          dots++
          afterDot = true
          lastLabelDigits = true
          continue
        }
        afterDot = false
        if (!(c.isWordChar() || c == '-' || c == '_')) return false
        if (!c.isAsciiDigit()) lastLabelDigits = false
      }
      return dots > 0 && !afterDot && !lastLabelDigits
    }
  }
}
