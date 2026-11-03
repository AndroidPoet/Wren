/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * History is aggregated per URL rather than per visit: a browser that keeps one row per page load
 * grows without bound, and the omnibox only ever needs "when last" and "how often".
 */
@Entity(tableName = "history")
data class HistoryEntity(
  @PrimaryKey val url: String,
  val title: String,
  val lastVisitedAt: Long,
  val visitCount: Int,
)
