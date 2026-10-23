//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.common.util.PersistedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Properties

/**
 * Tests for [NotificationRepositoryJvm] — the first write-path repository.
 *
 * Beyond the read/mapping behaviour, this suite answers the question a write path raises and a read
 * path does not: is the write actually durable when the call returns? `autoCommit` is `true` on this
 * driver (measured), so it should be, but that is the driver's default rather than a property of the
 * SQL — so [writesAreDurableAcrossAReopenedConnection] writes through this session and reads the row
 * back through a SECOND connection. A write left inside an uncommitted transaction would not be found.
 *
 * The session is built directly from an open connection rather than through [PayanamDatabaseOpener.open]
 * (which correctly refuses a version-0 database, and no driver route can write a version). The opener's
 * own guards are covered in [PayanamDatabaseOpenerTest].
 */
class NotificationRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "notification-test-passphrase"
    private lateinit var databasePath: Path
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        databasePath = tempFolder.newFolder().toPath().resolve("notifications.db")
        connection = openConnection()
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE scheduled_notifications (
                    id TEXT NOT NULL PRIMARY KEY,
                    taskId TEXT NOT NULL,
                    scheduledAt TEXT NOT NULL,
                    notificationType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    isDelivered INTEGER NOT NULL DEFAULT 0,
                    createdAt TEXT NOT NULL
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

    private fun openConnection(): Connection {
        val properties = Properties().apply { setProperty("key", passphrase) }
        return DriverManager.getConnection("jdbc:sqlcipher:${databasePath.toAbsolutePath()}", properties)
    }

    private fun repository() = NotificationRepositoryJvm(session)

    private fun at(hoursFromNow: Long): LocalDateTime = LocalDateTime.now().plusHours(hoursFromNow)

    @Test
    fun schedulingReturnsAnIdAndTheRowIsReadable() =
        runBlocking {
            val id =
                repository().scheduleNotification(
                    taskId = "task-1",
                    scheduledAt = at(1),
                    notificationType = "REMINDER",
                    title = "Stand up",
                    body = "Time to move",
                )

            assertThat(id).isNotEmpty()
            val stored = repository().getNotificationsForTask("task-1").single()
            assertThat(stored.id).isEqualTo(id)
            assertThat(stored.taskId).isEqualTo("task-1")
            assertThat(stored.notificationType).isEqualTo("REMINDER")
            assertThat(stored.title).isEqualTo("Stand up")
            assertThat(stored.body).isEqualTo("Time to move")
            // Stored as Int 0/1, exposed as Boolean — the conversion is the thing being checked.
            assertThat(stored.isDelivered).isFalse()
            Unit
        }

    @Test
    fun pendingExcludesDeliveredAndAlreadyPastNotifications() =
        runBlocking {
            val future = repository().scheduleNotification("task-1", at(2), "REMINDER", "t", "b")
            repository().scheduleNotification("task-1", at(-2), "REMINDER", "past", "b")
            val delivered = repository().scheduleNotification("task-1", at(3), "REMINDER", "t", "b")
            repository().markDelivered(delivered)

            val pending = repository().getPendingNotifications()

            // Strictly-after-now AND undelivered: the past row and the delivered row are both excluded.
            assertThat(pending.map { it.id }).containsExactly(future)
            Unit
        }

    @Test
    fun pendingIsOrderedAscendingBySchedule() =
        runBlocking {
            repository().scheduleNotification("task-1", at(5), "REMINDER", "later", "b")
            repository().scheduleNotification("task-1", at(1), "REMINDER", "sooner", "b")
            repository().scheduleNotification("task-1", at(3), "REMINDER", "middle", "b")

            val pending = repository().getPendingNotifications()

            assertThat(pending.map { it.title }).containsExactly("sooner", "middle", "later").inOrder()
            Unit
        }

    @Test
    fun markDeliveredIsIdempotent() =
        runBlocking {
            val id = repository().scheduleNotification("task-1", at(1), "REMINDER", "t", "b")

            repository().markDelivered(id)
            repository().markDelivered(id)

            assertThat(repository().getPendingNotifications()).isEmpty()
            // Marking delivered must not delete the row.
            assertThat(repository().getNotificationsForTask("task-1")).hasSize(1)
            Unit
        }

    @Test
    fun cancellingScopesToOneTaskOrOneId() =
        runBlocking {
            repository().scheduleNotification("task-1", at(1), "REMINDER", "a", "b")
            repository().scheduleNotification("task-1", at(2), "REMINDER", "b", "b")
            val kept = repository().scheduleNotification("task-2", at(3), "REMINDER", "c", "b")

            repository().cancelNotificationsForTask("task-1")

            assertThat(repository().getNotificationsForTask("task-1")).isEmpty()
            assertThat(repository().getNotificationsForTask("task-2").map { it.id })
                .containsExactly(kept)

            repository().cancelNotification(kept)
            assertThat(repository().getNotificationsForTask("task-2")).isEmpty()
            Unit
        }

    @Test
    fun schedulingRoundTripsTheScheduledInstant() =
        runBlocking {
            // scheduledAt goes out through PersistedDateTime.format and comes back through parse. A
            // lossy format would still read back as a LocalDateTime, so the instant is compared.
            val scheduled = LocalDateTime.now().plusHours(6).withNano(0)
            repository().scheduleNotification("task-1", scheduled, "REMINDER", "t", "b")

            val stored = repository().getNotificationsForTask("task-1").single()

            assertThat(PersistedDateTime.format(stored.scheduledAt))
                .isEqualTo(PersistedDateTime.format(scheduled))
            Unit
        }

    @Test
    fun writesAreDurableAcrossAReopenedConnection() =
        runBlocking {
            val id = repository().scheduleNotification("task-1", at(1), "REMINDER", "durable", "b")

            // The point of the test: a SECOND connection sees the row. If the write had been left in an
            // uncommitted transaction on the first connection, this read would find nothing.
            openConnection().use { second ->
                second.prepareStatement("SELECT title FROM scheduled_notifications WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { resultSet ->
                        assertThat(resultSet.next()).isTrue()
                        assertThat(resultSet.getString(1)).isEqualTo("durable")
                    }
                }
            }
            Unit
        }
}
