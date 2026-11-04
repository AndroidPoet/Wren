/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import androidx.annotation.VisibleForTesting
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.model.Suggestion
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import dev.androidpoet.wren.core.network.service.SuggestClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

@VisibleForTesting
class SuggestionRepositoryImpl @Inject constructor(
  private val suggestClient: SuggestClient,
  private val historyRepository: HistoryRepository,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : SuggestionRepository {

  override fun suggestions(
    query: String,
    includeRemote: Boolean,
    engine: SearchEngine,
    onError: (String?) -> Unit,
  ): Flow<List<Suggestion>> = flow {
    if (query.isBlank()) {
      emit(emptyList())
      return@flow
    }

    // One search on one site can leave several near-identical addresses behind; the row a person
    // reads is title plus host, so that is what has to be unique.
    val local = historyRepository.search(query)
      .distinctBy { entry -> entry.title.lowercase() to hostOf(entry.url) }
      .map { entry ->
        Suggestion(
          phrase = entry.title.ifBlank { entry.url },
          url = entry.url,
          fromHistory = true,
        )
      }
    emit(local)

    if (!includeRemote) return@flow

    suggestClient.suggest(query, engine)
      .onSuccess { data ->
        val seen = local.mapTo(mutableSetOf()) { it.phrase.lowercase() }
        val remote = data
          .map { Suggestion(phrase = it.phrase) }
          .filter { seen.add(it.phrase.lowercase()) }
        emit((local + remote).take(SuggestionRepository.MAX_SUGGESTIONS))
      }
      .onFailure { onError(it.message) }
  }.flowOn(ioDispatcher)

  private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
}
