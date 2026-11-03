/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity.mapper

import dev.androidpoet.wren.core.database.entity.DownloadEntity
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus

fun Download.asEntity(): DownloadEntity = DownloadEntity(
  id = id,
  url = url,
  fileName = fileName,
  mimeType = mimeType,
  totalBytes = totalBytes,
  status = status.name,
  localUri = localUri,
  createdAt = createdAt,
)

fun DownloadEntity.asDomain(): Download = Download(
  id = id,
  url = url,
  fileName = fileName,
  mimeType = mimeType,
  totalBytes = totalBytes,
  status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
  localUri = localUri,
  createdAt = createdAt,
)

@JvmName("downloadEntityListAsDomain")
fun List<DownloadEntity>.asDomain(): List<Download> = map { it.asDomain() }
