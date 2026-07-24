//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import io.payanam.database.entity.ImportBatchEntity

@Dao
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: ImportBatchEntity)
}
