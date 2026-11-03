/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.home

import dev.androidpoet.wren.browser.blocking.HostMatcher

/**
 * Speed-dial tile appearance.
 *
 * Tiles are generated letters on a colour derived from the host rather than real site logos:
 * shipping other companies' marks in an app is a trademark problem, and fetching favicons at
 * render time would announce to every site on the grid that the home page had been opened.
 * The colour is stable per host, so a tile always looks the same.
 */
object Tiles {

  // Mid-tone earth colours: light enough that the dark letter reads on every one of them.
  private const val CLAY = 0xFFE08A66
  private const val SAGE = 0xFF8FBF8A
  private const val OCHRE = 0xFFE3B45A
  private const val SKY = 0xFF7FB2D9
  private const val PLUM = 0xFFC08BB5
  private const val SAND = 0xFFD8C08E
  private const val TEAL = 0xFF6FBDB0
  private const val ROSE = 0xFFE39A9A

  private const val FNV_OFFSET_BASIS = 2166136261u
  private const val FNV_PRIME = 16777619u

  private val PALETTE = intArrayOf(
    CLAY.toInt(),
    SAGE.toInt(),
    OCHRE.toInt(),
    SKY.toInt(),
    PLUM.toInt(),
    SAND.toInt(),
    TEAL.toInt(),
    ROSE.toInt(),
  )

  fun colorFor(url: String): Int {
    val key = HostMatcher.hostOf(url)?.removePrefix("www.") ?: url
    return PALETTE[(fnv1a(key) % PALETTE.size.toUInt()).toInt()]
  }

  /**
   * FNV-1a rather than [String.hashCode]: taking hashCode modulo a small palette keys off the
   * trailing characters, so hosts that end alike all came out the same colour.
   */
  private fun fnv1a(value: String): UInt {
    var hash = FNV_OFFSET_BASIS
    for (char in value) {
      hash = hash xor char.code.toUInt()
      hash *= FNV_PRIME
    }
    return hash
  }

  fun letterFor(title: String, url: String): String {
    val fromTitle = title.firstOrNull { it.isLetterOrDigit() }
    if (fromTitle != null) return fromTitle.uppercase()
    val host = HostMatcher.hostOf(url)?.removePrefix("www.").orEmpty()
    return host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
  }

  /** The label under a tile: a given title, else the bare host. */
  fun labelFor(title: String, url: String): String =
    title.ifBlank { HostMatcher.hostOf(url)?.removePrefix("www.").orEmpty().substringBefore('.') }
}
