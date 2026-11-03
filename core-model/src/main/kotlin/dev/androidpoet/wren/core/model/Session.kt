/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/** A named set of open pages. Tabs live in exactly one session; switching swaps them all. */
data class Session(
  val name: String,
  val tabs: List<SessionTab>,
  val updatedAt: Long,
)

data class SessionTab(
  val url: String,
  val title: String,
)
