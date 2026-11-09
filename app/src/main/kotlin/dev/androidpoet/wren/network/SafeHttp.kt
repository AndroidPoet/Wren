/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.network

import dev.androidpoet.wren.core.network.DeferredProxySelector
import dev.androidpoet.wren.settings.SettingsStore
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one HTTP client for everything the browser fetches outside a WebView: downloads, icons
 * and filter lists. It follows the Tor proxy the moment it changes, drops its connections on
 * that change, and under Tor refuses any route that is not the tunnel ([TorRouteGuard]).
 *
 * It follows no redirects and keeps no cookies: each caller decides every hop and every
 * header, because what is right for a download is wrong for a filter list.
 */
@Singleton
class SafeHttp @Inject constructor(settings: SettingsStore) {
  val client: OkHttpClient = OkHttpClient.Builder()
    .proxySelector(DeferredProxySelector)
    .eventListener(TorRouteGuard { settings.torMode })
    .followRedirects(false)
    .followSslRedirects(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()
    .also(DeferredProxySelector::track)

  private companion object {
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val READ_TIMEOUT_MS = 60_000L
  }
}
