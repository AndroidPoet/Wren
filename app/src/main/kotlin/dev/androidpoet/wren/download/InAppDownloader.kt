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
import dev.androidpoet.wren.browser.tor.TorProxySelector
import dev.androidpoet.wren.core.data.repository.DownloadRepository
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
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
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
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
 * Fetching is plain HttpURLConnection, which reads the process proxy selector on every
 * connection, so it follows Tor on and off exactly as the pages do. A transfer writes to a
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
) {

  data class Request(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val pageUrl: String?,
    val isPrivate: Boolean,
  )

  private class Transfer(val id: Long, val request: Request, var fileName: String, val part: File) {
    var job: Job? = null
    var etag: String? = null
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
    // While Tor is still starting the connection would leave from the real address.
    if (TorProxySelector.wouldLeakFor(settings)) return null
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
        ),
      )
      launchTransfer(transfer)
    }
    return id
  }

  /**
   * Saves the bytes a `data:` address carries, under the page's `download="…"` name when the
   * link had one. Null when the address is not one.
   */
  fun saveDataUrl(url: String, isPrivate: Boolean, suggestedName: String? = null): Long? {
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

  fun resume(id: Long) {
    val transfer = transfers[id] ?: return
    if (transfer.job?.isActive == true) return
    if (TorProxySelector.wouldLeakFor(settings)) return
    transfer.paused = false
    scope.launch {
      repository.updateStatus(id, DownloadStatus.RUNNING, null, transfer.total)
      launchTransfer(transfer)
    }
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
    for (attempt in 1..2) {
      val have = if (part.exists()) part.length() else 0L
      val connection = URL(request.url).openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = true
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      request.userAgent?.let { connection.setRequestProperty("User-Agent", it) }
      DownloadHeaders.referer(request.pageUrl, settings.trimReferrers, request.url)
        ?.let { connection.setRequestProperty("Referer", it) }
      // A private page's download carries the private jar's cookies, never the normal one's;
      // without a private jar it carries none.
      val jar =
        if (request.isPrivate) privateProfile.cookieManager() else CookieManager.getInstance()
      jar?.getCookie(request.url)?.let { connection.setRequestProperty("Cookie", it) }
      if (have > 0) {
        connection.setRequestProperty("Range", "bytes=$have-")
        transfer.etag?.let { connection.setRequestProperty("If-Range", it) }
      }
      val code = connection.responseCode
      when {
        code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> Unit
        code == HttpURLConnection.HTTP_OK -> {
          // Full body: whatever was there is stale, or the server does not do ranges.
          part.delete()
          transfer.downloaded = 0L
        }
        code == RANGE_NOT_SATISFIABLE && have > 0 && attempt == 1 -> {
          connection.disconnect()
          part.delete()
          transfer.downloaded = 0L
          continue
        }
        else -> {
          connection.disconnect()
          error("HTTP $code")
        }
      }
      connection.getHeaderField("ETag")?.let { transfer.etag = it }
      nameFromServer(connection, request)?.let { transfer.fileName = it }
      val length = connection.contentLengthLong
      val resumed = code == HttpURLConnection.HTTP_PARTIAL
      transfer.total = if (length >= 0) length + (if (resumed) have else 0) else -1L
      transfer.downloaded = if (resumed) have else 0L
      if (transfer.total >= 0) {
        repository.updateStatus(transfer.id, DownloadStatus.RUNNING, null, transfer.total)
      }

      connection.inputStream.use { input ->
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
      connection.disconnect()
      if (transfer.total >= 0 && transfer.downloaded < transfer.total) error("short read")
      report(transfer)
      return@withContext part
    }
    error("range refused twice")
  }

  private fun nameFromServer(connection: HttpURLConnection, request: Request): String? {
    if (!request.contentDisposition.isNullOrBlank()) return null
    val disposition = connection.getHeaderField("Content-Disposition") ?: return null
    val type = connection.getHeaderField("Content-Type") ?: request.mimeType
    return DownloadNames.choose(request.url, disposition, type)
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
    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 60_000
    const val BUFFER_BYTES = 64 * 1024
    const val REPORT_INTERVAL_MS = 1_500L
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    const val RANGE_NOT_SATISFIABLE = 416
  }
}
