/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidpoet.wren.settings.SettingsStore
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The storage that private tabs live in: a second WebView profile with its own cookie jar,
 * cache, local storage and service workers, deleted whole when the private session ends.
 *
 * Without it "private" could only mean "writes nothing": the tab still read the normal
 * profile's cookies, so a private tab was logged in wherever the user was logged in.
 */
@Singleton
class PrivateProfile @Inject constructor(
  @ApplicationContext private val context: Context,
  private val torProfile: TorProfile,
  private val settings: SettingsStore,
) {

  val isSupported: Boolean
    get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

  /** Every name attached in this process, so a route change leaves none of them behind. */
  private val attached = LinkedHashSet<String>()

  /**
   * Private tabs over Tor get a profile of their own, tied to the Tor identity, so a site
   * visited privately over the real address shares nothing with the same site visited
   * privately through an exit, and a new identity ends the private session too.
   */
  private val name: String
    get() = if (settings.torMode) "$PREFIX-${torProfile.identityName}" else PREFIX

  /** Binds a freshly created WebView to the private profile. Must run before its first load. */
  fun attach(webView: WebView): Boolean {
    if (!isSupported) return false
    val profileName = name
    return runCatching {
      ProfileCache.minimise(ProfileStore.getInstance().getOrCreateProfile(profileName))
      WebViewCompat.setProfile(webView, profileName)
      attached.add(profileName)
    }.onFailure { Timber.w(it, "private profile unavailable") }
      .isSuccess
  }

  /** True once [attach] has really bound this view; a failed attach leaves it on the default. */
  fun isAttached(webView: WebView): Boolean = isSupported &&
    runCatching { WebViewCompat.getProfile(webView).name.startsWith(PREFIX) }.getOrDefault(false)

  fun cookieManager(): CookieManager? = profile { it.cookieManager }

  fun serviceWorkerController(): ServiceWorkerController? = profile { it.serviceWorkerController }

  /**
   * Erases everything the private session stored. The store only deletes a profile it has not
   * loaded in this process, so that path serves a cold start; once a private WebView has
   * existed the profile counts as in use until the process dies, and its stores are emptied
   * one by one instead. The directory itself goes on the next cold start.
   */
  fun destroy() {
    if (!isSupported) return
    val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
    // Whatever earlier processes or routes left behind goes too; the names all share the prefix.
    val names = LinkedHashSet<String>(attached)
    names.add(name)
    runCatching { store.allProfileNames.filterTo(names) { it.startsWith(PREFIX) } }
    attached.clear()
    for (profileName in names) {
      val deleted = runCatching { store.deleteProfile(profileName) }
      if (deleted.isSuccess) {
        if (deleted.getOrThrow()) Timber.i("private profile %s deleted", profileName)
        continue
      }
      Timber.d("private profile %s in use; wiping its stores instead", profileName)
      wipe(profileName)
      attached.add(profileName)
    }
  }

  private fun wipe(profileName: String) {
    val profile = runCatching { ProfileStore.getInstance().getProfile(profileName) }.getOrNull()
      ?: return
    runCatching {
      profile.cookieManager.removeAllCookies(null)
      profile.cookieManager.flush()
      profile.geolocationPermissions.clearAll()
      if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
        // Storage, service workers, their caches and the HTTP cache in one call.
        WebStorageCompat.deleteBrowsingData(profile.webStorage) {}
      } else {
        profile.webStorage.deleteAllData()
      }
      WebView(context).apply {
        WebViewCompat.setProfile(this, profileName)
        clearCache(true)
        destroy()
      }
    }.onFailure { Timber.w(it, "could not wipe the private profile") }
      .onSuccess { Timber.i("private profile %s wiped", profileName) }
  }

  private inline fun <T> profile(block: (androidx.webkit.Profile) -> T): T? {
    if (!isSupported) return null
    return runCatching { ProfileStore.getInstance().getProfile(name)?.let(block) }.getOrNull()
  }

  private companion object {
    const val PREFIX = "private"
  }
}
