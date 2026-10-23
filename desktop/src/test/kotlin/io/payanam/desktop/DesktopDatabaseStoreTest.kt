//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The database store reads the REAL file, and the facts it reports are derived from it.
 *
 * These cases exist because the store used to point at
 * `<root>/database/desktop-local-state-mock.unused` - a path
 * [DesktopLocalStateMock] documents as deliberately never created - so `hasArtifacts` was permanently
 * false, `initCompleted` was an in-memory marker that vanished on restart, and the startup gate's
 * database-lifecycle check could never report ready on any launch. No test caught it, because every
 * assertion about those two values was written against the store's own inputs rather than against a file
 * on disk.
 *
 * Each case therefore creates a real file (or deliberately does not) and reads the store back, so "the
 * store reports the file that is actually there" is a claim with a way to fail.
 */
class DesktopDatabaseStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a database that is not there reports no artifacts and a zero size`() {
        val store = DesktopDatabaseStore(databaseFilePath = tempFolder.newFolder().toPath().resolve("payanam.db"))

        val snapshot = store.loadSnapshot()

        assertThat(snapshot.hasArtifacts).isFalse()
        assertThat(snapshot.initCompleted).isFalse()
        // Not 0 because the file is empty: 0 because there is no file to measure. A size of 0 with
        // hasArtifacts true would be the signature of a truncated export, which is a different fault.
        assertThat(snapshot.databaseSizeKb).isEqualTo(0L)
        assertThat(snapshot.databaseLastModifiedMs).isEqualTo(0L)
    }

    @Test
    fun `a database that is there reports ready, and the size is read from the file`() {
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        // 3 KB, so the /1024 division is observable rather than rounded back to the same number.
        Files.write(path, ByteArray(3 * 1024))

        val snapshot = DesktopDatabaseStore(databaseFilePath = path).loadSnapshot()

        assertThat(snapshot.hasArtifacts).isTrue()
        // The one fact the whole change turns on: readiness IS the file being there, so it cannot
        // disagree with it.
        assertThat(snapshot.initCompleted).isTrue()
        assertThat(snapshot.databaseSizeKb).isEqualTo(3L)
        assertThat(snapshot.databaseLastModifiedMs).isGreaterThan(0L)
    }

    @Test
    fun `the store reports the path it was given, not a scaffold path`() {
        // The defect in one assertion: the old store reported
        // `.../desktop-local-state-mock.unused` no matter which file the desktop actually opened, so the
        // settings screen told the user their data lived somewhere it never did.
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")

        val snapshot = DesktopDatabaseStore(databaseFilePath = path).loadSnapshot()

        assertThat(snapshot.databaseFilePath).isEqualTo(path.toString())
        assertThat(snapshot.databaseFilePath).doesNotContain("desktop-local-state-mock")
    }

    @Test
    fun `reset does not delete the database`() {
        // `resetDatabaseArtifact` used to call `clearStateEntries()` + `clearInitializedMarker()` on the
        // scaffold. Against a real file that would be the user's data, so the method is now read-only -
        // and a test that only asserted the returned snapshot could not tell a read from a delete.
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        Files.write(path, ByteArray(1024))
        val store = DesktopDatabaseStore(databaseFilePath = path)

        val after = store.resetDatabaseArtifact()

        assertThat(Files.exists(path)).isTrue()
        assertThat(after.hasArtifacts).isTrue()
        assertThat(after.databaseSizeKb).isEqualTo(1L)
    }

    @Test
    fun `the default path is the real database file beside the data root`() {
        // Resolved on every call rather than captured in a default argument, so a redirected data root
        // is honoured - and the file name stays single-sourced with the transfer gateway's resolver.
        val resolved = DesktopDatabaseStore().getDatabaseFilePath()

        assertThat(resolved.fileName.toString()).isEqualTo(DesktopAppPaths.DATABASE_FILE_NAME)
        assertThat(resolved.fileName.toString()).isNotEqualTo("desktop-local-state-mock.unused")
        assertThat(resolved.parent).isEqualTo(DesktopAppPaths.resolveDatabaseDirectory() as Path?)
    }
}
