/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network

/**
 * Whether the browser is in Tor mode, for code in this module that has no settings of its own:
 * the autocomplete client's [TorRouteGuard] asks it before every connection. The app installs
 * the answer at startup, the way it installs the process proxy selector.
 */
object TorRoute {
  @Volatile var isTorMode: () -> Boolean = { false }
}
