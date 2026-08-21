//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import io.payanam.database.entity.ImportBatchEntity

@Dao
/**
 * Defines the contract for import batch dao.
 */
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert.
     */
    suspend fun insert(batch: ImportBatchEntity)
}
