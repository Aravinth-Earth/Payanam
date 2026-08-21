//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
/**
 * Defines the contract for backup worker entry point.
 */
interface BackupWorkerEntryPoint {
    /**
     * Performs the database backup coordinator.
     */
    fun databaseBackupCoordinator(): DatabaseBackupCoordinator
    /**
     * Performs the backup status store.
     */
    fun backupStatusStore(): BackupStatusStore
}
