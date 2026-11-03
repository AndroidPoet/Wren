/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Strips identifying headers from the autocomplete calls Wren makes on the user's behalf. The
 * omnibox sends keystrokes to the search engine, so it should not also send a referer, a cookie,
 * or a browser-shaped user agent that can be joined against browsing traffic.
 */
internal class HttpRequestInterceptor : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request().newBuilder()
      .removeHeader("Cookie")
      .removeHeader("Referer")
      .header("User-Agent", ANONYMOUS_USER_AGENT)
      .header("Accept", "application/json")
      .header("DNT", "1")
      .header("Sec-GPC", "1")
      .build()
    return chain.proceed(request)
  }

  private companion object {
    const val ANONYMOUS_USER_AGENT = "Mozilla/5.0 (compatible)"
  }
}
