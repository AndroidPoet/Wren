/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import androidx.annotation.VisibleForTesting
import dev.androidpoet.wren.core.database.DownloadDao
import dev.androidpoet.wren.core.database.entity.mapper.asDomain
import dev.androidpoet.wren.core.database.entity.mapper.asEntity
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@VisibleForTesting
class DownloadRepositoryImpl @Inject constructor(
  private val downloadDao: DownloadDao,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {

  override fun observeAll(): Flow<List<Download>> =
    downloadDao.observeAll().map { it.asDomain() }.flowOn(ioDispatcher)

  override suspend fun record(download: Download) = withContext(ioDispatcher) {
    downloadDao.upsert(download.asEntity())
  }

  override suspend fun get(id: Long): Download? =
    withContext(ioDispatcher) { downloadDao.getById(id)?.asDomain() }

  override suspend fun updateStatus(
    id: Long,
    status: DownloadStatus,
    localUri: String?,
    totalBytes: Long,
  ) = withContext(ioDispatcher) {
    downloadDao.updateStatus(id, status.name, localUri, totalBytes)
  }

  override suspend fun remove(id: Long) = withContext(ioDispatcher) { downloadDao.delete(id) }

  override suspend fun clearFinished() = withContext(ioDispatcher) { downloadDao.clearFinished() }

  override suspend fun clearPrivate() = withContext(ioDispatcher) { downloadDao.clearPrivate() }
}
