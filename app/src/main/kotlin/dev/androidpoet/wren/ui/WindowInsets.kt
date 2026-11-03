/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * Keeps app chrome clear of the system bars.
 *
 * From targetSdk 35 the platform draws every app edge to edge and stops honouring
 * `statusBarColor`, so a layout that does not consume insets ends up with its toolbar underneath
 * the clock. Padding the chrome - rather than the window - keeps the page itself full-bleed,
 * which is what a browser wants.
 */
fun View.padForSystemBars(includeIme: Boolean = false) {
  ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
    val bars = windowInsets.getInsets(
      WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
    )
    val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
    view.updatePadding(
      left = bars.left,
      top = bars.top,
      right = bars.right,
      // The keyboard is taller than the navigation bar, so the larger of the two wins and the
      // bottom bar stays visible above it.
      bottom = if (includeIme) maxOf(bars.bottom, ime.bottom) else bars.bottom,
    )
    windowInsets
  }
}

/**
 * Matches the system bar icons to the chrome behind them. Light chrome needs dark icons, and
 * getting this wrong renders the clock invisible rather than merely wrong.
 */
fun Activity.useLightSystemBarIcons() {
  val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
  val isDarkChrome = night == Configuration.UI_MODE_NIGHT_YES
  WindowInsetsControllerCompat(window, window.decorView).apply {
    isAppearanceLightStatusBars = !isDarkChrome
    isAppearanceLightNavigationBars = !isDarkChrome
  }
}
