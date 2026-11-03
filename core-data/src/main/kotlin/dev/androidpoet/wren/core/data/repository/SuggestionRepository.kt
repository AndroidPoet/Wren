/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.model.Suggestion
import kotlinx.coroutines.flow.Flow

interface SuggestionRepository {

  /**
   * Emits local history matches first, then the same list widened with remote autocomplete
   * results. Emitting twice keeps the omnibox responsive when the network is slow or absent.
   *
   * @param includeRemote when false, nothing leaves the device.
   * @param engine whose autocomplete to ask; the one the person searches with.
   */
  fun suggestions(
    query: String,
    includeRemote: Boolean,
    engine: SearchEngine = SearchEngine.DEFAULT,
    onError: (String?) -> Unit = {},
  ): Flow<List<Suggestion>>

  companion object {
    const val MAX_SUGGESTIONS = 8
  }
}
