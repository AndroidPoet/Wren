/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

/**
 * Whether the system WebView, which renders every page here and carries every engine security
 * fix, has fallen badly behind. A browser built on it cannot patch the engine itself; telling the
 * user is the one thing it can do.
 *
 * The expected version is worked out from the date rather than a constant in the app, so the check
 * does not itself go stale: Chromium ships a major release about every 30.5 days, and M100 shipped
 * on 2022-03-29. Twelve majors behind is roughly a year without an update.
 */
object WebViewFreshness {

  private const val ANCHOR_MAJOR = 100
  private const val ANCHOR_MILLIS = 1_648_512_000_000L // 2022-03-29T00:00:00Z
  private const val MILLIS_PER_MAJOR = 30.5 * 24 * 60 * 60 * 1000
  private const val TOLERATED_MAJORS = 12

  fun expectedMajor(nowMillis: Long): Int =
    ANCHOR_MAJOR + ((nowMillis - ANCHOR_MILLIS) / MILLIS_PER_MAJOR).toInt()

  /** False when the version cannot be read: an unknown engine is not proof of an old one. */
  fun isStale(versionName: String?, nowMillis: Long): Boolean {
    val major = versionName?.substringBefore('.')?.toIntOrNull() ?: return false
    return major < expectedMajor(nowMillis) - TOLERATED_MAJORS
  }
}
