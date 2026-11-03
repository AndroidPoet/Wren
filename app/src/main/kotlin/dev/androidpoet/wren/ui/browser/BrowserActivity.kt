/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.AppLock
import dev.androidpoet.wren.browser.BrowserTab
import dev.androidpoet.wren.browser.PageSaver
import dev.androidpoet.wren.browser.PrivacyCleaner
import dev.androidpoet.wren.browser.PrivateProfile
import dev.androidpoet.wren.browser.QrCode
import dev.androidpoet.wren.browser.ReaderMode
import dev.androidpoet.wren.browser.RenderingMode
import dev.androidpoet.wren.browser.SpeculativeLoader
import dev.androidpoet.wren.browser.TabManager
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.browser.WebViewConfigurator
import dev.androidpoet.wren.browser.applyAppLock
import dev.androidpoet.wren.browser.blocking.BlockStats
import dev.androidpoet.wren.browser.blocking.ContentBlocker
import dev.androidpoet.wren.browser.blocking.CosmeticFilter
import dev.androidpoet.wren.browser.blocking.FilterListManager
import dev.androidpoet.wren.browser.blocking.HostMatcher
import dev.androidpoet.wren.browser.blocking.ServiceWorkerBlocker
import dev.androidpoet.wren.browser.favicon.FaviconStore
import dev.androidpoet.wren.browser.privacy.HttpsUpgrader
import dev.androidpoet.wren.browser.privacy.LinkCleaner
import dev.androidpoet.wren.browser.privacy.PrivacyScripts
import dev.androidpoet.wren.browser.tor.TorController
import dev.androidpoet.wren.browser.tor.TorState
import dev.androidpoet.wren.browser.tor.TorStatus
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.databinding.ActivityBrowserBinding
import dev.androidpoet.wren.download.BlobDownloader
import dev.androidpoet.wren.download.DownloadController
import dev.androidpoet.wren.settings.SettingsStore
import dev.androidpoet.wren.ui.home.MostVisitedAdapter
import dev.androidpoet.wren.ui.home.ShortcutAdapter
import dev.androidpoet.wren.ui.library.BookmarksActivity
import dev.androidpoet.wren.ui.library.HistoryActivity
import dev.androidpoet.wren.ui.menu.MenuAction
import dev.androidpoet.wren.ui.menu.MenuSheet
import dev.androidpoet.wren.ui.menu.MenuState
import dev.androidpoet.wren.ui.padForSystemBars
import dev.androidpoet.wren.ui.security.SiteSecurityInfo
import dev.androidpoet.wren.ui.security.SiteSecuritySheet
import dev.androidpoet.wren.ui.settings.SettingsActivity
import dev.androidpoet.wren.ui.tabs.TabThumbnails
import dev.androidpoet.wren.ui.tabs.TabsSheet
import dev.androidpoet.wren.ui.tor.TorSheet
import dev.androidpoet.wren.ui.useLightSystemBarIcons
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

/**
 * The whole browser. A single [WebView] renders whichever tab is active; [TabManager] holds the
 * saved state of the others.
 */
