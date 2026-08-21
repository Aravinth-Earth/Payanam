//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PAYANAM_DATABASE_SCHEMA_VERSION
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the contracted room schema support floor for shipped beta users.
 */
@RunWith(AndroidJUnit4::class)
/**
 * Provides the database migration safety test.
 */
class DatabaseMigrationSafetyTest {
    private val logger = UnifiedLogger.getInstance()
    private lateinit var context: Context

    @Before
    /**
     * Updates the set up.
     */
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "android-test", 0)
        }
    }

    @Test
    /**
     * Performs the supported schema floor is locked to closed beta baseline.
     */
    fun supported_schema_floor_is_locked_to_closed_beta_baseline() {
        logger.i(
            "DatabaseMigrationSafetyTest.supported_schema_floor_is_locked_to_closed_beta_baseline",
            "Validating schema floor",
            mapOf(
                "minimumSupported" to DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
                "currentSchema" to PAYANAM_DATABASE_SCHEMA_VERSION,
            ),
        )
        assertThat(DatabaseHealthChecker.MIN_MIGRATABLE_VERSION).isEqualTo(16)
        assertThat(PAYANAM_DATABASE_SCHEMA_VERSION).isAtLeast(DatabaseHealthChecker.MIN_MIGRATABLE_VERSION)
    }

    @Test
    /**
     * Only supported schema snapshot is retained for current release.
     */
    fun only_supported_schema_snapshot_is_retained_for_current_release() {
        val schemaAssetPath = "$SCHEMA_ASSET_DIR/${PAYANAM_DATABASE_SCHEMA_VERSION}.json"
        context.assets.open(schemaAssetPath).use { stream ->
            assertThat(stream.available()).isGreaterThan(0)
        }
        val unsupportedSnapshot =
            runCatching {
                context.assets.open("$SCHEMA_ASSET_DIR/15.json").close()
            }
        assertThat(unsupportedSnapshot.isFailure).isTrue()
    }

    companion object {
        private const val SCHEMA_ASSET_DIR = "io.payanam.database.PayanamDatabase"
    }
}
