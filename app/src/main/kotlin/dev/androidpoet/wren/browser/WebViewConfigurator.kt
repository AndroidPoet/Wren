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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.BuildConfig
import dev.androidpoet.wren.browser.privacy.PrivacyHeaders
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
  private val torProfile: TorProfile,
) {

  @SuppressLint("SetJavaScriptEnabled")
  fun configure(webView: WebView, isPrivate: Boolean) {
    // Remote debugging over chrome://inspect. Debug builds only - this exposes page contents
    // to any adb client, so it must never ship enabled.
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

    // The platform Autofill service is handed every page's domain along with its fields, and
    // Google's asks its servers from the real address. A private or Tor page shows it nothing.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      webView.importantForAutofill = if (isPrivate || settings.torMode) {
        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
      } else {
        View.IMPORTANT_FOR_AUTOFILL_AUTO
      }
    }

    webView.settings.apply {
      javaScriptEnabled = settings.javaScriptEnabled && !settings.torSafestActive

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
      // mixed resource outright breaks the images on a long tail of older sites, so that is
      // reserved for HTTPS-only mode, where the user has asked for exactly that.
      mixedContentMode = if (settings.httpsOnly) {
        WebSettings.MIXED_CONTENT_NEVER_ALLOW
      } else {
        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
      }

      userAgentString = userAgentFor(webView, desktop = settings.desktopMode)
    }
    applyUserAgentMetadata(webView, desktop = settings.desktopMode)

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

    // GPC and DNT on every request of this view's profile, where the engine supports it.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
      runCatching {
        // Tor Browser sends neither: a signal few browsers send is itself a mark.
        PrivacyHeaders.apply(
          WebViewCompat.getProfile(webView),
          settings.sendDoNotTrack && !settings.torMode,
        )
      }
    }

    // Attribution Reporting lets a page register ad conversions with the Android advertising
    // stack, outside the page and past the blocker. Off, on every engine that has the switch.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
      runCatching {
        WebSettingsCompat.setAttributionRegistrationBehavior(
          webView.settings,
          WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED,
        )
      }
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
    // Off under Tor as well: a prerender tells the exit about pages never opened.
    val status = if (settings.preloadPages && !isPrivate && !settings.torMode) {
      WebSettingsCompat.SPECULATIVE_LOADING_PRERENDER_ENABLED
    } else {
      WebSettingsCompat.SPECULATIVE_LOADING_DISABLED
    }
    runCatching { WebSettingsCompat.setSpeculativeLoadingStatus(webView.settings, status) }
  }

  fun applyCookiePolicy(webView: WebView, isPrivate: Boolean) {
    // Cookies land in the private or Tor profile's own jar, which dies with the session. The
    // default manager is never touched from here: its settings are global to every tab.
    val jar = when {
      isPrivate && privateProfile.isAttached(webView) -> privateProfile.cookieManager()
      torProfile.isAttached(webView) -> torProfile.cookieManager()
      else -> null
    }
    // Under Tor the user's choice does not apply: a third-party cookie is the one identifier
    // that follows a Tor session from site to site, which is what the route exists to prevent.
    val thirdParty = !settings.blockThirdPartyCookies && !settings.torMode
    if (jar != null) {
      jar.setAcceptCookie(true)
      jar.setAcceptThirdPartyCookies(webView, thirdParty)
      return
    }
    // The default jar is shared by every normal tab, so only the per-view setting is touched
    // here; a process-wide switch flipped from one tab would reach the others' requests.
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !isPrivate && thirdParty)
  }

  /**
   * Asks WebView not to send the app's package name (`X-Requested-With`) to any server. The
   * engine only honours an empty allow-list once its deprecation of the header has rolled
   * out; on every WebView measured so far (up to 141) the header still goes to every site,
   * and nothing an app can call changes that. The call stays so the day the engine flips,
   * Wren is already quiet. The limit is documented in the README.
   */
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
    // Tor Browser's "Safest" level: no script runs, whatever the site or the global switch says.
    webView.settings.javaScriptEnabled =
      settings.javaScriptEnabled && !settings.isJsDisabledFor(host) && !settings.torSafestActive
    val desktop = settings.desktopMode || settings.isDesktopSite(host)
    val wanted = userAgentFor(webView, desktop)
    if (webView.settings.userAgentString != wanted) {
      webView.settings.userAgentString = wanted
      applyUserAgentMetadata(webView, desktop)
    }
  }

  /** Chrome's reduced user agent, mobile or desktop, on this device's engine version. */
  private fun userAgentFor(webView: WebView, desktop: Boolean): String {
    val major = chromeMajor(webView)
    return if (desktop) UserAgents.desktop(major) else UserAgents.mobile(major)
  }

  private fun applyUserAgentMetadata(webView: WebView, desktop: Boolean) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    runCatching {
      WebSettingsCompat.setUserAgentMetadata(
        webView.settings,
        UserAgents.metadata(chromeMajor(webView), desktop, engineVersion(webView)),
      )
    }.onFailure { Timber.w(it, "could not set user agent metadata") }
  }

  private var cachedChromeMajor = 0

  private fun chromeMajor(webView: WebView): Int {
    if (cachedChromeMajor != 0) return cachedChromeMajor
    val default = runCatching { WebSettings.getDefaultUserAgent(webView.context) }.getOrNull()
    return (UserAgents.chromeMajor(engineVersion(webView), default) ?: FALLBACK_CHROME_MAJOR)
      .also { cachedChromeMajor = it }
  }

  private fun engineVersion(webView: WebView): String? = runCatching {
    WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName
  }.getOrNull()

  /** Headers sent with every top-level navigation the omnibox starts. */
  fun requestHeaders(): Map<String, String> = buildMap {
    if (settings.sendDoNotTrack && !settings.torMode) {
      put("DNT", "1")
      put("Sec-GPC", "1")
    }
    // The Tor profile sets this for every request on engines with custom headers; this covers
    // the document on the ones without.
    if (settings.torMode) put(TorProfile.ACCEPT_LANGUAGE, TorProfile.ENGLISH)
    // Extra headers win over the engine's own, so the document request at least carries no
    // package name; subresources still do (see [stripIdentifyingHeaders]).
    put("X-Requested-With", "")
  }

  private companion object {
    /** Only reached when neither the WebView package nor its default UA names a version. */
    const val FALLBACK_CHROME_MAJOR = 133
  }
}
