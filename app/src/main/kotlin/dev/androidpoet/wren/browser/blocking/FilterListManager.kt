/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.app.Application
import dev.androidpoet.wren.browser.blocking.rules.EngineBuilder
import dev.androidpoet.wren.browser.tor.TorProxySelector
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the filter lists current. The bundled snapshot is what ships; a copy of each enabled
 * list is fetched weekly (or on demand), cached on disk, and compiled together with the others
 * into a fresh [dev.androidpoet.wren.browser.blocking.rules.BlockEngine] that replaces the
 * snapshot whole. Fetches are conditional, so an unchanged list costs one request and no bytes.
 */
@Singleton
class FilterListManager @Inject constructor(
  private val application: Application,
  private val blocklist: Blocklist,
  private val settings: SettingsStore,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val rebuildLock = Mutex()
  private var version = 0

  val lists: List<FilterList> get() = FilterListCatalog.all

  fun isEnabled(id: String): Boolean =
    (settings.enabledFilterLists ?: FilterListCatalog.defaultIds).contains(id)

  fun setEnabled(id: String, enabled: Boolean) {
    val current = (settings.enabledFilterLists ?: FilterListCatalog.defaultIds).toMutableSet()
    if (enabled) current.add(id) else current.remove(id)
    settings.enabledFilterLists = current
    scope.launch {
      if (enabled && !cacheFile(id).exists()) fetch(id)
      rebuild()
    }
  }

  /** Applies whatever is cached, then refetches the stale lists. Safe to call on every start. */
  fun start() {
    scope.launch {
      legacyCacheFile().delete()
      if (!rebuild()) withContext(Dispatchers.Default) { blocklist.preload() }
      val stale = enabledIds().filter {
        System.currentTimeMillis() - settings.filterListUpdatedAt(it) > REFRESH_INTERVAL_MS
      }
      if (stale.isEmpty()) return@launch
      delay(REFRESH_START_DELAY_MS)
      var changed = false
      for (id in stale) if (fetch(id) == Fetched.UPDATED) changed = true
      if (changed) rebuild()
    }
  }

  /** Refetches every enabled list now. Returns how many came back changed, or -1 on failure. */
  suspend fun refreshNow(): Int = withContext(Dispatchers.IO) {
    var updated = 0
    var failed = false
    for (id in enabledIds()) {
      when (fetch(id)) {
        Fetched.UPDATED -> updated++
        Fetched.FAILED -> failed = true
        Fetched.UNCHANGED -> Unit
      }
    }
    if (updated > 0) rebuild()
    if (failed && updated == 0) -1 else updated
  }

  /** When each enabled list was last confirmed fresh; 0 for one never fetched. */
  fun lastUpdated(): Long = enabledIds().minOfOrNull { settings.filterListUpdatedAt(it) } ?: 0L

  private fun enabledIds(): List<String> {
    val ids = lists.map { it.id }.filter(::isEnabled).toMutableList()
    if (settings.blocklistUrl.isNotBlank()) ids.add(FilterListCatalog.CUSTOM_ID)
    return ids
  }

  private fun urlOf(id: String): String? = if (id == FilterListCatalog.CUSTOM_ID) {
    settings.blocklistUrl
  } else {
    FilterListCatalog.byId(id)?.url
  }

  private enum class Fetched { UPDATED, UNCHANGED, FAILED }

  private fun fetch(id: String): Fetched {
    val listUrl = urlOf(id) ?: return Fetched.FAILED
    // With Tor on but still connecting, this would leave from the real address. The list
    // stays on its cached copy until the next start, when the tunnel is already in place.
    if (TorProxySelector.wouldLeakFor(settings)) return Fetched.FAILED
    return runCatching {
      // A filter list decides what the browser refuses to load, so it only travels over TLS,
      // and it is read through a hard byte cap so a hostile server cannot fill the disk.
      require(listUrl.startsWith("https://", ignoreCase = true)) { "filter list must use https" }
      val cached = cacheFile(id)
      var connection = open(id, listUrl, cached)
      var hops = 0
      // Redirects are followed by hand so each hop can be checked: HttpURLConnection would
      // happily land on a LAN address, and the list decides what the browser refuses to load.
      while (connection.responseCode in ListUrlPolicy.REDIRECT_CODES) {
        val location = connection.getHeaderField("Location")
        val next = location?.let { URL(connection.url, it).toString() }
        connection.disconnect()
        check(++hops <= ListUrlPolicy.MAX_REDIRECTS) { "too many redirects" }
        check(next != null && ListUrlPolicy.acceptsRedirect(next)) { "redirect refused: $location" }
        connection = open(id, next, cached)
      }
      try {
        if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
          settings.setFilterListUpdatedAt(id, System.currentTimeMillis())
          return@runCatching Fetched.UNCHANGED
        }
        val code = connection.responseCode
        check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
        val temp = File(cached.path + ".part")
        temp.outputStream().buffered().use { out ->
          BoundedInputStream(connection.inputStream, MAX_BYTES).copyTo(out)
        }
        check(temp.length() > MIN_BYTES) { "list too small: ${temp.length()} bytes" }
        check(temp.renameTo(cached)) { "could not replace cached list" }
        settings.setFilterListEtag(id, connection.getHeaderField("ETag"))
        settings.setFilterListUpdatedAt(id, System.currentTimeMillis())
        Timber.i("filter list %s fetched: %d KB", id, cached.length() / 1024)
        Fetched.UPDATED
      } finally {
        connection.disconnect()
      }
    }.onFailure { Timber.w(it, "filter list %s refresh failed", id) }.getOrDefault(Fetched.FAILED)
  }

  private fun open(id: String, url: String, cached: File): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = FETCH_TIMEOUT_MS
    connection.readTimeout = FETCH_TIMEOUT_MS
    connection.instanceFollowRedirects = false
    if (cached.exists()) {
      settings.filterListEtag(id)?.let { connection.setRequestProperty("If-None-Match", it) }
      connection.ifModifiedSince = cached.lastModified()
    }
    return connection
  }

  /**
   * Compiles the enabled lists from disk into a new engine and installs it. Until every list
   * the snapshot was built from has its own cached copy, the snapshot's content is folded in
   * too, so switching a list on never loses coverage while its download is pending.
   */
  private suspend fun rebuild(): Boolean = rebuildLock.withLock {
    withContext(Dispatchers.Default) {
      val started = System.nanoTime()
      val cached = enabledIds().filter { cacheFile(it).exists() }
      if (cached.isEmpty()) {
        Timber.i("no cached filter lists; bundled snapshot stays active")
        return@withContext false
      }
      val builder = EngineBuilder()
      val snapshotCovered = FilterListCatalog.bundledIds.all { !isEnabled(it) || it in cached }
      if (!snapshotCovered) {
        builder.addHostSet(blocklist.bundledHosts())
        for (asset in SNAPSHOT_TEXT_ASSETS) {
          runCatching {
            application.assets.open(asset).bufferedReader().useLines { builder.addLines(it) }
          }.onFailure { Timber.w(it, "bundled %s unreadable", asset) }
        }
      }
      for (id in cached) {
        runCatching {
          cacheFile(id).bufferedReader().useLines { builder.addLines(it) }
        }.onFailure { Timber.w(it, "cached list %s unreadable", id) }
      }
      blocklist.install(builder.build(++version), replacesSnapshot = snapshotCovered)
      Timber.i("filter lists compiled in %d ms", (System.nanoTime() - started) / 1_000_000)
      true
    }
  }

  private class BoundedInputStream(
    private val source: InputStream,
    private var remaining: Long,
  ) : InputStream() {
    override fun read(): Int {
      if (remaining <= 0) return -1
      return source.read().also { if (it >= 0) remaining-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (remaining <= 0) return -1
      val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
      if (n > 0) remaining -= n
      return n
    }

    override fun close() = source.close()
  }

  private fun cacheDir(): File = File(application.filesDir, CACHE_DIR).apply { mkdirs() }

  private fun cacheFile(id: String): File = File(cacheDir(), "$id.txt")

  private fun legacyCacheFile(): File = File(application.filesDir, LEGACY_CACHE_NAME)

  private companion object {
    const val CACHE_DIR = "filter_lists"
    val SNAPSHOT_TEXT_ASSETS = listOf("blocklist.txt", "rules.txt", "cosmetic.txt")
    const val LEGACY_CACHE_NAME = "blocklist_remote.bin"
    const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    const val REFRESH_START_DELAY_MS = 20_000L
    const val FETCH_TIMEOUT_MS = 20_000
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MIN_BYTES = 256L
  }
}
