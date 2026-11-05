//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.database.repository.DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY
import io.payanam.database.repository.buildLensDirtyDayMetadata
import io.payanam.database.repository.decodeLensDirtyDayMetadata
import io.payanam.database.repository.encodeLensDirtyDayMetadata
import io.payanam.database.repository.lensDirtyDayId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [LensDirtyDayInvalidationJvm] and the shared metadata format it writes.
 *
 * The format assertions matter as much as the JDBC ones: the payload lands in
 * `daily_insights.summary_json` in the file Android also writes, so a change to the encoding that the
 * Android side does not share would be a cross-platform read failure, not a cosmetic diff. The exact
 * encoded string is pinned here for that reason.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class LensDirtyDayInvalidationJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "lens-dirty-day-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("lens.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE daily_insights (
                    id TEXT PRIMARY KEY NOT NULL,
                    day_key TEXT NOT NULL,
                    module TEXT NOT NULL,
                    dimension_id TEXT,
                    planned_minutes INTEGER,
                    actual_minutes INTEGER,
                    focused_minutes INTEGER,
                    completed_count INTEGER,
                    total_count INTEGER,
                    summary_json TEXT,
                    generated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun rawSummary(dayKey: String): String? =
        connection
            .prepareStatement(
                "SELECT summary_json FROM daily_insights WHERE day_key = ? AND module = ?",
            ).use { statement ->
                statement.setString(1, dayKey)
                statement.setString(2, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString(1) else null
                }
            }

    private fun rowCount(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM daily_insights").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    @Test
    fun `the encoded payload is the exact on-disk format Android writes`() {
        val metadata =
            buildLensDirtyDayMetadata(
                dayKey = "2026-09-20",
                existingChangedModules = emptyList(),
                changedModules = setOf("task"),
                reason = "task_created",
                invalidatedAt = "2026-09-20T10:00:00",
            )

        assertThat(encodeLensDirtyDayMetadata(metadata)).isEqualTo(
            "dayKey=2026-09-20|changedModules=task|invalidatedAt=2026-09-20T10:00:00|reason=task_created",
        )
    }

    @Test
    fun `a pipe or newline in the reason cannot shift the following fields`() {
        val metadata =
            buildLensDirtyDayMetadata(
                dayKey = "2026-09-20",
                existingChangedModules = emptyList(),
                changedModules = setOf("time"),
                reason = "why|not\nnow",
                invalidatedAt = "2026-09-20T10:00:00",
            )

        val decoded = decodeLensDirtyDayMetadata("2026-09-20", encodeLensDirtyDayMetadata(metadata))

        assertThat(decoded.reason).isEqualTo("why/not now")
        assertThat(decoded.changedModules).containsExactly("time")
        assertThat(decoded.invalidatedAt).isEqualTo("2026-09-20T10:00:00")
    }

    @Test
    fun `marking a day persists one marker row and reads back`() =
        runBlocking {
            markLensDayDirty(session, "2026-09-20", setOf("task"), "task_created")

            assertThat(rowCount()).isEqualTo(1)
            val loaded = loadLensDirtyDayMetadata(session, "2026-09-20")
            assertThat(loaded?.changedModules).containsExactly("task")
            assertThat(loaded?.reason).isEqualTo("task_created")
            assertThat(rawSummary("2026-09-20")).startsWith("dayKey=2026-09-20|changedModules=task|")
        }

    @Test
    fun `a second mutation merges instead of replacing the module set`() =
        runBlocking {
            markLensDayDirty(session, "2026-09-20", setOf("task"), "task_created")
            markLensDayDirty(session, "2026-09-20", setOf("time"), "time_entry_added")

            assertThat(rowCount()).isEqualTo(1)
            val loaded = loadLensDirtyDayMetadata(session, "2026-09-20")
            assertThat(loaded?.changedModules).containsExactly("task", "time").inOrder()
            assertThat(loaded?.reason).isEqualTo("time_entry_added")
        }

    @Test
    fun `a day with no marker reads as clean, and clearing removes it`() =
        runBlocking {
            assertThat(loadLensDirtyDayMetadata(session, "2026-09-21")).isNull()

            markLensDayDirty(session, "2026-09-21", setOf("task"), "task_created")
            clearLensDayDirty(session, "2026-09-21")

            assertThat(loadLensDirtyDayMetadata(session, "2026-09-21")).isNull()
            assertThat(rowCount()).isEqualTo(0)
        }

    @Test
    fun `keys are resolved for a whole date range in one query`() =
        runBlocking {
            markLensDayDirty(session, "2026-09-20", setOf("task"), "task_created")
            markLensDayDirty(session, "2026-09-22", setOf("time"), "time_entry_added")

            val dirty = getLensDirtyDayKeys(session, setOf("2026-09-19", "2026-09-20", "2026-09-21", "2026-09-22"))

            assertThat(dirty).containsExactly("2026-09-20", "2026-09-22")
            assertThat(getLensDirtyDayKeys(session, emptySet())).isEmpty()
        }

    @Test
    fun `a marker row is keyed by day so a re-write cannot duplicate it`() =
        runBlocking {
            markLensDayDirty(session, "2026-09-20", setOf("task"), "first")
            markLensDayDirty(session, "2026-09-20", setOf("task"), "second")

            assertThat(rowCount()).isEqualTo(1)
            assertThat(lensDirtyDayId("2026-09-20")).isEqualTo("lens_dirty_2026-09-20")
        }
}
