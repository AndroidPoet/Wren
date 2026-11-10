/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.browser

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.R
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.ui.showSecure

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
  /** True for the one view whose page is on screen and not behind the app lock. */
  private val isOnScreen: (WebView) -> Boolean,
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
  // The page's own dialogs are page content: over a private or Tor window they must not land
  // in a screenshot or the recents preview either, which the engine's default dialogs would.
  override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
    if (!canShowDialog(view)) return dismissed(result)
    AlertDialog.Builder(view.context)
      .setTitle(dialogTitle(url))
      .setMessage(message)
      .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
      .setOnCancelListener { result.cancel() }
      .showSecure()
    return true
  }

  override fun onJsConfirm(
    view: WebView,
    url: String?,
    message: String?,
    result: JsResult,
  ): Boolean {
    if (!canShowDialog(view)) return dismissed(result)
    AlertDialog.Builder(view.context)
      .setTitle(dialogTitle(url))
      .setMessage(message)
      .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
      .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
      .setOnCancelListener { result.cancel() }
      .showSecure()
    return true
  }

  override fun onJsPrompt(
    view: WebView,
    url: String?,
    message: String?,
    defaultValue: String?,
    result: JsPromptResult,
  ): Boolean {
    if (!canShowDialog(view)) return dismissed(result)
    val input = EditText(view.context).apply {
      setText(defaultValue)
      imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    AlertDialog.Builder(view.context)
      .setTitle(dialogTitle(url))
      .setMessage(message)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
      .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
      .setOnCancelListener { result.cancel() }
      .showSecure()
    return true
  }

  override fun onJsBeforeUnload(
    view: WebView,
    url: String?,
    message: String?,
    result: JsResult,
  ): Boolean {
    if (!canShowDialog(view)) return dismissed(result)
    AlertDialog.Builder(view.context)
      .setTitle(dialogTitle(url))
      .setMessage(view.context.getString(R.string.page_leave_message))
      .setPositiveButton(R.string.page_leave) { _, _ -> result.confirm() }
      .setNegativeButton(R.string.page_stay) { _, _ -> result.cancel() }
      .setOnCancelListener { result.cancel() }
      .showSecure()
    return true
  }

  private fun canShowDialog(view: WebView): Boolean =
    view.isAttachedToWindow && isOnScreen(view) && (view.context as? Activity)?.isFinishing != true

  /** A page not on screen gets no dialog: the engine's own would not be secure either. */
  private fun dismissed(result: JsResult): Boolean {
    result.cancel()
    return true
  }

  private fun dialogTitle(url: String?): String =
    url?.let { Uri.parse(it).host }?.takeIf { it.isNotBlank() } ?: url.orEmpty()

  override fun onCreateWindow(
    view: WebView,
    isDialog: Boolean,
    isUserGesture: Boolean,
    resultMsg: Message,
  ): Boolean {
    val transportView = WebView(view.context)
    // The transport only exists to learn the address. It must never fetch anything itself: a
    // form posted into a popup never reaches shouldOverrideUrlLoading, and would otherwise
    // load here, outside the opener's profile and cookie jar.
    transportView.settings.blockNetworkLoads = true
    transportView.settings.domStorageEnabled = false
    // A popup shares its opener's storage. On the default profile that would join a private
    // or Tor opener to the clear-net jar; the transport joins the opener's profile instead.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
      runCatching { WebViewCompat.setProfile(transportView, WebViewCompat.getProfile(view).name) }
    }
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
