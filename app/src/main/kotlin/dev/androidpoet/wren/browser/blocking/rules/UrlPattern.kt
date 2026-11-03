/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

/**
 * The address part of a filter rule: `||host`, `|start`, `end|`, `*` wildcards and `^`
 * separators, matched by hand rather than through a compiled regex because a page loads
 * hundreds of resources and each one meets a handful of these.
 */
class UrlPattern private constructor(
  val text: String,
  val anchor: Int,
  val endAnchored: Boolean,
) {

  /** The literal run before the first wildcard or separator; where a match has to begin. */
  private val literalPrefix: String = text.takeWhile { it != '*' && it != '^' }

  /** True when the whole pattern is that literal run, so plain string search decides. */
  private val isLiteral = literalPrefix.length == text.length

  /**
   * True when [url] (already lowercased) matches. [hostStart] and [hostEnd] bound the host part
   * of the URL so `||` anchoring can insist on a label boundary.
   */
  fun matches(url: String, hostStart: Int, hostEnd: Int): Boolean = when (anchor) {
    START -> matchesAt(url, 0)
    HOST -> matchesInHost(url, hostStart, hostEnd)
    else -> matchesAnywhere(url)
  }

  /**
   * True when the pattern matches with its text starting exactly at [at]: the position the
   * index derived from where the rule's token sits in the address. The anchors still hold.
   */
  fun matchesAligned(url: String, at: Int, hostStart: Int, hostEnd: Int): Boolean = when (anchor) {
    START -> at == 0 && matchesAt(url, 0)
    HOST -> at >= hostStart && at <= hostEnd && (at == hostStart || url[at - 1] == '.') &&
      matchesAt(url, at)
    else -> matchesAt(url, at)
  }

  private fun matchesInHost(url: String, hostStart: Int, hostEnd: Int): Boolean {
    if (literalPrefix.isEmpty()) {
      for (p in hostStart..hostEnd) {
        if ((p == hostStart || url[p - 1] == '.') && matchesAt(url, p)) return true
      }
      return false
    }
    var from = hostStart
    while (true) {
      val p = url.indexOf(literalPrefix, from)
      if (p < 0 || p > hostEnd) return false
      if ((p == hostStart || url[p - 1] == '.') && matchesAt(url, p)) return true
      from = p + 1
    }
  }

  private fun matchesAnywhere(url: String): Boolean {
    if (isLiteral) {
      return if (endAnchored) url.endsWith(text) else url.contains(text)
    }
    if (literalPrefix.isEmpty()) {
      for (p in 0..url.length) if (matchesAt(url, p)) return true
      return false
    }
    var from = 0
    while (true) {
      val p = url.indexOf(literalPrefix, from)
      if (p < 0) return false
      if (matchesAt(url, p)) return true
      from = p + 1
    }
  }

  /** Glob match of the whole pattern against [url] from [start], backtracking over `*`. */
  private fun matchesAt(url: String, start: Int): Boolean {
    if (isLiteral) {
      if (!url.startsWith(text, start)) return false
      return !endAnchored || start + text.length == url.length
    }
    val pn = text.length
    val un = url.length
    var pi = 0
    var ui = start
    var starPi = -1
    var starUi = -1
    while (true) {
      if (pi < pn) {
        val pc = text[pi]
        if (pc == '*') {
          starPi = pi
          starUi = ui
          pi++
          continue
        }
        if (ui < un) {
          val uc = url[ui]
          if (pc == uc || (pc == '^' && isSeparator(uc))) {
            pi++
            ui++
            continue
          }
        } else if (pc == '^') {
          // The end of the address counts as a separator.
          pi++
          continue
        }
      } else if (!endAnchored || ui == un) {
        return true
      }
      if (starPi < 0) return false
      starUi++
      if (starUi > un) return false
      pi = starPi + 1
      ui = starUi
    }
  }

  override fun toString(): String = buildString {
    if (anchor == HOST) {
      append("||")
    } else if (anchor == START) {
      append('|')
    }
    append(text)
    if (endAnchored) append('|')
  }

  companion object {
    const val NONE = 0
    const val HOST = 1
    const val START = 2

    fun parse(raw: String): UrlPattern {
      var s = raw
      var anchor = NONE
      var endAnchored = false
      if (s.startsWith("||")) {
        anchor = HOST
        s = s.substring(2)
      } else if (s.startsWith("|")) {
        anchor = START
        s = s.substring(1)
      }
      if (s.endsWith("|")) {
        endAnchored = true
        s = s.dropLast(1)
      }
      s = s.lowercase()
      while (s.contains("**")) s = s.replace("**", "*")
      if (anchor == NONE) s = s.trimStart('*')
      if (!endAnchored) s = s.trimEnd('*')
      return UrlPattern(s, anchor, endAnchored)
    }

    /** Anything but a letter, a digit, or one of `_ - . %`, per the filter syntax. */
    fun isSeparator(c: Char): Boolean = when (c) {
      in 'a'..'z', in '0'..'9', in 'A'..'Z', '_', '-', '.', '%' -> false
      else -> c.code < 128 || !c.isLetterOrDigit()
    }
  }
}
