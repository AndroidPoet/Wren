/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

enum class DownloadStatus { RUNNING, PAUSED, COMPLETE, FAILED, CANCELLED }

/**
 * One download. [id] is the system DownloadManager id, reused as our own key so the two stay in
 * lock-step. [localUri] is set only once the file exists.
 */
data class Download(
  val id: Long,
  val url: String,
  val fileName: String,
  val mimeType: String,
  val totalBytes: Long,
  val status: DownloadStatus,
  val localUri: String?,
  val createdAt: Long,
)
