/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.favicon

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import dev.androidpoet.wren.browser.blocking.HostMatcher
import dev.androidpoet.wren.browser.tor.TorProxySelector
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.ProxySelector
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Site icons for the home page, kept on disk and keyed by host.
 *
 * Two sources, in order of preference:
 *
 * 1. Icons the WebView already handed us while browsing. These are free - the page fetched its
 *    own icon anyway - and they are the reason a site you actually visit gets its real logo
 *    without the browser making a single extra request.
 * 2. A direct fetch from the site itself, only for tiles that have no icon yet.
 *
 * There is deliberately no third-party favicon service. Those are one request per domain to a
 * single company, which would hand that company the entire contents of a speed dial and a
 * most-visited list in one go - the exact profile this browser exists to avoid building.
 */
@Singleton
class FaviconStore @Inject constructor(
  private val application: Application,
  private val settings: SettingsStore,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) {

  private val memory = LruCache<String, Bitmap>(MEMORY_ENTRIES)

  /** Hosts already tried and found to have nothing, so the same misses are not refetched. */
  private val misses = mutableSetOf<String>()

  private val directory: File by lazy {
    File(application.filesDir, "favicons").apply { mkdirs() }
  }

  /** Returns a cached icon, fetching one if the site has never provided it. */
  private val cacheChecked = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Icons cached before the attribution fix could carry the wrong site's icon under a host's
   * name; poisoned entries never heal on their own, so the cache is emptied exactly once.
   */
  private fun dropPoisonedCacheOnce() {
    if (!cacheChecked.compareAndSet(false, true)) return
    val marker = File(directory, CACHE_MARKER)
    if (marker.exists()) return
    directory.listFiles()?.forEach { it.delete() }
    runCatching { marker.createNewFile() }
  }

  suspend fun iconFor(url: String): Bitmap? {
    dropPoisonedCacheOnce()
    val host = HostMatcher.hostOf(url) ?: return null
    memory[host]?.let { return it }

    return withContext(ioDispatcher) {
      readFromDisk(host)?.also { memory.put(host, it) }
        ?: fetch(host)?.also {
          memory.put(host, it)
          writeToDisk(host, it)
        }
    }
  }

  /** Stores an icon the WebView produced while loading a page. Costs no extra request. */
  suspend fun remember(url: String, icon: Bitmap) {
    val host = HostMatcher.hostOf(url) ?: return
    // A page can hand over the same icon repeatedly; only the first is worth writing.
    if (memory[host] != null) return
    val scaled = scale(icon) ?: return
    memory.put(host, scaled)
    misses.remove(host)
    withContext(ioDispatcher) { writeToDisk(host, scaled) }
  }

  private fun readFromDisk(host: String): Bitmap? {
    val file = fileFor(host)
    if (!file.exists()) return null
    return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
  }

  private fun writeToDisk(host: String, icon: Bitmap) {
    runCatching {
      fileFor(host).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.onFailure { Timber.w(it, "could not cache an icon for %s", host) }
  }

  private fun fetch(host: String): Bitmap? {
    if (!settings.loadSiteIcons || host in misses) return null
    // With Tor on but still connecting, the proxy is not installed yet and this request would
    // leave from the real address. Skip without recording a miss; the next open retries.
    if (settings.torMode && ProxySelector.getDefault() !is TorProxySelector) return null

    for (path in ICON_PATHS) {
      val bitmap = runCatching { download("https://$host$path") }.getOrNull()
      if (bitmap != null) return scale(bitmap)
    }
    // Remember the miss so a speed dial full of icon-less sites does not retry on every open.
    misses.add(host)
    return null
  }

  private fun download(url: String): Bitmap? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      instanceFollowRedirects = true
      // The icon is for us, not for the page; send nothing that identifies the user.
      setRequestProperty("User-Agent", "Mozilla/5.0 (compatible)")
      setRequestProperty("Accept", "image/png,image/*")
    }
    return try {
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
      if (connection.contentLength > MAX_BYTES) return null
      connection.inputStream.use(BitmapFactory::decodeStream)
    } finally {
      connection.disconnect()
    }
  }

  /** Tiles are small; a 512px icon is wasted memory in an LRU that holds dozens. */
  private fun scale(icon: Bitmap): Bitmap? {
    if (icon.width <= 0 || icon.height <= 0) return null
    if (icon.width <= MAX_PIXELS && icon.height <= MAX_PIXELS) return icon
    return runCatching { Bitmap.createScaledBitmap(icon, MAX_PIXELS, MAX_PIXELS, true) }.getOrNull()
  }

  private fun fileFor(host: String) = File(
    directory,
    host.replace(Regex("[^a-z0-9.-]"), "_") + ".png",
  )

  private companion object {
    const val CACHE_MARKER = "v2"
    const val MEMORY_ENTRIES = 64
    const val MAX_PIXELS = 128
    const val MAX_BYTES = 512 * 1024
    const val TIMEOUT_MS = 4000

    /**
     * Ordered by how likely the result is to be a real bitmap. `favicon.ico` is last because
     * Android cannot decode the ICO container at all - it only works when a site happens to
     * serve PNG bytes from that path, which many do.
     */
    val ICON_PATHS = listOf(
      "/apple-touch-icon.png",
      "/apple-touch-icon-precomposed.png",
      "/favicon.png",
      "/favicon-96x96.png",
      "/favicon.ico",
    )
  }
}
