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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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

  /**
   * Opens a bottom sheet at its full height on a TV. A wide screen makes the peek a sliver, and
   * a remote has no way to drag it up.
   */
  fun expandSheet(sheet: BottomSheetDialogFragment) {
    if (!isTv(sheet.requireContext())) return
    val behavior = (sheet.dialog as? BottomSheetDialog)?.behavior ?: return
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
    behavior.skipCollapsed = true
  }
}
