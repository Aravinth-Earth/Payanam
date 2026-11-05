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
 * Tests for [DesktopStorageRootMigration], the one-time migration from the legacy desktop storage
 * root to the new parent-folder layout.
 *
 * The migration is the kind of code that must be pinned: running it twice must change nothing, and
 * running it once must move things correctly. Every case below constructs a fake home directory,
 * builds the legacy and new layouts inside it, runs the migration, and asserts the data landed
 * where the new resolver looks for it.
 *
 * Expected paths are derived from [DesktopAppPaths] rather than hardcoded, so the test cannot
 * drift from the production resolver.
 */
class DesktopStorageRootMigrationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fakeHome: Path by lazy { tempFolder.newFolder("home").toPath() }

    private val legacyRoot: Path by lazy { fakeHome.resolve(".local/share/Payanam") }

    private val newParent: Path by lazy {
        DesktopAppPaths.resolveRootDirectory(
            environment = emptyMap(),
            userHome = fakeHome.toString(),
            osName = "Linux",
        )
    }

    private val expectedNewDatabase: Path by lazy {
        DesktopAppPaths.resolveDatabaseDirectory(
            environment = emptyMap(),
            userHome = fakeHome.toString(),
            osName = "Linux",
        )
    }

    @Test
    fun `legacy layout is migrated to the new layout`() {
        // Arrange: put a file in the legacy database directory
        val legacyDatabase = legacyRoot.resolve("database")
        Files.createDirectories(legacyDatabase)
        val testFile = legacyDatabase.resolve("test.db")
        Files.writeString(testFile, "test data")

        // Act
        val report = DesktopStorageRootMigration.performMigration(legacyRoot, newParent)

        // Assert: the data landed in the new layout
        val migratedFile = expectedNewDatabase.resolve("test.db")
        assertThat(Files.exists(migratedFile)).isTrue()
        assertThat(Files.readString(migratedFile)).isEqualTo("test data")

        // Assert: the legacy directory is now empty (data was moved, not copied)
        assertThat(Files.list(legacyDatabase).use { it.toList() }).isEmpty()

        // Assert: the report says the database child was moved
        val databaseReport = report.children.find { it.legacyChildName == "database" }
        assertThat(databaseReport).isNotNull()
        assertThat(databaseReport!!.status).isEqualTo(MigrationChildStatus.MOVED)
    }

    @Test
    fun `running the migration a second time changes nothing and does not throw`() {
        // Arrange: put a file in the legacy database directory
        val legacyDatabase = legacyRoot.resolve("database")
        Files.createDirectories(legacyDatabase)
        val testFile = legacyDatabase.resolve("test.db")
        Files.writeString(testFile, "test data")

        // Act: run the migration twice
        val firstReport = DesktopStorageRootMigration.performMigration(legacyRoot, newParent)
        val secondReport = DesktopStorageRootMigration.performMigration(legacyRoot, newParent)

        // Assert: the first run moved the data
        assertThat(firstReport.moved).isNotEmpty()

        // Assert: the second run is a no-op
        assertThat(secondReport.moved).isEmpty()
        assertThat(secondReport.skipped).isNotEmpty()

        // Assert: the data is still in the new layout
        val migratedFile = expectedNewDatabase.resolve("test.db")
        assertThat(Files.exists(migratedFile)).isTrue()
        assertThat(Files.readString(migratedFile)).isEqualTo("test data")

        // Assert: the legacy directory is still empty
        assertThat(Files.list(legacyDatabase).use { it.toList() }).isEmpty()
    }

    @Test
    fun `a fresh or absent legacy layout is a no-op and creates no stray files`() {
        // Arrange: create empty legacy root and new parent
        Files.createDirectories(legacyRoot)
        Files.createDirectories(newParent)

        // Act
        val report = DesktopStorageRootMigration.performMigration(legacyRoot, newParent)

        // Assert: nothing was moved
        assertThat(report.moved).isEmpty()
        assertThat(report.skipped).isNotEmpty()

        // Assert: no stray files were created in the new parent
        assertThat(Files.list(newParent).use { it.toList() }).isEmpty()
    }
}
