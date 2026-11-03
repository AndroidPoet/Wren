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
import dev.androidpoet.wren.browser.blocking.HostMatcher
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
) {
  /** Clears everything a site left behind. [webView] is the live view whose cache is dropped. */
  suspend fun clearAll(webView: WebView?, includeHistory: Boolean) {
    clearSiteData(webView)
    if (includeHistory) historyRepository.clear()
  }

  fun clearSiteData(webView: WebView?) {
    val kept = settings.keptSignedInHosts().map(HostMatcher::registrableDomain).toSet()
    val cookies = CookieManager.getInstance()
    val saved = cookiesToKeep(cookies, kept)
    cookies.removeAllCookies {
      saved.forEach { (url, cookie) -> cookies.setCookie(url, cookie) }
      cookies.flush()
    }
    clearStorageExcept(kept)
    webView?.apply {
      clearCache(true)
      clearFormData()
      clearHistory()
      clearSslPreferences()
    }
    webView?.context?.let { WebViewDatabase.getInstance(it).clearHttpAuthUsernamePassword() }
  }

  /**
   * The cookie jar hands back only  pairs, so the attributes are rebuilt: scoped to
   * the site's registrable domain, secure, and a year long. A login cookie survives; what it
   * loses is its exact expiry, which the next sign-in refreshes anyway.
   */
  private fun cookiesToKeep(
    cookies: CookieManager,
    domains: Set<String>,
  ): List<Pair<String, String>> = domains.flatMap { domain ->
    val url = "https://$domain"
    val seen = mutableSetOf<String>()
    listOf(url, "https://www.$domain").flatMap { candidate ->
      cookies.getCookie(candidate).orEmpty().split(';')
        .map(String::trim)
        .filter { it.contains('=') && seen.add(it.substringBefore('=')) }
        .map { pair -> url to "$pair; Domain=$domain; Path=/; Max-Age=$ONE_YEAR_SECONDS; Secure" }
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

  private companion object {
    const val ONE_YEAR_SECONDS = 365L * 24 * 60 * 60
  }
}
