/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import dev.androidpoet.wren.core.data.repository.SuggestionRepositoryImpl
import dev.androidpoet.wren.core.model.HistoryEntry
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.network.model.SuggestionResponse
import dev.androidpoet.wren.core.network.service.SuggestClient
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SuggestionRepositoryImplTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val suggestClient: SuggestClient = mock()
  private val historyRepository: HistoryRepository = mock()

  private fun repository() = SuggestionRepositoryImpl(
    suggestClient = suggestClient,
    historyRepository = historyRepository,
    ioDispatcher = dispatcher,
  )

  @Test
  fun `history matches are emitted before the network answers`() = runTest(dispatcher) {
    whenever(historyRepository.search("kot", HistoryRepository.SUGGESTION_LIMIT))
      .thenReturn(listOf(historyEntry()))
    whenever(suggestClient.suggest("kot", SearchEngine.DUCKDUCKGO))
      .thenReturn(Result.success(listOf(SuggestionResponse("kotlin flow"))))

    repository().suggestions(query = "kot", includeRemote = true).test {
      val local = awaitItem()
      assertThat(local).hasSize(1)
      assertThat(local.single().fromHistory).isTrue()

      val merged = awaitItem()
      assertThat(merged.map { it.phrase }).containsExactly("Kotlin docs", "kotlin flow").inOrder()
      awaitComplete()
    }
  }

  @Test
  fun `nothing is sent to the network when remote suggestions are off`() = runTest(dispatcher) {
    whenever(historyRepository.search("kot", HistoryRepository.SUGGESTION_LIMIT))
      .thenReturn(emptyList())

    repository().suggestions(query = "kot", includeRemote = false).test {
      assertThat(awaitItem()).isEmpty()
      awaitComplete()
    }

    verify(suggestClient, never()).suggest("kot", SearchEngine.DUCKDUCKGO)
  }

  @Test
  fun `a blank query never reaches history or the network`() = runTest(dispatcher) {
    repository().suggestions(query = "  ", includeRemote = true).test {
      assertThat(awaitItem()).isEmpty()
      awaitComplete()
    }

    verify(historyRepository, never()).search("  ", HistoryRepository.SUGGESTION_LIMIT)
    verify(suggestClient, never()).suggest("  ", SearchEngine.DUCKDUCKGO)
  }

  @Test
  fun `repeat visits to one site with one title collapse into a single row`() = runTest(
    dispatcher,
  ) {
    whenever(historyRepository.search("kot", HistoryRepository.SUGGESTION_LIMIT))
      .thenReturn(
        listOf(
          historyEntry().copy(url = "https://duckduckgo.com/?q=kotlin", title = "kotlin at DDG"),
          historyEntry().copy(
            url = "https://duckduckgo.com/?q=kotlin&ia=web",
            title = "kotlin at DDG",
          ),
          historyEntry().copy(
            url = "https://www.duckduckgo.com/?t=h_&q=kotlin",
            title = "kotlin at DDG",
          ),
          historyEntry(),
        ),
      )

    repository().suggestions(query = "kot", includeRemote = false).test {
      val local = awaitItem()
      assertThat(local.map { it.url }).containsExactly(
        "https://duckduckgo.com/?q=kotlin",
        "https://kotlinlang.org/docs",
      ).inOrder()
      awaitComplete()
    }
  }

  private fun historyEntry() = HistoryEntry(
    url = "https://kotlinlang.org/docs",
    title = "Kotlin docs",
    lastVisitedAt = 1_700_000_000_000L,
    visitCount = 4,
  )
}
