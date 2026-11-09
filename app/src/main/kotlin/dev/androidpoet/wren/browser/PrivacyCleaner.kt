/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidpoet.wren.browser.blocking.HostMatcher
import dev.androidpoet.wren.browser.favicon.FaviconStore
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Single place that knows every store a page can write to, so "clear data" is actually complete.
 *
 * Complete, with one deliberate exception: the sites a person has asked to stay signed in to.
 * Their cookies are read out before the jar is emptied and written back after, and their
 * storage origins are skipped, so a clear ends every other site's session and none of theirs.
 */
@Singleton
class PrivacyCleaner @Inject constructor(
  @ApplicationContext private val context: Context,
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
  /**
   * Returns once the engine reports the deletion done, so a caller can say "cleared" and
   * open new pages knowing nothing of the old session is left to repopulate them. The engine
   * is given [DELETION_TIMEOUT_MS]; a callback that never comes must not hang the UI.
   */
  suspend fun clearSiteData(webView: WebView?) {
    withTimeoutOrNull(DELETION_TIMEOUT_MS) {
      suspendCancellableCoroutine { continuation ->
        clearSiteDataNow(webView) { if (continuation.isActive) continuation.resume(Unit) }
      }
    } ?: Timber.w("browsing data deletion did not report back in time")
  }

  /**
   * Fire-and-forget form for the one place that cannot wait: an activity being destroyed.
   * [onDone] runs when the engine has finished, if the process is still alive by then.
   */
  fun clearSiteDataNow(webView: WebView?, onDone: () -> Unit = {}) {
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
      onDone()
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
    // The cache and the per-view state need a WebView to speak for the profile. Without one
    // - a clear owed from a run that ended without a graceful exit - a throwaway stands in,
    // so the HTTP cache does not outlive the session it belongs to.
    val view = webView ?: if (Looper.getMainLooper().isCurrentThread) {
      runCatching { WebView(context) }.getOrNull()
    } else {
      null
    }
    view?.apply {
      clearCache(true)
      clearFormData()
      clearHistory()
      clearSslPreferences()
    }
    if (webView == null) view?.destroy()
    WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
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

  private companion object {
    const val DELETION_TIMEOUT_MS = 5_000L
  }
}
