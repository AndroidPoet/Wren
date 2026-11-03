/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.os.Bundle

/**
 * One open tab. [state] is the serialized WebView back/forward list, saved when the tab loses
 * focus so a single WebView instance can back every tab.
 */
data class BrowserTab(
  val id: Long,
  var url: String = UrlFormatter.NEW_TAB_URL,
  var title: String = "",
  val isPrivate: Boolean = false,
  var state: Bundle? = null,
  /**
   * The last address this tab actually painted, as opposed to [url], which is wherever it was
   * last pointed. They differ for a load still in flight, one that failed, and one the user
   * cancelled - and it is this one, never the other, that may be shown as the tab's address.
   */
  var committedUrl: String? = null,
)
