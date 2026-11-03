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
import dev.androidpoet.wren.core.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: BookmarkEntity)

  @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
  fun observeAll(): Flow<List<BookmarkEntity>>

  @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
  fun observeIsBookmarked(url: String): Flow<Boolean>

  @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
  suspend fun isBookmarked(url: String): Boolean

  @Query("DELETE FROM bookmarks WHERE url = :url")
  suspend fun delete(url: String)

  @Query("DELETE FROM bookmarks")
  suspend fun clear()

  @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
  suspend fun getAll(): List<BookmarkEntity>

  @Query("UPDATE bookmarks SET folder = :folder WHERE url = :url")
  suspend fun setFolder(url: String, folder: String)
}
