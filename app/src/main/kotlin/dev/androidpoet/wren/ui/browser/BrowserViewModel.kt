/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.androidpoet.wren.browser.Bangs
import dev.androidpoet.wren.browser.TabManager
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.browser.tor.TorProxySelector
import dev.androidpoet.wren.core.data.repository.BookmarkRepository
import dev.androidpoet.wren.core.data.repository.HistoryRepository
import dev.androidpoet.wren.core.data.repository.ShortcutRepository
import dev.androidpoet.wren.core.data.repository.SuggestionRepository
import dev.androidpoet.wren.core.model.HistoryEntry
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.model.Shortcut
import dev.androidpoet.wren.core.model.Suggestion
import dev.androidpoet.wren.settings.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
  private val historyRepository: HistoryRepository,
  private val bookmarkRepository: BookmarkRepository,
  private val suggestionRepository: SuggestionRepository,
  private val shortcutRepository: ShortcutRepository,
  private val settings: SettingsStore,
  val tabManager: TabManager,
) : ViewModel() {

  val shortcuts: StateFlow<List<Shortcut>> = shortcutRepository.observeAll()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val mostVisited: StateFlow<List<HistoryEntry>> = historyRepository.observeMostVisited()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  init {
    // A blank speed dial on first run reads as a broken page rather than an empty one.
    viewModelScope.launch { shortcutRepository.seedDefaultsIfEmpty() }
  }

  private val query = MutableStateFlow("")

  /**
   * Suggestions for the text currently in the omnibox. Debounced because every keystroke would
   * otherwise be a request; [SuggestionRepository] still answers from history immediately.
   */
  val suggestions: StateFlow<List<Suggestion>> = query
    .debounce(SUGGEST_DEBOUNCE_MILLIS)
    .distinctUntilChanged()
    .flatMapLatest { text ->
      // A bang being typed resolves against the local table; nothing is sent anywhere.
      if (Bangs.isTypingBang(text)) {
        flowOf(Bangs.suggestions(text))
      } else {
        suggestionRepository.suggestions(
          query = text,
          // While Tor is still starting, a lookup would leave from the real address.
          includeRemote = settings.remoteSuggestions && !TorProxySelector.wouldLeakFor(settings),
          // A custom engine has no known autocomplete endpoint; the default one stands in.
          engine = if (settings.usingCustomSearch) SearchEngine.DEFAULT else settings.searchEngine,
          onError = { Timber.w("suggestion lookup failed: %s", it) },
        )
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  private val currentUrl = MutableStateFlow("")

  val isBookmarked: StateFlow<Boolean> = currentUrl
    .flatMapLatest { url ->
      if (url.isBlank() || url == UrlFormatter.NEW_TAB_URL) {
        flowOf(false)
      } else {
        bookmarkRepository.observeIsBookmarked(url)
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

  val homeUrl: String get() = settings.searchHomeUrl

  fun onQueryChanged(text: String) {
    query.value = text
  }

  /** Resolves omnibox text into something loadable. */
  fun resolve(input: String): String =
    Bangs.resolve(input) ?: UrlFormatter.toUrlOrSearch(input, settings.searchTemplate)

  fun searchFor(phrase: String): String = UrlFormatter.search(phrase, settings.searchTemplate)

  fun onPageCommitted(url: String, title: String, isPrivate: Boolean) {
    currentUrl.value = url
    // A Tor visit written to history is a record of the Tor session, on disk, in the clear.
    val skip = isPrivate || settings.torMode || !settings.recordHistory ||
      url.isBlank() || url == UrlFormatter.NEW_TAB_URL || !url.startsWith("http")
    if (skip) return
    viewModelScope.launch { historyRepository.recordVisit(url, title) }
  }

  fun addShortcut(url: String, title: String) {
    viewModelScope.launch { shortcutRepository.add(url, title) }
  }

  fun removeShortcut(url: String) {
    viewModelScope.launch { shortcutRepository.remove(url) }
  }

  fun toggleBookmark(url: String, title: String, onResult: (Boolean) -> Unit) {
    if (url.isBlank() || url == UrlFormatter.NEW_TAB_URL) return
    viewModelScope.launch { onResult(bookmarkRepository.toggle(url, title)) }
  }

  private companion object {
    const val SUGGEST_DEBOUNCE_MILLIS = 180L
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
