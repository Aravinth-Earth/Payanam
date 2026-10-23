//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.port.BackupExecutionResult
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.domain.port.BackupTrigger
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's implementation of the shared [io.payanam.domain.port.BackupStatusStore].
 *
 * Pure delegation: Android's store already persists the snapshot and formats the display strings,
 * and the shared contract only asks for the read half — so this port exposes the wrapped store's
 * LIVE flow rather than a copy, and its formatter, which the store itself now implements from the
 * one shared display function.
 */
@Singleton
class AndroidBackupStatusPort
    @Inject
    constructor(
        private val delegate: io.payanam.service.BackupStatusStore,
    ) : io.payanam.domain.port.BackupStatusStore {
        /** The wrapped store's own flow — a reader sees every recorded outcome as it lands. */
        override val status: StateFlow<BackupStatusSnapshot> = delegate.status

        /** The wrapped store's formatter, backed by the shared display function. */
        override fun formatBackupTimestamp(millis: Long): String = delegate.formatBackupTimestamp(millis)
    }

/**
 * Android's implementation of the shared [io.payanam.domain.port.DatabaseBackupCoordinator].
 *
 * Pure delegation with one entry breadcrumb: the wrapped coordinator owns the whole backup — retry
 * policy, WAL checkpoint, verified snapshot, rotation, and outcome recording — and this port only
 * makes that same call reachable from shared code.
 */
@Singleton
class AndroidDatabaseBackupPort
    @Inject
    constructor(
        private val delegate: io.payanam.service.DatabaseBackupCoordinator,
    ) : io.payanam.domain.port.DatabaseBackupCoordinator {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Breadcrumb first, then the real backup; the outcome (and its recorded status) is the
         * wrapped coordinator's own, exactly as a direct caller would receive.
         */
        override suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult {
            logger.i(
                "AndroidDatabaseBackupPort.backupToAppBackupDirectory",
                "Backup requested via the shared port",
                mapOf("trigger" to trigger.key),
            )
            return delegate.backupToAppBackupDirectory(trigger)
        }
    }
