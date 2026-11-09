/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.app.Application
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import dev.androidpoet.wren.browser.PrivateProfile
import dev.androidpoet.wren.browser.TorProfile
import dev.androidpoet.wren.browser.tor.TorProxySelector
import dev.androidpoet.wren.core.data.repository.DownloadRepository
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import dev.androidpoet.wren.network.SafeHttp
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.ProxySelector
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One transfer as the notification and the list see it. */
data class TransferState(
  val id: Long,
  val fileName: String,
  val downloadedBytes: Long,
  val totalBytes: Long,
  val paused: Boolean,
)

/**
 * Downloads that run inside the app's own process, for the cases the system DownloadManager
 * cannot serve: a file fetched while Tor is on, which has to leave through the proxy this
 * process installed rather than from the system's; and a `data:` address, which holds its
 * bytes and needs no fetch at all.
 *
 * Fetching goes through [SafeHttp], which follows the process proxy selector on every
 * connection, so it follows Tor on and off exactly as the pages do, and under Tor refuses any
 * route that is not the tunnel. A transfer writes to a
 * part file in the cache and is published to the Downloads folder only when complete. It
 * resumes by byte range after a pause or a dropped connection, with `If-Range` on the ETag so
 * a file that changed on the server starts over instead of being stitched together wrong.
 */
