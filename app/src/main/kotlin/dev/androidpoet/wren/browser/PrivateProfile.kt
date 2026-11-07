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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
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
class PrivateProfile @Inject constructor(@ApplicationContext private val context: Context) {

  val isSupported: Boolean
    get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

  /** Binds a freshly created WebView to the private profile. Must run before its first load. */
  fun attach(webView: WebView): Boolean {
    if (!isSupported) return false
    return runCatching {
      ProfileStore.getInstance().getOrCreateProfile(NAME)
      WebViewCompat.setProfile(webView, NAME)
    }.onFailure { Timber.w(it, "private profile unavailable; falling back to write-nothing") }
      .isSuccess
  }

  /** True once [attach] has really bound this view; a failed attach leaves it on the default. */
  fun isAttached(webView: WebView): Boolean = isSupported &&
    runCatching { WebViewCompat.getProfile(webView).name == NAME }.getOrDefault(false)

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
    val deleted = runCatching { ProfileStore.getInstance().deleteProfile(NAME) }
    if (deleted.isSuccess) {
      if (deleted.getOrThrow()) Timber.i("private profile deleted")
      return
    }
    Timber.d("private profile in use; wiping its stores instead")
    wipe()
  }

  private fun wipe() {
    val profile = runCatching { ProfileStore.getInstance().getProfile(NAME) }.getOrNull() ?: return
    runCatching {
      profile.cookieManager.removeAllCookies(null)
      profile.cookieManager.flush()
      profile.webStorage.deleteAllData()
      profile.geolocationPermissions.clearAll()
      WebView(context).apply {
        WebViewCompat.setProfile(this, NAME)
        clearCache(true)
        destroy()
      }
    }.onFailure { Timber.w(it, "could not wipe the private profile") }
      .onSuccess { Timber.i("private profile wiped") }
  }

  private inline fun <T> profile(block: (androidx.webkit.Profile) -> T): T? {
    if (!isSupported) return null
    return runCatching { ProfileStore.getInstance().getProfile(NAME)?.let(block) }.getOrNull()
  }

  private companion object {
    const val NAME = "private"
  }
}
