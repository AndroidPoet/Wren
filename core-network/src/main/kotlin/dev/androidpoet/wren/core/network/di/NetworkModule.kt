/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.androidpoet.wren.core.network.DeferredProxySelector
import dev.androidpoet.wren.core.network.interceptor.HttpRequestInterceptor
import dev.androidpoet.wren.core.network.service.SuggestClient
import dev.androidpoet.wren.core.network.service.SuggestService
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

  @Provides
  @Singleton
  fun provideOkHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
      .addInterceptor(HttpRequestInterceptor())
      // Autocomplete is a keystroke-latency path: a slow answer is worse than no answer.
      .connectTimeout(SUGGEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(SUGGEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .cookieJar(okhttp3.CookieJar.NO_COOKIES)
      .proxySelector(DeferredProxySelector)
      .build()
  }

  @Provides
  @Singleton
  fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
    return Retrofit.Builder()
      .client(okHttpClient)
      .baseUrl("https://duckduckgo.com/")
      .addConverterFactory(MoshiConverterFactory.create())
      .build()
  }

  @Provides
  @Singleton
  fun provideSuggestService(retrofit: Retrofit): SuggestService {
    return retrofit.create(SuggestService::class.java)
  }

  @Provides
  @Singleton
  fun provideSuggestClient(suggestService: SuggestService): SuggestClient {
    return SuggestClient(suggestService)
  }

  private const val SUGGEST_TIMEOUT_SECONDS = 5L
}
