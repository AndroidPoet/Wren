/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.BuildConfig
import dev.androidpoet.wren.settings.SettingsStore
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies rendering and privacy policy to a [WebView].
 *
 * The goal for rendering is to land where Chrome lands: sites should serve their real mobile
 * layout, scroll without white bands, and keep their renderer alive across an app switch.
 * Everything privacy-related is a default the user can see and change in settings.
 */
@Singleton
class WebViewConfigurator @Inject constructor(
  private val settings: SettingsStore,
  private val privateProfile: PrivateProfile,
) {

  @SuppressLint("SetJavaScriptEnabled")
  fun configure(webView: WebView, isPrivate: Boolean) {
    // Remote debugging over chrome://inspect. Debug builds only - this exposes page contents
    // to any adb client, so it must never ship enabled.
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

    webView.settings.apply {
      javaScriptEnabled = settings.javaScriptEnabled

      // Storage. A private tab on its own profile may store whatever it likes: the profile is
      // deleted with the session. A WebView without profiles, or one where the profile did not
      // take, falls back to "writes nothing", the one meaning of private it can honour.
      val isolated = isPrivate && privateProfile.isAttached(webView)
      domStorageEnabled = !isPrivate || isolated
      cacheMode =
        if (isPrivate && !isolated) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT

      // Viewport: honour the page's own meta viewport and fit unresponsive pages to the screen.
      // This pair is what makes desktop-only sites arrive readable rather than zoomed into a
      // corner, and it is what Chrome does.
      useWideViewPort = true
      loadWithOverviewMode = true
      setSupportZoom(true)
      builtInZoomControls = true
      displayZoomControls = false

      textZoom = settings.textZoom

      loadsImagesAutomatically = true
      blockNetworkImage = false
      defaultTextEncodingName = "UTF-8"

      setSupportMultipleWindows(true)
      javaScriptCanOpenWindowsAutomatically = false
      mediaPlaybackRequiresUserGesture = true
      setGeolocationEnabled(false)

      // Password and form autofill are the browser's job, not the WebView's key/value store.
      saveFormData = false

      // A file:// page reading other local files is a long-standing exfiltration path.
      allowFileAccess = false
      allowContentAccess = false
      allowFileAccessFromFileURLs = false
      allowUniversalAccessFromFileURLs = false

      // Chrome's policy: insecure images, audio and video on an https page are upgraded to
      // https or dropped, and insecure scripts and frames are always blocked. Blocking every
      // mixed resource outright breaks the images on a long tail of older sites for no gain.
      mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

      userAgentString = userAgent(defaultUserAgent = userAgentString)
    }

    logSupportOnce()
    applyRenderingHints(webView)
    applySpeculativeLoading(webView, isPrivate)
    applyCookiePolicy(webView, isPrivate)
  }

  private var loggedSupport = false

  private fun logSupportOnce() {
    if (loggedSupport) return
    loggedSupport = true
    Timber.d(
      "webview perf: bfcache=%s speculativeLoading=%s offscreenPreRaster=%s",
      WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE),
      WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING),
      WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER),
    )
  }

  private fun applyRenderingHints(webView: WebView) {
    // Rasterise a little beyond the viewport so fast flings do not expose blank bands. This is
    // the single biggest scroll-quality win available to a WebView, at the cost of some memory.
    stripIdentifyingHeaders(webView)

    // The browser fetches favicons itself for the home tiles, so WebView downloading them
    // again per navigation is a wasted request on every page.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOWNLOAD_FAVICONS_ENABLED)) {
      runCatching { WebSettingsCompat.setDownloadFaviconsEnabled(webView.settings, false) }
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
      WebSettingsCompat.setOffscreenPreRaster(webView.settings, true)
    }

    // Safe Browsing checks go to Google through the system, not through the page's proxy, so
    // they would name every site visited while Tor is on.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
      WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, !settings.torMode)
    }

    // Passkeys and security keys. Browser mode hands the page's own origin to Credential Manager,
    // which is what lets any site sign in with a passkey rather than only this app's domains.
    // Off under Tor: the credential provider reaches its servers outside the proxy.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
      WebSettingsCompat.setWebAuthenticationSupport(
        webView.settings,
        if (settings.torMode) {
          WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
        } else {
          WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
        },
      )
    }

    // Back/forward cache: the previous page is kept alive in memory and restored whole on Back,
    // instead of being rebuilt and refetched. This is the single largest navigation-speed win a
    // modern WebView offers. In-memory only, and torn down with the tab, so it is safe for
    // private tabs too. No-ops on a WebView too old to have it.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
      WebSettingsCompat.setBackForwardCacheEnabled(webView.settings, true)
    }

    // Algorithmic darkening repaints sites that have no dark theme of their own. Chrome leaves
    // that off by default because it mangles images and low-contrast text, so it is opt-in here
    // too. Sites that support prefers-color-scheme still get dark either way, because the app
    // theme declares itself dark.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
      WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, settings.darkenWebPages)
    }

    // Thin overlay scrollbars that fade, rather than a permanent inset gutter.
    webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    webView.isScrollbarFadingEnabled = true
    webView.isVerticalScrollBarEnabled = true
    webView.isHorizontalScrollBarEnabled = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // The renderer of a visible page is important; one the user cannot see is not. Waiving
      // the priority while the browser is backgrounded lets the system reclaim it before it
      // reclaims the app the user is actually looking at. The tab comes back from its saved
      // state if that happens, which costs a reload, not the page.
      webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
    }
  }

  /**
   * Lets Chromium prerender the page it thinks is coming next - from a site's own speculation
   * rules, or a link the user is about to tap. Off in private tabs and when preloading is off,
   * since it fetches ahead of a committed navigation.
   */
  private fun applySpeculativeLoading(webView: WebView, isPrivate: Boolean) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING)) return
    val status = if (settings.preloadPages && !isPrivate) {
      WebSettingsCompat.SPECULATIVE_LOADING_PRERENDER_ENABLED
    } else {
      WebSettingsCompat.SPECULATIVE_LOADING_DISABLED
    }
    runCatching { WebSettingsCompat.setSpeculativeLoadingStatus(webView.settings, status) }
  }

  fun applyCookiePolicy(webView: WebView, isPrivate: Boolean) {
    if (isPrivate && privateProfile.isAttached(webView)) {
      // Cookies land in the private profile's own jar, which dies with the session. The
      // default manager is never touched from here: its settings are global to every tab.
      val jar = privateProfile.cookieManager()
      if (jar != null) {
        jar.setAcceptCookie(true)
        jar.setAcceptThirdPartyCookies(webView, !settings.blockThirdPartyCookies)
        return
      }
    }
    CookieManager.getInstance().apply {
      setAcceptCookie(!isPrivate)
      setAcceptThirdPartyCookies(webView, !isPrivate && !settings.blockThirdPartyCookies)
    }
  }

  /** Stops WebView broadcasting the app's package name to every server it talks to. */
  private fun stripIdentifyingHeaders(webView: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
      runCatching {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
      }
    }
  }

  /**
   * Applies the settings that vary by site. JavaScript takes effect for the page about to run;
   * the user agent takes effect from the next request, which is why the sheet reloads after
   * toggling.
   */
  @SuppressLint("SetJavaScriptEnabled")
  fun applyForHost(webView: WebView, host: String) {
    webView.settings.javaScriptEnabled =
      settings.javaScriptEnabled && !settings.isJsDisabledFor(host)
    val desktop = settings.desktopMode || settings.isDesktopSite(host)
    val wanted = if (desktop) {
      DESKTOP_USER_AGENT
    } else {
      WebSettings.getDefaultUserAgent(webView.context).replace("; wv", "")
    }
    if (webView.settings.userAgentString != wanted) {
      webView.settings.userAgentString = wanted
    }
  }

  /** Headers sent with every top-level navigation the omnibox starts. */
  fun requestHeaders(): Map<String, String> =
    if (settings.sendDoNotTrack) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

  private fun userAgent(defaultUserAgent: String): String = when {
    settings.desktopMode -> DESKTOP_USER_AGENT
    // Drop the "; wv" WebView marker. Plenty of sites branch on it and serve a stripped-down
    // page or an app-install interstitial; without it they serve their normal mobile site,
    // which is most of the gap between "a WebView" and "a browser".
    else -> defaultUserAgent.replace("; wv", "")
  }

  private companion object {
    const val DESKTOP_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Safari/537.36"
  }
}
