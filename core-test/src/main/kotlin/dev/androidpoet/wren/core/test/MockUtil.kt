/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.test

import dev.androidpoet.wren.core.model.Bookmark
import dev.androidpoet.wren.core.model.HistoryEntry
import dev.androidpoet.wren.core.model.Suggestion

object MockUtil {

  fun mockHistoryEntry(
    url: String = "https://duckduckgo.com/",
    title: String = "DuckDuckGo",
    lastVisitedAt: Long = 1_700_000_000_000L,
    visitCount: Int = 3,
  ): HistoryEntry = HistoryEntry(
    url = url,
    title = title,
    lastVisitedAt = lastVisitedAt,
    visitCount = visitCount,
  )

  fun mockHistory(): List<HistoryEntry> = listOf(
    mockHistoryEntry(),
    mockHistoryEntry(url = "https://www.mojeek.com/", title = "Mojeek", visitCount = 1),
  )

  fun mockBookmark(
    url: String = "https://developer.android.com/",
    title: String = "Android Developers",
    createdAt: Long = 1_700_000_000_000L,
  ): Bookmark = Bookmark(url = url, title = title, createdAt = createdAt)

  fun mockSuggestions(): List<Suggestion> = listOf(
    Suggestion(phrase = "kotlin coroutines"),
    Suggestion(phrase = "kotlin flow"),
  )
}
