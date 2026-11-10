/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import dev.androidpoet.wren.core.model.HistoryEntry
import dev.androidpoet.wren.core.test.MainCoroutinesRule
import dev.androidpoet.wren.ui.library.HistoryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {

  @get:Rule
  val coroutinesRule = MainCoroutinesRule()

  @Test
  fun `urls that differ only in query parameters collapse to the newest row`() = runTest {
    val repository = FakeHistoryRepository(
      listOf(
        entry("https://duckduckgo.com/?q=android&t=h_", "android at DuckDuckGo", 30L),
        entry("https://duckduckgo.com/?q=android", "android at DuckDuckGo", 20L),
        entry("https://kotlinlang.org/", "Kotlin Programming Language", 10L),
      ),
    )

    HistoryViewModel(repository).rows.test {
      assertThat(awaitItem()).isEmpty()
      val rows = awaitItem()
      assertThat(rows.map { it.url }).containsExactly(
        "https://duckduckgo.com/?q=android&t=h_",
        "https://kotlinlang.org/",
      ).inOrder()
    }
  }

  @Test
  fun `different pages on the same host are both kept`() = runTest {
    val repository = FakeHistoryRepository(
      listOf(
        entry("https://example.com/a", "Page A", 20L),
        entry("https://example.com/b", "Page B", 10L),
      ),
    )

    HistoryViewModel(repository).rows.test {
      assertThat(awaitItem()).isEmpty()
      assertThat(awaitItem()).hasSize(2)
    }
  }

  private fun entry(url: String, title: String, at: Long) =
    HistoryEntry(url = url, title = title, lastVisitedAt = at, visitCount = 1)

  private class FakeHistoryRepository(
    private val entries: List<HistoryEntry>,
  ) : HistoryRepository {
    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = flowOf(entries)
    override fun observeMostVisited(limit: Int): Flow<List<HistoryEntry>> = flowOf(entries)
    override suspend fun recordVisit(url: String, title: String) = Unit
    override suspend fun search(query: String, limit: Int): List<HistoryEntry> = emptyList()
    override suspend fun delete(url: String) = Unit
    override suspend fun deleteMatching(matches: (url: String) -> Boolean) = Unit
    override suspend fun clear() = Unit
  }
}
