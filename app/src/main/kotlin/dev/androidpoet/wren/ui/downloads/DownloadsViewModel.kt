/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidpoet.wren.core.data.repository.DownloadRepository
import dev.androidpoet.wren.core.model.Download
import dev.androidpoet.wren.core.model.DownloadStatus
import dev.androidpoet.wren.download.DownloadController
import dev.androidpoet.wren.download.DownloadProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A download row: the persisted record, plus live bytes while it is running. */
data class DownloadRow(val download: Download, val progress: DownloadProgress?)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
  repository: DownloadRepository,
  private val controller: DownloadController,
) : ViewModel() {

  val rows: StateFlow<List<DownloadRow>> =
    combine(repository.observeAll(), controller.progress) { downloads, progress ->
      downloads.map { download ->
        val live = progress[download.id].takeIf { download.status == DownloadStatus.RUNNING }
        DownloadRow(download, live)
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  fun dismiss(download: Download) = controller.dismiss(download)

  fun retry(download: Download) = controller.retry(download)

  fun clearFinished() = controller.clearFinished()

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
