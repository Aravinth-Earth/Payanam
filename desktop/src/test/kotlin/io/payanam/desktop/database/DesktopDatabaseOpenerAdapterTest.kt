//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.refusalIssue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the desktop's database-opener adapter.
 *
 * The success path needs a real encrypted database, which the JDBC module's own tests already cover
 * end-to-end; what belongs HERE is the adapter's own two responsibilities: that it translates a path that
 * cannot be opened into the shared refusal rather than throwing, and that it never claims a passphrase
 * verdict it did not earn. A missing file is the interesting case for the second, because a naive adapter
 * returns `false` from both methods and the caller cannot tell "wrong passphrase" from "no file" — the
 * contract says a missing file is not a passphrase verdict.
 */
class DesktopDatabaseOpenerAdapterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val opener = DesktopDatabaseOpenerAdapter()

    @Test
    fun `a missing file is refused rather than throwing`(): Unit =
        runBlocking {
            val missing =
                tempFolder.root
                    .toPath()
                    .resolve("absent.db")
                    .toString()

            val outcome = opener.open(missing, "Str0ng-Passphrase!".toCharArray())

            assertThat(outcome.refusalIssue()?.type).isEqualTo(DatabaseBootIssueType.OPEN_FAILED)
        }

    @Test
    fun `a missing file does not verify as a passphrase verdict`(): Unit =
        runBlocking {
            val missing =
                tempFolder.root
                    .toPath()
                    .resolve("absent.db")
                    .toString()

            assertThat(opener.verifyPassphrase(missing, "Str0ng-Passphrase!".toCharArray())).isFalse()
        }

    @Test
    fun `a directory is refused rather than opened`(): Unit =
        runBlocking {
            val directory = tempFolder.newFolder("not-a-database").absolutePath

            val outcome = opener.open(directory, "Str0ng-Passphrase!".toCharArray())

            // Opening a directory must not be reported as an opened database, whatever the driver does with
            // it: the contract's Refused case exists precisely so this cannot look like success.
            assertThat(outcome.refusalIssue()).isNotNull()
        }
}
