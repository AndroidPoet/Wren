/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import androidx.annotation.VisibleForTesting
import dev.androidpoet.wren.core.database.HistoryDao
import dev.androidpoet.wren.core.database.entity.HistoryEntity
import dev.androidpoet.wren.core.database.entity.mapper.asDomain
import dev.androidpoet.wren.core.model.HistoryEntry
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@VisibleForTesting
class HistoryRepositoryImpl @Inject constructor(
  private val historyDao: HistoryDao,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : HistoryRepository {

  override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = historyDao.observeRecent(limit)
    .map { entities -> entities.asDomain() }
    .flowOn(ioDispatcher)

  /**
   * The speed dial names sites, not pages. History stays per-URL, but three rows for the same
   * host - a bot challenge, a redirect and the front page - read as a broken list, so the rows
   * are folded by host and the host's best-known page represents it.
   */
  override fun observeMostVisited(limit: Int): Flow<List<HistoryEntry>> =
    historyDao.observeMostVisited(limit * HOST_FOLD_FACTOR)
      .map { entities -> entities.asDomain().foldByHost(limit) }
      .flowOn(ioDispatcher)

  private fun List<HistoryEntry>.foldByHost(limit: Int): List<HistoryEntry> = this
    .groupBy { entry -> hostOf(entry.url) }
    .values
    .map { forHost ->
      val best = forHost.maxByOrNull { it.visitCount }!!
      best.copy(visitCount = forHost.sumOf { it.visitCount })
    }
    .sortedWith(
      compareByDescending<HistoryEntry> {
        it.visitCount
      }.thenByDescending { it.lastVisitedAt },
    )
    .take(limit)

  private fun hostOf(url: String): String = url
    .substringAfter("://", url)
    .substringBefore('/')
    .substringBefore('?')
    .removePrefix("www.")
    .lowercase()

  override suspend fun recordVisit(url: String, title: String) = withContext(ioDispatcher) {
    val existing = historyDao.findByUrl(url)
    historyDao.upsert(
      HistoryEntity(
        url = url,
        // A page reports an empty title mid-load, and "about:blank" for one it is replacing;
        // never overwrite a good title with either.
        title = title.takeUnless { it.isBlank() || it == BLANK_PAGE } ?: existing?.title.orEmpty(),
        lastVisitedAt = System.currentTimeMillis(),
        visitCount = (existing?.visitCount ?: 0) + 1,
      ),
    )
  }

  override suspend fun search(query: String, limit: Int): List<HistoryEntry> =
    withContext(ioDispatcher) { historyDao.search(query, limit).asDomain() }

  override suspend fun delete(url: String) = withContext(ioDispatcher) { historyDao.delete(url) }

  override suspend fun clear() = withContext(ioDispatcher) { historyDao.clear() }

  private companion object {
    const val BLANK_PAGE = "about:blank"

    /** How many rows to read per row shown, so folding by host still fills the dial. */
    const val HOST_FOLD_FACTOR = 4
  }
}
