//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the database file guard: the one place an in-app delete of the production database (or its
 * sidecars) is allowed to happen.
 *
 * The guard's whole value is traceability, so the tests assert the LOG, not only the boolean: a delete that
 * succeeds silently is the failure mode the guard exists to prevent. Two of the cases run in both
 * directions on purpose - a non-primary file must not claim a database authorization, and a primary one must
 * - because either half alone would pass an implementation that logs everything or nothing.
 */
class DatabaseFileGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class RecordingLogger : UnifiedLogger {
        val infos = mutableListOf<Triple<String, String, Map<String, Any?>>>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            infos += Triple(source, message, data)
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit
    }

    private val logger = RecordingLogger()

    @Before
    fun installLogger() {
        // Reset first, then install: test classes in this module share one JVM, so the reset stops an
        // assertion about a real logger from passing on a logger leaked out of an earlier class.
        AppLogger.resetForTests()
        AppLogger.install(logger)
    }

    @After
    fun resetLogger() {
        AppLogger.resetForTests()
    }

    @Test
    fun `deletes an existing file and reports success`() {
        val file = temporaryFolder.newFile("unrelated-artifact.bin")

        assertThat(DatabaseFileGuard.safeDelete(file, DatabaseFileGuard.DeleteIntent.JANITOR_CLEANUP, CALLER))
            .isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `reports success for a file that is already gone`() {
        val file = File(temporaryFolder.root, "never-there.bin")

        assertThat(DatabaseFileGuard.safeDelete(file, DatabaseFileGuard.DeleteIntent.JANITOR_CLEANUP, CALLER))
            .isTrue()
    }

    @Test
    fun `traces a primary database deletion with intent and caller`() {
        val file = temporaryFolder.newFile(PayanamDatabaseSchema.DATABASE_NAME)
        file.writeBytes(ByteArray(16))
        val sizeBefore = file.length()

        assertThat(DatabaseFileGuard.safeDelete(file, DatabaseFileGuard.DeleteIntent.USER_DELETE_ALL, CALLER))
            .isTrue()

        val (source, message, data) = logger.infos.single()
        assertThat(source).isEqualTo("DatabaseFileGuard.safeDelete")
        assertThat(message).isEqualTo("Primary DB file deletion authorized")
        assertThat(data["file"]).isEqualTo(PayanamDatabaseSchema.DATABASE_NAME)
        assertThat(data["intent"]).isEqualTo("USER_DELETE_ALL")
        assertThat(data["caller"]).isEqualTo(CALLER)
        assertThat(data["existed"]).isEqualTo(true)
        // The size is read before the delete, so the log records what was actually removed.
        assertThat(data["sizeBytes"]).isEqualTo(sizeBefore)
    }

    @Test
    fun `a file outside the database family does not claim a database authorization`() {
        val file = temporaryFolder.newFile("settings-backup.bin")

        DatabaseFileGuard.safeDelete(file, DatabaseFileGuard.DeleteIntent.ADMIN_ARTIFACT_CLEANUP, CALLER)

        assertThat(logger.infos).isEmpty()
    }

    @Test
    fun `every sidecar of the database family counts as a primary file`() {
        // The family rule, not a single filename: a guard that only recognised the main .db would delete the
        // WAL of a live database without tracing it.
        DatabaseArtifacts.artifactSuffixes.forEach { suffix ->
            val file = temporaryFolder.newFile("${PayanamDatabaseSchema.DATABASE_NAME}$suffix")
            DatabaseFileGuard.safeDelete(file, DatabaseFileGuard.DeleteIntent.IMPORT_REPLACE, CALLER)
        }

        assertThat(logger.infos.size).isEqualTo(DatabaseArtifacts.artifactSuffixes.size)
        assertThat(logger.infos.map { it.second }.toSet())
            .containsExactly("Primary DB file deletion authorized")
        assertThat(logger.infos.map { it.third["intent"] }.toSet()).containsExactly("IMPORT_REPLACE")
    }

    @Test
    fun `a forgotten-passphrase reset is traced as its own intent, not as a user delete-all`() {
        // The two are different events: a user asked the app to forget a credential, and this is what the
        // app did about it. A log that called it a wipe would misattribute a recovery to a deletion.
        val file = temporaryFolder.newFile(PayanamDatabaseSchema.DATABASE_NAME)

        DatabaseFileGuard.safeDelete(
            file,
            DatabaseFileGuard.DeleteIntent.FORGOT_PASSPHRASE_RESET,
            CALLER,
        )

        val (_, _, data) = logger.infos.single()
        assertThat(data["intent"]).isEqualTo("FORGOT_PASSPHRASE_RESET")
        assertThat(DatabaseFileGuard.DeleteIntent.FORGOT_PASSPHRASE_RESET)
            .isNotEqualTo(DatabaseFileGuard.DeleteIntent.USER_DELETE_ALL)
    }

    @Test
    fun `deletes a directory tree and traces the intent`() {
        val dir = temporaryFolder.newFolder("artifacts")
        File(dir, "nested").mkdirs()
        File(dir, "nested/child.bin").writeBytes(ByteArray(4))

        assertThat(DatabaseFileGuard.safeDeleteDir(dir, DatabaseFileGuard.DeleteIntent.JANITOR_CLEANUP, CALLER))
            .isTrue()

        assertThat(dir.exists()).isFalse()
        val (source, message, data) = logger.infos.single()
        assertThat(source).isEqualTo("DatabaseFileGuard.safeDeleteDir")
        assertThat(message).isEqualTo("Directory deletion authorized")
        assertThat(data["dir"]).isEqualTo("artifacts")
        assertThat(data["existed"]).isEqualTo(true)
    }

    @Test
    fun `reports success for a directory that is already gone`() {
        val dir = File(temporaryFolder.root, "not-created")

        assertThat(DatabaseFileGuard.safeDeleteDir(dir, DatabaseFileGuard.DeleteIntent.JANITOR_CLEANUP, CALLER))
            .isTrue()

        assertThat(logger.infos.single().third["existed"]).isEqualTo(false)
    }

    private companion object {
        const val CALLER = "DatabaseFileGuardTest"
    }
}
