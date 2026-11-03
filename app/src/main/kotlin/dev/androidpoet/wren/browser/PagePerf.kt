/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.webkit.WebView
import timber.log.Timber

/**
 * Reads the page's own Navigation Timing and paint entries after load, so rendering speed is
 * measured where it actually happens instead of guessed at from the outside. Debug builds only
 * end up logging, because Timber has no tree in release.
 */
object PagePerf {

  private val SCRIPT = """
    (function () {
      try {
        var nav = performance.getEntriesByType('navigation')[0];
        if (!nav) return null;
        var fcp = 0;
        var paints = performance.getEntriesByType('paint');
        for (var i = 0; i < paints.length; i++) {
          if (paints[i].name === 'first-contentful-paint') fcp = paints[i].startTime;
        }
        return JSON.stringify({
          dns: Math.round(nav.domainLookupEnd - nav.domainLookupStart),
          connect: Math.round(nav.connectEnd - nav.connectStart),
          ttfb: Math.round(nav.responseStart - nav.startTime),
          response: Math.round(nav.responseEnd - nav.responseStart),
          domReady: Math.round(nav.domContentLoadedEventStart - nav.startTime),
          load: Math.round(nav.loadEventStart - nav.startTime),
          fcp: Math.round(fcp),
          transfer: nav.transferSize || 0,
          cached: nav.transferSize === 0 && nav.decodedBodySize > 0
        });
      } catch (ignored) {
        return null;
      }
    })()
  """.trimIndent()

  fun report(webView: WebView, url: String) {
    webView.evaluateJavascript(SCRIPT) { raw ->
      val json = raw?.takeIf { it != "null" }
        ?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")
        ?: return@evaluateJavascript
      Timber.i("pageperf %s %s", json, url)
    }
  }
}
