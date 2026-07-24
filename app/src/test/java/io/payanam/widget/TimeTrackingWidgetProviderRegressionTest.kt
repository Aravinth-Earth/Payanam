//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TimeTrackingWidgetProviderRegressionTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `requestUpdate emits widget refresh broadcast`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        TimeTrackingWidgetProvider.requestUpdate(context)

        val broadcastActions = shadowOf(context).broadcastIntents.map { it.action.orEmpty() }
        logger.i(
            "TimeTrackingWidgetProviderRegressionTest",
            "Captured broadcast actions",
            mapOf(
                "count" to broadcastActions.size,
            ),
        )
        assertTrue(
            "Expected widget update broadcast after provider enabled",
            broadcastActions.contains(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
        )
    }

    @Test
    fun `resolveWidgetDimensionLabel falls back to app-owned built-in label`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dimension = ConfiguredLifeDimension(
            id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            key = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.slug,
            label = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel,
            description = null,
            colorHex = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultColorHex,
            iconKey = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultIconKey,
            sortOrder = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.sortOrder,
            isActive = true,
        )

        val label = resolveWidgetDimensionLabel(
            context = context,
            dimension = dimension,
            canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            languageTag = "en",
        )

        assertEquals("Work & Livelihood", label)
    }

    @Test
    fun `resolveWidgetDimensionLabel preserves custom label`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dimension = ConfiguredLifeDimension(
            id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            key = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.slug,
            label = "Deep Work",
            description = null,
            colorHex = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultColorHex,
            iconKey = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultIconKey,
            sortOrder = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.sortOrder,
            isActive = true,
        )

        val label = resolveWidgetDimensionLabel(
            context = context,
            dimension = dimension,
            canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            languageTag = "ta",
        )

        assertEquals("Deep Work", label)
    }
}
