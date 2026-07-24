//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.database.entity.LifeDimensionEntity
import io.payanam.domain.model.ConfiguredLifeDimension

internal fun LifeDimensionEntity.toConfiguredLifeDimension(): ConfiguredLifeDimension =
    ConfiguredLifeDimension(
        id = id,
        key = key,
        label = label,
        description = description,
        colorHex = color,
        iconKey = icon,
        sortOrder = sortOrder,
        isActive = isActive != 0,
    )
