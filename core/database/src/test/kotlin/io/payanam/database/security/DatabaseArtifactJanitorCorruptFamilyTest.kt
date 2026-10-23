//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.database.DatabaseArtifacts
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The corrupt-family deletion, pinned directly - because it silently stopped deleting.
 *
 * WHY THIS FILE EXISTS: `sidecarFor` concatenates its suffix (`name + suffix`) and the shared suffixes
 * carry their own separator (`-wal`). One call site kept handing it BARE names ("wal"), so it looked for
 * `<candidate>wal` - a file that never exists - while the snapshot's real `-wal`/`-shm`/`-journal` sat
 * beside it. Nothing failed loudly: the main file was deleted, the sidecars were not, and a stale `-wal`
 * beside a database is the exact hazard the shared family exists to prevent. The case below is that
 * family, with real files, and it fails if the bare names ever come back.
 *
 * Robolectric only because the janitor resolves the logger singleton when it is touched; no database is
 * opened and nothing here depends on SQLCipher - the subject is file deletion.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseArtifactJanitorCorruptFamilyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun `a corrupt family is deleted with its real sidecars, and every shared suffix is covered`() {
        val dir = tempFolder.newFolder("db")
        val candidate = File(dir, "payanam.db.corrupt-20261004").apply { writeText("corrupt") }
        // The sidecars the deletion must find: the shared suffixes on the candidate's own name.
        val sidecars = DatabaseArtifacts.sidecarSuffixes.map { File(dir, candidate.name + it) }
        sidecars.forEach { it.writeText("sidecar") }
        // A decoy the OLD behaviour would have matched - the bare-name concatenation - which must NOT be
        // the thing this function looks for.
        val bareNameDecoy = File(dir, candidate.name + "wal").apply { writeText("decoy") }

        DatabaseArtifactJanitor.deleteCorruptFamily(candidate)

        assertThat(candidate.exists()).isFalse()
        for (sidecar in sidecars) {
            assertWithMessage("${sidecar.name} must be deleted with the family")
                .that(sidecar.exists())
                .isFalse()
        }
        // The decoy is untouched: proof the family comes from the shared suffixes, not from a name shape
        // that happens to end in "wal".
        assertThat(bareNameDecoy.exists()).isTrue()
    }

    @Test
    fun `a family whose sidecars are absent is handled without error`() {
        val dir = tempFolder.newFolder("db2")
        val candidate = File(dir, "payanam.db.corrupt-20261004").apply { writeText("corrupt") }

        DatabaseArtifactJanitor.deleteCorruptFamily(candidate)

        assertThat(candidate.exists()).isFalse()
    }
}
