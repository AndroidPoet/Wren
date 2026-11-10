/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.os.Bundle
import dev.androidpoet.wren.core.model.TabSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the open tabs. A small pool of tabs stays live; the rest are held as saved state, which
 * costs a re-render when one of them is switched to and buys a memory profile that does not grow
 * with the tab count, which is the whole point of a light browser.
 */
@Singleton
class TabManager @Inject constructor() {

  private val ids = AtomicLong(0L)

  // Mutated on the main thread only, but read from WebView's network threads for every
  // sub-resource (the page address a request belongs to). Copy-on-write keeps those reads
  // consistent without a lock on the interception path.
  private val openTabs = CopyOnWriteArrayList<BrowserTab>()

  private val _snapshots = MutableStateFlow<List<TabSnapshot>>(emptyList())
  val snapshots: StateFlow<List<TabSnapshot>> = _snapshots.asStateFlow()

  /** The most recently closed real page, for the undo affordance. */
  private var lastClosed: ClosedTab? = null

  private val _activeTabId = MutableStateFlow(NO_TAB)
  val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()

  val size: Int get() = openTabs.size

  fun activeTab(): BrowserTab? = openTabs.firstOrNull { it.id == _activeTabId.value }

  fun tab(id: Long): BrowserTab? = openTabs.firstOrNull { it.id == id }

  /** Creates a tab and makes it active. Returns its id. */
  fun openTab(url: String = UrlFormatter.NEW_TAB_URL, isPrivate: Boolean = false): Long {
    val tab = BrowserTab(id = ids.incrementAndGet(), url = url, isPrivate = isPrivate)
    openTabs += tab
    _activeTabId.value = tab.id
    publish()
    return tab.id
  }

  /** Ensures at least one tab exists, then returns the active one. */
  fun ensureTab(): BrowserTab {
    activeTab()?.let { return it }
    openTab()
    return requireNotNull(activeTab())
  }

  fun select(id: Long) {
    if (openTabs.none { it.id == id } || _activeTabId.value == id) return
    _activeTabId.value = id
    publish()
  }

  /**
   * Closes [id]. When the active tab is closed focus moves to its neighbour; closing the last tab
   * leaves a fresh empty one so the browser is never in a tab-less state.
   */
  fun close(id: Long) {
    val index = openTabs.indexOfFirst { it.id == id }
    if (index < 0) return
    val wasActive = _activeTabId.value == id
    val closing = openTabs[index]
    // A private tab leaves no trace behind, not even through undo.
    if (!closing.isPrivate && closing.url != UrlFormatter.NEW_TAB_URL) {
      lastClosed = ClosedTab(closing.url)
    }
    openTabs.removeAt(index)
    if (openTabs.isEmpty()) {
      openTab()
      return
    }
    if (wasActive) {
      _activeTabId.value = openTabs[index.coerceAtMost(openTabs.lastIndex)].id
    }
    publish()
  }

  fun consumeLastClosed(): ClosedTab? = lastClosed.also { lastClosed = null }

  /** Replaces every open tab with [pages]; used when a session is switched or restored. */
  fun replaceAll(pages: List<Pair<String, String>>) {
    openTabs.clear()
    if (pages.isEmpty()) {
      openTab()
      return
    }
    pages.forEach { (url, title) ->
      openTabs += BrowserTab(id = ids.incrementAndGet(), url = url, title = title)
    }
    _activeTabId.value = openTabs.first().id
    publish()
  }

  fun closeAll() {
    openTabs.clear()
    openTab()
  }

  fun closePrivateTabs() = closeWhere { it.isPrivate }

  /** Closes every tab [shouldClose] names, with no undo; the rest keep their order and focus. */
  fun closeWhere(shouldClose: (BrowserTab) -> Boolean) {
    openTabs.removeAll(openTabs.filter(shouldClose))
    if (openTabs.isEmpty()) {
      openTab()
      return
    }
    if (openTabs.none { it.id == _activeTabId.value }) {
      _activeTabId.value = openTabs.first().id
    }
    publish()
  }

  fun updateActive(url: String? = null, title: String? = null, state: Bundle? = null) =
    update(_activeTabId.value, url, title, state)

  /** Addressed by id, because a background page reports on itself while another tab is shown. */
  fun update(
    id: Long,
    url: String? = null,
    title: String? = null,
    state: Bundle? = null,
    committedUrl: String? = null,
  ) {
    val tab = tab(id) ?: return
    if (committedUrl != null) tab.committedUrl = committedUrl
    var changed = false
    if (url != null && url != tab.url) {
      tab.url = url
      changed = true
    }
    if (title != null && title != tab.title) {
      tab.title = title
      changed = true
    }
    if (state != null) tab.state = state
    if (changed) publish()
  }

  private fun publish() {
    _snapshots.value = openTabs.map {
      TabSnapshot(id = it.id, url = it.url, title = it.title, isPrivate = it.isPrivate)
    }
  }

  companion object {
    const val NO_TAB = -1L
  }
}

data class ClosedTab(val url: String)
