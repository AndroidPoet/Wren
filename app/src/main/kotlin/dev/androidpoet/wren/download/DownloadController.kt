/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.androidpoet.wren.core.data.repository.DownloadRepository
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Live byte progress for one download, kept in memory rather than churned through the database. */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)

/**
 * Runs downloads through the system [DownloadManager] - which already handles ranged resume,
 * scoped storage and retries - while keeping a record of each in our own database so the browser
 * can show its own list instead of sending the user to the system UI.
 *
 * The system reports completion by broadcast but never reports progress, so a light poller runs
 * only while something is in flight and stops the moment nothing is.
 */
@Singleton
class DownloadController @Inject constructor(
  private val application: Application,
  private val repository: DownloadRepository,
) {

  private val downloadManager = application.getSystemService<DownloadManager>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
  val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

  private var pollJob: kotlinx.coroutines.Job? = null

  init {
    ContextCompat.registerReceiver(
      application,
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
          if (id != -1L) scope.launch { syncOne(id) }
        }
      },
      IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
      // A system broadcast addressed to this app; it must be flagged exported on API 33+.
      ContextCompat.RECEIVER_EXPORTED,
    )
  }

  /** Queues a download and records it. Returns the id, or null when it could not be started. */
  fun enqueue(
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    pageUrl: String?,
  ): Long? {
    if (!url.startsWith("http")) return null
    val manager = downloadManager ?: return null
    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

    val request = DownloadManager.Request(Uri.parse(url)).apply {
      setMimeType(mimeType)
      userAgent?.let { addRequestHeader("User-Agent", it) }
      pageUrl?.let { addRequestHeader("Referer", it) }
      CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
      setTitle(fileName)
      setDescription(hostOf(pageUrl ?: url))
      setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
      setAllowedOverMetered(true)
      setAllowedOverRoaming(true)
    }

    val id = runCatching { manager.enqueue(request) }
      .onFailure { Timber.w(it, "download of %s could not be enqueued", url) }
      .getOrNull() ?: return null

    scope.launch {
      repository.record(
        Download(
          id = id,
          url = url,
          fileName = fileName,
          mimeType = mimeType.orEmpty(),
          totalBytes = -1L,
          status = DownloadStatus.RUNNING,
          localUri = null,
          createdAt = System.currentTimeMillis(),
        ),
      )
      startPolling()
    }
    return id
  }

  /** Stops and discards an in-flight download, or removes a finished one from the list. */
  fun dismiss(download: Download) {
    scope.launch {
      if (download.status == DownloadStatus.RUNNING || download.status == DownloadStatus.PAUSED) {
        // remove() also deletes the partial file, which is what cancelling should do.
        runCatching { downloadManager?.remove(download.id) }
      }
      repository.remove(download.id)
      _progress.value = _progress.value - download.id
    }
  }

  /** Re-queues a failed or cancelled download from its original address. */
  fun retry(download: Download) {
    scope.launch { repository.remove(download.id) }
    enqueue(download.url, null, null, download.mimeType.ifBlank { null }, null)
  }

  fun clearFinished() {
    scope.launch { repository.clearFinished() }
  }

  /** A content uri the system will let other apps read, for opening or sharing a finished file. */
  fun contentUriFor(download: Download): Uri? = download.localUri?.let(Uri::parse)
    ?: runCatching { downloadManager?.getUriForDownloadedFile(download.id) }.getOrNull()

  private fun startPolling() {
    if (pollJob?.isActive == true) return
    pollJob = scope.launch {
      while (isActive) {
        val active = syncActive()
        if (!active) break
        delay(POLL_INTERVAL_MS)
      }
      _progress.value = emptyMap()
    }
  }

  /** Updates every non-terminal download. Returns true while any is still running. */
  private suspend fun syncActive(): Boolean {
    val manager = downloadManager ?: return false
    val live = mutableMapOf<Long, DownloadProgress>()
    var anyRunning = false

    manager.query(DownloadManager.Query()).use { cursor ->
      val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
      val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
      val soFarCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
      val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
      while (cursor.moveToNext()) {
        val id = cursor.getLong(idCol)
        val systemStatus = cursor.getInt(statusCol)
        val soFar = cursor.getLong(soFarCol)
        val total = cursor.getLong(totalCol)
        when (systemStatus) {
          DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
            live[id] = DownloadProgress(soFar, total)
            anyRunning = true
          }

          DownloadManager.STATUS_PAUSED -> {
            live[id] = DownloadProgress(soFar, total)
            repository.updateStatus(id, DownloadStatus.PAUSED, null, total)
            anyRunning = true
          }

          else -> Unit // terminal states are settled by syncOne on the completion broadcast
        }
      }
    }
    _progress.value = live
    return anyRunning
  }

  /** Settles a single download to its terminal state when the system says it is done. */
  private suspend fun syncOne(id: Long) {
    val manager = downloadManager ?: return
    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
      if (!cursor.moveToFirst()) {
        repository.updateStatus(id, DownloadStatus.CANCELLED, null, -1L)
        return
      }
      val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
      val total = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
      when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> {
          val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
          repository.updateStatus(id, DownloadStatus.COMPLETE, uri?.toString(), total)
        }

        DownloadManager.STATUS_FAILED ->
          repository.updateStatus(id, DownloadStatus.FAILED, null, total)
        else -> Unit
      }
    }
    _progress.value = _progress.value - id
  }

  private fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/')

  private companion object {
    const val POLL_INTERVAL_MS = 700L
  }
}
