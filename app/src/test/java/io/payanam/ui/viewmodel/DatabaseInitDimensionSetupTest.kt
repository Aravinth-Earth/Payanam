//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.AppSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DatabaseInitDimensionSetupTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
    }

    private fun testLogger(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    private fun mockDimensionLabelContext(currentLocaleTag: String = "ta"): Context {
        val englishStrings = mapOf(
            R.string.loc_dimension_name_work_livelihood to "Work & Livelihood",
            R.string.loc_dimension_name_physical_health to "Physical Health",
            R.string.loc_dimension_name_mental_health to "Mental Health",
            R.string.loc_dimension_name_family_relationships to "Family & Relationships",
            R.string.loc_dimension_name_home_environment to "Home & Environment",
            R.string.loc_dimension_name_learning_growth to "Learning & Growth",
            R.string.loc_dimension_name_money_finance to "Money & Finance",
            R.string.loc_dimension_name_recreation_leisure to "Recreation & Leisure",
            R.string.loc_dimension_name_community_service to "Community & Service",
            R.string.loc_dimension_fallback_unassigned to "Unassigned",
        )
        val tamilStrings = mapOf(
            R.string.loc_dimension_name_work_livelihood to "வேலை & வாழ்வாதாரம்",
            R.string.loc_dimension_name_physical_health to "உடல் ஆரோக்கியம்",
            R.string.loc_dimension_name_mental_health to "மனநலம்",
            R.string.loc_dimension_name_family_relationships to "குடும்பம் & உறவுகள்",
            R.string.loc_dimension_name_home_environment to "வீடு & சூழல்",
            R.string.loc_dimension_name_learning_growth to "கற்றல் & வளர்ச்சி",
            R.string.loc_dimension_name_money_finance to "பணம் & நிதி",
            R.string.loc_dimension_name_recreation_leisure to "பொழுதுபோக்கு & ஓய்வு",
            R.string.loc_dimension_name_community_service to "சமூகம் & சேவை",
            R.string.loc_dimension_fallback_unassigned to "ஒதுக்கப்படாதது",
        )

        fun contextFor(strings: Map<Int, String>, localeTag: String): Context {
            val localeConfiguration = Configuration().apply { setLocale(Locale.forLanguageTag(localeTag)) }
            val resources = mock<Resources>()
            whenever(resources.configuration).thenReturn(localeConfiguration)
            val localizedContext = mock<Context>()
            whenever(localizedContext.resources).thenReturn(resources)
            strings.forEach { (resId, value) ->
                whenever(localizedContext.getString(resId)).thenReturn(value)
            }
            return localizedContext
        }

        val currentStrings = if (currentLocaleTag == "ta") tamilStrings else englishStrings
        val baseResources = mock<Resources>()
        val baseConfiguration = Configuration().apply { setLocale(Locale.forLanguageTag(currentLocaleTag)) }
        whenever(baseResources.configuration).thenReturn(baseConfiguration)
        val baseContext = mock<Context>()
        whenever(baseContext.resources).thenReturn(baseResources)
        currentStrings.forEach { (resId, value) ->
            whenever(baseContext.getString(resId)).thenReturn(value)
        }

        val englishContext = contextFor(englishStrings, "en")
        val tamilContext = contextFor(tamilStrings, "ta")
        whenever(baseContext.createConfigurationContext(any())).thenAnswer { invocation ->
            val requestedConfig = invocation.getArgument<Configuration>(0)
            val locale = requestedConfig.locales[0]?.language ?: "en"
            if (locale == "ta") tamilContext else englishContext
        }
        return baseContext
    }

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

        val tamilDefaults = defaultNewDatabaseDimensionInputs(mockDimensionLabelContext())

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
    fun `persistNewDatabaseDimensionSetup writes all NOT NULL columns including weight`() {
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying seed insert against real Room schema")
        val db =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        try {
            val sessionManager = mock<DatabaseSessionManager>()
            whenever(sessionManager.requireDatabase()).thenReturn(db)
            val settingsRepository = mock<AppSettingsRepository>()
            runBlocking {
                whenever(settingsRepository.setSetting(any(), any())).thenReturn(Unit)

                persistNewDatabaseDimensionSetup(
                    context = mockDimensionLabelContext(),
                    databaseSessionManager = sessionManager,
                    appSettingsRepository = settingsRepository,
                    dimensionInputs = defaultNewDatabaseDimensionInputs(mockDimensionLabelContext()),
                )
            }

            val rows =
                db.query(
                    "SELECT id, key, label, color, icon, sortOrder, isActive, weight FROM life_dimensions",
                    null,
                )
            try {
                assertTrue(rows.moveToFirst())
                assertTrue(rows.count > 0)
                val weightColumn = rows.getColumnIndexOrThrow("weight")
                do {
                    assertEquals(1.0, rows.getDouble(weightColumn), 0.0)
                } while (rows.moveToNext())
            } finally {
                rows.close()
            }
        } finally {
            db.close()
        }
    }
}
