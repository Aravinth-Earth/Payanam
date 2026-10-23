//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.database.entity.LifeDimensionEntity
import io.payanam.domain.model.ConfiguredLifeDimension

/**
 * Maps a persisted [LifeDimensionEntity] row to the domain [ConfiguredLifeDimension] model.
 *
 * Public rather than `internal` because :core:database consumes this mapper across a module
 * boundary; detekt requires public functions to carry KDoc.
 */
fun LifeDimensionEntity.toConfiguredLifeDimension(): ConfiguredLifeDimension =
    ConfiguredLifeDimension(
        id = id,
        key = key,
        label = label,
        description = description,
        colorHex = color,
        iconKey = icon,
        sortOrder = sortOrder,
        isActive = isActive != 0,
        weight = weight,
    )

/**
 * Maps a domain [ConfiguredLifeDimension] back to the [LifeDimensionEntity] row that stores it.
 *
 * The reverse of [toConfiguredLifeDimension], and the write half of the pair. It lives beside it in
 * `:core:database-model` rather than in either repository so BOTH platforms build the identical row from
 * the identical model — a second hand-written mapping is exactly how the two databases would drift.
 *
 * [timestampIso] is passed in rather than read from the clock here: the caller owns the instant, so every
 * row written by one replace carries the same createdAt/updatedAt, and a repository test can pin it.
 */
fun ConfiguredLifeDimension.toLifeDimensionEntity(timestampIso: String): LifeDimensionEntity =
    LifeDimensionEntity(
        id = id,
        key = key,
        label = label,
        description = description,
        color = colorHex,
        icon = iconKey,
        sortOrder = sortOrder,
        // The column is an INTEGER, not a boolean: SQLite has no boolean type, and every reader of this
        // table compares against 0 rather than reading a Boolean out of Room.
        isActive = if (isActive) 1 else 0,
        weight = weight,
        createdAt = timestampIso,
        updatedAt = timestampIso,
    )
