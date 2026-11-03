/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.androidpoet.wren.core.data.repository.BackupRepository
import dev.androidpoet.wren.core.data.repository.BackupRepositoryImpl
import dev.androidpoet.wren.core.data.repository.BookmarkRepository
import dev.androidpoet.wren.core.data.repository.BookmarkRepositoryImpl
import dev.androidpoet.wren.core.data.repository.DownloadRepository
import dev.androidpoet.wren.core.data.repository.DownloadRepositoryImpl
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import dev.androidpoet.wren.core.data.repository.HistoryRepositoryImpl
import dev.androidpoet.wren.core.data.repository.SessionRepository
import dev.androidpoet.wren.core.data.repository.SessionRepositoryImpl
import dev.androidpoet.wren.core.data.repository.ShortcutRepository
import dev.androidpoet.wren.core.data.repository.ShortcutRepositoryImpl
import dev.androidpoet.wren.core.data.repository.SuggestionRepository
import dev.androidpoet.wren.core.data.repository.SuggestionRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {

  @Binds
  @Singleton
  fun bindsDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

  @Binds
  fun bindsBackupRepository(impl: BackupRepositoryImpl): BackupRepository

  @Binds
  fun bindsSessionRepository(impl: SessionRepositoryImpl): SessionRepository

  @Binds
  @Singleton
  fun bindsHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

  @Binds
  @Singleton
  fun bindsBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

  @Binds
  @Singleton
  fun bindsShortcutRepository(impl: ShortcutRepositoryImpl): ShortcutRepository

  @Binds
  @Singleton
  fun bindsSuggestionRepository(impl: SuggestionRepositoryImpl): SuggestionRepository
}
