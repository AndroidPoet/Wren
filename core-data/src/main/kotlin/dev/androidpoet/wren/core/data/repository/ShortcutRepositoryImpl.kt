/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import androidx.annotation.VisibleForTesting
import dev.androidpoet.wren.core.database.ShortcutDao
import dev.androidpoet.wren.core.database.entity.ShortcutEntity
import dev.androidpoet.wren.core.database.entity.mapper.asDomain
import dev.androidpoet.wren.core.model.Shortcut
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@VisibleForTesting
class ShortcutRepositoryImpl @Inject constructor(
  private val shortcutDao: ShortcutDao,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : ShortcutRepository {

  override fun observeAll(): Flow<List<Shortcut>> = shortcutDao.observeAll()
    .map { entities -> entities.asDomain() }
    .flowOn(ioDispatcher)

  override suspend fun add(url: String, title: String) = withContext(ioDispatcher) {
    shortcutDao.upsert(
      ShortcutEntity(
        url = url,
        title = title.ifBlank { url },
        position = shortcutDao.nextPosition(),
      ),
    )
  }

  override suspend fun remove(url: String) = withContext(ioDispatcher) {
    shortcutDao.delete(url)
  }

  override suspend fun seedDefaultsIfEmpty() = withContext(ioDispatcher) {
    if (shortcutDao.count() > 0) return@withContext
    shortcutDao.insertAll(
      ShortcutRepository.DEFAULTS.mapIndexed { index, (title, url) ->
        ShortcutEntity(url = url, title = title, position = index)
      },
    )
  }
}
