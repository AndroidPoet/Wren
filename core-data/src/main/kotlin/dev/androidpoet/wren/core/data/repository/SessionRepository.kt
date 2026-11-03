/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.data.repository

import dev.androidpoet.wren.core.model.Session
import dev.androidpoet.wren.core.model.SessionTab
import kotlinx.coroutines.flow.Flow

interface SessionRepository {

  fun observeAll(): Flow<List<Session>>

  suspend fun getAll(): List<Session>

  suspend fun get(name: String): Session?

  suspend fun save(name: String, tabs: List<SessionTab>)

  suspend fun delete(name: String)
}
