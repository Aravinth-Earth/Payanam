//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.payanam.core.ui.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.presentation.database.NewDatabaseDimensionInput
import io.payanam.presentation.dimensions.DimensionTextCatalog
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import java.time.Instant
import java.util.Locale

/**
 * A life-dimension seed row as written to the database on first-run setup
 * (defaults merged with user choices, plus the unassigned fallback).
 */
data class NewDatabaseDimensionSeedRow(
    val id: String,
    val key: String,
    val label: String,
    val description: String,
    val color: String,
    val icon: String,
    val sortOrder: Int,
    val isActive: Boolean,
)

/**
 * Builds the life-dimension rows a brand-new database is seeded with, from the user's first-run
 * choices.
 *
 * **This is a new user's entire expense-category catalog**, so the return is a REPLACE, not a
 * merge: the writer hands the whole list to `replaceAllDimensions`, and there is no second chance
 * to add a row later. Nothing here invents a dimension - ids, colours, icons and descriptions come
 * from [DimensionTaxonomyCatalog] by way of the inputs, and a user-typed label is the only thing
 * that overrides them.
 *
 * **The order is load-bearing and is produced here, not inherited.** The user's chosen rows come
 * first, in the order they were offered, at `sortOrder` 10, 20, 30 and so on in steps of ten - a
 * gap that leaves room to insert a dimension later without renumbering the catalog. The
 * `dim_unassigned` fallback is appended LAST, always, and always ACTIVE: it is what an uncategorized
 * or imported record resolves to, so a first run that seeds it inactive would leave those records
 * with nowhere to go. Its own catalog sort order is 9999; the appended row keeps that, so the
 * fallback sorts after every real dimension rather than competing with the first one.
 *
 * Rows the user dropped are dropped, not restored - `isEnabled` is stored as the user left it, and
 * a disabled row is how a hidden-but-still-valid dimension stays scored. The floor is therefore one
 * row, not zero: an empty input list still yields the unassigned fallback alone, which is why a
 * caller that wants real dimensions must pass
 * [defaultNewDatabaseDimensionInputs] when the user confirmed nothing.
 *
 * Every input is normalized on the way through - labels and icon keys trimmed, colour uppercased -
 * and the normalization is what the filters then judge, so a label of only whitespace counts as
 * blank. The cap is [MAX_USER_DIMENSIONS], applied AFTER the de-duplication, so it spends the cap
 * on distinct dimensions rather than on a user's repeats of one.
 *
 * @param dimensionInputs the rows the first-run screen reported, in the order it presented them.
 * @return the user's rows followed by the unassigned fallback, never empty.
 */
fun buildDimensionSeedRows(dimensionInputs: List<NewDatabaseDimensionInput>): List<NewDatabaseDimensionSeedRow> {
    val logger = AppLogger.current
    logger.i(
        "DatabaseInitDimensionSetupSupport.buildDimensionSeedRows",
        "Building dimension seed rows",
        mapOf("inputCount" to dimensionInputs.size),
    )
    val normalizedInputs =
        dimensionInputs
            .asSequence()
            .map {
                it.copy(
                    label = it.label.trim(),
                    colorHex = it.colorHex.trim().uppercase(Locale.US),
                    iconKey = it.iconKey.trim(),
                )
            }.filter { it.id.isNotBlank() && it.label.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_USER_DIMENSIONS)
            .toList()
    logger.i(
        "DatabaseInitDimensionSetupSupport.buildDimensionSeedRows",
        "Normalized dimension inputs",
        mapOf(
            "normalizedCount" to normalizedInputs.size,
            "maxAllowed" to MAX_USER_DIMENSIONS,
        ),
    )
    val rows =
        normalizedInputs.mapIndexed { index, input ->
            val template = DEFAULT_NEW_DB_DIMENSION_ROWS_BY_ID[input.id]
            NewDatabaseDimensionSeedRow(
                id = input.id,
                key = template?.key ?: toDimensionKey(input.label, input.id),
                label = input.label,
                description = template?.description ?: DEFAULT_CUSTOM_DESCRIPTION,
                color = input.colorHex,
                icon = input.iconKey.ifEmpty { template?.icon ?: DEFAULT_CUSTOM_ICON },
                sortOrder = (index + 1) * 10,
                isActive = input.isEnabled,
            )
        }
    val finalRows = rows + UNASSIGNED_DIMENSION_ROW
    logger.i(
        "DatabaseInitDimensionSetupSupport.buildDimensionSeedRows",
        "Finalized dimension seed rows including unassigned fallback",
        mapOf("rowCount" to finalRows.size),
    )
    return finalRows
}

