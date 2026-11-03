/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** How many requests were blocked on each tab, for the counter in the menu. */
@Singleton
class BlockStats @Inject constructor() {

  private val counts = mutableMapOf<Long, Int>()

  /**
   * The distinct hosts blocked on each tab, so the site panel can name them. Capped: a page can
   * make hundreds of requests to a handful of hosts, and the count already carries the volume.
   */
  private val hosts = mutableMapOf<Long, LinkedHashSet<String>>()

  private val _blocked = MutableStateFlow<Map<Long, Int>>(emptyMap())
  val blocked: StateFlow<Map<Long, Int>> = _blocked.asStateFlow()

  /** Called from the resource-interception thread. */
  @Synchronized
  fun recordBlocked(tabId: Long, host: String) {
    counts[tabId] = (counts[tabId] ?: 0) + 1
    val forTab = hosts.getOrPut(tabId) { LinkedHashSet() }
    if (forTab.size < MAX_HOSTS) forTab.add(host)
    _blocked.value = counts.toMap()
  }

  @Synchronized
  fun hostsFor(tabId: Long): List<String> = hosts[tabId]?.toList().orEmpty()

  @Synchronized
  fun countFor(tabId: Long): Int = counts[tabId] ?: 0

  @Synchronized
  fun reset(tabId: Long) {
    hosts.remove(tabId)
    if (counts.remove(tabId) != null) _blocked.value = counts.toMap()
  }

  private companion object {
    const val MAX_HOSTS = 40
  }
}
