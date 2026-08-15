//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.model.DimensionTextCatalog
import java.time.Instant
import java.util.Locale

data class NewDatabaseDimensionInput(
    val id: String,
    val label: String,
    val colorHex: String,
    val isEnabled: Boolean,
    val iconKey: String = DimensionIconCatalog.defaultIconKeyForDimensionId(id),
)

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

internal suspend fun persistNewDatabaseDimensionSetup(
    context: Context,
    databaseSessionManager: DatabaseSessionManager,
    appSettingsRepository: AppSettingsRepository,
    dimensionInputs: List<NewDatabaseDimensionInput>,
) {
    val logger = UnifiedLogger.getInstance()
    val rows = buildDimensionSeedRows(dimensionInputs)
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Persisting new database dimension setup",
        mapOf("rowCount" to rows.size),
    )
    val nowIso = Instant.now().toString()
    val db = databaseSessionManager.requireDatabase()
    val writableDb = db.openHelper.writableDatabase
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Clearing existing life_dimensions rows before seed write",
    )
    writableDb.execSQL("DELETE FROM life_dimensions")
    var insertedRows = 0
    rows.forEach { row ->
        val storedLabel = canonicalizeDefaultSeedLabel(
            context = context,
            dimensionId = row.id,
            candidateLabel = row.label,
        )
        logger.d(
            "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
            "Inserting life dimension row",
            mapOf(
                "id" to row.id,
                "key" to row.key,
                "label" to storedLabel,
                "color" to row.color,
                "icon" to row.icon,
                "isActive" to row.isActive,
                "sortOrder" to row.sortOrder,
                "weight" to 1.0,
            ),
        )
        try {
            writableDb.execSQL(
                """
                    INSERT INTO life_dimensions
                    (id, key, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    row.id,
                    row.key,
                    storedLabel,
                    row.description,
                    row.color,
                    row.icon,
                    row.sortOrder,
                    if (row.isActive) 1 else 0,
                    1.0,
                    nowIso,
                    nowIso,
                ),
            )
            insertedRows++
        } catch (e: Exception) {
            logger.e(
                "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
                "Seed row insert failed",
                e,
                mapOf(
                    "id" to row.id,
                    "key" to row.key,
                    "insertedBeforeFailure" to insertedRows,
                    "totalRows" to rows.size,
                ),
            )
            throw e
        }
    }
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Seed rows inserted",
        mapOf("insertedRows" to insertedRows, "totalRows" to rows.size),
    )
    appSettingsRepository.setSetting("database_init_completed", "true")
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Completed mandatory dimension setup persistence",
        mapOf("persistedRows" to rows.size),
    )
}

internal fun buildDimensionSeedRows(
    dimensionInputs: List<NewDatabaseDimensionInput>,
): List<NewDatabaseDimensionSeedRow> {
    val logger = UnifiedLogger.getInstance()
    logger.i(
        "DatabaseInitDimensionSetupSupport.buildDimensionSeedRows",
        "Building dimension seed rows",
        mapOf("inputCount" to dimensionInputs.size),
    )
    val normalizedInputs = dimensionInputs
        .asSequence()
        .map {
            it.copy(
                label = it.label.trim(),
                colorHex = it.colorHex.trim().uppercase(Locale.US),
                iconKey = it.iconKey.trim(),
            )
        }
        .filter { it.id.isNotBlank() && it.label.isNotBlank() }
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

    val rows = normalizedInputs.mapIndexed { index, input ->
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

internal fun defaultNewDatabaseDimensionInputs(context: Context): List<NewDatabaseDimensionInput> {
    val logger = UnifiedLogger.getInstance()
    val defaults = DEFAULT_NEW_DB_DIMENSION_ROWS
        .filterNot { it.id == UNASSIGNED_DIMENSION_ID }
        .map { row ->
            NewDatabaseDimensionInput(
                id = row.id,
                label = DimensionTextCatalog.localizedLabel(context, row.id) ?: row.label,
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
internal const val MAX_USER_DIMENSIONS = 25
private const val DEFAULT_CUSTOM_ICON = "category"
private const val DEFAULT_CUSTOM_DESCRIPTION = "User-defined life dimension"
private val SUPPORTED_DIMENSION_LOCALE_TAGS = listOf("en", "ta")

private fun toDimensionKey(label: String, id: String): String {
    val logger = UnifiedLogger.getInstance()
    val base = label.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
    val normalized = if (base.isBlank()) {
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

private fun defaultSeedRow(definition: io.payanam.domain.model.CanonicalDimensionDefinition): NewDatabaseDimensionSeedRow = NewDatabaseDimensionSeedRow(
    id = definition.id,
    key = definition.slug,
    label = definition.fallbackLabel,
    description = definition.fallbackDescription,
    color = definition.defaultColorHex,
    icon = definition.defaultIconKey,
    sortOrder = definition.sortOrder,
    isActive = true,
)

private val DEFAULT_NEW_DB_DIMENSION_ROWS = DimensionTaxonomyCatalog.entries
    .map(::defaultSeedRow)

private val DEFAULT_NEW_DB_DIMENSION_ROWS_BY_ID = DEFAULT_NEW_DB_DIMENSION_ROWS.associateBy { it.id }
private val UNASSIGNED_DIMENSION_ROW = DEFAULT_NEW_DB_DIMENSION_ROWS_BY_ID.getValue(UNASSIGNED_DIMENSION_ID)

private fun canonicalizeDefaultSeedLabel(
    context: Context,
    dimensionId: String,
    candidateLabel: String,
): String {
    val trimmedLabel = candidateLabel.trim()
    val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: return trimmedLabel
    val knownAppOwnedLabels = buildSet {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel?.let(::add)
        SUPPORTED_DIMENSION_LOCALE_TAGS
            .mapNotNull { localeTag ->
                DimensionTextCatalog.labelResIdForCanonicalId(canonicalId)?.let { resId ->
                    localizedStringForLocale(context, resId, localeTag)
                }
            }
            .forEach(::add)
    }
    return if (trimmedLabel.isBlank() || trimmedLabel in knownAppOwnedLabels) {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel ?: trimmedLabel
    } else {
        trimmedLabel
    }
}

private fun localizedStringForLocale(context: Context, resId: Int, localeTag: String): String {
    val localizedContext = context.createConfigurationContext(
        android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(localeTag))
        },
    )
    return localizedContext.getString(resId)
}
