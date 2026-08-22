//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.MainActivity
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.ui.model.DimensionTextCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject

/**
 * Time Tracking Widget Provider.
 *
 * Shows current active time entry or prompts to start tracking.
 * Default size: 2x1 (resizable horizontally).
 */
@AndroidEntryPoint
class TimeTrackingWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var timeEntryRepository: TimeEntryRepository

    @Inject
    lateinit var taskRepository: TaskRepository

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var lifeDimensionCatalogRepository: LifeDimensionCatalogRepository

    private val logger = UnifiedLogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class WidgetThemePalette(
        val backgroundDrawableRes: Int,
        val primaryTextColor: Int,
        val secondaryTextColor: Int,
        val activeStatusColor: Int,
        val idleStatusColor: Int,
        val actionIconColor: Int,
    )

    private data class DimensionVisual(
        val label: String,
        val color: Int,
    )

    companion object {
        const val ACTION_TOGGLE_TRACKING = "io.payanam.widget.TOGGLE_TRACKING"
        const val ACTION_REFRESH = "io.payanam.widget.REFRESH"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val THEME_DARK = "dark"
        private const val THEME_LIGHT = "light"
        private const val WIDGET_NAV_SOURCE = "widget"

        /**
         * Request update for all time tracking widgets.
         */
        fun requestUpdate(context: Context) {
            val intent = Intent(context, TimeTrackingWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val widgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = widgetManager.getAppWidgetIds(
                ComponentName(context, TimeTrackingWidgetProvider::class.java),
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            context.sendBroadcast(intent)
        }
    }

    /**
     * Redraws each widget: active state (task title, dimension chip, running
     * chronometer, stop action) or idle prompt with quick-start action.
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        logger.d(
            "TimeTrackingWidget.onUpdate",
            "Updating widgets",
            mapOf(
                "count" to appWidgetIds.size,
            ),
        )
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    /**
     * Dispatches widget button actions: toggle tracking and manual refresh.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_TRACKING -> {
                logger.i("TimeTrackingWidget.onReceive", "Toggle tracking requested")
                handleToggleTracking(context)
            }

            ACTION_REFRESH -> {
                logger.d("TimeTrackingWidget.onReceive", "Refresh requested")
                requestUpdate(context)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        scope.launch {
            try {
                // Get active time entry
                val activeEntry = timeEntryRepository.observeActiveTimeEntry().first()
                val views = RemoteViews(context.packageName, R.layout.widget_time_tracking)
                val themePalette = resolveThemePalette(context)
                applyThemePalette(views, themePalette)
                val configuredDimensions = lifeDimensionCatalogRepository.observeAllDimensions().first()
                if (activeEntry != null) {
                    // Tracking is active
                    val trackedTask = activeEntry.taskId?.let { taskId ->
                        runCatching { taskRepository.getTaskById(taskId) }.getOrNull()
                    }
                    val title = when {
                        trackedTask != null && trackedTask.recurrenceEnabled -> context.getString(
                            R.string.widget_tracking_title_habit,
                            trackedTask.title,
                        )

                        trackedTask != null -> context.getString(
                            R.string.widget_tracking_title_task,
                            trackedTask.title,
                        )

                        activeEntry.taskId != null -> context.getString(R.string.widget_tracking_title_linked)

                        else -> context.getString(R.string.widget_tracking_title_general)
                    }
                    val dimensionVisual = resolveDimensionVisual(
                        context = context,
                        dimensionId = activeEntry.dimensionId,
                        dimensionDisplayName = activeEntry.lifeIntentionCategory,
                        configuredDimensions = configuredDimensions,
                    )
                    val elapsedMillis = Duration.between(activeEntry.startedAt, LocalDateTime.now())
                        .toMillis()
                        .coerceAtLeast(0L)
                    val chronometerBase = SystemClock.elapsedRealtime() - elapsedMillis

                    views.setTextViewText(R.id.widget_task_title, title)
                    views.setTextViewText(R.id.widget_dimension, dimensionVisual.label)
                    views.setTextViewText(
                        R.id.widget_status,
                        context.getString(R.string.widget_tracking_status_active),
                    )
                    views.setImageViewResource(
                        R.id.widget_action_icon,
                        android.R.drawable.ic_menu_close_clear_cancel,
                    )
                    views.setInt(R.id.widget_action_icon, "setColorFilter", themePalette.actionIconColor)
                    views.setInt(R.id.widget_dimension_chip, "setColorFilter", dimensionVisual.color)
                    views.setTextColor(R.id.widget_duration_chronometer, dimensionVisual.color)
                    views.setTextColor(R.id.widget_status, themePalette.activeStatusColor)
                    views.setViewVisibility(R.id.widget_duration_chronometer, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_duration_text, View.GONE)
                    views.setChronometer(
                        R.id.widget_duration_chronometer,
                        chronometerBase,
                        "%s",
                        true,
                    )
                } else {
                    // No active tracking
                    views.setTextViewText(
                        R.id.widget_task_title,
                        context.getString(R.string.widget_tracking_no_active),
                    )
                    views.setTextViewText(
                        R.id.widget_dimension,
                        context.getString(R.string.widget_tracking_tap_to_start),
                    )
                    views.setTextViewText(
                        R.id.widget_status,
                        context.getString(R.string.widget_tracking_status_idle),
                    )
                    views.setTextViewText(
                        R.id.widget_duration_text,
                        context.getString(R.string.widget_tracking_duration_placeholder),
                    )
                    views.setImageViewResource(R.id.widget_action_icon, android.R.drawable.ic_media_play)
                    views.setInt(R.id.widget_action_icon, "setColorFilter", themePalette.actionIconColor)
                    views.setInt(R.id.widget_dimension_chip, "setColorFilter", themePalette.secondaryTextColor)
                    views.setTextColor(R.id.widget_duration_text, themePalette.primaryTextColor)
                    views.setTextColor(R.id.widget_status, themePalette.idleStatusColor)
                    views.setViewVisibility(R.id.widget_duration_chronometer, View.GONE)
                    views.setViewVisibility(R.id.widget_duration_text, View.VISIBLE)
                    views.setChronometer(
                        R.id.widget_duration_chronometer,
                        SystemClock.elapsedRealtime(),
                        "%s",
                        false,
                    )
                }

                // Set click intent - open app
                val openQuickPickPendingIntent = createOpenTimeQuickPickPendingIntent(
                    context = context,
                    requestCode = appWidgetId * 10 + 1,
                )
                views.setOnClickPendingIntent(R.id.widget_container, openQuickPickPendingIntent)
                if (activeEntry != null) {
                    // Active state: icon stops tracking
                    val stopPendingIntent = createOpenTimeStopTrackingPendingIntent(
                        context = context,
                        requestCode = appWidgetId * 10 + 2,
                    )
                    views.setOnClickPendingIntent(R.id.widget_action_icon, stopPendingIntent)
                } else {
                    // Idle state: icon opens quick picker
                    views.setOnClickPendingIntent(
                        R.id.widget_action_icon,
                        createOpenTimeQuickPickPendingIntent(
                            context = context,
                            requestCode = appWidgetId * 10 + 3,
                        ),
                    )
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)

                logger.d(
                    "TimeTrackingWidget.updateWidget",
                    "Widget updated",
                    mapOf(
                        "widgetId" to appWidgetId,
                        "hasActiveEntry" to (activeEntry != null),
                    ),
                )
            } catch (e: Exception) {
                logger.e(
                    "TimeTrackingWidget.updateWidget",
                    "Failed to update widget",
                    e,
                    mapOf(
                        "widgetId" to appWidgetId,
                    ),
                )
            }
        }
    }

    private suspend fun resolveThemePalette(context: Context): WidgetThemePalette {
        val configuredTheme = appSettingsRepository.getSetting(KEY_THEME_MODE)
        val useDarkTheme = when (configuredTheme) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> isSystemDarkMode(context)
        }
        return if (useDarkTheme) {
            WidgetThemePalette(
                backgroundDrawableRes = R.drawable.widget_background_dark,
                primaryTextColor = Color.parseColor("#F6F7FB"),
                secondaryTextColor = Color.parseColor("#B5B9C8"),
                activeStatusColor = Color.parseColor("#92E0A7"),
                idleStatusColor = Color.parseColor("#B5B9C8"),
                actionIconColor = Color.parseColor("#EDEFF5"),
            )
        } else {
            WidgetThemePalette(
                backgroundDrawableRes = R.drawable.widget_background_light,
                primaryTextColor = Color.parseColor("#1B1F2A"),
                secondaryTextColor = Color.parseColor("#5E6578"),
                activeStatusColor = Color.parseColor("#1F8A4C"),
                idleStatusColor = Color.parseColor("#5E6578"),
                actionIconColor = Color.parseColor("#2F3A52"),
            )
        }
    }

    private suspend fun resolveDimensionVisual(
        context: Context,
        dimensionId: String?,
        dimensionDisplayName: String,
        configuredDimensions: List<ConfiguredLifeDimension>,
    ): DimensionVisual {
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        val configuredDimension = configuredDimensions.firstOrNull { candidate ->
            candidate.id == dimensionId ||
                (!canonicalId.isNullOrBlank() && DimensionTaxonomyCatalog.fromCanonicalId(candidate.id)?.id == canonicalId)
        }
        if (configuredDimension == null) {
            logger.w(
                "TimeTrackingWidget.resolveDimensionVisual",
                "Missing canonical widget dimension; using default visual",
                mapOf(
                    "dimensionId" to (dimensionId ?: "none"),
                    "dimensionDisplayName" to dimensionDisplayName,
                ),
            )
            return DimensionVisual(
                label = dimensionDisplayName,
                color = defaultUnknownDimensionColor(),
            )
        }
        val preferredLanguageTag = when (appSettingsRepository.getSetting(KEY_APP_LANGUAGE)) {
            "en" -> "en"
            "ta" -> "ta"
            else -> null
        }
        val effectiveLabel = resolveWidgetDimensionLabel(
            context = context,
            dimension = configuredDimension,
            canonicalId = canonicalId,
            languageTag = preferredLanguageTag,
        )
        val effectiveColor = parseColorOrNull(configuredDimension.colorHex)
            ?: defaultDimensionColor(canonicalId)
        return DimensionVisual(label = effectiveLabel, color = effectiveColor)
    }

    private fun applyThemePalette(views: RemoteViews, palette: WidgetThemePalette) {
        views.setInt(R.id.widget_container, "setBackgroundResource", palette.backgroundDrawableRes)
        views.setTextColor(R.id.widget_task_title, palette.primaryTextColor)
        views.setTextColor(R.id.widget_dimension, palette.secondaryTextColor)
    }

    private fun parseColorOrNull(rawColor: String?): Int? {
        if (rawColor.isNullOrBlank()) {
            return null
        }
        return runCatching { Color.parseColor(rawColor) }.getOrNull()
    }

    private fun defaultUnknownDimensionColor(): Int = Color.parseColor("#8A90A2")

    private fun defaultDimensionColor(canonicalId: String?): Int {
        val defaultHex = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.defaultColorHex
            ?: return defaultUnknownDimensionColor()
        return Color.parseColor(defaultHex)
    }

    private fun isSystemDarkMode(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun handleToggleTracking(context: Context) {
        scope.launch {
            try {
                val activeEntry = timeEntryRepository.observeActiveTimeEntry().first()
                if (activeEntry != null) {
                    context.startActivity(createOpenTimeStopTrackingIntent(context))
                    logger.i("TimeTrackingWidget.handleToggleTracking", "Redirected active stop to in-app focus dialog")
                } else {
                    // Open app quick picker (can't start without task/dimension selection)
                    context.startActivity(createOpenTimeQuickPickIntent(context))
                }

                // Request widget update
                requestUpdate(context)
            } catch (e: Exception) {
                logger.e("TimeTrackingWidget.handleToggleTracking", "Failed to toggle tracking", e)
            }
        }
    }

    /**
     * First widget placed: refreshes immediately so it never shows stale data.
     */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        logger.i("TimeTrackingWidget.onEnabled", "First widget added")
        requestUpdate(context)
        logger.d("TimeTrackingWidget.onEnabled", "Requested widget refresh after enable")
    }

    /**
     * Last widget removed; no widget-scoped resources to release.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        logger.i("TimeTrackingWidget.onDisabled", "Last widget removed")
    }

    private fun createOpenTimeQuickPickPendingIntent(
        context: Context,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        createOpenTimeQuickPickIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createOpenTimeStopTrackingPendingIntent(
        context: Context,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        createOpenTimeStopTrackingIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createOpenTimeQuickPickIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
        putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, true)
        putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, false)
        putExtra(MainActivity.EXTRA_NAV_SOURCE, WIDGET_NAV_SOURCE)
    }

    private fun createOpenTimeStopTrackingIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
        putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
        putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
        putExtra(MainActivity.EXTRA_NAV_SOURCE, WIDGET_NAV_SOURCE)
    }
}

internal fun resolveWidgetDimensionLabel(
    context: Context,
    dimension: ConfiguredLifeDimension,
    canonicalId: String?,
    languageTag: String?,
): String {
    val trimmedLabel = dimension.label.trim()
    val localizedLabel = localizedWidgetCatalogLabel(context, canonicalId, languageTag)
    if (canonicalId.isNullOrBlank()) {
        return trimmedLabel.ifBlank { localizedLabel ?: dimension.id }
    }
    val knownAppOwnedLabels = buildSet {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel?.let(::add)
        listOf("en", "ta")
            .mapNotNull { localeTag -> localizedWidgetCatalogLabel(context, canonicalId, localeTag) }
            .forEach(::add)
    }
    return when {
        trimmedLabel.isBlank() -> localizedLabel ?: dimension.id
        trimmedLabel in knownAppOwnedLabels -> localizedLabel ?: trimmedLabel
        else -> trimmedLabel
    }
}

private fun localizedWidgetCatalogLabel(
    context: Context,
    canonicalId: String?,
    languageTag: String?,
): String? {
    val resId = DimensionTextCatalog.labelResIdForCanonicalId(canonicalId) ?: return null
    return runCatching {
        if (languageTag.isNullOrBlank()) {
            context.getString(resId)
        } else {
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(languageTag))
            context.createConfigurationContext(config).getString(resId)
        }
    }.getOrElse {
        runCatching { context.getString(resId) }.getOrNull()
            ?: DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel
    }
}
