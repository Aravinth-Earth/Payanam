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
/**
 * DatabaseInitDimensionSetupTest.
 */
class DatabaseInitDimensionSetupTest {
    private lateinit var context: Context

    @Before
    /**
     * Setup.
     */
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
    }

    private fun testLogger(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    private fun mockDimensionLabelContext(currentLocaleTag: String = "ta"): Context {
        /** English strings. */
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
        /** Tamil strings. */
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

        /**
         * Context for.
         */
        fun contextFor(strings: Map<Int, String>, localeTag: String): Context {
            /** Locale configuration. */
            val localeConfiguration = Configuration().apply { setLocale(Locale.forLanguageTag(localeTag)) }
            /** Resources. */
            val resources = mock<Resources>()
            /** Whenever. */
            whenever(resources.configuration).thenReturn(localeConfiguration)
            /** Localized context. */
            val localizedContext = mock<Context>()
            /** Whenever. */
            whenever(localizedContext.resources).thenReturn(resources)
            strings.forEach { (resId, value) ->
                /** Whenever. */
                whenever(localizedContext.getString(resId)).thenReturn(value)
            }
            return localizedContext
        }

        /** Current strings. */
        val currentStrings = if (currentLocaleTag == "ta") tamilStrings else englishStrings
        /** Base resources. */
        val baseResources = mock<Resources>()
        /** Base configuration. */
        val baseConfiguration = Configuration().apply { setLocale(Locale.forLanguageTag(currentLocaleTag)) }
        /** Whenever. */
        whenever(baseResources.configuration).thenReturn(baseConfiguration)
        /** Base context. */
        val baseContext = mock<Context>()
        /** Whenever. */
        whenever(baseContext.resources).thenReturn(baseResources)
        currentStrings.forEach { (resId, value) ->
            /** Whenever. */
            whenever(baseContext.getString(resId)).thenReturn(value)
        }

        /** English context. */
        val englishContext = contextFor(englishStrings, "en")
        /** Tamil context. */
        val tamilContext = contextFor(tamilStrings, "ta")
        /** Whenever. */
        whenever(baseContext.createConfigurationContext(any())).thenAnswer { invocation ->
            /** Requested config. */
            val requestedConfig = invocation.getArgument<Configuration>(0)
            /** Locale. */
            val locale = requestedConfig.locales[0]?.language ?: "en"
            /** If. */
            if (locale == "ta") tamilContext else englishContext
        }
        return baseContext
    }

    @Test
    fun `buildDimensionSeedRows keeps defaults when no custom input is provided`() {
        /** Test logger. */
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying default rows")
        /** Rows. */
        val rows = buildDimensionSeedRows(emptyList())

        /** Assert equals. */
        assertEquals(1, rows.size)
        /** Assert true. */
        assertTrue(rows.first { it.id == "dim_unassigned" }.isActive)
    }

    @Test
    fun `buildDimensionSeedRows applies custom labels and enabled state for user dimensions`() {
        /** Test logger. */
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying custom labels and colors")
        /** Rows. */
        val rows = buildDimensionSeedRows(
            /** List of. */
            listOf(
                /** New database dimension input. */
                NewDatabaseDimensionInput(
                    id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
                    label = "Deep Work",
                    colorHex = "#123456",
                    isEnabled = true,
                ),
                /** New database dimension input. */
                NewDatabaseDimensionInput(
                    id = DimensionTaxonomyCatalog.RECREATION_LEISURE.id,
                    label = "Fun Time",
                    colorHex = "#654321",
                    isEnabled = false,
                ),
            ),
        )

        /** Assert equals. */
        assertEquals("Deep Work", rows.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.label)
        /** Assert equals. */
        assertEquals("#123456", rows.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.color)
        /** Assert true. */
        assertTrue(rows.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.isActive)
        /** Assert equals. */
        assertEquals("Fun Time", rows.first { it.id == DimensionTaxonomyCatalog.RECREATION_LEISURE.id }.label)
        /** Assert equals. */
        assertEquals("#654321", rows.first { it.id == DimensionTaxonomyCatalog.RECREATION_LEISURE.id }.color)
        /** Assert false. */
        assertFalse(rows.first { it.id == DimensionTaxonomyCatalog.RECREATION_LEISURE.id }.isActive)
        /** Assert true. */
        assertTrue(rows.first { it.id == "dim_unassigned" }.isActive)
    }

    @Test
    fun `defaultNewDatabaseDimensionInputs uses localized app-owned labels`() {
        /** Test logger. */
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying localized default dimension labels")

        /** Tamil defaults. */
        val tamilDefaults = defaultNewDatabaseDimensionInputs(mockDimensionLabelContext())

        /** Assert equals. */
        assertEquals(
            "வேலை & வாழ்வாதாரம்",
            tamilDefaults.first { it.id == DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id }.label,
        )
        /** Assert equals. */
        assertEquals(
            "வீடு & சூழல்",
            tamilDefaults.first { it.id == DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id }.label,
        )
    }

    @Test
    fun `persistNewDatabaseDimensionSetup writes all NOT NULL columns including weight`() {
        /** Test logger. */
        testLogger()?.d("DatabaseInitDimensionSetupTest", "Verifying seed insert against real Room schema")
        /** Db. */
        val db =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        try {
            /** Session manager. */
            val sessionManager = mock<DatabaseSessionManager>()
            /** Whenever. */
            whenever(sessionManager.requireDatabase()).thenReturn(db)
            /** Settings repository. */
            val settingsRepository = mock<AppSettingsRepository>()
            runBlocking {
                /** Whenever. */
                whenever(settingsRepository.setSetting(any(), any())).thenReturn(Unit)

                /** Persist new database dimension setup. */
                persistNewDatabaseDimensionSetup(
                    context = mockDimensionLabelContext(),
                    databaseSessionManager = sessionManager,
                    appSettingsRepository = settingsRepository,
                    dimensionInputs = defaultNewDatabaseDimensionInputs(mockDimensionLabelContext()),
                )
            }

            /** Rows. */
            val rows =
                db.query(
                    "SELECT id, key, label, color, icon, sortOrder, isActive, weight FROM life_dimensions",
                    /** Null. */
                    null,
                )
            try {
                /** Assert true. */
                assertTrue(rows.moveToFirst())
                /** Assert true. */
                assertTrue(rows.count > 0)
                /** Weight column. */
                val weightColumn = rows.getColumnIndexOrThrow("weight")
                do {
                    /** Assert equals. */
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
