/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.core.data.repository.HistoryRepositoryImpl
import dev.androidpoet.wren.core.database.HistoryDao
import dev.androidpoet.wren.core.database.entity.HistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HistoryRepositoryImplTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val historyDao: HistoryDao = mock()
  private val repository = HistoryRepositoryImpl(historyDao, dispatcher)

  @Test
  fun `revisiting a page increments its visit count instead of adding a row`() =
    runTest(dispatcher) {
      whenever(historyDao.findByUrl(URL)).thenReturn(
        HistoryEntity(url = URL, title = "Kotlin", lastVisitedAt = 1L, visitCount = 2),
      )

      repository.recordVisit(URL, "Kotlin")

      val captor = argumentCaptor<HistoryEntity>()
      verify(historyDao).upsert(captor.capture())
      assertThat(captor.firstValue.visitCount).isEqualTo(3)
    }

  @Test
  fun `a blank title mid-load does not erase the title already stored`() = runTest(dispatcher) {
    whenever(historyDao.findByUrl(URL)).thenReturn(
      HistoryEntity(url = URL, title = "Kotlin", lastVisitedAt = 1L, visitCount = 1),
    )

    repository.recordVisit(URL, "")

    val captor = argumentCaptor<HistoryEntity>()
    verify(historyDao).upsert(captor.capture())
    assertThat(captor.firstValue.title).isEqualTo("Kotlin")
  }

  @Test
  fun `a first visit starts at one`() = runTest(dispatcher) {
    whenever(historyDao.findByUrl(URL)).thenReturn(null)

    repository.recordVisit(URL, "Kotlin")

    val captor = argumentCaptor<HistoryEntity>()
    verify(historyDao).upsert(captor.capture())
    assertThat(captor.firstValue.visitCount).isEqualTo(1)
  }

  @Test
  fun `about blank is a placeholder, not a title, and never overwrites a real one`() =
    runTest(dispatcher) {
      whenever(historyDao.findByUrl(URL)).thenReturn(
        HistoryEntity(url = URL, title = "Kotlin", lastVisitedAt = 1L, visitCount = 1),
      )

      repository.recordVisit(URL, "about:blank")

      val captor = argumentCaptor<HistoryEntity>()
      verify(historyDao).upsert(captor.capture())
      assertThat(captor.firstValue.title).isEqualTo("Kotlin")
    }

  @Test
  fun `most visited names each host once, summing the pages under it`() = runTest(dispatcher) {
    whenever(historyDao.observeMostVisited(any())).thenReturn(
      flowOf(
        listOf(
          entity("https://www.reddit.com/?challenge=1", "Reddit", visits = 3),
          entity("https://www.reddit.com/", "Reddit", visits = 2),
          entity("https://example.com/", "Example Domain", visits = 4),
          entity("https://example.com/example.com", "Example Domain", visits = 1),
        ),
      ),
    )

    val rows = repository.observeMostVisited(limit = 5).first()

    assertThat(rows.map { it.url }).containsExactly(
      "https://example.com/",
      "https://www.reddit.com/?challenge=1",
    ).inOrder()
    assertThat(rows.map { it.visitCount }).containsExactly(5, 5).inOrder()
  }

  @Test
  fun `different hosts stay separate rows`() = runTest(dispatcher) {
    whenever(historyDao.observeMostVisited(any())).thenReturn(
      flowOf(
        listOf(
          entity("https://en.wikipedia.org/wiki/Main_Page", "Wikipedia", visits = 5),
          entity("https://www.wikipedia.org/", "Wikipedia", visits = 3),
        ),
      ),
    )

    val rows = repository.observeMostVisited(limit = 5).first()

    assertThat(rows).hasSize(2)
  }

  private fun entity(url: String, title: String, visits: Int) =
    HistoryEntity(url = url, title = title, lastVisitedAt = visits.toLong(), visitCount = visits)

  private companion object {
    const val URL = "https://kotlinlang.org/"
  }
}
