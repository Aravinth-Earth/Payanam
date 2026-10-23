//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import io.payanam.presentation.dimensions.DimensionIconKeys

/**
 * A dimension chosen during first-run setup: id, user label/color, enabled
 * flag, and icon key.
 *
 * Lives here rather than beside the setup screen because this is a plain input
 * shape, not UI: it carries no strings and no Compose types, so the first-run
 * setup ViewModel can hold one on a platform that never loads :core:ui. The
 * builders that DO need UI strings (`defaultNewDatabaseDimensionInputs` and
 * friends) stay in :core:ui and speak this type from above.
 */
data class NewDatabaseDimensionInput(
    val id: String,
    val label: String,
    val colorHex: String,
    val isEnabled: Boolean,
    val iconKey: String = DimensionIconKeys.defaultIconKeyForDimensionId(id),
)
