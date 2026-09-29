//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.domain.model.CanonicalDimensionDefinition
import io.payanam.domain.model.DimensionTaxonomyCatalog

/**
 * One editable row in the desktop onboarding dimension step.
 *
 * Deliberately mirrors Android's `NewDatabaseDimensionInput` field for field (id, label, colour,
 * enabled, icon key) so the two screens describe the same thing and a future shared type is a
 * rename rather than a redesign.
 *
 * @param id the canonical dimension id — stable, and the key a real write would use.
 * @param label the user-editable display name.
 * @param colorHex hex colour without alpha, e.g. `#4CAF50`.
 * @param isEnabled whether the dimension is included in scoring.
 * @param iconKey key into the icon catalog, e.g. `monitor_heart`.
 */
internal data class DesktopDimensionInput(
    val id: String,
    val label: String,
    val colorHex: String,
    val isEnabled: Boolean,
    val iconKey: String,
)

/**
 * Builds the initial dimension rows for onboarding from the SHARED canonical catalog.
 *
 * This is the part that is real rather than mocked: the ids, labels, colours, icon keys and ordering
 * all come from [DimensionTaxonomyCatalog] in `core/domain`, the same source Android reads. Only the
 * persistence is mocked — the rows never reach a database.
 *
 * Rows are ordered by [CanonicalDimensionDefinition.sortOrder] so the desktop list matches Android's
 * order rather than the declaration order of the catalog's constants.
 *
 * The `dim_unassigned` entry is excluded, matching Android's first-run seed, which adds the
 * unassigned fallback separately rather than offering it as a user dimension.
 */
internal fun desktopDefaultDimensionInputs(
    catalog: List<CanonicalDimensionDefinition> = DimensionTaxonomyCatalog.entries,
): List<DesktopDimensionInput> =
    catalog
        .filter { it.id != UNASSIGNED_DIMENSION_ID }
        .sortedBy { it.sortOrder }
        .map { definition ->
            DesktopDimensionInput(
                id = definition.id,
                label = definition.fallbackLabel,
                colorHex = definition.defaultColorHex,
                isEnabled = true,
                iconKey = definition.defaultIconKey,
            )
        }

/**
 * The colour choices offered when editing a dimension.
 *
 * Hard-coded rather than read from Android's `DimensionColorOptions`, which lives in the Android-only
 * `app/` module and resolves each entry through a string resource. These are the same Material 500
 * values; the labels are rendered from the hex by the screen, so no resource lookup is needed.
 *
 * When the visuals are promoted to a shared module this list is replaced, not extended.
 */
internal val desktopDimensionColorOptions: List<String> =
    listOf(
        "#3F51B5", // indigo 500
        "#4CAF50", // green 500
        "#E91E63", // pink 500
        "#009688", // teal 500
        "#FF9800", // orange 500
        "#9C27B0", // purple 500
        "#2196F3", // blue 500
        "#F44336", // red 500
        "#795548", // brown 500
        "#607D8B", // blue grey 500
    )

/** The canonical unassigned fallback, which is seeded separately and not user-editable. */
internal const val UNASSIGNED_DIMENSION_ID = "dim_unassigned"