@AndroidEntryPoint
class BrowserActivity :
  AppCompatActivity(),
  SiteSecuritySheet.Host,
  MenuSheet.Host,
  TorSheet.Host {

  @Inject
  lateinit var configurator: WebViewConfigurator

  @Inject
  lateinit var privacyCleaner: PrivacyCleaner

  @Inject
  lateinit var settings: SettingsStore

  @Inject
  lateinit var downloadController: DownloadController

  @Inject
  lateinit var readerMode: ReaderMode

  @Inject
  lateinit var speculativeLoader: SpeculativeLoader

  @Inject
  lateinit var contentBlocker: ContentBlocker

  @Inject
  lateinit var cosmeticFilter: CosmeticFilter

  @Inject
  lateinit var privacyScripts: PrivacyScripts

  @Inject
  lateinit var blockStats: BlockStats

  @Inject
  lateinit var serviceWorkerBlocker: ServiceWorkerBlocker

  @Inject
  lateinit var privateProfile: PrivateProfile

  @Inject
  lateinit var filterListManager: FilterListManager

  @Inject
  lateinit var torController: TorController

  @Inject
  lateinit var appLock: AppLock

  @Inject
  lateinit var tabThumbnails: TabThumbnails

  @Inject
  lateinit var blobDownloader: BlobDownloader

  @Inject
  lateinit var sessionRepository: dev.androidpoet.wren.core.data.repository.SessionRepository

  @Inject
  lateinit var faviconStore: FaviconStore

  private val viewModel: BrowserViewModel by viewModels()

  private lateinit var binding: ActivityBrowserBinding

  /**
   * The live pages, most recently used last. Tabs beyond [maxLiveWebViews] fall back to saved
   * state: keeping every tab live would grow memory without bound, and keeping only one - as this
   * browser used to - meant a background tab could neither finish loading nor keep playing audio.
   */
  private val liveWebViews = linkedMapOf<Long, WebView>()

  private val webView: WebView? get() = liveWebViews[viewModel.tabManager.activeTabId.value]

  private val maxLiveWebViews: Int by lazy {
    val activityManager = getSystemService<android.app.ActivityManager>()
    if (activityManager?.isLowRamDevice == true) LOW_RAM_LIVE_WEB_VIEWS else DEFAULT_LIVE_WEB_VIEWS
  }
  private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
  private var isOmniboxFocused = false
  private var isHomeVisible = false

  private var lastTorStatus: TorStatus? = null

  /** Addresses Tor mode forced onto https, so a failure can be explained as the site's. */
  private val torForcedHttps = mutableSetOf<String>()
  private var appliedNightMode = 0
  private var blockedTotal = 0

  /** The address of the document actually on screen, which is the only one the omnibox may show. */
  private var committedUrl = UrlFormatter.NEW_TAB_URL
  private var isLoading = false

  /** Main-frame failures, so an error page is not filed away as somewhere the user has been. */
  private val failedMainFrameUrls = mutableSetOf<String>()

  /** The last failed document per tab, so the error screen survives a tab switch. */
  private val mainFrameErrors = mutableMapOf<Long, Pair<String, Int>>()

  /** Set while an aborted load unwinds: a cancelled page is not a page the user arrived at. */
  private var stoppedByUser = false

  /**
   * https addresses we substituted for an http one, so a connection failure can be walked back
   * rather than presenting the user with an error for a site that simply has no TLS.
   */
  private val upgradedFrom = mutableMapOf<String, String>()

  /**
   * Hosts that answered the https attempt with a failure this session. The next link to one of
   * them goes straight to http instead of paying for the doomed round trip again.
   */
  private val httpOnlyHosts = mutableSetOf<String>()
  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var customView: View? = null
  private var customViewCallback: WebChromeClient.CustomViewCallback? = null

  private val shortcutAdapter = ShortcutAdapter(
    scope = lifecycleScope,
    loadIcon = { url -> faviconStore.iconFor(url) },
    onOpen = { shortcut -> loadUrl(shortcut.url) },
    onRemove = { shortcut ->
      viewModel.removeShortcut(shortcut.url)
      toast(R.string.shortcut_removed)
    },
    onAdd = ::promptForShortcut,
  )

  private val mostVisitedAdapter = MostVisitedAdapter(
    scope = lifecycleScope,
    loadIcon = { url -> faviconStore.iconFor(url) },
    onOpen = { entry -> loadUrl(entry.url) },
  )

  private val suggestionAdapter = SuggestionAdapter(
    onClick = { suggestion ->
      val completion = suggestion.completion
      if (completion != null) {
        // A bang: keep the box open so the query can be typed after it.
        binding.omnibox.setText(completion)
        binding.omnibox.setSelection(completion.length)
      } else {
        loadUrl(suggestion.url ?: viewModel.searchFor(suggestion.phrase))
        clearOmniboxFocus()
      }
    },
    onFill = { suggestion ->
      val fill = suggestion.completion ?: suggestion.url ?: suggestion.phrase
      binding.omnibox.setText(fill)
      binding.omnibox.setSelection(fill.length)
    },
  )

  private val fileChooserLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    val uris = when {
      result.resultCode != RESULT_OK -> null
      else -> result.data?.data?.let { arrayOf(it) }
    }
    fileChooserCallback?.onReceiveValue(uris)
    fileChooserCallback = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    serviceWorkerBlocker.install()
    appliedNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    binding = ActivityBrowserBinding.inflate(layoutInflater)
    setContentView(binding.root)
    applyAppLock(appLock, binding.root)
    applyAddressBarPosition()
    // The layout paints its own background everywhere; the window's is pure overdraw.
    window.setBackgroundDrawable(null)
    useLightSystemBarIcons()
    // Only the chrome is padded; the page keeps the whole window, including behind the bars.
    binding.browserChrome.padForSystemBars(includeIme = true)

    setUpOmnibox()
    setUpFindBar()
    setUpHome()
    setUpSuggestions()
    setUpChrome()
    binding.torPill.setOnClickListener { TorSheet.show(supportFragmentManager) }
    observeState()
    registerBackHandling()

    if (savedInstanceState == null) privateProfile.destroy()
    whenNetworkReady {
      attachWebView(viewModel.tabManager.ensureTab())
      handleIntent(intent, isColdStart = true)
    }
  }

  /**
   * The manifest keeps the activity alive across a day/night flip so live pages are not torn
   * down mid-load, but the chrome was inflated under the old palette. Rebuilding is the only way
   * to repaint every view; tab state is saved first, so the pages come back where they were.
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
    if (night != appliedNightMode) {
      saveActiveTabState()
      recreate()
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent, isColdStart = false)
  }

  private fun handleIntent(intent: Intent?, isColdStart: Boolean) {
    // The intent filter only constrains implicit intents; an explicit one can carry any scheme,
    // and javascript: or file: handed in from another app must never reach a tab.
    val url = intent?.dataString?.takeIf { UrlFormatter.isWebUrl(it) }
      ?: intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { UrlFormatter.isProbablyUrl(it) }
    when {
      url != null && isColdStart -> loadUrl(viewModel.resolve(url))
      // Shared text arrives schemeless; handed over raw it resolves against the current page.
      url != null -> openTab(viewModel.resolve(url), isPrivate = false)
      isColdStart -> {
        restoreActiveTab()
        restoreSessionIfEmpty()
      }
    }
    if (isColdStart) {
      // Priming the usual sites through Tor would announce them to the exit before a tap.
      if (!settings.torMode) {
        lifecycleScope.launch {
          // The sites this user actually opens get QUIC hints and open sockets up front - and on
          // Wi-Fi, the top destinations are pulled whole into the prefetch cache.
          val urls = viewModel.mostVisited.first { it.isNotEmpty() }.map { entry -> entry.url }
          speculativeLoader.primeForSites(urls)
          val connectivity = getSystemService<android.net.ConnectivityManager>()
          if (connectivity?.isActiveNetworkMetered == false) {
            speculativeLoader.prefetchLikelyPages(urls)
          }
        }
      }
      watchNetworkForDataSaver()
    }
  }

  // region web view

  /**
   * Makes [tab] the visible page, building it a WebView if it has none. The outgoing page stays
   * live and merely hidden, so audio, timers and half-finished loads survive a tab switch.
   */
  private fun attachWebView(tab: BrowserTab) {
    val view = liveWebViews[tab.id] ?: createWebView(tab).also { created ->
      liveWebViews[tab.id] = created
      binding.webViewContainer.addView(created)
      evictSurplusWebViews()
    }
    // Re-inserting marks it most recently used; the eviction order is this map's order.
    liveWebViews.remove(tab.id)
    liveWebViews[tab.id] = view
    liveWebViews.forEach { (id, live) -> live.isVisible = id == tab.id }
    configurator.configure(view, tab.isPrivate)
    applySecureFlag()
    renderErrorView()
  }

  private fun createWebView(tab: BrowserTab): WebView = WebView(this).also { created ->
    val tabId = tab.id
    if (tab.isPrivate && privateProfile.attach(created)) {
      privateProfile.serviceWorkerController()?.let(serviceWorkerBlocker::install)
    }
    created.layoutParams = ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    // Paint the WebView the theme background from the start, so a page load is not preceded by
    // a white flash on a dark theme - the cheapest perceived-speed win there is.
    created.setBackgroundColor(getColor(R.color.ground))
    created.webViewClient = WrenWebViewClient(
      onPageStarted = { url -> onPageStarted(tabId, url) },
      onPageFinished = { url, title -> onPageFinished(tabId, url, title) },
      onExternalIntent = ::openExternally,
      onIntercept = { request -> interceptRequest(tabId, request) },
      onRewriteUrl = { url -> rewriteUrl(tabId, url) },
      onMainFrameError = { url, code -> onMainFrameError(tabId, url, code) },
      onFirstPaint = { url -> onFirstPaint(tabId, url) },
      onRendererGone = { view, didCrash -> onRendererGone(tabId, view, didCrash) },
    )
    created.webChromeClient = WrenWebChromeClient(
      onProgress = { progress -> onProgress(tabId, progress) },
      onTitle = { title ->
        viewModel.tabManager.update(
          tabId,
          title = displayTitle(urlOf(tabId), title),
        )
      },
      onIcon = { pageUrl, icon ->
        // A favicon file is named after its host, which is a visited-site record on disk.
        val isPrivate = viewModel.tabManager.tab(tabId)?.isPrivate == true
        if (pageUrl != null && !isPrivate) {
          lifecycleScope.launch { faviconStore.remember(pageUrl, icon) }
        }
      },
      onNewWindow = { url -> openTab(url, viewModel.tabManager.tab(tabId)?.isPrivate == true) },
      onEnterFullscreen = ::showCustomView,
      onExitFullscreen = ::hideCustomView,
      onFileChooserRequest = ::showFileChooser,
    )
    created.setFindListener { activeMatch, matchCount, isDoneCounting ->
      if (!isDoneCounting || tabId != viewModel.tabManager.activeTabId.value) return@setFindListener
      binding.findCount.text = if (matchCount == 0) {
        getString(R.string.find_no_matches)
      } else {
        getString(R.string.find_matches, activeMatch + 1, matchCount)
      }
    }
    created.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
      enqueueDownload(created, tabId, url, userAgent, contentDisposition, mimeType)
    }
    blobDownloader.install(created)
    speculativeLoader.warmUpRenderer()
    cosmeticFilter.updateFor(created, tab.url)
    privacyScripts.updateFor(created)
    installLongPressMenu(created)
  }

  /**
   * Retires the least recently used page once the pool is full, folding it back into the saved
   * state it came from so nothing but the render cost is lost.
   */
  private fun evictSurplusWebViews() {
    val activeTabId = viewModel.tabManager.activeTabId.value
    while (liveWebViews.size > maxLiveWebViews) {
      val victim = liveWebViews.keys.firstOrNull { it != activeTabId } ?: return
      retireWebView(victim)
    }
  }

  private fun destroyWebView(view: WebView) {
    binding.webViewContainer.removeView(view)
    view.stopLoading()
    view.webChromeClient = null
    view.destroy()
  }

  /** Drops every live page, for a session swap where none of the tabs survive. */
  private fun discardAllWebViews() {
    liveWebViews.values.toList().forEach(::destroyWebView)
    liveWebViews.clear()
  }

  /**
   * Retires every live page, keeping what each one needs to come back. A theme flip or an address
   * bar move recreates the activity, and only the active tab is saved elsewhere; without this the
   * background tabs would return rewound to whatever they last looked like in the foreground.
   */
  private fun retireAllWebViews() {
    liveWebViews.keys.toList().forEach(::retireWebView)
  }

  private fun urlOf(tabId: Long): String = viewModel.tabManager.tab(tabId)?.url.orEmpty()

  /**
   * Long press offers what is actually under the finger. The WebView's own hit test knows about
   * links and images but has no notion of a video, so the page itself is asked.
   */
  @SuppressLint("ClickableViewAccessibility")
  private fun installLongPressMenu(webView: WebView) {
    webView.setOnTouchListener { _, event ->
      lastTouchX = event.x
      lastTouchY = event.y
      // Chrome's oldest trick: by the time a finger lifts off a link, the connection is already
      // warming. The hit test needs a beat to register the touch, hence the small delay.
      if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
        webView.postDelayed({ warmLinkUnderFinger(webView) }, TOUCH_WARM_DELAY_MS)
      }
      false
    }
    webView.setOnLongClickListener {
      val hit = webView.hitTestResult
      val linkUrl = hit.extra?.takeIf {
        hit.type == HitTestResult.SRC_ANCHOR_TYPE ||
          hit.type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE
      }
      queryImageUnderTouch { imageUrl -> showLongPressMenu(linkUrl, imageUrl) }
      // Consumed only for links. Long-pressing text has to stay available for selection, so
      // the image menu can arrive a frame later.
      linkUrl != null
    }
  }

  private fun warmLinkUnderFinger(webView: WebView) {
    if (viewModel.tabManager.activeTab()?.isPrivate == true) return
    val hit = webView.hitTestResult
    val link = hit.extra?.takeIf {
      (
        hit.type == HitTestResult.SRC_ANCHOR_TYPE ||
          hit.type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        ) &&
        it.startsWith("https://", ignoreCase = true)
    } ?: return
    speculativeLoader.warmOrigin(link)
  }

  private fun queryImageUnderTouch(onResult: (image: String?) -> Unit) {
    val view = webView ?: return onResult(null)
    val density = resources.displayMetrics.density
    val script = IMAGE_AT_POINT_SCRIPT
      .replace("__X__", (lastTouchX / density).toInt().toString())
      .replace("__Y__", (lastTouchY / density).toInt().toString())
    view.evaluateJavascript(script) { raw ->
      // evaluateJavascript returns a JSON string, itself a JSON-encoded value here.
      val json = runCatching {
        JSONObject(raw?.trim('"').orEmpty().replace("\\\"", "\"").replace("\\/", "/"))
      }.getOrNull()
      onResult(json?.optString("image")?.takeIf { it.isNotBlank() && it != "null" })
    }
  }

  private fun showLongPressMenu(linkUrl: String?, imageUrl: String?) {
    // Only a fetchable http image can be saved; a blob: or data: image has no address.
    val saveableImage = imageUrl?.takeIf { it.startsWith("http") }
    if (linkUrl == null && saveableImage == null) return

    val actions = buildList<Pair<Int, () -> Unit>> {
      if (linkUrl != null) {
        add(R.string.open_in_new_tab to { openTab(linkUrl, isPrivate = false) })
        add(R.string.open_in_private_tab to { openTab(linkUrl, isPrivate = true) })
        add(R.string.copy_link to { copyToClipboard(linkUrl) })
        add(R.string.share_link to { shareUrl(linkUrl, linkUrl) })
        add(R.string.download_link to { downloadUrl(linkUrl) })
      }
      if (saveableImage != null) {
        add(R.string.open_image to { openTab(saveableImage, isPrivate = false) })
        add(R.string.save_image to { downloadUrl(saveableImage) })
        add(R.string.copy_image_link to { copyToClipboard(saveableImage) })
      }
    }

    AlertDialog.Builder(this)
      .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
        actions[which].second()
      }
      .show()
  }

  private fun downloadUrl(url: String) {
    val id = downloadController.enqueue(
      url = url,
      userAgent = null,
      contentDisposition = null,
      mimeType = null,
      pageUrl = viewModel.tabManager.activeTab()?.url,
    )
    toast(if (id != null) R.string.download_started else R.string.download_failed)
  }

  private fun applyRenderingMode() {
    val mode = RenderingMode.fromOrdinal(settings.renderingMode)
    webView?.evaluateJavascript(RenderingMode.injectionFor(mode), null)
  }

  private fun chooseRenderingMode() {
    val modes = RenderingMode.entries
    val labels = intArrayOf(
      R.string.rendering_normal,
      R.string.rendering_invert,
      R.string.rendering_grayscale,
      R.string.rendering_high_contrast,
    ).map { getString(it) }.toTypedArray()
    AlertDialog.Builder(this)
      .setTitle(R.string.rendering_mode)
      .setSingleChoiceItems(labels, settings.renderingMode) { dialog, which ->
        settings.renderingMode = which
        applyRenderingMode()
        dialog.dismiss()
      }
      .show()
  }

  private fun savePageOffline() {
    val view = webView ?: return
    val name = viewModel.tabManager.activeTab()?.title.orEmpty()
    PageSaver.saveArchive(this, view, name) { ok ->
      toast(if (ok) R.string.page_saved else R.string.page_save_failed)
    }
  }

  private fun copyToClipboard(url: String) {
    getSystemService<ClipboardManager>()
      ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
    toast(R.string.link_copied)
  }

  private fun restoreActiveTab() {
    val tab = viewModel.tabManager.ensureTab()
    val wasLive = tab.id in liveWebViews
    attachWebView(tab)
    // Its page is already up. Restoring the bundle it was last saved with would reload from the
    // network and discard everything it has navigated to since.
    if (wasLive) {
      renderTabChrome(tab)
      return
    }
    val view = webView ?: return
    val state = tab.state
    when {
      state != null && view.restoreState(state) != null -> renderHome(tab.url)
      tab.url != UrlFormatter.NEW_TAB_URL -> loadUrl(tab.url)
      else -> renderHome(UrlFormatter.NEW_TAB_URL)
    }
    val shown = tab.committedUrl ?: tab.url
    renderOmnibox(shown)
    committedUrl = shown
  }

  private fun saveActiveTabState() {
    val view = webView ?: return
    val bundle = Bundle()
    view.saveState(bundle) ?: return
    viewModel.tabManager.updateActive(state = bundle)
  }

  /**
   * Freeze-dried tab switch: the destination's last snapshot goes up instantly and stays until
   * the real page paints, so switching feels immediate even while the engine restores state.
   */
  private fun showTabSnapshot(tabId: Long) {
    val bitmap = tabThumbnails[tabId] ?: return
    binding.tabSnapshot.setImageBitmap(bitmap)
    binding.tabSnapshot.isVisible = true
    binding.tabSnapshot.removeCallbacks(hideSnapshotFallback)
    binding.tabSnapshot.postDelayed(hideSnapshotFallback, SNAPSHOT_FALLBACK_MS)
  }

  private val hideSnapshotFallback = Runnable { hideTabSnapshot() }

  private fun hideTabSnapshot() {
    binding.tabSnapshot.removeCallbacks(hideSnapshotFallback)
    binding.tabSnapshot.isVisible = false
  }

  private fun captureActiveThumbnail() {
    val view = webView ?: return
    val id = viewModel.tabManager.activeTabId.value
    if (!isHomeVisible) tabThumbnails.capture(window, view, id)
  }

  fun switchToTab(id: Long) {
    captureActiveThumbnail()
    if (id == viewModel.tabManager.activeTabId.value) return
    saveActiveTabState()
    val wasLive = id in liveWebViews
    // A page still in the pool is already painted, so the freeze-dried cover would only hide it.
    if (!wasLive) showTabSnapshot(id)
    viewModel.tabManager.select(id)
    val tab = viewModel.tabManager.activeTab() ?: return
    attachWebView(tab)
    applySecureFlag()
    val view = webView ?: return
    if (wasLive) {
      renderTabChrome(tab)
      return
    }
    val state = tab.state
    if (state != null && view.restoreState(state) != null) {
      renderTabChrome(tab)
    } else {
      loadUrl(tab.url)
    }
  }

  /** Re-points the chrome at a page that is already loaded, without touching the page itself. */
  private fun renderTabChrome(tab: BrowserTab) {
    stoppedByUser = false
    val shown = tab.committedUrl ?: tab.url
    committedUrl = shown
    failedMainFrameUrls.remove(shown)
    setLoading(false)
    binding.progress.isVisible = false
    renderHome(shown)
    if (!isOmniboxFocused) renderOmnibox(shown)
    updateNavigationButtons()
  }

  private fun openTab(url: String, isPrivate: Boolean) {
    saveActiveTabState()
    viewModel.tabManager.openTab(url, isPrivate)
    val tab = viewModel.tabManager.activeTab() ?: return
    attachWebView(tab)
    val view = webView ?: return
    view.clearHistory()
    loadUrl(url)
  }

  fun openNewTab(isPrivate: Boolean) {
    openTab(UrlFormatter.NEW_TAB_URL, isPrivate)
  }

  fun closeTab(id: Long) {
    val wasActive = id == viewModel.tabManager.activeTabId.value
    val wasPrivate = viewModel.tabManager.tab(id)?.isPrivate == true
    tabThumbnails.remove(id)
    mainFrameErrors.remove(id)
    liveWebViews.remove(id)?.let(::destroyWebView)
    viewModel.tabManager.close(id)
    if (wasPrivate) endPrivateSessionIfOver()
    if (wasActive) restoreActiveTab()
    offerUndoClose()
  }

  /** Once no private tab is left, nothing it stored survives either. */
  private fun endPrivateSessionIfOver() {
    if (viewModel.tabManager.snapshots.value.any { it.isPrivate }) return
    privateProfile.destroy()
  }

  /** Save-Data follows the network: on for metered connections, off the moment Wi-Fi returns. */
  private fun watchNetworkForDataSaver() {
    val connectivity = getSystemService<android.net.ConnectivityManager>() ?: return
    syncDataSaver(connectivity)
    connectivity.registerDefaultNetworkCallback(
      object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
          network: android.net.Network,
          capabilities: android.net.NetworkCapabilities,
        ) {
          runOnUiThread { syncDataSaver(connectivity) }
        }
      },
    )
  }

  private fun syncDataSaver(connectivity: android.net.ConnectivityManager) {
    speculativeLoader.applyDataSaver(settings.dataSaver && connectivity.isActiveNetworkMetered)
  }

  private fun offerUndoClose() {
    val closed = viewModel.tabManager.consumeLastClosed() ?: return
    com.google.android.material.snackbar.Snackbar
      .make(
        binding.root,
        R.string.tab_closed,
        com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
      )
      .setAction(R.string.undo) { openTab(closed.url, isPrivate = false) }
      .setAnchorView(binding.menuButton)
      .show()
  }

  /** Applied to everything that navigates: typed, tapped, or followed by the page. */
  private fun rewriteUrl(tabId: Long, url: String): String? {
    var rewritten = url
    if (settings.stripTrackingParameters) rewritten = LinkCleaner.clean(rewritten)
    if (settings.httpsUpgrade && HostMatcher.hostOf(rewritten) !in httpOnlyHosts) {
      HttpsUpgrader.upgrade(rewritten)?.let { upgraded ->
        upgradedFrom[upgraded] = rewritten
        rewritten = upgraded
      }
    }
    rewritten = routeForTor(rewritten)
    if (rewritten == url) return null
    loadUrlInTab(tabId, rewritten)
    return rewritten
  }

  /**
   * A site with no TLS at all fails the upgraded request outright. Rather than show an error for
   * a page that would have loaded, fall back once and say so.
   */
  private fun onMainFrameError(tabId: Long, url: String, errorCode: Int) {
    failedMainFrameUrls += url
    // Falling back to http is exactly the leak Tor mode exists to prevent.
    val original = upgradedFrom.remove(url)?.takeUnless { settings.torMode }
    if (original != null) {
      HostMatcher.hostOf(original)?.let(httpOnlyHosts::add)
      if (isActiveTab(tabId)) toast(R.string.https_fell_back)
      loadUrlInTab(tabId, original)
      return
    }
    mainFrameErrors[tabId] = url to errorCode
    renderErrorView()
  }

  /** Wren's own "can't reach this site" screen instead of WebView's stock one. */
  private fun renderErrorView() {
    val error = mainFrameErrors[viewModel.tabManager.activeTabId.value]
    binding.error.errorRoot.isVisible = error != null && !isHomeVisible
    if (error == null) return
    val (url, code) = error
    binding.error.errorHost.text = UrlFormatter.prettyHost(url)
    binding.error.errorReason.setText(errorReasonFor(url, code))
  }

  private fun errorReasonFor(url: String, code: Int): Int = when {
    settings.torMode && !torController.state.value.isOn -> R.string.error_tor_connecting
    settings.torMode && url in torForcedHttps -> R.string.error_tor_plain_http
    else -> errorReason(code)
  }

  private fun errorReason(code: Int): Int = when (code) {
    WebViewClient.ERROR_HOST_LOOKUP -> R.string.error_host_lookup
    WebViewClient.ERROR_TIMEOUT -> R.string.error_timeout
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> R.string.error_ssl
    WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO, WebViewClient.ERROR_UNKNOWN ->
      if (isOnline()) R.string.error_connect else R.string.error_offline
    else -> R.string.error_generic
  }

  private fun isOnline(): Boolean {
    val connectivity = getSystemService<android.net.ConnectivityManager>() ?: return true
    val capabilities =
      connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  private fun loadUrlInTab(tabId: Long, url: String) {
    if (isActiveTab(tabId)) {
      loadUrl(url)
      return
    }
    val view = liveWebViews[tabId] ?: return
    val routed = routeForTor(url)
    viewModel.tabManager.update(tabId, url = routed)
    view.loadUrl(routed, configurator.requestHeaders())
  }

  /** Tor's tunnel port only carries CONNECT, so a plain-http page cannot travel through it. */
  private fun routeForTor(url: String): String {
    if (!settings.torMode || !url.startsWith("http://", ignoreCase = true)) return url
    return ("https://" + url.substring("http://".length)).also { torForcedHttps += it }
  }

  private fun loadUrl(requested: String) {
    val view = webView ?: return
    val url = routeForTor(requested)
    viewModel.tabManager.updateActive(url = url)
    renderHome(url)
    if (url == UrlFormatter.NEW_TAB_URL) {
      // Nothing to fetch; stop whatever the tab was doing and show the speed dial.
      view.stopLoading()
      view.loadUrl(UrlFormatter.NEW_TAB_URL)
      renderOmnibox(url)
      return
    }
    view.loadUrl(url, configurator.requestHeaders())
  }

  // endregion

  // region page callbacks

  private fun onPageStarted(tabId: Long, url: String) {
    if (mainFrameErrors.remove(tabId) != null && isActiveTab(tabId)) renderErrorView()
    // Blocked counts belong to the document that collected them.
    if (url != viewModel.tabManager.tab(tabId)?.url) blockStats.reset(tabId)
    liveWebViews[tabId]?.let { view ->
      HostMatcher.hostOf(url)?.let { host -> configurator.applyForHost(view, host) }
    }
    viewModel.tabManager.update(tabId, url = url)
    failedMainFrameUrls.remove(url)
    if (!isActiveTab(tabId)) return
    stoppedByUser = false
    binding.progress.isVisible = true
    readerMode.onNavigated()
    speculativeLoader.cancel()
    renderHome(url)
    // The omnibox deliberately keeps naming the committed page until the new one paints.
    updateNavigationButtons()
  }

  private fun isActiveTab(tabId: Long): Boolean = tabId == viewModel.tabManager.activeTabId.value

  /**
   * The new document has painted, so the chrome may finally describe it: the snapshot covering
   * the switch comes down and the address bar moves to the URL the user is actually looking at.
   */
  private fun onFirstPaint(tabId: Long, url: String) {
    viewModel.tabManager.update(tabId, committedUrl = url)
    if (!isActiveTab(tabId)) return
    hideTabSnapshot()
    committedUrl = url
    if (!isOmniboxFocused) renderOmnibox(url)
  }

  private fun onPageFinished(tabId: Long, url: String, title: String) {
    val isPrivate = viewModel.tabManager.tab(tabId)?.isPrivate == true
    val pageTitle = displayTitle(url, title)
    val aborted = stoppedByUser && isActiveTab(tabId)
    val tab = viewModel.tabManager.tab(tabId)
    if (aborted) {
      // Put the tab back where it actually is, so a switch or a restore does not resume a
      // navigation the user stopped.
      tab?.committedUrl?.let { viewModel.tabManager.update(tabId, url = it) }
    } else {
      viewModel.tabManager.update(tabId, url = url, title = pageTitle, committedUrl = url)
    }
    // A page that never loaded, or one the user cancelled, is not a place they went.
    if (url !in failedMainFrameUrls && !aborted) {
      viewModel.onPageCommitted(url, pageTitle, isPrivate)
    }
    if (!isActiveTab(tabId)) return
    if (aborted) {
      renderOmnibox(committedUrl)
      updateNavigationButtons()
      return
    }
    committedUrl = url
    setLoading(false)
    binding.progress.isVisible = false
    binding.swipeRefresh.isRefreshing = false
    if (settings.renderingMode != 0) applyRenderingMode()
    if (dev.androidpoet.wren.BuildConfig.DEBUG) {
      webView?.let { view -> dev.androidpoet.wren.browser.PagePerf.report(view, url) }
    }
    binding.root.postDelayed({ captureActiveThumbnail() }, THUMBNAIL_SETTLE_MS)
    renderHome(url)
    if (!isOmniboxFocused) renderOmnibox(url)
    updateNavigationButtons()
  }

  /** Called on a background thread. Returning a response blocks or replaces the request. */
  private fun interceptRequest(tabId: Long, request: WebResourceRequest): WebResourceResponse? {
    // The document itself must come back null instantly: anything else bypasses the
    // prefetch/prerender cache on activation and turns a warm navigation cold.
    if (request.isForMainFrame) return null
    val pageUrl = viewModel.tabManager.tab(tabId)?.url.orEmpty()
    val response = contentBlocker.intercept(request, pageUrl) ?: return null
    HostMatcher.hostOf(request.url?.toString().orEmpty())?.let { host ->
      blockStats.recordBlocked(tabId, host)
    }
    Timber.v("blocked %s", request.url)
    return response
  }

  private fun onProgress(tabId: Long, progress: Int) {
    if (!isActiveTab(tabId)) return
    // A stopped load keeps reporting for a moment; it must not re-arm the stop button.
    if (stoppedByUser && progress < 100) return
    binding.progress.progress = progress
    binding.progress.isVisible = progress in 1..99
    setLoading(progress in 1..99)
  }

  private fun setLoading(loading: Boolean) {
    if (isLoading == loading) return
    isLoading = loading
    renderOmniboxAction()
  }

  /**
   * A page that hangs has to be cancellable, so the trailing button is a stop while the load is
   * in flight, a reload once it is done, and a clear while the omnibox is being typed into.
   */
  private fun renderOmniboxAction() = with(binding.omniboxAction) {
    when {
      isOmniboxFocused -> {
        setImageResource(R.drawable.ic_close)
        contentDescription = getString(R.string.clear)
      }
      isLoading -> {
        setImageResource(R.drawable.ic_close)
        contentDescription = getString(R.string.stop_loading)
      }
      else -> {
        setImageResource(R.drawable.ic_refresh)
        contentDescription = getString(R.string.reload)
      }
    }
  }

  /**
   * A WebView reports the outgoing document's title until the new one parses its own, so a fresh
   * tab hands back "about:blank" for the page it is busy replacing. Storing that poisons history
   * and every tab card built from it; the host is a worse title but never a wrong one.
   */
  private fun displayTitle(url: String, raw: String): String {
    val title = raw.trim()
    val looksLikeAnAddress = title == UrlFormatter.NEW_TAB_URL || title == url
    return if (title.isBlank() || looksLikeAnAddress) UrlFormatter.prettyHost(url) else title
  }

  private fun updateNavigationButtons() {
    val view = webView
    binding.backButton.isEnabled = view?.canGoBack() == true
    binding.forwardButton.isEnabled = view?.canGoForward() == true
    binding.backButton.alpha = if (binding.backButton.isEnabled) 1f else DISABLED_ALPHA
    binding.forwardButton.alpha = if (binding.forwardButton.isEnabled) 1f else DISABLED_ALPHA
  }

  // endregion

  // region chrome

  private fun setUpOmnibox() = with(binding) {
    omnibox.setOnEditorActionListener { _, actionId, event ->
      val submitted = actionId == EditorInfo.IME_ACTION_GO ||
        actionId == EditorInfo.IME_ACTION_SEARCH ||
        event?.keyCode == KeyEvent.KEYCODE_ENTER
      if (submitted) {
        loadUrl(viewModel.resolve(omnibox.text.toString()))
        clearOmniboxFocus()
      }
      submitted
    }
    omnibox.doAfterTextChanged { text ->
      if (isOmniboxFocused) viewModel.onQueryChanged(text?.toString().orEmpty())
    }
    omnibox.setOnFocusChangeListener { _, hasFocus ->
      // Focusing the bar is intent to go somewhere; the search engine's socket opens now.
      if (hasFocus && viewModel.tabManager.activeTab()?.isPrivate != true) {
        speculativeLoader.warmOrigin(viewModel.homeUrl)
      }
      isOmniboxFocused = hasFocus
      updateChromeVisibility()
      // The committed address, not the one being fetched: focusing the bar to edit a URL that
      // has not loaded yet would put a page's name over somebody else's document.
      val url = committedUrl
      if (hasFocus) {
        if (url != UrlFormatter.NEW_TAB_URL) omnibox.setText(url)
        omnibox.selectAll()
        viewModel.onQueryChanged(omnibox.text.toString())
      } else {
        viewModel.onQueryChanged("")
        renderOmnibox(url)
      }
      suggestionList.isVisible = hasFocus
      renderOmniboxAction()
    }
    securityIcon.setOnClickListener {
      if (viewModel.tabManager.activeTab()?.url.orEmpty().startsWith("http")) {
        SiteSecuritySheet.show(supportFragmentManager)
      }
    }
    omniboxAction.setOnClickListener {
      when {
        isOmniboxFocused -> clearOmniboxFocus()
        isLoading -> stopLoading()
        else -> webView?.reload()
      }
    }
  }

  /** Cancels a load in flight and leaves the chrome describing the page still on screen. */
  private fun stopLoading() {
    stoppedByUser = true
    webView?.stopLoading()
    setLoading(false)
    binding.progress.isVisible = false
    binding.swipeRefresh.isRefreshing = false
    renderOmnibox(committedUrl)
    updateNavigationButtons()
  }

  private fun renderOmnibox(url: String) = with(binding) {
    omnibox.setText(UrlFormatter.prettyHost(url))
    securityIcon.setImageResource(
      when {
        url.isBlank() || url == UrlFormatter.NEW_TAB_URL -> R.drawable.ic_search
        UrlFormatter.isSecure(url) -> R.drawable.ic_lock
        else -> R.drawable.ic_lock_open
      },
    )
  }

  private fun setUpFindBar() = with(binding) {
    swipeRefresh.setColorSchemeColors(getColor(R.color.russet))
    swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.sheet))
    swipeRefresh.setOnRefreshListener { webView?.reload() ?: swipeRefresh.setRefreshing(false) }
    // Without this, SwipeRefreshLayout consults the container rather than the page, and a
    // scroll gesture in the middle of an article can drag the refresh spinner out.
    swipeRefresh.setOnChildScrollUpCallback { _, _ -> (webView?.scrollY ?: 0) > 0 }
    swipeRefresh.setDistanceToTriggerSync(
      (resources.displayMetrics.density * REFRESH_TRIGGER_DP).toInt(),
    )

    findInput.doAfterTextChanged { text ->
      val query = text?.toString().orEmpty()
      if (query.isEmpty()) {
        webView?.clearMatches()
        findCount.setText(R.string.find_no_matches)
      } else {
        webView?.findAllAsync(query)
      }
    }
    findPrevious.setOnClickListener { webView?.findNext(false) }
    findNext.setOnClickListener { webView?.findNext(true) }
    findClose.setOnClickListener { hideFindBar() }
  }

  private fun showFindBar() = with(binding) {
    findBar.isVisible = true
    findInput.setText("")
    findInput.requestFocus()
    getSystemService<InputMethodManager>()
      ?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
  }

  private fun hideFindBar() = with(binding) {
    findBar.isVisible = false
    findInput.setText("")
    webView?.clearMatches()
    getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(findInput.windowToken, 0)
  }

  private fun toggleReaderMode() {
    val view = webView ?: return
    if (readerMode.isActive) {
      readerMode.exit(view)
      return
    }
    readerMode.enter(view) { entered ->
      if (!entered) toast(R.string.reader_unavailable)
    }
  }

  private fun setUpHome() = with(binding.home) {
    shortcutGrid.layoutManager = GridLayoutManager(this@BrowserActivity, HOME_GRID_SPAN)
    shortcutGrid.adapter = shortcutAdapter
    mostVisitedList.layoutManager = LinearLayoutManager(this@BrowserActivity)
    mostVisitedList.adapter = mostVisitedAdapter
    // The card is a target, not a field: tapping it hands focus to the real omnibox.
    homeSearchCard.setOnClickListener {
      binding.omnibox.requestFocus()
      getSystemService<InputMethodManager>()
        ?.showSoftInput(binding.omnibox, InputMethodManager.SHOW_IMPLICIT)
    }
    homeEngineChip.setOnClickListener { chooseSearchEngine() }
    renderSearchEngineChip()
  }

  /** The engine's own favicon, so the chip reads as "who answers this query". */
  private fun renderSearchEngineChip() {
    val icon = binding.home.homeEngineIcon
    val url = settings.searchHomeUrl
    icon.setImageResource(R.drawable.ic_search)
    icon.tag = url
    lifecycleScope.launch {
      val bitmap = faviconStore.iconFor(url) ?: return@launch
      if (icon.tag != url) return@launch
      icon.setImageBitmap(bitmap)
    }
  }

  private fun chooseSearchEngine() {
    val engines = SearchEngine.entries
    val labels = engines.map { it.label } + getString(R.string.search_engine_custom)
    val current =
      if (settings.usingCustomSearch) engines.size else engines.indexOf(settings.searchEngine)
    androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle(R.string.search_engine)
      .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
        dialog.dismiss()
        if (which == engines.size) {
          startActivity(android.content.Intent(this, SettingsActivity::class.java))
        } else {
          settings.customSearchTemplate = ""
          settings.searchEngine = engines[which]
          renderSearchEngineChip()
        }
      }
      .show()
  }

  /** The home page stands in for the WebView whenever the tab has nowhere to be. */
  private fun renderHome(url: String) {
    isHomeVisible = url.isBlank() || url == UrlFormatter.NEW_TAB_URL
    binding.home.root.isVisible = isHomeVisible
    binding.swipeRefresh.isVisible = !isHomeVisible
    if (isHomeVisible) hideFindBar()
    updateChromeVisibility()
  }

  /**
   * The home page has its own search card, so showing the toolbar as well would put two search
   * fields on one screen. It comes back the moment the omnibox is actually being used.
   */
  private fun updateChromeVisibility() {
    binding.topBar.isVisible = !isHomeVisible || isOmniboxFocused
  }

  private fun promptForShortcut() {
    val input = EditText(this).apply {
      hint = getString(R.string.search_or_type_url)
      setPadding(48, 32, 48, 32)
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.add_shortcut_title)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val typed = input.text.toString().trim()
        if (typed.isEmpty()) return@setPositiveButton
        val resolved = viewModel.resolve(typed)
        viewModel.addShortcut(resolved, UrlFormatter.prettyHost(resolved).substringBefore('.'))
        toast(R.string.shortcut_added)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun setUpSuggestions() = with(binding.suggestionList) {
    layoutManager = LinearLayoutManager(this@BrowserActivity)
    adapter = suggestionAdapter
  }

  private fun setUpChrome() = with(binding) {
    backButton.setOnClickListener { webView?.takeIf { it.canGoBack() }?.goBack() }
    forwardButton.setOnClickListener { webView?.takeIf { it.canGoForward() }?.goForward() }
    backButton.setOnLongClickListener { showPageHistory() }
    forwardButton.setOnLongClickListener { showPageHistory() }
    // Home is the speed dial, not the search engine's front page.
    homeButton.setOnClickListener { loadUrl(UrlFormatter.NEW_TAB_URL) }
    tabsButton.setOnClickListener {
      captureActiveThumbnail()
      TabsSheet.show(supportFragmentManager)
    }
    burnButton.setOnClickListener { confirmBurn() }
    menuButton.setOnClickListener { showMenu() }
    error.errorRetry.setOnClickListener { webView?.reload() }
  }

  private fun observeState() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          viewModel.suggestions.collectLatest { suggestions ->
            suggestionAdapter.submitList(suggestions)
            binding.suggestionList.isGone = suggestions.isEmpty() || !isOmniboxFocused
            speculateFrom(suggestions)
          }
        }
        launch {
          viewModel.tabManager.snapshots.collectLatest { tabs ->
            binding.tabsButton.text = tabs.size.toString()
          }
        }
        launch {
          viewModel.shortcuts.collectLatest { shortcutAdapter.submitShortcuts(it) }
        }
        launch {
          torController.state.collectLatest { renderTor(it) }
        }
        launch {
          viewModel.mostVisited.collectLatest { entries ->
            mostVisitedAdapter.submitList(entries)
            binding.home.homeEmptyVisited.isVisible = entries.isEmpty()
            binding.home.mostVisitedList.isVisible = entries.isNotEmpty()
          }
        }
        launch {
          blockStats.blocked.collectLatest { byTab ->
            blockedTotal = byTab.values.sum()
            renderShieldPill(viewModel.tabManager.activeTab()?.isPrivate == true)
          }
        }
        launch {
          viewModel.isBookmarked.collectLatest { bookmarked ->
            binding.menuButton.setImageResource(
              if (bookmarked) R.drawable.ic_more_starred else R.drawable.ic_more,
            )
          }
        }
      }
    }
  }

  // region tor

  /**
   * With Tor on, nothing may leave until the proxy sits in front of WebView. The first page
   * waits the few milliseconds that takes rather than slip out direct.
   */
  private fun whenNetworkReady(block: () -> Unit) {
    if (torController.isNetworkReady) {
      block()
      return
    }
    lifecycleScope.launch {
      torController.state.first { it.proxyInstalled || it.status == TorStatus.FAILED }
      block()
    }
  }

  private fun renderTor(state: TorState) = with(binding.torPill) {
    isVisible = settings.torMode
    text = when (state.status) {
      TorStatus.STARTING -> getString(R.string.tor_pill_connecting, state.progress)
      TorStatus.FAILED -> getString(R.string.tor_pill_failed)
      else -> getString(R.string.tor)
    }
    alpha = if (state.isOn) 1f else DIM_PILL_ALPHA
    val previous = lastTorStatus
    lastTorStatus = state.status
    // A page that loaded while the route was changing hands is worth fetching again on the
    // route the user asked for: the circuit just opened, or the direct path just returned.
    val cameBack = previous == TorStatus.STOPPING && state.status == TorStatus.OFF
    val routeChanged = previous != null && previous != state.status &&
      (state.status == TorStatus.ON || cameBack)
    if (routeChanged && !isHomeVisible) webView?.reload()
  }

  private fun setTorEnabled(enabled: Boolean) {
    if (enabled && !torController.isSupported) {
      toast(R.string.tor_unsupported)
      return
    }
    torController.setEnabled(enabled)
    liveWebViews.forEach { (id, view) ->
      configurator.configure(view, viewModel.tabManager.tab(id)?.isPrivate == true)
      privacyScripts.updateFor(view)
    }
    renderTor(torController.state.value)
  }

  private fun torMenuLabel(): String = when (torController.state.value.status) {
    TorStatus.ON -> getString(R.string.tor_menu_on)
    TorStatus.STARTING -> getString(R.string.tor_menu_connecting)
    else -> getString(R.string.tor)
  }

  override fun torState(): StateFlow<TorState> = torController.state

  override fun isTorEnabled(): Boolean = torController.isEnabled

  override fun onTorSetEnabled(enabled: Boolean) = setTorEnabled(enabled)

  override fun onTorNewIdentity() {
    torController.newIdentity()
    toast(R.string.tor_new_identity_done)
    if (!isHomeVisible) webView?.reload()
  }

  override fun onTorVerify() = openTab(TOR_CHECK_URL, isPrivate = false)

  // endregion

  private fun showMenu() = MenuSheet.show(supportFragmentManager)

  override fun menuState(): MenuState = MenuState(
    hasPage = !isHomeVisible,
    isBookmarked = viewModel.isBookmarked.value,
    isReaderActive = readerMode.isActive,
    isDesktopSite = settings.desktopMode,
    siteBlockingLabel = siteBlockingLabel(),
    torLabel = torMenuLabel(),
    isTorOn = torController.state.value.isOn,
  )

  override fun onMenuAction(action: MenuAction) {
    val tab = viewModel.tabManager.activeTab()
    when (action) {
      MenuAction.NEW_TAB -> openNewTab(isPrivate = false)
      MenuAction.NEW_PRIVATE_TAB -> openNewTab(isPrivate = true)
      MenuAction.BOOKMARK -> viewModel.toggleBookmark(
        url = tab?.url.orEmpty(),
        title = tab?.title.orEmpty(),
      ) { added -> toast(if (added) R.string.bookmark_added else R.string.bookmark_removed) }

      MenuAction.BOOKMARKS -> startActivity(Intent(this, BookmarksActivity::class.java))
      MenuAction.HISTORY -> startActivity(Intent(this, HistoryActivity::class.java))
      MenuAction.DOWNLOADS ->
        startActivity(
          Intent(this, dev.androidpoet.wren.ui.downloads.DownloadsActivity::class.java),
        )
      MenuAction.FIND -> showFindBar()
      MenuAction.READER -> toggleReaderMode()
      MenuAction.RENDERING -> chooseRenderingMode()
      MenuAction.PRINT -> webView?.let { PageSaver.print(this, it, activePageTitle()) }
      MenuAction.SAVE_PAGE -> savePageOffline()
      MenuAction.SHARE -> shareCurrentPage()
      MenuAction.SHARE_QR -> showQrCode()
      MenuAction.ADD_TO_HOME -> addToHomeScreen()
      MenuAction.TRANSLATE -> translateCurrentPage()
      MenuAction.DESKTOP_SITE -> {
        settings.desktopMode = !settings.desktopMode
        webView?.let { view ->
          configurator.configure(view, tab?.isPrivate == true)
          view.reload()
        }
      }

      MenuAction.TOR -> setTorEnabled(!torController.isEnabled)
      MenuAction.SITE_BLOCKING -> toggleBlockingForSite()
      MenuAction.CLEAR_DATA -> clearBrowsingData()
      MenuAction.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
    }
  }

  /** Reads either as a count, or as the action to take when blocking is off here. */
  private fun siteBlockingLabel(): String {
    val host = HostMatcher.hostOf(viewModel.tabManager.activeTab()?.url.orEmpty())
    return when {
      !settings.blockAds -> getString(R.string.block_ads)
      host != null && settings.isAllowlisted(host) -> getString(R.string.block_ads_here)
      else -> getString(
        R.string.blocked_count,
        blockStats.countFor(viewModel.tabManager.activeTabId.value),
      )
    }
  }

  private fun toggleBlockingForSite() {
    val tab = viewModel.tabManager.activeTab() ?: return
    val host = HostMatcher.hostOf(tab.url) ?: return
    if (!settings.blockAds) {
      // Blocking is off everywhere; per-site is meaningless until it is back on.
      settings.blockAds = true
    } else {
      settings.setAllowlisted(host, !settings.isAllowlisted(host))
    }
    webView?.let { view ->
      // Cosmetic rules are fixed for the life of a document, so the change lands on reload.
      cosmeticFilter.updateFor(view, tab.url)
      blockStats.reset(tab.id)
      view.reload()
    }
  }

  override fun siteSecurityInfo(): SiteSecurityInfo? {
    val tab = viewModel.tabManager.activeTab() ?: return null
    val host = HostMatcher.hostOf(tab.url) ?: return null
    return SiteSecurityInfo(
      host = host,
      isSecure = UrlFormatter.isSecure(tab.url),
      // Null for a page served over http, which is exactly what the panel should show.
      certificate = webView?.certificate,
      blockedHosts = blockStats.hostsFor(tab.id),
      isBlockingEnabled = settings.blockAds && !settings.isAllowlisted(host),
      isJsEnabledHere = settings.javaScriptEnabled && !settings.isJsDisabledFor(host),
      isDesktopHere = settings.desktopMode || settings.isDesktopSite(host),
      isKeptSignedIn = settings.isKeptSignedIn(host),
    )
  }

  override fun onToggleSiteBlocking() = toggleBlockingForSite()

  /**
   * A report is a share, not a form: it carries what the blocker did on the page so whoever
   * receives it can reproduce the breakage, and nothing else about the user.
   */
  override fun onReportBrokenSite() {
    val tab = viewModel.tabManager.activeTab() ?: return
    val lists = filterListManager.lists.filter { filterListManager.isEnabled(it.id) }
    val report = buildString {
      appendLine(getString(R.string.report_broken_site_title))
      appendLine(tab.url)
      appendLine()
      appendLine(getString(R.string.report_blocked_hosts))
      blockStats.hostsFor(tab.id).forEach { appendLine("- $it") }
      appendLine()
      appendLine(getString(R.string.report_lists) + " " + lists.joinToString { it.title })
      val version = dev.androidpoet.wren.BuildConfig.VERSION_NAME
      appendLine("Wren $version, Android ${android.os.Build.VERSION.RELEASE}")
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.report_broken_site_title))
      putExtra(android.content.Intent.EXTRA_TEXT, report)
    }
    val title = getString(R.string.report_broken_site)
    startActivity(android.content.Intent.createChooser(send, title))
  }

  override fun onSetSiteJavascript(enabled: Boolean) {
    val host = activeHost() ?: return
    settings.setJsDisabledFor(host, !enabled)
    applySiteSettingsAndReload()
  }

  override fun onSetSiteDesktop(enabled: Boolean) {
    val host = activeHost() ?: return
    settings.setDesktopSite(host, enabled)
    applySiteSettingsAndReload()
  }

  override fun onSetKeepSignedIn(enabled: Boolean) {
    val host = activeHost() ?: return
    settings.setKeptSignedIn(host, enabled)
  }

  private fun activeHost(): String? =
    viewModel.tabManager.activeTab()?.url?.let(HostMatcher::hostOf)

  private fun applySiteSettingsAndReload() {
    val view = webView ?: return
    activeHost()?.let { configurator.applyForHost(view, it) }
    view.reload()
  }

  private fun activePageTitle(): String =
    viewModel.tabManager.activeTab()?.title?.ifBlank { getString(R.string.app_name) }
      ?: getString(R.string.app_name)

  /** Pins the current site to the launcher, favicon and all where one is cached. */
  private fun addToHomeScreen() {
    val tab = viewModel.tabManager.activeTab() ?: return
    if (tab.url == UrlFormatter.NEW_TAB_URL) return
    val label = tab.title.ifBlank { UrlFormatter.prettyHost(tab.url) }
    val open = Intent(this, BrowserActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = tab.url.toUri()
    }
    lifecycleScope.launch {
      val icon = faviconStore.iconFor(tab.url)
        ?.let { androidx.core.graphics.drawable.IconCompat.createWithBitmap(it) }
        ?: androidx.core.graphics.drawable.IconCompat.createWithResource(
          this@BrowserActivity,
          R.mipmap.ic_launcher,
        )
      val shortcut = androidx.core.content.pm.ShortcutInfoCompat
        .Builder(this@BrowserActivity, tab.url)
        .setShortLabel(label)
        .setIcon(icon)
        .setIntent(open)
        .build()
      androidx.core.content.pm.ShortcutManagerCompat
        .requestPinShortcut(this@BrowserActivity, shortcut, null)
    }
  }

  /** Hands the page to Google's web translator - explicit, user-initiated, one page. */
  private fun translateCurrentPage() {
    val url = viewModel.tabManager.activeTab()?.url ?: return
    if (url == UrlFormatter.NEW_TAB_URL) return
    val language = resources.configuration.locales[0].language
    val translated = "https://translate.google.com/translate?sl=auto&tl=" +
      language + "&u=" + java.net.URLEncoder.encode(url, "UTF-8")
    loadUrl(translated)
  }

  /**
   * The tab's whole navigation trail, from a long press on back or forward. Tapping an entry
   * jumps straight there instead of mashing back N times.
   */
  private fun showPageHistory(): Boolean {
    val view = webView ?: return false
    val list = view.copyBackForwardList()
    if (list.size == 0) return false
    val labels = (0 until list.size).map { index ->
      val item = list.getItemAtIndex(index)
      val marker = if (index == list.currentIndex) "\u25B8 " else "   "
      marker + item.title.orEmpty().ifBlank { UrlFormatter.prettyHost(item.url) }
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.page_history)
      .setItems(labels.toTypedArray()) { _, which ->
        view.goBackOrForward(which - list.currentIndex)
      }
      .show()
    return true
  }

  /**
   * Hardware keyboard, the desktop grammar: browsing on a keyboard should not require touching
   * the screen. Ctrl combos arrive here via the framework's shortcut channel.
   */
  override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
    when (keyCode) {
      android.view.KeyEvent.KEYCODE_T ->
        openNewTab(isPrivate = event.isShiftPressed)
      android.view.KeyEvent.KEYCODE_W ->
        closeTab(viewModel.tabManager.activeTabId.value)
      android.view.KeyEvent.KEYCODE_L -> {
        binding.omnibox.requestFocus()
        binding.omnibox.selectAll()
      }
      android.view.KeyEvent.KEYCODE_F -> showFindBar()
      android.view.KeyEvent.KEYCODE_R -> webView?.reload()
      android.view.KeyEvent.KEYCODE_D -> viewModel.tabManager.activeTab()?.let { tab ->
        viewModel.toggleBookmark(tab.url, tab.title) { added ->
          toast(if (added) R.string.bookmark_added else R.string.bookmark_removed)
        }
      }
      android.view.KeyEvent.KEYCODE_TAB ->
        cycleTab(if (event.isShiftPressed) -1 else 1)
      in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_9 ->
        switchToTabAt(keyCode - android.view.KeyEvent.KEYCODE_1)
      else -> return super.onKeyShortcut(keyCode, event)
    }
    return true
  }

  override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
      when {
        event.keyCode == android.view.KeyEvent.KEYCODE_F5 -> {
          webView?.reload()
          return true
        }
        event.keyCode == android.view.KeyEvent.KEYCODE_F3 -> {
          webView?.findNext(!event.isShiftPressed)
          return true
        }
        event.keyCode == android.view.KeyEvent.KEYCODE_TAB && event.isCtrlPressed -> {
          cycleTab(if (event.isShiftPressed) -1 else 1)
          return true
        }
      }
    }
    return super.dispatchKeyEvent(event)
  }

  private fun cycleTab(direction: Int) {
    val tabs = viewModel.tabManager.snapshots.value
    if (tabs.size < 2) return
    val current = tabs.indexOfFirst { it.id == viewModel.tabManager.activeTabId.value }
    val next = (current + direction + tabs.size) % tabs.size
    switchToTab(tabs[next].id)
  }

  private fun switchToTabAt(index: Int) {
    val tabs = viewModel.tabManager.snapshots.value
    // Ctrl+9 goes to the last tab whatever the count, the way every desktop browser does it.
    val target = if (index >= tabs.lastIndex) tabs.lastOrNull() else tabs.getOrNull(index)
    target?.let { switchToTab(it.id) }
  }

  fun currentSessionName(): String = settings.currentSession

  /** Everything non-private that is open, written into the current session's record. */
  private fun persistCurrentSession() {
    val pages = viewModel.tabManager.snapshots.value
      .filter { !it.isPrivate && it.url != UrlFormatter.NEW_TAB_URL }
      .map { dev.androidpoet.wren.core.model.SessionTab(url = it.url, title = it.title) }
    val name = settings.currentSession
    lifecycleScope.launch { sessionRepository.save(name, pages) }
  }

  /** On a fresh process, the current session's pages come back instead of an empty browser. */
  private fun restoreSessionIfEmpty() {
    if (viewModel.tabManager.size > 1) return
    if (viewModel.tabManager.activeTab()?.url != UrlFormatter.NEW_TAB_URL) return
    lifecycleScope.launch {
      val session = sessionRepository.get(settings.currentSession) ?: return@launch
      if (session.tabs.isEmpty()) return@launch
      discardAllWebViews()
      privateProfile.destroy()
      viewModel.tabManager.replaceAll(session.tabs.map { it.url to it.title })
      restoreActiveTab()
    }
  }

  fun showSessionManager() {
    lifecycleScope.launch {
      val sessions = sessionRepository.getAll()
      val current = settings.currentSession
      val labels = sessions.map { session ->
        val marker = if (session.name == current) "\u25B8 " else "   "
        marker + getString(R.string.session_tab_count, session.name, session.tabs.size)
      } + getString(R.string.new_session)
      AlertDialog.Builder(this@BrowserActivity)
        .setTitle(R.string.sessions)
        .setItems(labels.toTypedArray()) { _, which ->
          if (which == sessions.size) {
            promptNewSession()
          } else {
            switchSession(sessions[which].name)
          }
        }
        .show()
    }
  }

  private fun promptNewSession() {
    val pad = (resources.displayMetrics.density * 20).toInt()
    val input = android.widget.EditText(this).apply {
      hint = getString(R.string.session_name_hint)
    }
    val container = android.widget.LinearLayout(this).apply {
      orientation = android.widget.LinearLayout.VERTICAL
      setPadding(pad, pad / 2, pad, 0)
      addView(input)
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.new_session)
      .setView(container)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val name = input.text.toString().trim()
        if (name.isNotEmpty()) startFreshSession(name)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** A new session begins empty: the old one is saved, the slate is clean. */
  private fun startFreshSession(name: String) {
    persistCurrentSession()
    settings.currentSession = name
    discardAllWebViews()
    privateProfile.destroy()
    viewModel.tabManager.replaceAll(emptyList())
    restoreActiveTab()
    persistCurrentSession()
  }

  private fun switchSession(name: String) {
    if (name == settings.currentSession) {
      confirmDeleteSession(name)
      return
    }
    persistCurrentSession()
    lifecycleScope.launch {
      val session = sessionRepository.get(name) ?: return@launch
      settings.currentSession = name
      discardAllWebViews()
      privateProfile.destroy()
      viewModel.tabManager.replaceAll(session.tabs.map { it.url to it.title })
      restoreActiveTab()
    }
  }

  /** Tapping the session you are already in offers the one destructive act, guarded. */
  private fun confirmDeleteSession(name: String) {
    if (name == SettingsStore.DEFAULT_SESSION) return
    AlertDialog.Builder(this)
      .setTitle(R.string.delete_session)
      .setMessage(name)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        lifecycleScope.launch {
          sessionRepository.delete(name)
          settings.currentSession = SettingsStore.DEFAULT_SESSION
          val home = sessionRepository.get(SettingsStore.DEFAULT_SESSION)
          discardAllWebViews()
          privateProfile.destroy()
          viewModel.tabManager.replaceAll(
            home?.tabs?.map { it.url to it.title }.orEmpty(),
          )
          restoreActiveTab()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showQrCode() {
    val url = viewModel.tabManager.activeTab()?.url.orEmpty()
    if (url.isBlank() || url == UrlFormatter.NEW_TAB_URL) return
    val size = (resources.displayMetrics.density * QR_SIZE_DP).toInt()
    val bitmap = QrCode.encode(url, size) ?: return
    val image = android.widget.ImageView(this).apply {
      setImageBitmap(bitmap)
      val pad = (resources.displayMetrics.density * 24).toInt()
      setPadding(pad, pad, pad, pad)
    }
    AlertDialog.Builder(this)
      .setTitle(UrlFormatter.prettyHost(url))
      .setView(image)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun shareCurrentPage() {
    val tab = viewModel.tabManager.activeTab() ?: return
    if (tab.url == UrlFormatter.NEW_TAB_URL) return
    shareUrl(tab.url, tab.title)
  }

  private fun shareUrl(url: String, subject: String) {
    val share = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, subject)
      putExtra(Intent.EXTRA_TEXT, url)
    }
    startActivity(Intent.createChooser(share, getString(R.string.share_page)))
  }

  /** One tap to end the session: every tab closed, every trace of it deleted. */
  private fun confirmBurn() {
    AlertDialog.Builder(this)
      .setMessage(R.string.burn_confirm)
      .setPositiveButton(R.string.burn_action) { _, _ -> clearBrowsingData() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun clearBrowsingData() {
    lifecycleScope.launch {
      privacyCleaner.clearAll(webView, includeHistory = true)
      // Every page goes, private ones first so their profile can be deleted underneath them.
      discardAllWebViews()
      privateProfile.destroy()
      viewModel.tabManager.closeAll()
      restoreActiveTab()
      toast(R.string.browsing_data_cleared)
    }
  }

  // endregion

  // region web chrome callbacks

  private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
    if (customView != null) {
      callback.onCustomViewHidden()
      return
    }
    customView = view
    customViewCallback = callback
    binding.customViewContainer.addView(view)
    binding.customViewContainer.isVisible = true
    binding.browserChrome.isGone = true
  }

  private fun hideCustomView() {
    val view = customView ?: return
    binding.customViewContainer.removeView(view)
    binding.customViewContainer.isGone = true
    binding.browserChrome.isVisible = true
    customViewCallback?.onCustomViewHidden()
    customView = null
    customViewCallback = null
  }

  private fun showFileChooser(
    callback: ValueCallback<Array<Uri>>,
    params: WebChromeClient.FileChooserParams,
  ): Boolean {
    fileChooserCallback?.onReceiveValue(null)
    fileChooserCallback = callback
    return try {
      fileChooserLauncher.launch(params.createIntent())
      true
    } catch (e: ActivityNotFoundException) {
      Timber.w(e, "no activity can pick files")
      fileChooserCallback = null
      false
    }
  }

  /**
   * Bound to the WebView that fired, not whichever tab is on screen: a background tab can start
   * a download too, and its blob must be read out of its own page, with its own page as referer.
   */
  private fun enqueueDownload(
    source: WebView,
    tabId: Long,
    url: String,
    userAgent: String,
    contentDisposition: String,
    mimeType: String,
  ) {
    if (url.startsWith("blob:")) {
      // A blob lives only in the page's JS heap; it is read out through the page itself.
      blobDownloader.download(source, url, mimeType)
      toast(R.string.download_started)
      return
    }
    if (settings.torMode) {
      // DownloadManager fetches from its own process, outside the proxy.
      toast(R.string.tor_downloads_off)
      return
    }
    val id = downloadController.enqueue(
      url = url,
      userAgent = userAgent,
      contentDisposition = contentDisposition,
      mimeType = mimeType,
      pageUrl = viewModel.tabManager.tab(tabId)?.url,
    )
    toast(if (id != null) R.string.download_started else R.string.download_failed)
  }

  private fun openExternally(url: String): Boolean {
    val intent = runCatching {
      if (url.startsWith("intent:")) {
        // A page may pick the app, never the exact component: with a component set, an
        // intent: link could start this app's own non-exported screens with its own extras.
        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
          addCategory(Intent.CATEGORY_BROWSABLE)
          component = null
          selector = null
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
      } else {
        Intent(Intent.ACTION_VIEW, url.toUri())
      }
    }.getOrNull() ?: return false
    return try {
      startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      Timber.w(e, "no app handles %s", url)
      val fallback = intent.getStringExtra(BROWSER_FALLBACK_URL)
      if (fallback != null && UrlFormatter.isWebUrl(fallback)) {
        loadUrl(fallback)
        true
      } else {
        false
      }
    }
  }

  // endregion

  private fun registerBackHandling() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          when {
            customView != null -> hideCustomView()
            binding.findBar.isVisible -> hideFindBar()
            isOmniboxFocused -> clearOmniboxFocus()
            webView?.canGoBack() == true -> webView?.goBack()
            viewModel.tabManager.size > 1 -> closeTab(viewModel.tabManager.activeTabId.value)
            else -> {
              isEnabled = false
              onBackPressedDispatcher.onBackPressed()
            }
          }
        }
      },
    )
  }

  /**
   * Picks the one destination the omnibox is clearly pointing at - a fully typed address, or a
   * history match - and asks the loader to get a head start on it. Bare search suggestions are
   * left alone: they are not a committed destination and prefetching them would be a guess.
   */
  private fun speculateFrom(suggestions: List<dev.androidpoet.wren.core.model.Suggestion>) {
    if (!isOmniboxFocused) {
      speculativeLoader.cancel()
      return
    }
    val text = binding.omnibox.text.toString().trim()
    val hasScheme = text.startsWith("http://", true) || text.startsWith("https://", true)
    val target = when {
      text.isEmpty() -> null
      hasScheme || UrlFormatter.isProbablyUrl(text) -> viewModel.resolve(text)
      // A history match is the only kind of suggestion worth preloading; a bare search is not.
      else -> suggestions.firstOrNull { it.fromHistory && it.url != null }?.url
    }
    if (target != null) {
      val isPrivate = viewModel.tabManager.activeTab()?.isPrivate == true
      speculativeLoader.speculate(target, isPrivate)
      // A history match is a page the user already goes to: strong enough to render it whole.
      if (!isPrivate && suggestions.firstOrNull()?.fromHistory == true) {
        webView?.let { speculativeLoader.prerender(it, target) }
      }
    } else {
      speculativeLoader.cancel()
      // A typed query that is not a URL ends at the search engine: have its socket open before
      // the user finishes typing. Nothing about the query itself leaves the device here.
      if (text.isNotEmpty() && viewModel.tabManager.activeTab()?.isPrivate != true) {
        speculativeLoader.warmOrigin(viewModel.homeUrl)
      }
    }
  }

  private fun clearOmniboxFocus() {
    isOmniboxFocused = false
    speculativeLoader.cancel()
    updateChromeVisibility()
    binding.omnibox.clearFocus()
    binding.root.requestFocus()
    binding.suggestionList.isGone = true
    getSystemService<InputMethodManager>()
      ?.hideSoftInputFromWindow(binding.omnibox.windowToken, 0)
  }

  private fun toast(messageRes: Int) {
    Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
  }

  override fun onPause() {
    super.onPause()
    liveWebViews.values.forEach { it.onPause() }
    saveActiveTabState()
    CookieManager.getInstance().flush()
  }

  /**
   * Under memory pressure the pool is the first thing to give: every background page folds back
   * into saved state, leaving the browser exactly as light as its single-WebView ancestor.
   */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // TRIM_MEMORY_UI_HIDDEN sits between the running and background levels and means only that
    // the browser left the screen, which happens constantly and is not pressure. Collapsing the
    // pool there would empty it every time the user answers a message.
    val underPressure = level == TRIM_MEMORY_RUNNING_LOW ||
      level == TRIM_MEMORY_RUNNING_CRITICAL ||
      level >= TRIM_MEMORY_BACKGROUND
    if (!underPressure) return
    val activeTabId = viewModel.tabManager.activeTabId.value
    liveWebViews.keys.filter { it != activeTabId }.forEach { tabId -> retireWebView(tabId) }
  }

  /**
   * Chromium's renderer behind [view] is gone: the page crashed it, or the system took it for
   * memory. Answering false would take the whole browser down with it. Instead the dead view is
   * released and the tab rebuilt from whatever state it can still give up, so the person sees
   * the page come back rather than the launcher.
   */
  private fun onRendererGone(tabId: Long, view: WebView, didCrash: Boolean): Boolean {
    val state = Bundle()
    if (runCatching { view.saveState(state) }.getOrNull() != null) {
      viewModel.tabManager.update(tabId, state = state)
    }
    liveWebViews.remove(tabId)
    destroyWebView(view)
    Timber.w("renderer gone for tab %d (crash=%s)", tabId, didCrash)
    if (isActiveTab(tabId)) {
      toast(R.string.page_crashed)
      restoreActiveTab()
    }
    return true
  }

  /** Folds one live page back into the saved state it can be rebuilt from, then releases it. */
  private fun retireWebView(tabId: Long) {
    liveWebViews.remove(tabId)?.let { view ->
      val state = Bundle()
      if (view.saveState(state) != null) viewModel.tabManager.update(tabId, state = state)
      destroyWebView(view)
    }
  }

  private var appliedBottomBar = false

  /**
   * Reachability: the whole address bar moves above the navigation row when asked. Same views,
   * same ids, same bindings - only their place in the column changes, so every listener and
   * inset continues to work untouched.
   */
  private fun applyAddressBarPosition() {
    appliedBottomBar = settings.bottomAddressBar
    if (!appliedBottomBar) return
    val chrome = binding.browserChrome
    val navRow = binding.backButton.parent as android.view.View
    chrome.removeView(binding.topBar)
    chrome.removeView(binding.progress)
    val navIndex = chrome.indexOfChild(navRow)
    chrome.addView(binding.progress, navIndex)
    chrome.addView(binding.topBar, navIndex + 1)
  }

  /** A private tab must not appear in screenshots or the recents preview. */
  private fun applySecureFlag() {
    val isPrivate = viewModel.tabManager.activeTab()?.isPrivate == true
    if (isPrivate || appLock.needsSecureWindow) {
      window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    renderPrivateChrome(isPrivate)
  }

  /**
   * A private tab has to look private, or nobody can tell which kind they are typing into. The
   * address bar and the home badge both turn amber, the colour every private control already
   * uses, so the answer is visible without reading anything.
   */
  private fun renderPrivateChrome(isPrivate: Boolean) = with(binding) {
    omniboxContainer.setBackgroundResource(
      if (isPrivate) R.drawable.bg_omnibox_private else R.drawable.bg_omnibox,
    )
    omnibox.setHint(if (isPrivate) R.string.search_privately else R.string.search_or_type_url)
    renderShieldPill(isPrivate)
  }

  private fun renderShieldPill(isPrivate: Boolean) = with(binding.home.homeShieldPill) {
    if (isPrivate) {
      setText(R.string.private_tab_badge)
      setTextColor(getColor(R.color.amber))
      setBackgroundResource(R.drawable.bg_badge_private)
      setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_private, 0, 0, 0)
      androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
        this,
        android.content.res.ColorStateList.valueOf(getColor(R.color.amber)),
      )
    } else {
      text = getString(R.string.trackers_blocked, blockedTotal)
      setTextColor(getColor(R.color.moss))
      setBackgroundResource(R.drawable.bg_badge_shield)
      setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_shield, 0, 0, 0)
      androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
        this,
        android.content.res.ColorStateList.valueOf(getColor(R.color.moss)),
      )
    }
  }

  private fun applyWindowPreferences() {
    if (settings.keepScreenOn) {
      window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    requestedOrientation = when (settings.orientationLock) {
      1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  override fun onStop() {
    super.onStop()
    persistCurrentSession()
  }

  override fun onResume() {
    super.onResume()
    applyWindowPreferences()
    applySecureFlag()
    if (appliedBottomBar != settings.bottomAddressBar) recreate()
    liveWebViews.values.forEach { it.onResume() }
    // Settings is a separate activity, so anything changed there lands on the live WebView here.
    webView?.let { view ->
      configurator.configure(view, viewModel.tabManager.activeTab()?.isPrivate == true)
      cosmeticFilter.updateFor(view, viewModel.tabManager.activeTab()?.url)
      privacyScripts.updateFor(view)
    }
  }

  override fun onDestroy() {
    if (isFinishing && settings.clearOnExit) privacyCleaner.clearSiteData(webView)
    if (isFinishing) discardAllWebViews() else retireAllWebViews()
    if (isFinishing) privateProfile.destroy()
    super.onDestroy()
  }

  private companion object {
    const val DISABLED_ALPHA = 0.35f
    const val DIM_PILL_ALPHA = 0.55f
    const val TOR_CHECK_URL = "https://check.torproject.org/"
    const val BROWSER_FALLBACK_URL = "browser_fallback_url"

    /** Live pages kept in the pool: enough for tab-to-tab work, few enough to stay light. */
    const val DEFAULT_LIVE_WEB_VIEWS = 4
    const val LOW_RAM_LIVE_WEB_VIEWS = 1
    const val QR_SIZE_DP = 260
    const val THUMBNAIL_SETTLE_MS = 350L
    const val TOUCH_WARM_DELAY_MS = 50L
    const val SNAPSHOT_FALLBACK_MS = 1500L
    const val REFRESH_TRIGGER_DP = 120
    const val HOME_GRID_SPAN = 5

    /**
     * Walks up from the element under the finger looking for an image. Coordinates are viewport
     * CSS pixels, which is what the touch point becomes once density is divided out.
     */
    val IMAGE_AT_POINT_SCRIPT = """
      (function (x, y) {
        try {
          var probe = document.elementFromPoint(x, y);
          while (probe) {
            if (probe.tagName === 'IMG') {
              return JSON.stringify({ image: probe.currentSrc || probe.src || null });
            }
            probe = probe.parentElement;
          }
          return JSON.stringify({});
        } catch (ignored) {
          return JSON.stringify({});
        }
      })(__X__, __Y__)
    """.trimIndent()
  }
}
