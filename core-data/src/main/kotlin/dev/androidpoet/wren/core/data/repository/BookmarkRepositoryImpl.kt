/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import androidx.annotation.VisibleForTesting
import dev.androidpoet.wren.core.database.BookmarkDao
import dev.androidpoet.wren.core.database.entity.BookmarkEntity
import dev.androidpoet.wren.core.database.entity.mapper.asDomain
import dev.androidpoet.wren.core.model.Bookmark
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@VisibleForTesting
class BookmarkRepositoryImpl @Inject constructor(
  private val bookmarkDao: BookmarkDao,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : BookmarkRepository {

  override fun observeAll(): Flow<List<Bookmark>> = bookmarkDao.observeAll()
    .map { entities -> entities.asDomain() }
    .flowOn(ioDispatcher)

  override fun observeIsBookmarked(url: String): Flow<Boolean> =
    bookmarkDao.observeIsBookmarked(url).flowOn(ioDispatcher)

  override suspend fun toggle(url: String, title: String): Boolean = withContext(ioDispatcher) {
    if (bookmarkDao.isBookmarked(url)) {
      bookmarkDao.delete(url)
      false
    } else {
      bookmarkDao.upsert(
        BookmarkEntity(
          url = url,
          title = title.ifBlank { url },
          createdAt = System.currentTimeMillis(),
        ),
      )
      true
    }
  }

  override suspend fun delete(url: String) = withContext(ioDispatcher) { bookmarkDao.delete(url) }

  override suspend fun setFolder(url: String, folder: String) = withContext(ioDispatcher) {
    bookmarkDao.setFolder(url, folder)
  }

  override suspend fun clear() = withContext(ioDispatcher) { bookmarkDao.clear() }
}
