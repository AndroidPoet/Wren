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
import dev.androidpoet.wren.core.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: SessionEntity)

  @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
  fun observeAll(): Flow<List<SessionEntity>>

  @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
  suspend fun getAll(): List<SessionEntity>

  @Query("SELECT * FROM sessions WHERE name = :name")
  suspend fun get(name: String): SessionEntity?

  @Query("DELETE FROM sessions WHERE name = :name")
  suspend fun delete(name: String)
}
