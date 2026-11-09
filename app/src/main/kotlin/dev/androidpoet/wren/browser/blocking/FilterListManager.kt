/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.app.Application
import dev.androidpoet.wren.browser.blocking.rules.EngineBuilder
import dev.androidpoet.wren.browser.tor.TorProxySelector
import dev.androidpoet.wren.network.SafeHttp
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
  http: SafeHttp,
) {
  private val client = http.client.newBuilder()
    .connectTimeout(FETCH_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    .readTimeout(FETCH_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    .build()

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
      // The bundled snapshot first: it is the smaller parse, and until some engine exists the
      // first page's requests go through unfiltered. The cached lists then replace it.
      withContext(Dispatchers.Default) { blocklist.preload() }
      rebuild()
      // A cached list fetched by an older build may lack what this build reads out of it, such
      // as the files a list includes; one refetch brings every list up to the current shape.
      val reshaped = settings.filterListFormat != LIST_FORMAT
      val stale = enabledIds().filter {
        reshaped || System.currentTimeMillis() - settings.filterListUpdatedAt(
          it,
        ) > REFRESH_INTERVAL_MS
      }
      if (stale.isEmpty()) return@launch
      delay(REFRESH_START_DELAY_MS)
      var changed = false
      var failed = false
      for (id in stale) {
        when (fetch(id, whole = reshaped)) {
          Fetched.UPDATED -> changed = true
          Fetched.FAILED -> failed = true
          Fetched.UNCHANGED -> Unit
        }
      }
      if (reshaped && !failed) settings.filterListFormat = LIST_FORMAT
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

  /** [whole] asks for the full list even where the server would answer "not modified". */
  private fun fetch(id: String, whole: Boolean = false): Fetched {
    val listUrl = urlOf(id) ?: return Fetched.FAILED
    // With Tor on but still connecting, this would leave from the real address. The list
    // stays on its cached copy until the next start, when the tunnel is already in place.
    if (TorProxySelector.wouldLeakFor(settings)) return Fetched.FAILED
    return runCatching {
      // A filter list decides what the browser refuses to load, so it only travels over TLS,
      // and it is read through a hard byte cap so a hostile server cannot fill the disk.
      require(listUrl.startsWith("https://", ignoreCase = true)) { "filter list must use https" }
      val cached = cacheFile(id)
      var connection = open(id, listUrl, cached, whole)
      var hops = 0
      // Redirects are followed by hand so each hop can be checked: an automatic follow would
      // happily land on a LAN address, and the list decides what the browser refuses to load.
      while (connection.code in ListUrlPolicy.REDIRECT_CODES) {
        val location = connection.header("Location")
        val next = location?.let { URL(connection.request.url.toUrl(), it).toString() }
        connection.close()
        check(++hops <= ListUrlPolicy.MAX_REDIRECTS) { "too many redirects" }
        check(next != null && ListUrlPolicy.acceptsRedirect(next)) { "redirect refused: $location" }
        connection = open(id, next, cached, whole)
      }
      try {
        if (connection.code == HTTP_NOT_MODIFIED) {
          settings.setFilterListUpdatedAt(id, System.currentTimeMillis())
          return@runCatching Fetched.UNCHANGED
        }
        val code = connection.code
        check(code == HTTP_OK) { "HTTP $code" }
        val temp = File(cached.path + ".part")
        temp.outputStream().buffered().use { out ->
          BoundedInputStream(connection.body.byteStream(), MAX_BYTES).copyTo(out)
        }
        check(temp.length() > MIN_BYTES) { "list too small: ${temp.length()} bytes" }
        appendIncludes(temp, connection.request.url.toUrl())
        check(temp.renameTo(cached)) { "could not replace cached list" }
        settings.setFilterListEtag(id, connection.header("ETag"))
        settings.setFilterListUpdatedAt(id, System.currentTimeMillis())
        Timber.i("filter list %s fetched: %d KB", id, cached.length() / 1024)
        Fetched.UPDATED
      } finally {
        connection.close()
      }
    }.onFailure { Timber.w(it, "filter list %s refresh failed", id) }.getOrDefault(Fetched.FAILED)
  }

  /**
   * uBlock's lists are split: `filters.txt` is a stub that `!#include`s the yearly files that
   * hold most of its rules. The includes are fetched one level deep, from the same host as the
   * list that names them, and appended to the cached copy, so the engine reads one file.
   */
  private fun appendIncludes(file: File, listUrl: URL) {
    val names = file.bufferedReader().useLines { lines ->
      lines.filter { it.startsWith(INCLUDE_DIRECTIVE) }
        .map { it.substring(INCLUDE_DIRECTIVE.length).trim() }
        .filter { it.isNotEmpty() && !it.contains(' ') }
        .take(MAX_INCLUDES)
        .toList()
    }
    if (names.isEmpty()) return
    FileOutputStream(file, true).buffered().use { out ->
      for (name in names) {
        val target = runCatching { URL(listUrl, name) }.getOrNull() ?: continue
        val ok = target.protocol == "https" && target.host == listUrl.host &&
          ListUrlPolicy.acceptsRedirect(target.toString())
        if (!ok) {
          Timber.w("include %s of %s refused", name, listUrl)
          continue
        }
        runCatching {
          val connection =
            open(FilterListCatalog.CUSTOM_ID, target.toString(), File(""), whole = true)
          try {
            val code = connection.code
            check(code == HTTP_OK) { "HTTP $code" }
            out.write("\n! --- included: $name ---\n".toByteArray())
            BoundedInputStream(connection.body.byteStream(), MAX_BYTES).copyTo(out)
          } finally {
            connection.close()
          }
        }.onFailure { Timber.w(it, "include %s of %s failed", name, listUrl) }
      }
    }
  }

  private fun open(id: String, url: String, cached: File, whole: Boolean): Response {
    val request = okhttp3.Request.Builder().url(url).apply {
      if (cached.exists() && !whole) {
        settings.filterListEtag(id)?.let { header("If-None-Match", it) }
        header("If-Modified-Since", httpDate(cached.lastModified()))
      }
    }.build()
    return client.newCall(request).execute()
  }

  private fun httpDate(millis: Long): String =
    java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
      .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
      .format(java.util.Date(millis))

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
    const val INCLUDE_DIRECTIVE = "!#include "
    const val MAX_INCLUDES = 12
    const val CACHE_DIR = "filter_lists"
    val SNAPSHOT_TEXT_ASSETS = listOf("blocklist.txt", "rules.txt", "cosmetic.txt")
    const val LEGACY_CACHE_NAME = "blocklist_remote.bin"
    const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** Bump when a build reads cached lists differently; 2 = included files appended. */
    const val LIST_FORMAT = 2
    const val REFRESH_START_DELAY_MS = 20_000L
    const val FETCH_TIMEOUT_MS = 20_000
    const val HTTP_OK = 200
    const val HTTP_NOT_MODIFIED = 304
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MIN_BYTES = 256L
  }
}
