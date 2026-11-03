/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.model.HistoryEntry
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {

  fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<HistoryEntry>>

  /** Pages visited most often, for the home page. Search result pages are excluded. */
  fun observeMostVisited(limit: Int = MOST_VISITED_LIMIT): Flow<List<HistoryEntry>>

  suspend fun recordVisit(url: String, title: String)

  suspend fun search(query: String, limit: Int = SUGGESTION_LIMIT): List<HistoryEntry>

  suspend fun delete(url: String)

  suspend fun clear()

  companion object {
    const val DEFAULT_LIMIT = 500
    const val SUGGESTION_LIMIT = 4
    const val MOST_VISITED_LIMIT = 8
  }
}
