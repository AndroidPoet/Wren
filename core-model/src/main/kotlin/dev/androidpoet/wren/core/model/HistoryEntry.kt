/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/** A page the user has visited, aggregated by URL. */
data class HistoryEntry(
  val url: String,
  val title: String,
  val lastVisitedAt: Long,
  val visitCount: Int,
)
