/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.downloads

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.androidpoet.wren.R
import dev.androidpoet.wren.core.model.DownloadStatus
import dev.androidpoet.wren.databinding.ItemDownloadBinding
import dev.androidpoet.wren.download.DownloadProgress

class DownloadAdapter(
  private val onOpen: (DownloadRow) -> Unit,
  private val onMenu: (DownloadRow, android.view.View) -> Unit,
) : ListAdapter<DownloadRow, DownloadAdapter.ViewHolder>(DIFF) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    ViewHolder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

  inner class ViewHolder(
    private val binding: ItemDownloadBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(row: DownloadRow) = with(binding) {
      val context = root.context
      val download = row.download
      downloadName.text = download.fileName
      downloadIcon.setImageResource(iconFor(download.mimeType))

      val running = download.status == DownloadStatus.RUNNING && row.progress != null
      downloadProgress.isVisible = running
      if (running) {
        val progress = requireNotNull(row.progress)
        if (progress.totalBytes > 0) {
          downloadProgress.isIndeterminate = false
          downloadProgress.max = PROGRESS_MAX
          downloadProgress.progress =
            (progress.downloadedBytes * PROGRESS_MAX / progress.totalBytes).toInt()
        } else {
          downloadProgress.isIndeterminate = true
        }
      }

      downloadStatus.text = when (download.status) {
        DownloadStatus.RUNNING -> row.progress?.let { progressLabel(context, it) }
          ?: context.getString(R.string.download_running)
        DownloadStatus.PAUSED -> context.getString(R.string.download_paused)
        DownloadStatus.COMPLETE -> sizeLabel(context, download.totalBytes)
        DownloadStatus.FAILED -> context.getString(R.string.download_failed_status)
        DownloadStatus.CANCELLED -> context.getString(R.string.download_cancelled)
      }

      root.setOnClickListener { onOpen(row) }
      downloadMenu.setOnClickListener { onMenu(row, it) }
    }

    private fun progressLabel(
      context: android.content.Context,
      progress: DownloadProgress,
    ): String {
      val soFar = Formatter.formatShortFileSize(context, progress.downloadedBytes)
      return if (progress.totalBytes > 0) {
        "$soFar / ${Formatter.formatShortFileSize(context, progress.totalBytes)}"
      } else {
        soFar
      }
    }

    private fun sizeLabel(context: android.content.Context, bytes: Long): String =
      if (bytes > 0) Formatter.formatShortFileSize(context, bytes) else ""

    private fun iconFor(mimeType: String): Int = when {
      mimeType.startsWith("image/") -> R.drawable.ic_file_image
      mimeType.startsWith("video/") -> R.drawable.ic_file_video
      mimeType.startsWith("audio/") -> R.drawable.ic_file_audio
      mimeType.startsWith("text/") || mimeType.contains("pdf") -> R.drawable.ic_file_document
      else -> R.drawable.ic_file_generic
    }
  }

  private companion object {
    const val PROGRESS_MAX = 1000

    val DIFF = object : DiffUtil.ItemCallback<DownloadRow>() {
      override fun areItemsTheSame(oldItem: DownloadRow, newItem: DownloadRow): Boolean =
        oldItem.download.id == newItem.download.id

      override fun areContentsTheSame(oldItem: DownloadRow, newItem: DownloadRow): Boolean =
        oldItem == newItem
    }
  }
}
