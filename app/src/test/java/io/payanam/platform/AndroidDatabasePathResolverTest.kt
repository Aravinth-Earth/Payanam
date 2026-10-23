//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests for Android's DatabasePathResolver implementation.
 *
 * What is asserted is the port's contract as the platform supplies it: the file is the database file
 * the platform really opens (named by [PayanamDatabaseSchema.DATABASE_NAME], not restated), the directory is
 * the parent of that very file rather than an independent guess, and both are absolute — the shared
 * lifecycle screens treat these strings as complete paths, never as fragments to join.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidDatabasePathResolverTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the file path is the platform's database file`() {
        val resolver = AndroidDatabasePathResolver(context)

        assertThat(resolver.databaseFilePath()).endsWith(PayanamDatabaseSchema.DATABASE_NAME)
    }

    @Test
    fun `the directory is the file's parent and both paths are absolute`() {
        val resolver = AndroidDatabasePathResolver(context)
        val filePath = File(resolver.databaseFilePath())
        val directory = File(resolver.databaseDirectory())

        assertThat(resolver.databaseDirectory()).isEqualTo(filePath.parent)
        assertThat(filePath.isAbsolute).isTrue()
        assertThat(directory.isAbsolute).isTrue()
    }
}
