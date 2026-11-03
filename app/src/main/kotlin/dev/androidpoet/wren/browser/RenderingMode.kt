/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

/**
 * Whole-page rendering filters, for reading in conditions a site never designed for. Applied as a
 * CSS filter on the document, which the GPU does essentially for free.
 */
enum class RenderingMode(val css: String) {
  NORMAL(""),

  // Invert the page, then invert media back so photos and video stay true - the standard way to
  // turn a stubbornly-white site dark without the artefacts of algorithmic darkening.
  INVERT(
    "html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important;}" +
      "img,video,picture,canvas,[style*=\"background-image\"]" +
      "{filter:invert(1) hue-rotate(180deg) !important;}",
  ),

  GRAYSCALE("html{filter:grayscale(1) !important;}"),

  HIGH_CONTRAST("html{filter:contrast(1.35) !important;}"),
  ;

  companion object {
    fun fromOrdinal(value: Int): RenderingMode = entries.getOrElse(value) { NORMAL }

    /** JS that installs, updates or clears a single style element carrying the filter. */
    fun injectionFor(mode: RenderingMode): String {
      val css = mode.css.replace("\\", "\\\\").replace("'", "\\'")
      return """
        (function (css) {
          try {
            var style = document.getElementById('wren-render');
            if (!css) { if (style) style.remove(); return; }
            if (!style) {
              style = document.createElement('style');
              style.id = 'wren-render';
              (document.head || document.documentElement).appendChild(style);
            }
            style.textContent = css;
          } catch (ignored) {}
        })('$css');
      """.trimIndent()
    }
  }
}