@Singleton
class InAppDownloader @Inject constructor(
  private val application: Application,
  private val repository: DownloadRepository,
  private val settings: SettingsStore,
  private val privateProfile: PrivateProfile,
  private val torProfile: TorProfile,
  private val http: SafeHttp,
) {

  data class Request(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val pageUrl: String?,
    val isPrivate: Boolean,
    /** Started under Tor: every connection, including a resumed one, must go through Tor. */
    val viaTor: Boolean,
    /** The user already confirmed an app download, or the address never looked like one. */
    val apkAllowed: Boolean = false,
  )

  /**
   * Asked before a file the page named late turns out to be an app. The first function
   * publishes it, the second drops it; without a listener the file is dropped.
   */
  var confirmAppFile: ((publish: () -> Unit, drop: () -> Unit) -> Unit)? = null

  private class Transfer(val id: Long, val request: Request, var fileName: String, val part: File) {
    var job: Job? = null
    var etag: String? = null
    var lastModified: String? = null
    var paused = false
    var downloaded = 0L
    var total = -1L
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val transfers = ConcurrentHashMap<Long, Transfer>()

  private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
  val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

  private val _states = MutableStateFlow<List<TransferState>>(emptyList())

  /** Every live or paused transfer, for the notifications. Empty once all are settled. */
  val states: StateFlow<List<TransferState>> = _states.asStateFlow()

  private val _completed = MutableSharedFlow<Download>(extraBufferCapacity = 8)
  val completed: SharedFlow<Download> = _completed.asSharedFlow()

  /** True for an id this downloader owns; the system's ids are positive. */
  fun owns(id: Long): Boolean = id < 0

  /** Starts a transfer and records it. Null when nothing could even be queued. */
  fun start(request: Request): Long? {
    if (!request.url.startsWith("http://", true) && !request.url.startsWith("https://", true)) {
      return null
    }
    if (!routeAvailable(request)) return null
    val id = newId()
    val name = DownloadNames.choose(request.url, request.contentDisposition, request.mimeType)
    val transfer = Transfer(id, request, name, partFile(id))
    transfers[id] = transfer
    scope.launch {
      repository.record(
        Download(
          id = id,
          url = request.url,
          fileName = name,
          mimeType = request.mimeType.orEmpty().substringBefore(';').trim(),
          totalBytes = -1L,
          status = DownloadStatus.RUNNING,
          localUri = null,
          createdAt = System.currentTimeMillis(),
          isPrivate = request.isPrivate,
          viaTor = request.viaTor,
        ),
      )
      launchTransfer(transfer)
    }
    return id
  }

  /**
   * Whether a connection for [request] may open right now. A Tor transfer never leaves while
   * Tor is off or still starting - the same address and byte range from the real address
   * would tie the two together - and no transfer leaves while Tor is on but its proxy is not
   * yet in place.
   */
  private fun routeAvailable(request: Request): Boolean {
    if (request.viaTor && (!settings.torMode || ProxySelector.getDefault() !is TorProxySelector)) {
      return false
    }
    return !TorProxySelector.wouldLeakFor(settings)
  }

  /**
   * Saves the bytes a `data:` address carries, under the page's `download="…"` name when the
   * link had one. Null when the address is not one.
   */
  fun saveDataUrl(
    url: String,
    isPrivate: Boolean,
    suggestedName: String? = null,
    apkAllowed: Boolean = false,
  ): Long? {
    if (!url.startsWith("data:", ignoreCase = true)) return null
    val id = newId()
    scope.launch {
      val saved = runCatching { decodeDataUrl(url) }.getOrNull()
      if (saved == null) {
        Timber.w("data: download could not be decoded")
        return@launch
      }
      val (mime, bytes) = saved
      val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
      val name = suggestedName?.let(DownloadNames::sanitize)
        ?.takeIf { it.isNotBlank() && it != "." }
        ?: ("download-" + System.currentTimeMillis() + "." + extension)
      val file = File(application.cacheDir, "data-$id")
      runCatching {
        file.writeBytes(bytes)
        if (!apkAllowed && (
            AndroidAppFile.isAppName(
              name,
            ) || mime.equals(AndroidAppFile.MIME_TYPE, true)
            )
        ) {
          // The address said nothing about an app; the name or type does. Ask, as for any app.
          val confirm = confirmAppFile ?: error("app file refused")
          val proceed = kotlinx.coroutines.CompletableDeferred<Boolean>()
          confirm({ proceed.complete(true) }, { proceed.complete(false) })
          if (!proceed.await()) error("app file refused")
        }
        val uri = DownloadsFolder.publish(application, file, name, mime) ?: error("not published")
        val download = Download(
          id = id,
          url = "data:",
          fileName = name,
          mimeType = mime,
          totalBytes = bytes.size.toLong(),
          status = DownloadStatus.COMPLETE,
          localUri = uri,
          createdAt = System.currentTimeMillis(),
          isPrivate = isPrivate,
        )
        repository.record(download)
        _completed.tryEmit(download)
      }.onFailure { Timber.w(it, "data: download failed") }
      file.delete()
    }
    return id
  }

  fun pause(id: Long) {
    val transfer = transfers[id] ?: return
    transfer.paused = true
    transfer.job?.cancel()
    transfer.job = null
    scope.launch { repository.updateStatus(id, DownloadStatus.PAUSED, null, transfer.total) }
    publishStates()
  }

  /** False when the transfer cannot continue on the current route; it stays paused. */
  fun resume(id: Long): Boolean {
    val transfer = transfers[id] ?: return false
    if (transfer.job?.isActive == true) return true
    if (!routeAvailable(transfer.request)) return false
    transfer.paused = false
    scope.launch {
      repository.updateStatus(id, DownloadStatus.RUNNING, null, transfer.total)
      launchTransfer(transfer)
    }
    return true
  }

  /** Stops a transfer and drops its partial file; the record is the caller's to remove. */
  fun cancel(id: Long) {
    val transfer = transfers.remove(id) ?: return
    transfer.job?.cancel()
    transfer.part.delete()
    _progress.update { it - id }
    publishStates()
  }

  /** Removes the record of a cancelled transfer. */
  suspend fun forget(id: Long) = repository.remove(id)

  /** Starts a failed or cancelled download over from its record. */
  fun retry(download: Download): Long? = start(
    Request(
      url = download.url,
      userAgent = null,
      contentDisposition = null,
      mimeType = download.mimeType.ifBlank { null },
      pageUrl = null,
      isPrivate = download.isPrivate,
      viaTor = download.viaTor,
      // The record's name is on the downloads screen; a retry of an app is a known app.
      apkAllowed = AndroidAppFile.isAppName(download.fileName) ||
        download.mimeType.equals(AndroidAppFile.MIME_TYPE, ignoreCase = true),
    ),
  )

  private fun launchTransfer(transfer: Transfer) {
    transfer.job = scope.launch { run(transfer) }
    publishStates()
    DownloadService.start(application)
  }

  private suspend fun run(transfer: Transfer) {
    val id = transfer.id
    val outcome = runCatching { fetch(transfer) }
    if (!currentCoroutineIsActive()) {
      // Paused: the part file stays for the resume. Cancelled: already removed.
      return
    }
    outcome.onSuccess { file ->
      if (!transfer.request.apkAllowed && AndroidAppFile.isAppName(transfer.fileName)) {
        // The server named the file an app after the address had been passed: ask now, before
        // anything a file manager would offer to install lands in Downloads.
        val proceed = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val confirm = confirmAppFile
        if (confirm == null) {
          proceed.complete(false)
        } else {
          confirm({
            proceed.complete(true)
          }, { proceed.complete(false) })
        }
        if (!proceed.await()) {
          file.delete()
          transfers.remove(id)
          _progress.update { it - id }
          publishStates()
          repository.updateStatus(id, DownloadStatus.CANCELLED, null, transfer.total)
          return
        }
      }
      val mime = transfer.request.mimeType.orEmpty().substringBefore(';').trim()
        .ifBlank { "application/octet-stream" }
      val uri = DownloadsFolder.publish(application, file, transfer.fileName, mime)
      file.delete()
      transfers.remove(id)
      _progress.update { it - id }
      publishStates()
      if (uri == null) {
        repository.updateStatus(id, DownloadStatus.FAILED, null, transfer.total)
        return
      }
      repository.updateStatus(id, DownloadStatus.COMPLETE, uri, transfer.downloaded)
      repository.get(id)?.let { _completed.tryEmit(it) }
    }.onFailure { error ->
      Timber.w(error, "in-app download failed")
      transfers.remove(id)
      transfer.part.delete()
      _progress.update { it - id }
      publishStates()
      repository.updateStatus(id, DownloadStatus.FAILED, null, transfer.total)
    }
  }

  private suspend fun currentCoroutineIsActive(): Boolean = currentCoroutineContext().isActive

  /**
   * Fetches into the part file, resuming what is already there. Returns the finished file, or
   * the part file as it stands when the transfer was paused underneath it. At most two
   * attempts: the second only when the server refused the range the first one asked for.
   */
  private suspend fun fetch(transfer: Transfer): File = withContext(Dispatchers.IO) {
    val request = transfer.request
    val part = transfer.part
    // The download carries the cookies of the profile its page lived in and no other: a
    // private page's private jar, a Tor page's Tor jar, else the ordinary one. A profile
    // without a jar sends none. The ordinary jar under Tor would hand the exit the same
    // cookie the site set over the real connection.
    val jar = when {
      request.isPrivate -> privateProfile.cookieManager()
      request.viaTor -> torProfile.cookieManager()
      else -> CookieManager.getInstance()
    }
    var currentUrl = request.url
    var hops = 0
    for (attempt in 1..2) {
      check(routeAvailable(request)) { "route unavailable" }
      // A resume is only safe when the server can tell us it is still the same file; without
      // an ETag or Last-Modified the ranges of two versions could be spliced together.
      val validator = transfer.etag ?: transfer.lastModified
      if (part.exists() && validator == null) part.delete()
      val have = if (part.exists()) part.length() else 0L
      var response: Response
      while (true) {
        // Redirects are followed by hand: an automatic follow would replay the Cookie and
        // Referer of the first address at every hop, to whatever host the chain ends on.
        val hop = okhttp3.Request.Builder().url(currentUrl).apply {
          request.userAgent?.let { header("User-Agent", it) }
          DownloadHeaders.referer(request.pageUrl, settings.trimReferrers, currentUrl)
            ?.let { header("Referer", it) }
          jar?.getCookie(currentUrl)?.let { header("Cookie", it) }
          if (have > 0 && validator != null) {
            header("Range", "bytes=$have-")
            header("If-Range", validator)
          }
        }.build()
        response = http.client.newCall(hop).execute()
        if (response.code !in REDIRECT_CODES) break
        val location = response.header("Location")
        response.close()
        check(location != null) { "redirect without a location" }
        check(++hops <= MAX_REDIRECTS) { "too many redirects" }
        val next = URL(URL(currentUrl), location).toString()
        val encrypted = next.startsWith("https://", ignoreCase = true)
        val plain = next.startsWith("http://", ignoreCase = true)
        check(encrypted || plain) { "redirect off the web" }
        // Tor mode and HTTPS-only never fetch over plain http; a redirect is no exception.
        check(
          encrypted || (!settings.torMode && !settings.httpsOnly),
        ) { "redirect to http refused" }
        currentUrl = next
      }
      val code = response.code
      when {
        code == HTTP_PARTIAL && have > 0 -> {
          val range = CONTENT_RANGE.find(response.header("Content-Range").orEmpty())
          val from = range?.groupValues?.get(1)?.toLongOrNull()
          val sameFile = response.header("ETag").let { it == null || it == transfer.etag }
          if (from != have || !sameFile) {
            // The server continued from the wrong place or from another version: start over.
            response.close()
            part.delete()
            transfer.downloaded = 0L
            transfer.etag = null
            transfer.lastModified = null
            if (attempt == 1) continue
            error("bad range response")
          }
        }
        code == HTTP_OK -> {
          // Full body: whatever was there is stale, or the server does not do ranges.
          part.delete()
          transfer.downloaded = 0L
        }
        code == RANGE_NOT_SATISFIABLE && have > 0 && attempt == 1 -> {
          response.close()
          part.delete()
          transfer.downloaded = 0L
          continue
        }
        else -> {
          response.close()
          error("HTTP $code")
        }
      }
      response.header("ETag")?.let { transfer.etag = it }
      response.header("Last-Modified")?.let { transfer.lastModified = it }
      nameFromServer(response, request, currentUrl)?.let { transfer.fileName = it }
      val length = response.body.contentLength()
      val resumed = code == HTTP_PARTIAL
      transfer.total = if (length >= 0) length + (if (resumed) have else 0) else -1L
      transfer.downloaded = if (resumed) have else 0L
      if (transfer.total >= 0) {
        repository.updateStatus(transfer.id, DownloadStatus.RUNNING, null, transfer.total)
      }

      response.use { open ->
        open.body.byteStream().use { input ->
          FileOutputStream(part, resumed).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var lastReport = 0L
            var lastPercent = -1
            while (true) {
              if (!currentCoroutineIsActive()) return@withContext part
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              transfer.downloaded += read
              if (transfer.downloaded > MAX_BYTES) error("file too large")
              val now = System.currentTimeMillis()
              val percent =
                if (transfer.total > 0) (transfer.downloaded * 100 / transfer.total).toInt() else -1
              if (now - lastReport >= REPORT_INTERVAL_MS || percent != lastPercent) {
                lastReport = now
                lastPercent = percent
                report(transfer)
              }
            }
          }
        }
      }
      if (transfer.total >= 0 && transfer.downloaded < transfer.total) error("short read")
      report(transfer)
      return@withContext part
    }
    error("range refused twice")
  }

  private fun nameFromServer(response: Response, request: Request, finalUrl: String): String? {
    if (!request.contentDisposition.isNullOrBlank()) return null
    val disposition = response.header("Content-Disposition") ?: return null
    val type = response.header("Content-Type") ?: request.mimeType
    return DownloadNames.choose(finalUrl, disposition, type)
  }

  private fun report(transfer: Transfer) {
    _progress.update { it + (transfer.id to DownloadProgress(transfer.downloaded, transfer.total)) }
    publishStates()
  }

  private fun publishStates() {
    _states.value = transfers.values
      .sortedBy { it.id }
      .map { TransferState(it.id, it.fileName, it.downloaded, it.total, it.paused) }
  }

  private fun newId(): Long = -(System.currentTimeMillis() * 1000 + (0..999).random())

  private fun partFile(id: Long): File = File(application.cacheDir, "download-${-id}.part")

  /** `data:[<mime>][;base64],<data>` to its type and bytes. */
  private fun decodeDataUrl(url: String): Pair<String, ByteArray> {
    val comma = url.indexOf(',')
    require(comma > 0) { "no payload" }
    val header = url.substring("data:".length, comma)
    val payload = url.substring(comma + 1)
    val base64 = header.endsWith(";base64", ignoreCase = true)
    val mime = header.removeSuffix(";base64").removeSuffix(";BASE64")
      .substringBefore(';').ifBlank { "text/plain" }
    val bytes = if (base64) {
      Base64.decode(payload, Base64.DEFAULT)
    } else {
      URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
    }
    require(bytes.size <= MAX_BYTES) { "too large" }
    return mime to bytes
  }

  private companion object {
    val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    const val MAX_REDIRECTS = 5

    /** `Content-Range: bytes START-END/TOTAL`; only START is trusted, and only against our own. */
    val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(?:\d+|\*)""")
    const val HTTP_OK = 200
    const val HTTP_PARTIAL = 206
    const val BUFFER_BYTES = 64 * 1024
    const val REPORT_INTERVAL_MS = 1_500L
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    const val RANGE_NOT_SATISFIABLE = 416
  }
}
