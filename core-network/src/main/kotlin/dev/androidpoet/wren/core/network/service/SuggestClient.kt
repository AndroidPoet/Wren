/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.service

import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.network.model.SuggestionResponse
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject

class SuggestClient @Inject constructor(
  private val suggestService: SuggestService,
) {
  /**
   * Autocomplete for [query] from [engine]'s own endpoint. Network and HTTP errors come back as a
   * failed [Result] rather than an exception: a missing suggestion must never break typing.
   */
  suspend fun suggest(query: String, engine: SearchEngine): Result<List<SuggestionResponse>> {
    val url = engine.suggestTemplate.replace("%s", URLEncoder.encode(query, "UTF-8"))
    return try {
      val response = suggestService.fetch(url)
      val body = response.body()
      if (!response.isSuccessful || body == null) {
        response.errorBody()?.close()
        Result.failure(HttpException(response))
      } else {
        val text = body.use { it.string() }
        Result.success(SuggestParser.parse(engine.suggestFormat, text).map(::SuggestionResponse))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      Result.failure(e)
    }
  }
}
