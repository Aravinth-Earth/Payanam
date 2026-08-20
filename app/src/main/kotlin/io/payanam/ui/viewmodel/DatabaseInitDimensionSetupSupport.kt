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

/**
 * NewDatabaseDimensionInput.
 */
data class NewDatabaseDimensionInput(
    /** Id. */
    val id: String,
    /** Label. */
    val label: String,
    /** Color hex. */
    val colorHex: String,
    /** Is enabled. */
    val isEnabled: Boolean,
    /** Icon key. */
    val iconKey: String = DimensionIconCatalog.defaultIconKeyForDimensionId(id),
)

/**
 * NewDatabaseDimensionSeedRow.
 */
data class NewDatabaseDimensionSeedRow(
    /** Id. */
    val id: String,
    /** Key. */
    val key: String,
    /** Label. */
    val label: String,
    /** Description. */
    val description: String,
    /** Color. */
    val color: String,
    /** Icon. */
    val icon: String,
    /** Sort order. */
    val sortOrder: Int,
    /** Is active. */
    val isActive: Boolean,
)

internal suspend fun persistNewDatabaseDimensionSetup(
    /** Context. */
    context: Context,
    /** Database session manager. */
    databaseSessionManager: DatabaseSessionManager,
    /** App settings repository. */
    appSettingsRepository: AppSettingsRepository,
    dimensionInputs: List<NewDatabaseDimensionInput>,
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Rows. */
    val rows = buildDimensionSeedRows(dimensionInputs)
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Persisting new database dimension setup",
        /** Map of. */
        mapOf("rowCount" to rows.size),
    )
    /** Now iso. */
    val nowIso = Instant.now().toString()
    /** Db. */
    val db = databaseSessionManager.requireDatabase()
    /** Writable db. */
    val writableDb = db.openHelper.writableDatabase
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Clearing existing life_dimensions rows before seed write",
    )
    writableDb.execSQL("DELETE FROM life_dimensions")
    /** Inserted rows. */
    var insertedRows = 0
    rows.forEach { row ->
        /** Stored label. */
        val storedLabel = canonicalizeDefaultSeedLabel(
            context = context,
            dimensionId = row.id,
            candidateLabel = row.label,
        )
        logger.d(
            "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
            "Inserting life dimension row",
            /** Map of. */
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
                    /** Values. */
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    row.id,
                    row.key,
                    /** Stored label. */
                    storedLabel,
                    row.description,
                    row.color,
                    row.icon,
                    row.sortOrder,
                    /** If. */
                    if (row.isActive) 1 else 0,
                    1.0,
                    /** Now iso. */
                    nowIso,
                    /** Now iso. */
                    nowIso,
                ),
            )
            insertedRows++
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
                "Seed row insert failed",
                /** E. */
                e,
                /** Map of. */
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
        /** Map of. */
        mapOf("insertedRows" to insertedRows, "totalRows" to rows.size),
    )
    appSettingsRepository.setSetting("database_init_completed", "true")
    logger.i(
        "DatabaseInitDimensionSetupSupport.persistNewDatabaseDimensionSetup",
        "Completed mandatory dimension setup persistence",
        /** Map of. */
        mapOf("persistedRows" to rows.size),
    )
}

internal fun buildDimensionSeedRows(
    dimensionInputs: List<NewDatabaseDimensionInput>,
): List<NewDatabaseDimensionSeedRow> {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    logger.i(
        "DatabaseInitDimensionSetupSupport.buildDimensionSeedRows",
        "Building dimension seed rows",
        /** Map of. */
        mapOf("inputCount" to dimensionInputs.size),
    )
    /** Normalized inputs. */
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
        /** Map of. */
        mapOf(
            "normalizedCount" to normalizedInputs.size,
            "maxAllowed" to MAX_USER_DIMENSIONS,
        ),
    )

    /** Rows. */
    val rows = normalizedInputs.mapIndexed { index, input ->
        /** Template. */
        val template = DEFAULT_NEW_DB_DIMENSION_ROWS_BY_ID[input.id]
        /** New database dimension seed row. */
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
    /** Final rows. */
    val finalRows = rows + UNASSIGNED_DIMENSION_ROW
    logger.i(
        "DatabaseInitDimensionSetupSupport.buildDimensionSeedRows",
        "Finalized dimension seed rows including unassigned fallback",
        /** Map of. */
        mapOf("rowCount" to finalRows.size),
    )
    return finalRows
}

internal fun defaultNewDatabaseDimensionInputs(context: Context): List<NewDatabaseDimensionInput> {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Defaults. */
    val defaults = DEFAULT_NEW_DB_DIMENSION_ROWS
        .filterNot { it.id == UNASSIGNED_DIMENSION_ID }
        .map { row ->
            /** New database dimension input. */
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
        /** Map of. */
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
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Base. */
    val base = label.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
    /** Normalized. */
    val normalized = if (base.isBlank()) {
        id.removePrefix("dim_").lowercase(Locale.US)
    } else {
        /** Base. */
        base
    }
    /** Key. */
    val key = normalized.take(40).ifBlank { "custom_dimension" }
    logger.d(
        "DatabaseInitDimensionSetupSupport.toDimensionKey",
        "Derived dimension key",
        /** Map of. */
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
    /** Context. */
    context: Context,
    /** Dimension id. */
    dimensionId: String,
    /** Candidate label. */
    candidateLabel: String,
): String {
    /** Trimmed label. */
    val trimmedLabel = candidateLabel.trim()
    /** Canonical id. */
    val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: return trimmedLabel
    /** Known app owned labels. */
    val knownAppOwnedLabels = buildSet {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel?.let(::add)
        /** Supported dimension locale tags. */
        SUPPORTED_DIMENSION_LOCALE_TAGS
            .mapNotNull { localeTag ->
                DimensionTextCatalog.labelResIdForCanonicalId(canonicalId)?.let { resId ->
                    /** Localized string for locale. */
                    localizedStringForLocale(context, resId, localeTag)
                }
            }
            .forEach(::add)
    }
    return if (trimmedLabel.isBlank() || trimmedLabel in knownAppOwnedLabels) {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel ?: trimmedLabel
    } else {
        /** Trimmed label. */
        trimmedLabel
    }
}

private fun localizedStringForLocale(context: Context, resId: Int, localeTag: String): String {
    /** Localized context. */
    val localizedContext = context.createConfigurationContext(
        android.content.res.Configuration(context.resources.configuration).apply {
            /** Set locale. */
            setLocale(java.util.Locale.forLanguageTag(localeTag))
        },
    )
    return localizedContext.getString(resId)
}
