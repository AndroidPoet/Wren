/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui

import android.view.WindowManager
import androidx.fragment.app.DialogFragment

/**
 * A sheet or dialog is its own window: the browser window's FLAG_SECURE does not cover it, so
 * a screenshot or the recents preview would show it while the page behind stays black. Every
 * sheet inherits the flag from its host, and [force] adds it for content of its own that must
 * not be captured, such as private tabs' thumbnails.
 */
fun DialogFragment.inheritSecureFlag(force: Boolean = false) {
  val secure = WindowManager.LayoutParams.FLAG_SECURE
  val hostFlags = activity?.window?.attributes?.flags ?: 0
  if (force || hostFlags and secure != 0) dialog?.window?.addFlags(secure)
}
