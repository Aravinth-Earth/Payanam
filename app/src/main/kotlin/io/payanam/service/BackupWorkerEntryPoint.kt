//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
/**
 * BackupWorkerEntryPoint.
 */
interface BackupWorkerEntryPoint {
    /**
     * Database backup coordinator.
     */
    fun databaseBackupCoordinator(): DatabaseBackupCoordinator
    /**
     * Backup status store.
     */
    fun backupStatusStore(): BackupStatusStore
}
