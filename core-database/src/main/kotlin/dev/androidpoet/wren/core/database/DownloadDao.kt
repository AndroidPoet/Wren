/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.androidpoet.wren.core.database.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: DownloadEntity)

  @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
  fun observeAll(): Flow<List<DownloadEntity>>

  @Query("SELECT * FROM downloads WHERE id = :id")
  suspend fun getById(id: Long): DownloadEntity?

  @Query(
    "UPDATE downloads SET status = :status, localUri = :localUri, totalBytes = :totalBytes " +
      "WHERE id = :id",
  )
  suspend fun updateStatus(id: Long, status: String, localUri: String?, totalBytes: Long)

  @Query("DELETE FROM downloads WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM downloads WHERE status IN ('COMPLETE','FAILED','CANCELLED')")
  suspend fun clearFinished()

  @Query("DELETE FROM downloads WHERE isPrivate = 1")
  suspend fun clearPrivate()
}
