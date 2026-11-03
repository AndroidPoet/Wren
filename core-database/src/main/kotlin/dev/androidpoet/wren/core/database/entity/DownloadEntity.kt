/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
  @PrimaryKey val id: Long,
  val url: String,
  val fileName: String,
  val mimeType: String,
  val totalBytes: Long,
  val status: String,
  val localUri: String?,
  val createdAt: Long,
)
