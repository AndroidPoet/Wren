/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.Profile
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewStartUpConfig
import dagger.hilt.android.HiltAndroidApp
import dev.androidpoet.wren.browser.blocking.FilterListManager
import dev.androidpoet.wren.browser.privacy.PrivacyScripts
import dev.androidpoet.wren.browser.tor.TorController
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class WrenApp : Application() {

  @Inject
  lateinit var settings: SettingsStore

  @Inject
  lateinit var filterListManager: FilterListManager

  @Inject
  lateinit var privacyScripts: PrivacyScripts

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Inject
  lateinit var torController: TorController

  /**
   * True until the browser screen's first creation in this process. A screen recreated after
   * the process was killed arrives with saved state, like one recreated for a rotation, but
   * unlike that one it owes everything a cold start owes: the exit clean-up, fresh private and
   * Tor profiles, and no replay of the intent that launched the dead process.
   */
  @Volatile var firstBrowserCreate: Boolean = true
    private set

  fun takeFirstBrowserCreate(): Boolean = firstBrowserCreate.also { firstBrowserCreate = false }

  override fun onCreate() {
    super.onCreate()
    // The autocomplete client lives in a module without settings; its route guard asks here.
    dev.androidpoet.wren.core.network.TorRoute.isTorMode = { settings.torMode }
    // Applied before the first activity inflates, so there is no flash of the wrong theme.
    AppCompatDelegate.setDefaultNightMode(settings.themeMode)
    if (BuildConfig.DEBUG && Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
    startWebViewEarly()
    filterListManager.start()
    appScope.launch {
      privacyScripts.warmUp()
    }
    // Puts the proxy in front of WebView before any activity can load a page.
    torController.startIfEnabled()
  }

  /**
   * Kicks WebView's provider and profile loading onto a background thread now, so the browser
   * screen only pays the small chunked UI-thread remainder instead of the whole monolithic
   * first-WebView cost. Old WebView providers simply fall back to doing nothing here.
   */
  private fun startWebViewEarly() {
    runCatching {
      val builder = WebViewStartUpConfig.Builder(Executors.newSingleThreadExecutor())
      if (
        WebViewFeature.isStartupFeatureSupported(
          this,
          WebViewFeature.STARTUP_FEATURE_SET_UI_THREAD_STARTUP_MODE,
        )
      ) {
        builder.setShouldRunUiThreadStartUpTasks(false)
      }
      if (
        WebViewFeature.isStartupFeatureSupported(
          this,
          WebViewFeature.STARTUP_FEATURE_SET_PROFILES_TO_LOAD,
        )
      ) {
        builder.setProfilesToLoadDuringStartup(setOf(Profile.DEFAULT_PROFILE_NAME))
      }
      val config = builder.build()
      val callback = WebViewCompat.WebViewStartUpCallback { result ->
        Timber.d(
          "webview warm start done; ui-thread blocking locations: %d",
          result.uiThreadBlockingStartUpLocations?.size ?: 0,
        )
      }
      WebViewCompat.startUpWebView(this, config, callback)
    }.onFailure { Timber.w("async webview startup unavailable: %s", it.message) }
  }
}
