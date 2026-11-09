/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
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

/**
 * [AlertDialog.Builder.show] for a dialog over a secure window. A confirmation that quotes the
 * page, a download name or a Tor address is as much page content as the page itself.
 */
fun AlertDialog.Builder.showSecure(): AlertDialog {
  val dialog = create()
  val secure = WindowManager.LayoutParams.FLAG_SECURE
  val hostFlags = context.hostActivity()?.window?.attributes?.flags ?: 0
  if (hostFlags and secure != 0) dialog.window?.addFlags(secure)
  dialog.show()
  return dialog
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.hostActivity()
  else -> null
}
