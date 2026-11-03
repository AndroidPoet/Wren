/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.di

import android.app.Application
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.androidpoet.wren.core.database.BookmarkDao
import dev.androidpoet.wren.core.database.DownloadDao
import dev.androidpoet.wren.core.database.HistoryDao
import dev.androidpoet.wren.core.database.SessionDao
import dev.androidpoet.wren.core.database.ShortcutDao
import dev.androidpoet.wren.core.database.WrenDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

  @Provides
  @Singleton
  fun provideAppDatabase(application: Application): WrenDatabase {
    return Room
      .databaseBuilder(application, WrenDatabase::class.java, "wren.db")
      .fallbackToDestructiveMigration(dropAllTables = true)
      .build()
  }

  @Provides
  @Singleton
  fun provideHistoryDao(appDatabase: WrenDatabase): HistoryDao = appDatabase.historyDao()

  @Provides
  @Singleton
  fun provideBookmarkDao(appDatabase: WrenDatabase): BookmarkDao = appDatabase.bookmarkDao()

  @Provides
  @Singleton
  fun provideShortcutDao(appDatabase: WrenDatabase): ShortcutDao = appDatabase.shortcutDao()

  @Provides
  @Singleton
  fun provideSessionDao(database: WrenDatabase): SessionDao = database.sessionDao()

  @Provides
  @Singleton
  fun provideDownloadDao(appDatabase: WrenDatabase): DownloadDao = appDatabase.downloadDao()
}
