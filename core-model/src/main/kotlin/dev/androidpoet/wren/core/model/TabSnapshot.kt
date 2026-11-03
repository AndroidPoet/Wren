/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/** The persistable identity of an open tab. The live [android.webkit.WebView] state is separate. */
data class TabSnapshot(
  val id: Long,
  val url: String,
  val title: String,
  val isPrivate: Boolean,
)
