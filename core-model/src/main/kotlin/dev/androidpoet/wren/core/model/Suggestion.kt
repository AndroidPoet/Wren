/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/**
 * A single omnibox completion. [fromHistory] entries are produced locally and never leave the
 * device; the rest come from the configured search engine's autocomplete endpoint.
 */
data class Suggestion(
  val phrase: String,
  val url: String? = null,
  val fromHistory: Boolean = false,
  /**
   * When set, choosing this suggestion inserts [completion] into the omnibox and keeps typing,
   * rather than navigating. Used by !bang shortcuts, where the point is to add the query next.
   */
  val completion: String? = null,
)
