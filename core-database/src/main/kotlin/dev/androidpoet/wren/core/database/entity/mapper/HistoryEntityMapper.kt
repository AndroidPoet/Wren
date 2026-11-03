/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity.mapper

import dev.androidpoet.wren.core.database.entity.HistoryEntity
import dev.androidpoet.wren.core.model.HistoryEntry

object HistoryEntityMapper : EntityMapper<HistoryEntity, HistoryEntry> {

  override fun asEntity(domain: HistoryEntry): HistoryEntity = HistoryEntity(
    url = domain.url,
    title = domain.title,
    lastVisitedAt = domain.lastVisitedAt,
    visitCount = domain.visitCount,
  )

  override fun asDomain(entity: HistoryEntity): HistoryEntry = HistoryEntry(
    url = entity.url,
    title = entity.title,
    lastVisitedAt = entity.lastVisitedAt,
    visitCount = entity.visitCount,
  )
}

fun HistoryEntry.asEntity(): HistoryEntity = HistoryEntityMapper.asEntity(this)

fun HistoryEntity.asDomain(): HistoryEntry = HistoryEntityMapper.asDomain(this)

fun List<HistoryEntity>.asDomain(): List<HistoryEntry> = map { HistoryEntityMapper.asDomain(it) }
