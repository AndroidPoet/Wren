/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tabs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.PixelCopy
import android.view.Window
import android.webkit.WebView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Page snapshots for the tab grid and the freeze-dried tab switch.
 *
 * Hardware-rendered WebView content does not reliably land in a software Canvas - View.draw()
 * can hand back a blank bitmap on real devices - so the primary path reads the pixels the GPU
 * actually put on screen with PixelCopy, and the Canvas path stays only as the fallback for
 * whatever PixelCopy refuses. Half resolution, LRU-capped: the grid needs a picture, not the
 * pixels of the whole session.
 */
@Singleton
class TabThumbnails @Inject constructor() {

  private val cache = object : LruCache<Long, Bitmap>(MAX_BYTES) {
    override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  fun capture(window: Window, webView: WebView, tabId: Long) {
    val width = webView.width / 2
    val height = webView.height / 2
    if (width <= 0 || height <= 0 || !webView.isShown) return
    val location = IntArray(2)
    webView.getLocationInWindow(location)
    val source = Rect(
      location[0],
      location[1],
      location[0] + webView.width,
      location[1] + webView.height,
    )
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    runCatching {
      PixelCopy.request(
        window,
        source,
        bitmap,
        { result ->
          if (result == PixelCopy.SUCCESS) {
            cache.put(tabId, bitmap)
          } else {
            captureBySoftwareDraw(webView, tabId)
          }
        },
        mainHandler,
      )
    }.onFailure { captureBySoftwareDraw(webView, tabId) }
  }

  private fun captureBySoftwareDraw(webView: WebView, tabId: Long) {
    val width = webView.width / 2
    val height = webView.height / 2
    if (width <= 0 || height <= 0) return
    runCatching {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
      val canvas = Canvas(bitmap)
      canvas.scale(HALF, HALF)
      canvas.translate(0f, -webView.scrollY.toFloat())
      webView.draw(canvas)
      cache.put(tabId, bitmap)
    }
  }

  operator fun get(tabId: Long): Bitmap? = cache.get(tabId)

  fun remove(tabId: Long) {
    cache.remove(tabId)
  }

  private companion object {
    const val MAX_BYTES = 24 * 1024 * 1024
    const val HALF = 0.5f
  }
}
