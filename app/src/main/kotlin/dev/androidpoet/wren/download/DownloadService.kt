/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.androidpoet.wren.R
import dev.androidpoet.wren.ui.downloads.DownloadsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while an in-app download runs, and shows one notification per
 * transfer with pause, resume and cancel. The system DownloadManager has its own service and
 * its own notifications; this one is only for the transfers [InAppDownloader] carries itself,
 * and it stops the moment none is left.
 */
@AndroidEntryPoint
class DownloadService : Service() {

  @Inject lateinit var downloader: InAppDownloader

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watch: Job? = null
  private val shown = HashSet<Long>()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
    when (intent?.action) {
      ACTION_PAUSE -> downloader.pause(id)
      ACTION_RESUME -> downloader.resume(id)
      ACTION_CANCEL -> {
        downloader.cancel(id)
        scope.launch { downloader.forget(id) }
      }
    }
    if (watch == null) {
      val summary = build(getString(R.string.downloads), null, 0, 0, indeterminate = true)
      ServiceCompat.startForeground(
        this,
        FOREGROUND_ID,
        summary,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
          0
        },
      )
      watch = scope.launch {
        downloader.states.collectLatest { states ->
          render(states)
          if (states.isEmpty()) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
          }
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    val notifications = NotificationManagerCompat.from(this)
    for (id in shown) notifications.cancel(notificationIdFor(id))
    super.onDestroy()
  }

  private fun render(states: List<TransferState>) {
    val notifications = NotificationManagerCompat.from(this)
    val live = states.map { it.id }.toSet()
    for (gone in shown - live) notifications.cancel(notificationIdFor(gone))
    shown.retainAll(live)
    if (!notifications.areNotificationsEnabled()) return
    for (state in states) {
      shown.add(state.id)
      val text = if (state.paused) {
        getString(R.string.download_paused)
      } else {
        progressText(state.downloadedBytes, state.totalBytes)
      }
      val percent = if (state.totalBytes > 0) {
        (state.downloadedBytes * 100 / state.totalBytes).toInt()
      } else {
        0
      }
      val notification = build(
        state.fileName,
        text,
        max = if (state.totalBytes > 0) 100 else 0,
        progress = percent,
        indeterminate = state.totalBytes <= 0 && !state.paused,
      ) {
        if (state.paused) {
          addAction(0, getString(R.string.download_resume), action(ACTION_RESUME, state.id))
        } else {
          addAction(0, getString(R.string.download_pause), action(ACTION_PAUSE, state.id))
        }
        addAction(0, getString(R.string.download_cancel), action(ACTION_CANCEL, state.id))
      }
      runCatching { notifications.notify(notificationIdFor(state.id), notification) }
    }
  }

  private fun progressText(downloaded: Long, total: Long): String {
    val done = android.text.format.Formatter.formatShortFileSize(this, downloaded)
    if (total <= 0) return done
    val all = android.text.format.Formatter.formatShortFileSize(this, total)
    return getString(R.string.download_progress_of, done, all)
  }

  private fun build(
    title: String,
    text: String?,
    max: Int,
    progress: Int,
    indeterminate: Boolean,
    actions: NotificationCompat.Builder.() -> Unit = {},
  ): Notification {
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, DownloadsActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_download)
      .setContentTitle(title)
      .setContentText(text)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setSilent(true)
      .setProgress(max, progress, indeterminate)
      .setContentIntent(open)
      .setVisibility(NotificationCompat.VISIBILITY_SECRET)
      .apply(actions)
      .build()
  }

  private fun action(action: String, id: Long): PendingIntent = PendingIntent.getService(
    this,
    (id and 0xffff).toInt() xor action.hashCode(),
    Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.downloads),
      NotificationManager.IMPORTANCE_LOW,
    ).apply { setShowBadge(false) }
    getSystemService<NotificationManager>()?.createNotificationChannel(channel)
  }

  private fun notificationIdFor(id: Long): Int = NOTIFICATION_BASE + (id and 0x7fff).toInt()

  companion object {
    const val CHANNEL_ID = "downloads"
    private const val FOREGROUND_ID = 7001
    private const val NOTIFICATION_BASE = 7100
    private const val EXTRA_ID = "id"
    private const val ACTION_PAUSE = "dev.androidpoet.wren.download.PAUSE"
    private const val ACTION_RESUME = "dev.androidpoet.wren.download.RESUME"
    private const val ACTION_CANCEL = "dev.androidpoet.wren.download.CANCEL"

    fun start(context: Context) {
      androidx.core.content.ContextCompat.startForegroundService(
        context,
        Intent(context, DownloadService::class.java),
      )
    }
  }
}
