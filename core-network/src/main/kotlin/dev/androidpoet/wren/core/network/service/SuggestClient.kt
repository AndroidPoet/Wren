/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.service

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.mapSuccess
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.network.model.SuggestionResponse
import java.net.URLEncoder
import javax.inject.Inject

class SuggestClient @Inject constructor(
  private val suggestService: SuggestService,
) {
  /** Autocomplete for [query] from [engine]'s own endpoint. */
  suspend fun suggest(query: String, engine: SearchEngine): ApiResponse<List<SuggestionResponse>> {
    val url = engine.suggestTemplate.replace("%s", URLEncoder.encode(query, "UTF-8"))
    return suggestService.fetch(url).mapSuccess {
      SuggestParser.parse(engine.suggestFormat, string()).map(::SuggestionResponse)
    }
  }
}
