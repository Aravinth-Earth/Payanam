//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import io.payanam.database.entity.ImportBatchEntity

@Dao
/**
 * Room DAO for the `import_batches` table: one [ImportBatchEntity] per external
 * import run, used to group and reconcile imported rows.
 */
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces an import-batch record.
     */
    suspend fun insert(batch: ImportBatchEntity)
}
