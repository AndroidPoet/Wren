/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/** A page the user explicitly saved. */
data class Bookmark(
  val url: String,
  val title: String,
  val createdAt: Long,
  val folder: String = "",
)
