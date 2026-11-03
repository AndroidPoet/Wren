/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.database.BookmarkDao
import dev.androidpoet.wren.core.database.HistoryDao
import dev.androidpoet.wren.core.database.ShortcutDao
import dev.androidpoet.wren.core.database.entity.BookmarkEntity
import dev.androidpoet.wren.core.database.entity.HistoryEntity
import dev.androidpoet.wren.core.database.entity.ShortcutEntity
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class BackupRepositoryImpl @Inject constructor(
  private val bookmarkDao: BookmarkDao,
  private val shortcutDao: ShortcutDao,
  private val historyDao: HistoryDao,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : BackupRepository {

  override suspend fun exportJson(): String = withContext(ioDispatcher) {
    val root = JSONObject()
    root.put("version", 1)
    root.put(
      "bookmarks",
      JSONArray().also { array ->
        bookmarkDao.getAll().forEach { row ->
          array.put(
            JSONObject()
              .put("url", row.url)
              .put("title", row.title)
              .put("createdAt", row.createdAt),
          )
        }
      },
    )
    root.put(
      "shortcuts",
      JSONArray().also { array ->
        shortcutDao.getAll().forEach { row ->
          array.put(
            JSONObject()
              .put("url", row.url)
              .put("title", row.title)
              .put("position", row.position),
          )
        }
      },
    )
    root.put(
      "history",
      JSONArray().also { array ->
        historyDao.getAll().forEach { row ->
          array.put(
            JSONObject()
              .put("url", row.url)
              .put("title", row.title)
              .put("lastVisitedAt", row.lastVisitedAt)
              .put("visitCount", row.visitCount),
          )
        }
      },
    )
    root.toString()
  }

  override suspend fun importJson(json: String): Unit = withContext(ioDispatcher) {
    val root = JSONObject(json)
    root.optJSONArray("bookmarks")?.forEachObject { row ->
      val url = row.optString("url").takeIf { it.isNotBlank() } ?: return@forEachObject
      bookmarkDao.upsert(
        BookmarkEntity(
          url = url,
          title = row.optString("title"),
          createdAt = row.optLong("createdAt", System.currentTimeMillis()),
        ),
      )
    }
    root.optJSONArray("shortcuts")?.forEachObject { row ->
      val url = row.optString("url").takeIf { it.isNotBlank() } ?: return@forEachObject
      shortcutDao.upsert(
        ShortcutEntity(
          url = url,
          title = row.optString("title"),
          position = row.optInt("position"),
        ),
      )
    }
    root.optJSONArray("history")?.forEachObject { row ->
      val url = row.optString("url").takeIf { it.isNotBlank() } ?: return@forEachObject
      historyDao.upsert(
        HistoryEntity(
          url = url,
          title = row.optString("title"),
          lastVisitedAt = row.optLong("lastVisitedAt", System.currentTimeMillis()),
          visitCount = row.optInt("visitCount", 1),
        ),
      )
    }
  }

  override suspend fun exportBookmarksHtml(): String = withContext(ioDispatcher) {
    buildString {
      append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
      append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
      append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
      bookmarkDao.getAll().forEach { row ->
        append("    <DT><A HREF=\"")
        append(escapeHtml(row.url))
        append("\" ADD_DATE=\"")
        append(row.createdAt / MILLIS_PER_SECOND)
        append("\">")
        append(escapeHtml(row.title.ifBlank { row.url }))
        append("</A>\n")
      }
      append("</DL><p>\n")
    }
  }

  override suspend fun importBookmarksHtml(html: String): Int = withContext(ioDispatcher) {
    var imported = 0
    ANCHOR.findAll(html).forEach { match ->
      val url = unescapeHtml(match.groupValues[1]).trim()
      if (!url.startsWith("http")) return@forEach
      val title = unescapeHtml(match.groupValues[2].replace(TAGS, "")).trim()
      bookmarkDao.upsert(
        BookmarkEntity(
          url = url,
          title = title.ifBlank { url },
          createdAt = System.currentTimeMillis(),
        ),
      )
      imported++
    }
    imported
  }

  private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
      optJSONObject(index)?.let(action)
    }
  }

  private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

  private fun unescapeHtml(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&amp;", "&")

  private companion object {
    const val MILLIS_PER_SECOND = 1000L
    val ANCHOR = Regex("<A[^>]*HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", RegexOption.IGNORE_CASE)
    val TAGS = Regex("<[^>]+>")
  }
}
