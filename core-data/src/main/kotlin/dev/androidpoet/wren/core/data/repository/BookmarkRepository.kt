/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {

  fun observeAll(): Flow<List<Bookmark>>

  fun observeIsBookmarked(url: String): Flow<Boolean>

  /** Adds or removes the bookmark for [url]. Returns true when the page is bookmarked after. */
  suspend fun toggle(url: String, title: String): Boolean

  suspend fun delete(url: String)

  /** Moves the bookmark into [folder]; a blank folder means the top level. */
  suspend fun setFolder(url: String, folder: String)

  suspend fun clear()
}
