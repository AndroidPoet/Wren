/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

interface BackupRepository {

  /** Every bookmark, shortcut and history row as one JSON document, for a full backup. */
  suspend fun exportJson(): String

  /** Restores from a document produced by [exportJson]. Existing rows with the same key win. */
  suspend fun importJson(json: String)

  /** Bookmarks in the Netscape bookmark-file format that every browser can read. */
  suspend fun exportBookmarksHtml(): String

  /** Adds the bookmarks found in a Netscape bookmark file, keeping any already present. */
  suspend fun importBookmarksHtml(html: String): Int
}
