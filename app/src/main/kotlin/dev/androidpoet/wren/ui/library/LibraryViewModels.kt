/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.core.data.repository.BookmarkRepository
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
  private val historyRepository: HistoryRepository,
) : ViewModel() {

  val rows: StateFlow<List<LinkRow>> = historyRepository.observeRecent()
    .map { entries ->
      entries
        // A single-page app rewrites its own URL as you interact with it, so one visit can leave
        // several rows that differ only in query parameters. Storage keeps them all - they really
        // were distinct URLs - but the list shows the newest of each page.
        .distinctBy { UrlFormatter.prettyHost(it.url) to it.title.ifBlank { it.url } }
        .map { entry ->
          LinkRow(
            url = entry.url,
            title = entry.title,
            subtitle = "${UrlFormatter.prettyHost(entry.url)} · ${formatTime(entry.lastVisitedAt)}",
          )
        }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  fun remove(url: String) {
    viewModelScope.launch { historyRepository.delete(url) }
  }

  fun clearAll() {
    viewModelScope.launch { historyRepository.clear() }
  }
}

@HiltViewModel
class BookmarksViewModel @Inject constructor(
  private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {

  /** null shows everything; otherwise a single folder's bookmarks. */
  val activeFolder = MutableStateFlow<String?>(null)

  private val all: StateFlow<List<LinkRow>> = bookmarkRepository.observeAll()
    .map { bookmarks ->
      bookmarks.map { bookmark ->
        LinkRow(
          url = bookmark.url,
          title = bookmark.title,
          subtitle = UrlFormatter.prettyHost(bookmark.url),
          folder = bookmark.folder,
        )
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val folders: StateFlow<List<String>> = all
    .map { rows -> rows.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val rows: StateFlow<List<LinkRow>> = combine(all, activeFolder) { rows, folder ->
    if (folder == null) rows else rows.filter { it.folder == folder }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  fun moveToFolder(url: String, folder: String) {
    viewModelScope.launch { bookmarkRepository.setFolder(url, folder) }
  }

  fun remove(url: String) {
    viewModelScope.launch { bookmarkRepository.delete(url) }
  }

  fun clearAll() {
    viewModelScope.launch { bookmarkRepository.clear() }
  }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L

private fun formatTime(epochMillis: Long): String =
  DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