/**
 * The canonical dimension set a brand-new database starts from, localized for display.
 *
 * This is the input to the first-run screen, not the seed itself: it supplies the rows the user
 * then edits, toggles and renames, and the result of that editing is what
 * [buildDimensionSeedRows] turns into stored rows. The `dim_unassigned` fallback is removed here
 * rather than left for the user to toggle, because it is not a life dimension they can live
 * without - [buildDimensionSeedRows] appends it unconditionally.
 *
 * The ids, colours and icons are [DimensionTaxonomyCatalog]'s, in its canonical order, so this
 * list and the scoring config's dimension weights are guaranteed to be the same set. Labels are the
 * only localized part, and they are read from the copy table rather than hardcoded: a locale with
 * no app-owned name for a dimension falls back to the catalog's English label, so the row is never
 * offered to a user with a blank name.
 *
 * **Every row comes back enabled.** A fresh catalog that started mostly inactive would silently
 * score close to nothing until the user went looking for the toggles, so the unedited default is
 * that all nine score.
 *
 * @param strings the copy table the labels are read from; the first-run screen passes the app's
 *   current language, which is a language the catalog has been written for.
 */
fun defaultNewDatabaseDimensionInputs(strings: UiStrings): List<NewDatabaseDimensionInput> {
    val logger = AppLogger.current
    val defaults =
        DEFAULT_NEW_DB_DIMENSION_ROWS
            .filterNot { it.id == UNASSIGNED_DIMENSION_ID }
            .map { row ->
                NewDatabaseDimensionInput(
                    id = row.id,
                    label = DimensionTextCatalog.localizedLabel(row.id, strings) ?: row.label,
                    colorHex = row.color,
                    isEnabled = row.isActive,
                    iconKey = row.icon,
                )
            }
    logger.i(
        "DatabaseInitDimensionSetupSupport.defaultNewDatabaseDimensionInputs",
        "Loaded default mandatory dimension input list",
        mapOf("count" to defaults.size),
    )
    return defaults
}

private const val UNASSIGNED_DIMENSION_ID = "dim_unassigned"
const val MAX_USER_DIMENSIONS = 25
private const val DEFAULT_CUSTOM_ICON = "category"
private const val DEFAULT_CUSTOM_DESCRIPTION = "User-defined life dimension"
private val SUPPORTED_DIMENSION_LOCALE_TAGS = listOf("en", "ta")

private fun toDimensionKey(
    label: String,
    id: String,
): String {
    val logger = AppLogger.current
    val base =
        label
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    val normalized =
        if (base.isBlank()) {
            id.removePrefix("dim_").lowercase(Locale.US)
        } else {
            base
        }
    val key = normalized.take(40).ifBlank { "custom_dimension" }
    logger.d(
        "DatabaseInitDimensionSetupSupport.toDimensionKey",
        "Derived dimension key",
        mapOf("id" to id, "label" to label, "key" to key),
    )
    return key
}

private fun defaultSeedRow(definition: io.payanam.domain.model.CanonicalDimensionDefinition): NewDatabaseDimensionSeedRow =
    NewDatabaseDimensionSeedRow(
        id = definition.id,
        key = definition.slug,
        label = definition.fallbackLabel,
        description = definition.fallbackDescription,
        color = definition.defaultColorHex,
        icon = definition.defaultIconKey,
        sortOrder = definition.sortOrder,
        isActive = true,
    )

private val DEFAULT_NEW_DB_DIMENSION_ROWS =
    DimensionTaxonomyCatalog.entries
        .map(::defaultSeedRow)

private val DEFAULT_NEW_DB_DIMENSION_ROWS_BY_ID = DEFAULT_NEW_DB_DIMENSION_ROWS.associateBy { it.id }
private val UNASSIGNED_DIMENSION_ROW = DEFAULT_NEW_DB_DIMENSION_ROWS_BY_ID.getValue(UNASSIGNED_DIMENSION_ID)

/**
 * Resolves the label a seeded dimension should actually store.
 *
 * Public because the WRITE path needs it: a label the user typed that happens to equal one of the app's
 * OWN translations is replaced with the canonical fallback, so a seeded dimension is never left carrying
 * copy the user did not choose. Making it public rather than duplicating it keeps one rule in one place.
 */
fun canonicalizeDefaultSeedLabel(
    dimensionId: String,
    candidateLabel: String,
): String {
    val trimmedLabel = candidateLabel.trim()
    val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: return trimmedLabel
    val knownAppOwnedLabels =
        buildSet {
            DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel?.let(::add)
            SUPPORTED_DIMENSION_LOCALE_TAGS
                .mapNotNull { localeTag -> DimensionTextCatalog.localizedLabel(canonicalId, uiStringsFor(localeTag)) }
                .forEach(::add)
        }
    return if (trimmedLabel.isBlank() || trimmedLabel in knownAppOwnedLabels) {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel ?: trimmedLabel
    } else {
        trimmedLabel
    }
}
