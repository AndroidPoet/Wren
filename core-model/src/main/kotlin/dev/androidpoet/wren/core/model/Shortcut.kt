/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/** A tile on the home page speed dial. */
data class Shortcut(
  val url: String,
  val title: String,
  val position: Int = 0,
)
