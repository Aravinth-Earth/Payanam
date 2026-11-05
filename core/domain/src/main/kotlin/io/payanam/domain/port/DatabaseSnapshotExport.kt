//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import java.io.OutputStream

/**
 * Writes a verified snapshot of the live database to a destination the USER chose, on either platform.
 *
 * ## Why this is not a member on [DatabaseBackupCoordinator]
 *
 * The two backup questions have different answers on the two platforms, and merging them would force
 * one of them to be false somewhere. A scheduled backup writes into the app's own backup directory,
 * which every platform can name as a path. An export writes wherever the user pointed — on Android
 * that is a Storage Access Framework `Uri` which is frequently NOT a filesystem path and cannot be
 * opened as one. Adding an export member to [DatabaseBackupCoordinator] would have obliged every
 * implementer to answer it, including the desktop's, which has no SAF surface to answer it with; the
 * alternative was a default method returning "unsupported", and a port whose member can be
 * unimplemented on a supported platform is a port that stops saying what it means.
 *
 * ## Why the destination is an [OutputStream] and not a path or a `Uri`
 *
 * An [OutputStream] is the only one of the three that both platforms can actually satisfy:
 * - a **path** cannot express an Android SAF destination at all, which is the destination this member
 *   exists for;
 * - a **`Uri`** is an `android.*` type, and a port may not name one;
 * - an **[OutputStream]** is ordinary `java.io`, and each platform opens it however its own storage
 *   works — `ContentResolver.openOutputStream` on one side, `FileOutputStream` on the other.
 *
 * The stream is passed OPEN and is the CALLER's to close, so a caller writing to a document it must
 * keep open (a multi-file export) controls its own lifetime.
 *
 * ## What a caller may assume
 * - The bytes written are a WAL-checkpointed, integrity-verified snapshot — the same artifact a
 *   scheduled backup produces, not a raw copy of the main file that could be missing committed rows.
 * - The outcome is recorded in [BackupStatusStore] as a [BackupTrigger.EXPORT], success or failure.
 * - A failure is wrapped in [IllegalStateException] with the reason, never swallowed, and the
 *   partially-written destination is not claimed as a backup.
 */
interface DatabaseSnapshotExport {
    /**
     * Writes a verified snapshot of the database into [destination] and returns the bytes written.
     *
     * @param destination the caller-owned stream, already open for writing. Closed by the caller.
     * @return the number of bytes copied, so a caller can log or verify the size it actually got
     *   rather than assuming the whole database was written.
     * @throws IllegalStateException when the snapshot could not be taken or written. The recorded
     *   failure carries the reason.
     */
    suspend fun exportSnapshotTo(destination: OutputStream): Long
}
