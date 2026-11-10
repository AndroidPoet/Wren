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
import dev.androidpoet.wren.core.database.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: HistoryEntity)

  @Query("SELECT * FROM history ORDER BY lastVisitedAt DESC LIMIT :limit")
  fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

  @Query(
    """
    SELECT * FROM history
    WHERE url NOT LIKE '%/?q=%' AND url NOT LIKE '%&q=%'
    ORDER BY visitCount DESC, lastVisitedAt DESC
    LIMIT :limit
    """,
  )
  fun observeMostVisited(limit: Int): Flow<List<HistoryEntity>>

  @Query("SELECT * FROM history WHERE url = :url")
  suspend fun findByUrl(url: String): HistoryEntity?

  @Query(
    """
    SELECT * FROM history
    WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%'
    ORDER BY visitCount DESC, lastVisitedAt DESC
    LIMIT :limit
    """,
  )
  suspend fun search(query: String, limit: Int): List<HistoryEntity>

  @Query("DELETE FROM history WHERE url = :url")
  suspend fun delete(url: String)

  @Query("DELETE FROM history WHERE url IN (:urls)")
  suspend fun deleteAll(urls: List<String>)

  @Query("DELETE FROM history")
  suspend fun clear()

  @Query("SELECT * FROM history ORDER BY lastVisitedAt DESC")
  suspend fun getAll(): List<HistoryEntity>
}
