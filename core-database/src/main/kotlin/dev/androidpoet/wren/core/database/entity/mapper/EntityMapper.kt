/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.database.entity.mapper

interface EntityMapper<Entity, Domain> {

  fun asEntity(domain: Domain): Entity

  fun asDomain(entity: Entity): Domain
}
