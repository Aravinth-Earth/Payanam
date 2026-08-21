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
        Index("source"),
        Index("importedAt"),
    ],
)
/**
 * ImportBatchEntity.
 */
data class ImportBatchEntity(
    @PrimaryKey
    val id: String,
    val source: String,
    val importedAt: String,
    val version: String? = null,
    val fileHash: String? = null,
    val notes: String? = null,
)
