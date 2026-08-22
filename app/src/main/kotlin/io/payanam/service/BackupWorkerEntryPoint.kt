//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point letting WorkManager-instantiated backup workers resolve the
 * coordinator and status store without field injection.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerEntryPoint {
    /**
     * The singleton backup executor (snapshot + rotation).
     */
    fun databaseBackupCoordinator(): DatabaseBackupCoordinator
    /**
     * The persisted last-success/last-failure store shown in Settings.
     */
    fun backupStatusStore(): BackupStatusStore
}
