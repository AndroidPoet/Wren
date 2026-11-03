/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity.mapper

import dev.androidpoet.wren.core.database.entity.ShortcutEntity
import dev.androidpoet.wren.core.model.Shortcut

object ShortcutEntityMapper : EntityMapper<ShortcutEntity, Shortcut> {

  override fun asEntity(domain: Shortcut): ShortcutEntity = ShortcutEntity(
    url = domain.url,
    title = domain.title,
    position = domain.position,
  )

  override fun asDomain(entity: ShortcutEntity): Shortcut = Shortcut(
    url = entity.url,
    title = entity.title,
    position = entity.position,
  )
}

fun Shortcut.asEntity(): ShortcutEntity = ShortcutEntityMapper.asEntity(this)

@JvmName("shortcutEntityListAsDomain")
fun List<ShortcutEntity>.asDomain(): List<Shortcut> = map { ShortcutEntityMapper.asDomain(it) }
