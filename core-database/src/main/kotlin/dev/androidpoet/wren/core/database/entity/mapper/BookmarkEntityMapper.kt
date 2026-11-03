/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity.mapper

import dev.androidpoet.wren.core.database.entity.BookmarkEntity
import dev.androidpoet.wren.core.model.Bookmark

object BookmarkEntityMapper : EntityMapper<BookmarkEntity, Bookmark> {

  override fun asEntity(domain: Bookmark): BookmarkEntity = BookmarkEntity(
    url = domain.url,
    title = domain.title,
    createdAt = domain.createdAt,
    folder = domain.folder,
  )

  override fun asDomain(entity: BookmarkEntity): Bookmark = Bookmark(
    url = entity.url,
    title = entity.title,
    createdAt = entity.createdAt,
    folder = entity.folder,
  )
}

fun Bookmark.asEntity(): BookmarkEntity = BookmarkEntityMapper.asEntity(this)

fun BookmarkEntity.asDomain(): Bookmark = BookmarkEntityMapper.asDomain(this)

@JvmName("bookmarkEntityListAsDomain")
fun List<BookmarkEntity>.asDomain(): List<Bookmark> = map { BookmarkEntityMapper.asDomain(it) }
