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
/**
 * TimeTrackingWidgetProviderRegressionTest.
 */
class TimeTrackingWidgetProviderRegressionTest {
    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `requestUpdate emits widget refresh broadcast`() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Application>()
        TimeTrackingWidgetProvider.requestUpdate(context)

        /** Broadcast actions. */
        val broadcastActions = shadowOf(context).broadcastIntents.map { it.action.orEmpty() }
        logger.i(
            "TimeTrackingWidgetProviderRegressionTest",
            "Captured broadcast actions",
            /** Map of. */
            mapOf(
                "count" to broadcastActions.size,
            ),
        )
        /** Assert true. */
        assertTrue(
            "Expected widget update broadcast after provider enabled",
            broadcastActions.contains(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
        )
    }

    @Test
    fun `resolveWidgetDimensionLabel falls back to app-owned built-in label`() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Application>()
        /** Dimension. */
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

        /** Label. */
        val label = resolveWidgetDimensionLabel(
            context = context,
            dimension = dimension,
            canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            languageTag = "en",
        )

        /** Assert equals. */
        assertEquals("Work & Livelihood", label)
    }

    @Test
    fun `resolveWidgetDimensionLabel preserves custom label`() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Application>()
        /** Dimension. */
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

        /** Label. */
        val label = resolveWidgetDimensionLabel(
            context = context,
            dimension = dimension,
            canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            languageTag = "ta",
        )

        /** Assert equals. */
        assertEquals("Deep Work", label)
    }
}
