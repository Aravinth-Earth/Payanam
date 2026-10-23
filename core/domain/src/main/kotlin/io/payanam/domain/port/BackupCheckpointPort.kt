//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.shared.database.WalCheckpointRule

/**
 * The WAL-fold seam, Android wraps DatabaseSessionManager.
 *
 * Abstracts the two operations the backup coordinator needs from the database session:
 * checking whether the session is open, and folding the write-ahead log.
 */
public interface BackupCheckpointPort {
    /** Returns true when the database session is open and usable. */
    public fun isDbOpen(): Boolean

    /**
     * Folds the write-ahead log and returns the report.
     *
     * @return the [WalCheckpointRule.FoldReport] from the checkpoint pragma.
     */
    public fun foldWal(): WalCheckpointRule.FoldReport
}
