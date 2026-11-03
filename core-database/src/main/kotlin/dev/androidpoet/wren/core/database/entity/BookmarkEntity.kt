/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
  @PrimaryKey val url: String,
  val title: String,
  val createdAt: Long,
  @ColumnInfo(defaultValue = "") val folder: String = "",
)
