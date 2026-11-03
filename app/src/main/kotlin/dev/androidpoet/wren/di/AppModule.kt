/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.di

import android.app.Application
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

  @Provides
  @Singleton
  fun provideSharedPreferences(application: Application): SharedPreferences =
    application.getSharedPreferences("wren_settings", android.content.Context.MODE_PRIVATE)
}
