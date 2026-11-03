/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.model.Shortcut
import kotlinx.coroutines.flow.Flow

interface ShortcutRepository {

  fun observeAll(): Flow<List<Shortcut>>

  /** Adds [url] to the end of the speed dial. */
  suspend fun add(url: String, title: String)

  suspend fun remove(url: String)

  /** Fills an empty speed dial so a first run is not a blank page. */
  suspend fun seedDefaultsIfEmpty()

  companion object {
    /**
     * A first run should not open on a wall of ad-funded platforms. These are non-commercial or
     * open sites; plain URLs, no partner tags, editable by the user.
     */
    val DEFAULTS: List<Pair<String, String>> = listOf(
      "Wikipedia" to "https://www.wikipedia.org/",
      "OpenStreetMap" to "https://www.openstreetmap.org/",
      "Internet Archive" to "https://archive.org/",
      "Gutenberg" to "https://www.gutenberg.org/",
      "F-Droid" to "https://f-droid.org/",
      "Mastodon" to "https://joinmastodon.org/",
      "Hacker News" to "https://news.ycombinator.com/",
      "Codeberg" to "https://codeberg.org/",
    )
  }
}
