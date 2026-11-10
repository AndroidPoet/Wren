/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.Context
import android.os.LocaleList
import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.CustomHeader
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The storage Tor pages live in: a WebView profile of their own, never the one clear-net
 * browsing uses, thrown away whenever the identity ends.
 *
 * A profile is a whole network context: cookie jar, cache, storage, service workers, and also
 * the open connections, HSTS state and TLS session cache that no API clears one by one. Sharing
 * any of it between the two routes joins them: a session cookie set over the real address and
 * replayed through an exit, or the other way round, is the identity leak Tor exists to prevent.
 * Each identity gets a fresh name, because the store cannot delete a profile this process has
 * already loaded; such a profile is emptied instead and its directory goes on a later start.
 */
@Singleton
class TorProfile @Inject constructor(@ApplicationContext private val context: Context) {

  val isSupported: Boolean
    get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

  private var generation = 0

  /** The profile whose identity ended with [retireIdentity] and that [rotate] still owes. */
  private var retired: String? = null
  private var savedLocales: LocaleList? = null

  private val name: String get() = "$PREFIX$generation"

  /** The current identity's profile name; changes with every [rotate]. */
  val identityName: String get() = name

  /** Binds a freshly created WebView to the current identity. Must run before its first load. */
  fun attach(webView: WebView): Boolean {
    if (!isSupported) return false
    return runCatching {
      val profile = ProfileStore.getInstance().getOrCreateProfile(name)
      speakEnglish(profile)
      ProfileCache.minimise(profile)
      WebViewCompat.setProfile(webView, name)
    }.onFailure { Timber.w(it, "tor profile unavailable") }.isSuccess
  }

  fun isAttached(webView: WebView): Boolean = isSupported &&
    runCatching { WebViewCompat.getProfile(webView).name.startsWith(PREFIX) }.getOrDefault(false)

  fun cookieManager(): CookieManager? = current { it.cookieManager }

  fun webStorage(): WebStorage? = current { it.webStorage }

  fun serviceWorkerController(): ServiceWorkerController? = current { it.serviceWorkerController }

  /**
   * Ends the identity: the profile with everything in it is deleted, or emptied where it is
   * still loaded, and the next page gets a new one. Call with no Tor WebView alive.
   */
  /**
   * Ends the identity by name only: everything gated on [identityName] - the old profile's
   * service workers above all - is refused from this moment, before the circuits change.
   * The profile itself is deleted by the [rotate] that follows.
   */
  fun retireIdentity() {
    if (!isSupported || retired != null) return
    retired = name
    generation++
  }

  fun rotate() {
    if (!isSupported) return
    val old = retired ?: name.also { generation++ }
    retired = null
    val deleted = runCatching { ProfileStore.getInstance().deleteProfile(old) }
    if (deleted.isSuccess) {
      if (deleted.getOrThrow()) Timber.i("tor profile deleted")
      return
    }
    wipe(old)
  }

  /**
   * Tor Browser answers every site in `en-US`, so the language stops being a signal. Chromium
   * derives `Accept-Language` on every request and frame, `navigator.language` and the default
   * `Intl` locale from the process locale list, so that is what changes while Tor is on; the
   * profile header below only reaches engines that honour it. Call before the first Tor WebView
   * is created. The app's own strings keep the device language: they come from resources.
   */
  fun enterEnglish() {
    if (savedLocales != null) return
    savedLocales = LocaleList.getDefault()
    LocaleList.setDefault(LocaleList(Locale.US))
  }

  /** Restores the device language once no Tor WebView is alive. */
  fun leaveEnglish() {
    val saved = savedLocales ?: return
    savedLocales = null
    LocaleList.setDefault(saved)
  }

  val speaksEnglish: Boolean get() = savedLocales != null

  /** Deletes what earlier processes left behind. Safe to call on a cold start. */
  fun destroyAll() {
    if (!isSupported) return
    val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
    val names = runCatching { store.allProfileNames.filter { it.startsWith(PREFIX) } }
      .getOrElse { emptyList() }
    // One profile at a time: the first one still loaded must not spare the others.
    for (stale in names) {
      val deleted = runCatching { store.deleteProfile(stale) }
      if (deleted.isFailure) wipe(stale)
    }
  }

  /**
   * Tor Browser answers every site in `en-US`, so the language stops being a signal. The
   * header is set on the profile, which covers every request of every page, not only the
   * document; the page-side `navigator.language` is handled by tor.js.
   */
  private fun speakEnglish(profile: Profile) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) return
    runCatching {
      if (!profile.hasCustomHeader(ACCEPT_LANGUAGE)) {
        profile.addCustomHeader(CustomHeader(ACCEPT_LANGUAGE, ENGLISH, setOf("*")))
      }
    }.onFailure { Timber.w(it, "could not set the tor Accept-Language") }
  }

  private fun wipe(profileName: String) {
    val profile = runCatching { ProfileStore.getInstance().getProfile(profileName) }.getOrNull()
      ?: return
    runCatching {
      profile.cookieManager.removeAllCookies(null)
      profile.cookieManager.flush()
      profile.geolocationPermissions.clearAll()
      if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
        // Storage, service workers and their caches, HSTS and the HTTP cache in one call.
        WebStorageCompat.deleteBrowsingData(profile.webStorage) {}
      } else {
        profile.webStorage.deleteAllData()
      }
      WebView(context).apply {
        WebViewCompat.setProfile(this, profileName)
        clearCache(true)
        destroy()
      }
    }.onFailure { Timber.w(it, "could not wipe the tor profile") }
      .onSuccess { Timber.i("tor profile wiped") }
  }

  private inline fun <T> current(block: (Profile) -> T): T? {
    if (!isSupported) return null
    return runCatching { ProfileStore.getInstance().getProfile(name)?.let(block) }.getOrNull()
  }

  companion object {
    private const val PREFIX = "tor-"
    const val ACCEPT_LANGUAGE = "Accept-Language"
    const val ENGLISH = "en-US,en;q=0.9"
  }
}
