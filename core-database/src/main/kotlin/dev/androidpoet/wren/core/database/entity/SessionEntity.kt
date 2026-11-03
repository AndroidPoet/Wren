/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Tabs are held as a JSON array of {url,title}; a session is small and read whole. */
@Entity(tableName = "sessions")
data class SessionEntity(
  @PrimaryKey val name: String,
  val tabsJson: String,
  val updatedAt: Long,
)
