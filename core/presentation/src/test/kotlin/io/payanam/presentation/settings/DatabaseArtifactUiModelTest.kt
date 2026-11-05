//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A database file as the Settings backup list shows it.
 *
 * The `isActive` default is the piece with a consequence: the list is split into live and stale files, and the
 * live SQLite trio (the database plus its `-wal`/`-shm`) must land on the live side when the caller does not say.
 * A default of `false` would quietly file the running database under "stale" — the one row the user must not be
 * invited to delete.
 */
class DatabaseArtifactUiModelTest {
    @Test
    fun `a row defaults to active, so the live database is never listed as stale`() {
        val row = DatabaseArtifactUiModel(fileName = "payanam.db", sizeKb = 512, lastModifiedLabel = "2026-09-21 06:00")

        assertThat(row.isActive).isTrue()
    }

    @Test
    fun `a row keeps the name, size and timestamp it was built with`() {
        val row =
            DatabaseArtifactUiModel(
                fileName = "payanam.db-wal",
                sizeKb = 128,
                lastModifiedLabel = "2026-09-21 05:59",
                isActive = true,
            )

        assertThat(row.fileName).isEqualTo("payanam.db-wal")
        assertThat(row.sizeKb).isEqualTo(128L)
        assertThat(row.lastModifiedLabel).isEqualTo("2026-09-21 05:59")
    }

    @Test
    fun `a stale row can say so`() {
        val row =
            DatabaseArtifactUiModel(fileName = "payanam.db.bak", sizeKb = 900, lastModifiedLabel = "2026-09-01 09:00", isActive = false)

        assertThat(row.isActive).isFalse()
        assertThat(row.fileName).endsWith(".bak")
    }
}
