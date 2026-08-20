//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import io.payanam.database.entity.ImportBatchEntity

@Dao
/**
 * ImportBatchDao.
 */
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(batch: ImportBatchEntity)
}
