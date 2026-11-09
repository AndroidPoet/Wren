/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.floor

/**
 * Tor Browser's letterboxing, for a WebView.
 *
 * The exact viewport in CSS pixels is one of the strongest signals a page can read without
 * asking: a width of 412 and a height of 783 name a handful of phone models, and the page sees
 * both through `innerWidth`, media queries and layout itself, which no script can hide. While
 * Tor is on, every page is sized to a multiple of [STEP_CSS_PX] on each side and centred, so
 * the viewport falls into a few buckets shared by every Wren user on a similar screen. The gap
 * is painted in the theme's ground colour, so it reads as a margin rather than a fault.
 */
class Letterbox(
  private val container: FrameLayout,
  private val enabled: () -> Boolean,
) {

  init {
    container.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
      val resized = r - l != oldR - oldL || b - t != oldB - oldT
      if (resized) apply()
    }
  }

  /** Sizes every page in the container for the current route; a no-op when nothing changes. */
  fun apply() {
    val on = enabled()
    val step = STEP_CSS_PX * container.resources.displayMetrics.density
    val width = if (on) letterboxedSize(container.width, step) else MATCH_PARENT
    val height = if (on) letterboxedSize(container.height, step) else MATCH_PARENT
    for (i in 0 until container.childCount) {
      val page = container.getChildAt(i) as? WebView ?: continue
      val params = page.layoutParams as? FrameLayout.LayoutParams ?: continue
      val unchanged =
        params.width == width && params.height == height && params.gravity == Gravity.CENTER
      if (unchanged) continue
      params.width = width
      params.height = height
      params.gravity = Gravity.CENTER
      page.layoutParams = params
    }
  }

  companion object {
    /** Tor Browser rounds to 100 px steps on small windows; a phone is always a small window. */
    const val STEP_CSS_PX = 100

    /**
     * The largest multiple of [step] that fits in [available] physical pixels, or match-parent
     * when the container has no size yet (before its first layout) or is smaller than one step.
     */
    fun letterboxedSize(available: Int, step: Float): Int {
      if (available <= 0 || step <= 0f) return MATCH_PARENT
      val steps = floor(available / step).toInt()
      // Floor, not round: the engine rounds a fractional CSS pixel up, so 1837.5 px would read as 701.
      return if (steps < 1) MATCH_PARENT else (steps * step).toInt()
    }
  }
}
