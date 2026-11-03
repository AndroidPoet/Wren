/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import dev.androidpoet.wren.core.database.entity.BookmarkEntity
import dev.androidpoet.wren.core.database.entity.DownloadEntity
import dev.androidpoet.wren.core.database.entity.HistoryEntity
import dev.androidpoet.wren.core.database.entity.SessionEntity
import dev.androidpoet.wren.core.database.entity.ShortcutEntity

@Database(
  entities = [
    HistoryEntity::class,
    BookmarkEntity::class,
    ShortcutEntity::class,
    DownloadEntity::class,
    SessionEntity::class,
  ],
  version = 5,
  exportSchema = true,
  // Adding a table is a migration Room can write itself, and it keeps the history, bookmarks and
  // shortcuts that a destructive fallback would have thrown away.
  autoMigrations = [
    AutoMigration(from = 1, to = 2),
    AutoMigration(from = 2, to = 3),
    AutoMigration(from = 3, to = 4),
    AutoMigration(from = 4, to = 5),
  ],
)
abstract class WrenDatabase : RoomDatabase() {

  abstract fun historyDao(): HistoryDao
  abstract fun bookmarkDao(): BookmarkDao
  abstract fun shortcutDao(): ShortcutDao
  abstract fun downloadDao(): DownloadDao

  abstract fun sessionDao(): SessionDao
}
