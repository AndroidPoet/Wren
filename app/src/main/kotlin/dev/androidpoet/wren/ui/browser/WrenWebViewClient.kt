/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import dev.androidpoet.wren.browser.UrlFormatter

/**
 * Navigation policy. Anything that is not an ordinary web page — `mailto:`, `tel:`, `intent:` —
 * is handed to the system rather than loaded, which is both what the user expects and what keeps
 * a page from driving the WebView somewhere it cannot render.
 */
class WrenWebViewClient(
  private val onPageStarted: (url: String) -> Unit,
  private val onPageFinished: (url: String, title: String) -> Unit,
  private val onExternalIntent: (url: String) -> Boolean,
  private val onIntercept: (WebResourceRequest) -> WebResourceResponse? = { null },
  /** Returns a replacement address to load instead, or null to let the request proceed. */
  private val onRewriteUrl: (url: String) -> String? = { null },
  private val onMainFrameError: (url: String, errorCode: Int) -> Unit = { _, _ -> },
  /** A server asked for HTTP credentials; the handler must be answered or cancelled. */
  private val onHttpAuth: (handler: HttpAuthHandler, host: String, realm: String) -> Unit =
    { handler, _, _ -> handler.cancel() },
  private val onFirstPaint: (url: String) -> Unit = {},
  /** Returns true once the dead [WebView] has been dealt with; false lets the app die with it. */
  private val onRendererGone: (view: WebView, didCrash: Boolean) -> Boolean = { _, _ -> false },
) : WebViewClient() {

  /**
   * The renderer behind this view is gone: it crashed, or the system killed it for memory. The
   * default answer is to crash the whole app. A browser is expected to lose the page instead.
   */
  @RequiresApi(Build.VERSION_CODES.O)
  override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
    onRendererGone(view, detail.didCrash())

  /**
   * The first paint of the new document is the moment the address bar may safely name it. Before
   * this, the viewport still belongs to the previous page, and an omnibox that has already moved
   * on is both wrong and the shape address-bar spoofing takes.
   */
  override fun onPageCommitVisible(view: WebView, url: String) {
    super.onPageCommitVisible(view, url)
    onFirstPaint(url)
  }

  override fun onReceivedHttpAuthRequest(
    view: WebView,
    handler: HttpAuthHandler,
    host: String,
    realm: String,
  ) {
    onHttpAuth(handler, host, realm)
  }

  override fun onReceivedError(
    view: WebView,
    request: WebResourceRequest,
    error: WebResourceError,
  ) {
    super.onReceivedError(view, request, error)
    if (request.isForMainFrame) onMainFrameError(request.url.toString(), error.errorCode)
  }

  override fun shouldInterceptRequest(
    view: WebView,
    request: WebResourceRequest,
  ): WebResourceResponse? = onIntercept(request)

  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
    handleUrl(
      url = request.url.toString(),
      isMainFrame = request.isForMainFrame,
      // Only a tap may hand the user to another app; an ad frame redirecting itself may not.
      mayLeaveApp = request.isForMainFrame && request.hasGesture(),
    )

  private fun handleUrl(url: String, isMainFrame: Boolean, mayLeaveApp: Boolean): Boolean = when {
    // Only the document's own navigation is rewritten: the replacement is loaded into the tab,
    // and a frame steering the tab that way would be a navigation the page never authorised.
    url.startsWith("http://") || url.startsWith("https://") -> isMainFrame && rewrite(url)
    // Anything with a scheme the engine cannot render belongs to another app: a store link, a
    // messenger, or the custom scheme a sign-in flow returns to.
    UrlFormatter.isExternalScheme(url) -> if (mayLeaveApp) onExternalIntent(url) else true
    // A page navigating the top frame to data: or javascript: is a phishing shape, not a link.
    isMainFrame && UrlFormatter.isScriptOrDataUrl(url) -> true
    else -> false
  }

  /**
   * Upgrades and cleans links the page itself follows, not just ones typed into the omnibox.
   * Returning true cancels the original request; the caller loads the replacement.
   */
  private fun rewrite(url: String): Boolean {
    val replacement = onRewriteUrl(url) ?: return false
    return replacement != url
  }

  /** True between a load starting and finishing; history updates inside it belong to the load. */
  private var isLoading = false

  override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
    super.onPageStarted(view, url, favicon)
    isLoading = true
    onPageStarted(url)
  }

  override fun onPageFinished(view: WebView, url: String) {
    super.onPageFinished(view, url)
    isLoading = false
    onPageFinished(url, view.title.orEmpty())
  }

  /**
   * Single-page apps change the address bar without a page load; this is the only callback that
   * fires for those, so history and the omnibox both hang off it. A normal load fires it too, at
   * commit, long before the page is done - reporting that as finished would end the progress bar,
   * record the visit and take the tab thumbnail while the page is still arriving.
   */
  override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
    super.doUpdateVisitedHistory(view, url, isReload)
    if (!isReload && !isLoading) onPageFinished(url, view.title.orEmpty())
  }
}
