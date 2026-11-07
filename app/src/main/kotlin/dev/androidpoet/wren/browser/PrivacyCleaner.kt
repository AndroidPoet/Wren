/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.browser.blocking.HostMatcher
import dev.androidpoet.wren.browser.favicon.FaviconStore
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import dev.androidpoet.wren.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single place that knows every store a page can write to, so "clear data" is actually complete.
 *
 * Complete, with one deliberate exception: the sites a person has asked to stay signed in to.
 * Their cookies are read out before the jar is emptied and written back after, and their
 * storage origins are skipped, so a clear ends every other site's session and none of theirs.
 */
@Singleton
class PrivacyCleaner @Inject constructor(
  private val historyRepository: HistoryRepository,
  private val settings: SettingsStore,
  private val faviconStore: FaviconStore,
) {
  /** Clears everything a site left behind. [webView] is the live view whose cache is dropped. */
  suspend fun clearAll(webView: WebView?, includeHistory: Boolean) {
    clearSiteData(webView)
    if (includeHistory) {
      historyRepository.clear()
      faviconStore.clear()
    }
  }

  /**
   * Under Tor nothing is kept: a new identity that introduces itself with the old session
   * cookie is the old identity. Elsewhere the kept sites' cookies come back; their other
   * storage does not, because the engine's one complete wipe cannot spare an origin.
   */
  fun clearSiteData(webView: WebView?) {
    val kept = if (settings.torMode) {
      emptySet()
    } else {
      settings.keptSignedInHosts().map(HostMatcher::registrableDomain).toSet()
    }
    val cookies = CookieManager.getInstance()
    val saved = cookiesToKeep(cookies, kept)
    val restore = {
      saved.forEach { (url, cookie) -> cookies.setCookie(url, cookie) }
      cookies.flush()
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
      // Cookies, cache, every storage API, service workers and their caches, in one call:
      // the per-API calls below leave localStorage behind when an origin is spared and never
      // touched service workers at all.
      WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { restore() }
    } else {
      cookies.removeAllCookies { restore() }
      clearStorageExcept(kept)
    }
    webView?.apply {
      clearCache(true)
      clearFormData()
      clearHistory()
      clearSslPreferences()
    }
    webView?.context?.let { WebViewDatabase.getInstance(it).clearHttpAuthUsernamePassword() }
  }

  /**
   * Each kept cookie is read back as the full `Set-Cookie` line it was stored with, so it
   * returns with its own domain, path, expiry, `HttpOnly` and `SameSite`. Rebuilding those from
   * a bare name=value pair would hand a session cookie to scripts and to every subdomain, so
   * where the WebView cannot report them the site is simply not kept signed in.
   */
  private fun cookiesToKeep(
    cookies: CookieManager,
    domains: Set<String>,
  ): List<Pair<String, String>> {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) return emptyList()
    return domains.flatMap { domain ->
      val seen = mutableSetOf<String>()
      listOf("https://$domain", "https://www.$domain").flatMap { url ->
        runCatching { CookieManagerCompat.getCookieInfo(cookies, url) }.getOrDefault(emptyList())
          .filter { line -> line.contains('=') && seen.add(line.substringBefore('=').trim()) }
          .map { line -> url to line }
      }
    }
  }

  private fun clearStorageExcept(domains: Set<String>) {
    val storage = WebStorage.getInstance()
    if (domains.isEmpty()) {
      storage.deleteAllData()
      return
    }
    storage.getOrigins { origins ->
      origins?.keys?.forEach { origin ->
        val host = HostMatcher.hostOf(origin.toString())
        if (host == null || HostMatcher.registrableDomain(host) !in domains) {
          storage.deleteOrigin(origin.toString())
        }
      }
    }
  }
}
