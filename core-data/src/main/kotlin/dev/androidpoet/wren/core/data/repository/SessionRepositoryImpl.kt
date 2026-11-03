/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.database.SessionDao
import dev.androidpoet.wren.core.database.entity.SessionEntity
import dev.androidpoet.wren.core.model.Session
import dev.androidpoet.wren.core.model.SessionTab
import dev.androidpoet.wren.core.network.Dispatcher
import dev.androidpoet.wren.core.network.WrenAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
  private val sessionDao: SessionDao,
  @Dispatcher(WrenAppDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : SessionRepository {

  override fun observeAll(): Flow<List<Session>> =
    sessionDao.observeAll().map { rows -> rows.map(::asDomain) }

  override suspend fun getAll(): List<Session> = withContext(ioDispatcher) {
    sessionDao.getAll().map(::asDomain)
  }

  override suspend fun get(name: String): Session? = withContext(ioDispatcher) {
    sessionDao.get(name)?.let(::asDomain)
  }

  override suspend fun save(name: String, tabs: List<SessionTab>): Unit =
    withContext(ioDispatcher) {
      val json = JSONArray().also { array ->
        tabs.forEach { tab ->
          array.put(JSONObject().put("url", tab.url).put("title", tab.title))
        }
      }
      sessionDao.upsert(
        SessionEntity(
          name = name,
          tabsJson = json.toString(),
          updatedAt = System.currentTimeMillis(),
        ),
      )
    }

  override suspend fun delete(name: String): Unit = withContext(ioDispatcher) {
    sessionDao.delete(name)
  }

  private fun asDomain(entity: SessionEntity): Session {
    val tabs = runCatching {
      val array = JSONArray(entity.tabsJson)
      (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { row ->
          SessionTab(url = row.optString("url"), title = row.optString("title"))
        }
      }
    }.getOrDefault(emptyList())
    return Session(name = entity.name, tabs = tabs, updatedAt = entity.updatedAt)
  }
}
