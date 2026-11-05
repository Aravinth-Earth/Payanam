//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.session

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.refusalIssue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the Android side of the shared database-opener contract.
 *
 * The two Android-specific rules are what these assert, because they are the ones a reader would otherwise
 * assume away: read-only is refused rather than approximated as read-write, and the passphrase verifier
 * answers without consulting the file it is handed, since Android has exactly one database and its verifier
 * is a hash comparison.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidDatabaseOpenerTest {
    private fun opener(): AndroidDatabaseOpener {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AndroidDatabaseOpener(
            sessionManager = DatabaseSessionManager(context, DatabaseEncryptionManager(context)),
            context = context,
        )
    }

    @Test
    fun `read-only is refused, never approximated as read-write`(): Unit =
        runBlocking {
            // The caller is relying on read-only to avoid mutating what it inspects, so opening read-write
            // behind its back would be worse than refusing.
            val outcome = opener().open("/data/whatever/payanam.db", "Str0ng-Passphrase!".toCharArray(), readOnly = true)

            val issue = outcome.refusalIssue()
            assertThat(issue?.type).isEqualTo(DatabaseBootIssueType.OPEN_FAILED)
            assertThat(issue?.detailMessage).contains("read_only")
        }

    @Test
    fun `a fresh install has no passphrase to verify`(): Unit =
        runBlocking {
            val result = opener().verifyPassphrase("/data/whatever/payanam.db", "Str0ng-Passphrase!".toCharArray())

            assertThat(result).isFalse()
        }
}
