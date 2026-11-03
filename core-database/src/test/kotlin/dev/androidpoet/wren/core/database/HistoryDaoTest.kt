/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.core.database.entity.HistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryDaoTest {

  private lateinit var database: WrenDatabase
  private lateinit var dao: HistoryDao

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      WrenDatabase::class.java,
    ).allowMainThreadQueries().build()
    dao = database.historyDao()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `upserting the same url replaces the row rather than duplicating it`() = runTest {
    dao.upsert(entity(visitCount = 1, lastVisitedAt = 10L))
    dao.upsert(entity(visitCount = 2, lastVisitedAt = 20L))

    val rows = dao.observeRecent(limit = 10).first()
    assertThat(rows).hasSize(1)
    assertThat(rows.single().visitCount).isEqualTo(2)
  }

  @Test
  fun `recent history comes back newest first`() = runTest {
    dao.upsert(entity(url = "https://a.example/", lastVisitedAt = 10L))
    dao.upsert(entity(url = "https://b.example/", lastVisitedAt = 30L))
    dao.upsert(entity(url = "https://c.example/", lastVisitedAt = 20L))

    val urls = dao.observeRecent(limit = 10).first().map { it.url }
    assertThat(urls)
      .containsExactly("https://b.example/", "https://c.example/", "https://a.example/")
      .inOrder()
  }

  @Test
  fun `search matches both title and url, most visited first`() = runTest {
    dao.upsert(entity(url = "https://kotlinlang.org/", title = "Kotlin", visitCount = 1))
    dao.upsert(entity(url = "https://example.com/kotlin", title = "Blog", visitCount = 9))
    dao.upsert(entity(url = "https://unrelated.example/", title = "Nope", visitCount = 50))

    val results = dao.search(query = "kotlin", limit = 10).map { it.url }
    assertThat(results)
      .containsExactly("https://example.com/kotlin", "https://kotlinlang.org/")
      .inOrder()
  }

  private fun entity(
    url: String = "https://kotlinlang.org/",
    title: String = "Kotlin",
    lastVisitedAt: Long = 1L,
    visitCount: Int = 1,
  ) = HistoryEntity(
    url = url,
    title = title,
    lastVisitedAt = lastVisitedAt,
    visitCount = visitCount,
  )
}
