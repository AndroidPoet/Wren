/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {

  fun observeAll(): Flow<List<Download>>

  suspend fun record(download: Download)

  suspend fun get(id: Long): Download?

  suspend fun updateStatus(id: Long, status: DownloadStatus, localUri: String?, totalBytes: Long)

  suspend fun remove(id: Long)

  suspend fun clearFinished()

  /** Forgets every download a private tab started; the files themselves stay. */
  suspend fun clearPrivate()
}
