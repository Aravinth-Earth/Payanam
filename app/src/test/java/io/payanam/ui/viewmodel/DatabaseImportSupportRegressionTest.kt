//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The single-file import gate: any `.db` name is accepted (the picker is launched with a wildcard
 * MIME filter, so the extension rule is what keeps wrong picks out), and SQLite companion files
 * never are.
 *
 * Robolectric only to initialize [UnifiedLogger]: `DatabaseImportSupport` is an object whose
 * initializer calls `UnifiedLogger.getInstance()`, which throws on a JVM that has not initialized
 * the logger.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseImportSupportRegressionTest {
    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun isImportableDatabaseFileName_acceptsAnyDbName() {
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db")).isTrue()
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("renamed-export.db")).isTrue()
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("backup 2026-09-12.DB")).isTrue()
    }

    @Test
    fun isImportableDatabaseFileName_rejectsCompanionsAndOtherExtensions() {
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db-wal")).isFalse()
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db-shm")).isFalse()
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("payanam")).isFalse()
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("notes.txt")).isFalse()
        assertThat(DatabaseImportSupport.isImportableDatabaseFileName("notes.db.bak")).isFalse()
    }
}
