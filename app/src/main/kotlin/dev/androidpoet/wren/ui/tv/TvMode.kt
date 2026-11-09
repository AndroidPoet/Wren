/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.getSystemService

/**
 * Whether this is a television: a leanback device, or one whose UI mode says so. On a TV there is
 * no touchscreen, so the page is driven by a D-pad cursor ([CursorLayout]) and the chrome sits in
 * one focusable row at the top of the screen.
 */
object TvMode {
  fun isTv(context: Context): Boolean {
    val mode = context.getSystemService<UiModeManager>()?.currentModeType
    return mode == Configuration.UI_MODE_TYPE_TELEVISION ||
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
  }
}
