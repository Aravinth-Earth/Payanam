//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Groups a single external import operation for audit and dedupe support.
 */
@Entity(
    tableName = "import_batches",
    indices = [
        /** Index. */
        Index("source"),
        /** Index. */
        Index("importedAt"),
    ],
)
/**
 * ImportBatchEntity.
 */
data class ImportBatchEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Source. */
    val source: String,
    /** Imported at. */
    val importedAt: String,
    /** Version. */
    val version: String? = null,
    /** File hash. */
    val fileHash: String? = null,
    /** Notes. */
    val notes: String? = null,
)
