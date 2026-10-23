//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Test

/**
 * Tests for the desktop's database path resolver.
 *
 * What is under test is the CONTRACT the shared port relies on: the file is the directory joined with
 * the one file name both platforms share, an injected directory is honoured rather than overridden by
 * the host's real layout, and the default (no injection) lands inside the app's own `Payanam`
 * directory — the regression this port exists to make visible.
 */
class DesktopDatabasePathResolverTest {
    @Test
    fun `the file path is the directory joined with the shared file name`() {
        val directory = "/home/demo/.local/share/Payanam/database"
        val resolver = DesktopDatabasePathResolver(databaseDirectory = { directory })

        // The literal "payanam.db" is deliberate: it pins the name shared with Android, so a change to
        // either platform's single-sourced name breaks here instead of silently splitting the file.
        // The name now has exactly one declaration, in the shared schema object both platforms read,
        // so this pins that single source's value rather than restating it.
        assertThat(PayanamDatabaseSchema.DATABASE_NAME).isEqualTo("payanam.db")
        assertThat(resolver.databaseFilePath()).isEqualTo("$directory/payanam.db")
    }

    @Test
    fun `an injected directory is honoured rather than the host's real layout`() {
        val injected = "/var/tmp/payanam-test-data"
        val resolver = DesktopDatabasePathResolver(databaseDirectory = { injected })

        assertThat(resolver.databaseDirectory()).isEqualTo(injected)
        assertThat(resolver.databaseFilePath()).isEqualTo("$injected/payanam.db")
    }

    @Test
    fun `the default path lands in the app's database directory`() {
        val filePath = DesktopDatabasePathResolver().databaseFilePath()

        // `<root>/database/payanam.db` from DesktopAppPaths: the "database" segment proves the resolver
        // did not fall back to some other subdirectory, and "Payanam" proves it stayed in the app's own
        // data directory rather than a bare shared location.
        assertThat(filePath).endsWith("/database/payanam.db")
        assertThat(filePath).contains("Payanam")
    }
}
