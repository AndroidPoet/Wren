/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.androidpoet.wren.browser.UrlFormatter

/**
 * Everything a page can ask the browser to do. Capability requests are denied by default:
 * geolocation and camera/microphone access are answered no rather than prompted, because a
 * browser that has not shipped per-site permission UI cannot ask the question honestly.
 */
class WrenWebChromeClient(
  private val onProgress: (Int) -> Unit,
  private val onTitle: (String) -> Unit,
  private val onIcon: (pageUrl: String?, icon: Bitmap) -> Unit,
  private val onNewWindow: (String) -> Unit,
  private val onEnterFullscreen: (View, CustomViewCallback) -> Unit,
  private val onExitFullscreen: () -> Unit,
  private val onFileChooserRequest: (ValueCallback<Array<Uri>>, FileChooserParams) -> Boolean,
) : WebChromeClient() {

  override fun onProgressChanged(view: WebView, newProgress: Int) {
    onProgress(newProgress)
  }

  override fun onReceivedTitle(view: WebView, title: String?) {
    onTitle(title.orEmpty())
  }

  /**
   * The page fetched this itself, so taking a copy costs nothing. The URL is read from the
   * WebView at delivery time: an icon can arrive after the user has moved on, and attributing
   * it to whichever tab happens to be active would file one site's icon under another's name.
   */
  override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
    icon?.let { onIcon(view.url, it) }
  }

  /**
   * `target="_blank"` links. The transport WebView is never attached or rendered; it exists only
   * long enough to report the URL the page wanted, which then opens as a real tab.
   */
  override fun onCreateWindow(
    view: WebView,
    isDialog: Boolean,
    isUserGesture: Boolean,
    resultMsg: Message,
  ): Boolean {
    val transportView = WebView(view.context)
    var done = false
    val finish = {
      if (!done) {
        done = true
        transportView.destroy()
      }
    }
    transportView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(
        transport: WebView,
        request: WebResourceRequest,
      ): Boolean {
        openIfWeb(request.url.toString())
        finish()
        return true
      }

      @Deprecated("Kept for API 23, which does not call the WebResourceRequest overload.")
      override fun shouldOverrideUrlLoading(transport: WebView, url: String): Boolean {
        openIfWeb(url)
        finish()
        return true
      }
    }
    // A popup the page writes into directly (`window.open()` then `document.write`) never
    // navigates, so nothing above would ever run; the transport is not kept around for it.
    transportView.postDelayed(finish, TRANSPORT_LIFETIME_MS)
    (resultMsg.obj as? WebView.WebViewTransport)?.webView = transportView
    resultMsg.sendToTarget()
    return true
  }

  override fun onCloseWindow(window: WebView) {
    window.destroy()
  }

  /**
   * Only web pages become tabs. A tab opened here is loaded by the app, not the page, so a
   * `data:` or `intent:` target would skip both Chromium's block on page-initiated top-level
   * data: navigations and the gesture check external apps get in the WebViewClient.
   */
  private fun openIfWeb(url: String) {
    if (UrlFormatter.isWebUrl(url)) onNewWindow(url)
  }

  override fun onShowCustomView(view: View, callback: CustomViewCallback) {
    onEnterFullscreen(view, callback)
  }

  override fun onHideCustomView() {
    onExitFullscreen()
  }

  override fun onShowFileChooser(
    webView: WebView,
    filePathCallback: ValueCallback<Array<Uri>>,
    fileChooserParams: FileChooserParams,
  ): Boolean = onFileChooserRequest(filePathCallback, fileChooserParams)

  override fun onGeolocationPermissionsShowPrompt(
    origin: String,
    callback: GeolocationPermissions.Callback,
  ) {
    callback.invoke(origin, false, false)
  }

  override fun onPermissionRequest(request: PermissionRequest) {
    request.deny()
  }

  private companion object {
    const val TRANSPORT_LIFETIME_MS = 10_000L
  }
}
