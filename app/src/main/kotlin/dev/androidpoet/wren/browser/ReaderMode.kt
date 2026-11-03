/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.app.Application
import android.content.res.Configuration
import android.webkit.WebView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strips a page down to its article.
 *
 * The extraction runs in the page rather than natively because it needs the rendered DOM - text
 * length, link density and layout are what the scoring is built on, and none of that exists in
 * the raw HTML. Leaving reader mode is a reload: the original document was replaced, so there is
 * nothing to restore.
 */
@Singleton
class ReaderMode @Inject constructor(
  private val application: Application,
) {

  private val template: String by lazy {
    application.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
  }

  /**
   * Reader mode replaces the page's own stylesheet, so it has to bring the app's colours with
   * it - otherwise the one screen made entirely of our own styling would ignore the theme.
   */
  private fun script(): String {
    val night = application.resources.configuration.uiMode and
      Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    return template
      .replace("__BG__", if (night) "#171512" else "#FFFDF8")
      .replace("__FG__", if (night) "#F2EDE4" else "#1E1B17")
      .replace("__MUTED__", if (night) "#B3AA9C" else "#5A5349")
      .replace("__ACCENT__", if (night) "#F0A283" else "#8A3A20")
      .replace("__SURFACE__", if (night) "#1F1C18" else "#F4F1EA")
  }

  var isActive: Boolean = false
    private set

  /** [onResult] reports false when the page has no article worth showing. */
  fun enter(webView: WebView, onResult: (Boolean) -> Unit) {
    webView.evaluateJavascript(script()) { raw ->
      val entered = raw?.trim('"').equals("true", ignoreCase = true)
      isActive = entered
      onResult(entered)
    }
  }

  fun exit(webView: WebView) {
    isActive = false
    webView.reload()
  }

  /** Any navigation leaves reader mode behind, since the document is replaced. */
  fun onNavigated() {
    isActive = false
  }

  private companion object {
    const val SCRIPT_ASSET = "reader.js"
  }
}
