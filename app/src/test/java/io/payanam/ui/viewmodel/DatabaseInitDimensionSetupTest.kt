//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.presentation.database.NewDatabaseDimensionInput
import io.payanam.core.ui.database.buildDimensionSeedRows
import io.payanam.core.ui.database.defaultNewDatabaseDimensionInputs
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseInitDimensionSetupTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
    }

    private fun testLogger(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()


    @Test
    fun `buildDimensionSeedRows keeps defaults when no custom input is provided`() {
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying default rows")
        val rows = buildDimensionSeedRows(emptyList())
        assertEquals(1, rows.size)
        assertTrue(rows.first { it.id == "dim_unassigned" }.isActive)
    }

    @Test
    fun `buildDimensionSeedRows applies custom labels and enabled state for user dimensions`() {
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying custom labels and colors")
        val rows = buildDimensionSeedRows(
            listOf(
                NewDatabaseDimensionInput(
                    id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
                    label = "Deep Work",
                    colorHex = "#123456",
                    isEnabled = true,
                ),
                NewDatabaseDimensionInput(
                    id = DimensionTaxonomyCatalog.RECREATION_LEISURE.id,
                    label = "Fun Time",
                    colorHex = "#654321",
                    isEnabled = false,
                ),
            ),
        )
        assertEquals("Deep Work", rows.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.label)
        assertEquals("#123456", rows.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.color)
        assertTrue(rows.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.isActive)
        assertEquals("Fun Time", rows.first { it.id == DimensionTaxonomyCatalog.RECREATION_LEISURE.id }.label)
        assertEquals("#654321", rows.first { it.id == DimensionTaxonomyCatalog.RECREATION_LEISURE.id }.color)
        assertFalse(rows.first { it.id == DimensionTaxonomyCatalog.RECREATION_LEISURE.id }.isActive)
        assertTrue(rows.first { it.id == "dim_unassigned" }.isActive)
    }

    @Test
    fun `defaultNewDatabaseDimensionInputs uses localized app-owned labels`() {
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying localized default dimension labels")
        val tamilDefaults = defaultNewDatabaseDimensionInputs(uiStringsFor("ta"))
        assertEquals(
            "வேலை & வாழ்வாதாரம்",
            tamilDefaults.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.label,
        )
        assertEquals(
            "வீடு & சூழல்",
            tamilDefaults.first { it.id == DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id }.label,
        )
    }

    @Test
    fun `persistNewDatabaseDimensionSetup replaces the catalog and seeds every dimension at weight one`() {
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying the catalog replace")
        val catalog = mock<LifeDimensionCatalogRepository>()
        val settingsRepository = mock<AppSettingsRepository>()
        val captured = mutableListOf<ConfiguredLifeDimension>()
        val completedSetting = arrayOfNulls<Pair<String, String>>(1)

        runBlocking {
            whenever(settingsRepository.setSetting(any(), any())).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                completedSetting[0] =
                    (invocation.getArgument<String>(0) to invocation.getArgument<String>(1))
                Unit
            }
            whenever(
                catalog.replaceAllDimensions(any()),
            ).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val rows = invocation.getArgument<List<ConfiguredLifeDimension>>(0)
                captured.addAll(rows)
                rows.size
            }

            persistNewDatabaseDimensionSetup(
                lifeDimensionCatalogRepository = catalog,
                appSettingsRepository = settingsRepository,
                dimensionInputs = defaultNewDatabaseDimensionInputs(uiStringsFor("ta")),
            )
        }

        // The seed is written through the shared atomic replace, not raw SQL on the session. The rows are
        // captured rather than read back from a database because that is now the repository's own
        // contract, already covered by LifeDimensionCatalogRepositoryJvmTest against a real SQLCipher
        // file; what this test owns is the SEED's shape.
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.all { it.weight == 1.0 })
        assertTrue(captured.any { it.id == "dim_unassigned" })
        assertTrue(captured.all { it.label.isNotBlank() })
        // Every dimension starts active-equivalent: a fresh database must not silently score nothing.
        assertTrue(captured.any { it.isActive })
        // The seed must mark DB init complete, or the gate is shown again on the next launch.
        assertEquals("database_init_completed" to "true", completedSetting[0])
    }
}
