/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that can tell the keyboard what it is typing into.
 *
 * [incognitoKeyboard] is set for private and Tor pages: the keyboard is asked not to learn from
 * what is typed, the same flag Chrome raises in incognito, so a search typed into a Tor page is
 * not offered back as a suggestion in another app tomorrow. A keyboard may ignore it; Gboard
 * honours it.
 */
class WrenWebView(
  context: Context,
  private val incognitoKeyboard: Boolean,
) : WebView(context) {

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
    val connection = super.onCreateInputConnection(outAttrs)
    if (incognitoKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    return connection
  }
}
