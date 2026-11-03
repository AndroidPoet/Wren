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
import dev.androidpoet.wren.core.database.entity.ShortcutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: ShortcutEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(entities: List<ShortcutEntity>)

  @Query("SELECT * FROM shortcuts ORDER BY position ASC")
  fun observeAll(): Flow<List<ShortcutEntity>>

  @Query("SELECT COUNT(*) FROM shortcuts")
  suspend fun count(): Int

  @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM shortcuts")
  suspend fun nextPosition(): Int

  @Query("DELETE FROM shortcuts WHERE url = :url")
  suspend fun delete(url: String)

  @Query("SELECT * FROM shortcuts ORDER BY position ASC")
  suspend fun getAll(): List<ShortcutEntity>
}
