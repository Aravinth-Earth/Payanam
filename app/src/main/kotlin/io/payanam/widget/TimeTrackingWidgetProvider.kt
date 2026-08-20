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
/**
 * TimeTrackingWidgetProvider.
 */
class TimeTrackingWidgetProvider : AppWidgetProvider() {

    @Inject
    /** Time entry repository. */
    lateinit var timeEntryRepository: TimeEntryRepository

    @Inject
    /** Task repository. */
    lateinit var taskRepository: TaskRepository

    @Inject
    /** App settings repository. */
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    /** Life dimension catalog repository. */
    lateinit var lifeDimensionCatalogRepository: LifeDimensionCatalogRepository

    private val logger = UnifiedLogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class WidgetThemePalette(
        /** Background drawable res. */
        val backgroundDrawableRes: Int,
        /** Primary text color. */
        val primaryTextColor: Int,
        /** Secondary text color. */
        val secondaryTextColor: Int,
        /** Active status color. */
        val activeStatusColor: Int,
        /** Idle status color. */
        val idleStatusColor: Int,
        /** Action icon color. */
        val actionIconColor: Int,
    )

    private data class DimensionVisual(
        /** Label. */
        val label: String,
        /** Color. */
        val color: Int,
    )

    companion object {
        /** Action toggle tracking. */
        const val ACTION_TOGGLE_TRACKING = "io.payanam.widget.TOGGLE_TRACKING"
        /** Action refresh. */
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
            /** Intent. */
            val intent = Intent(context, TimeTrackingWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            /** Widget manager. */
            val widgetManager = AppWidgetManager.getInstance(context)
            /** Widget ids. */
            val widgetIds = widgetManager.getAppWidgetIds(
                /** Component name. */
                ComponentName(context, TimeTrackingWidgetProvider::class.java),
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        /** Context. */
        context: Context,
        /** App widget manager. */
        appWidgetManager: AppWidgetManager,
        /** App widget ids. */
        appWidgetIds: IntArray,
    ) {
        logger.d(
            "TimeTrackingWidget.onUpdate",
            "Updating widgets",
            /** Map of. */
            mapOf(
                "count" to appWidgetIds.size,
            ),
        )

        /** For. */
        for (appWidgetId in appWidgetIds) {
            /** Update widget. */
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        /** When. */
        when (intent.action) {
            ACTION_TOGGLE_TRACKING -> {
                logger.i("TimeTrackingWidget.onReceive", "Toggle tracking requested")
                /** Handle toggle tracking. */
                handleToggleTracking(context)
            }

            ACTION_REFRESH -> {
                logger.d("TimeTrackingWidget.onReceive", "Refresh requested")
                /** Request update. */
                requestUpdate(context)
            }
        }
    }

    private fun updateWidget(
        /** Context. */
        context: Context,
        /** App widget manager. */
        appWidgetManager: AppWidgetManager,
        /** App widget id. */
        appWidgetId: Int,
    ) {
        scope.launch {
            try {
                // Get active time entry
                /** Active entry. */
                val activeEntry = timeEntryRepository.observeActiveTimeEntry().first()
                /** Views. */
                val views = RemoteViews(context.packageName, R.layout.widget_time_tracking)
                /** Theme palette. */
                val themePalette = resolveThemePalette(context)
                /** Apply theme palette. */
                applyThemePalette(views, themePalette)
                /** Configured dimensions. */
                val configuredDimensions = lifeDimensionCatalogRepository.observeAllDimensions().first()

                /** If. */
                if (activeEntry != null) {
                    // Tracking is active
                    /** Tracked task. */
                    val trackedTask = activeEntry.taskId?.let { taskId ->
                        runCatching { taskRepository.getTaskById(taskId) }.getOrNull()
                    }
                    /** Title. */
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
                    /** Dimension visual. */
                    val dimensionVisual = resolveDimensionVisual(
                        context = context,
                        dimensionId = activeEntry.dimensionId,
                        dimensionDisplayName = activeEntry.lifeIntentionCategory,
                        configuredDimensions = configuredDimensions,
                    )
                    /** Elapsed millis. */
                    val elapsedMillis = Duration.between(activeEntry.startedAt, LocalDateTime.now())
                        .toMillis()
                        .coerceAtLeast(0L)
                    /** Chronometer base. */
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
                        /** Chronometer base. */
                        chronometerBase,
                        "%s",
                        /** True. */
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
                        /** False. */
                        false,
                    )
                }

                // Set click intent - open app
                /** Open quick pick pending intent. */
                val openQuickPickPendingIntent = createOpenTimeQuickPickPendingIntent(
                    context = context,
                    requestCode = appWidgetId * 10 + 1,
                )
                views.setOnClickPendingIntent(R.id.widget_container, openQuickPickPendingIntent)

                /** If. */
                if (activeEntry != null) {
                    // Active state: icon stops tracking
                    /** Stop pending intent. */
                    val stopPendingIntent = createOpenTimeStopTrackingPendingIntent(
                        context = context,
                        requestCode = appWidgetId * 10 + 2,
                    )
                    views.setOnClickPendingIntent(R.id.widget_action_icon, stopPendingIntent)
                } else {
                    // Idle state: icon opens quick picker
                    views.setOnClickPendingIntent(
                        R.id.widget_action_icon,
                        /** Create open time quick pick pending intent. */
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
                    /** Map of. */
                    mapOf(
                        "widgetId" to appWidgetId,
                        "hasActiveEntry" to (activeEntry != null),
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeTrackingWidget.updateWidget",
                    "Failed to update widget",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "widgetId" to appWidgetId,
                    ),
                )
            }
        }
    }

    private suspend fun resolveThemePalette(context: Context): WidgetThemePalette {
        /** Configured theme. */
        val configuredTheme = appSettingsRepository.getSetting(KEY_THEME_MODE)
        /** Use dark theme. */
        val useDarkTheme = when (configuredTheme) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> isSystemDarkMode(context)
        }
        return if (useDarkTheme) {
            /** Widget theme palette. */
            WidgetThemePalette(
                backgroundDrawableRes = R.drawable.widget_background_dark,
                primaryTextColor = Color.parseColor("#F6F7FB"),
                secondaryTextColor = Color.parseColor("#B5B9C8"),
                activeStatusColor = Color.parseColor("#92E0A7"),
                idleStatusColor = Color.parseColor("#B5B9C8"),
                actionIconColor = Color.parseColor("#EDEFF5"),
            )
        } else {
            /** Widget theme palette. */
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
        /** Context. */
        context: Context,
        dimensionId: String?,
        /** Dimension display name. */
        dimensionDisplayName: String,
        configuredDimensions: List<ConfiguredLifeDimension>,
    ): DimensionVisual {
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        /** Configured dimension. */
        val configuredDimension = configuredDimensions.firstOrNull { candidate ->
            candidate.id == dimensionId ||
                (!canonicalId.isNullOrBlank() && DimensionTaxonomyCatalog.fromCanonicalId(candidate.id)?.id == canonicalId)
        }
        /** If. */
        if (configuredDimension == null) {
            logger.w(
                "TimeTrackingWidget.resolveDimensionVisual",
                "Missing canonical widget dimension; using default visual",
                /** Map of. */
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
        /** Preferred language tag. */
        val preferredLanguageTag = when (appSettingsRepository.getSetting(KEY_APP_LANGUAGE)) {
            "en" -> "en"
            "ta" -> "ta"
            else -> null
        }
        /** Effective label. */
        val effectiveLabel = resolveWidgetDimensionLabel(
            context = context,
            dimension = configuredDimension,
            canonicalId = canonicalId,
            languageTag = preferredLanguageTag,
        )
        /** Effective color. */
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
        /** If. */
        if (rawColor.isNullOrBlank()) {
            return null
        }
        return runCatching { Color.parseColor(rawColor) }.getOrNull()
    }

    private fun defaultUnknownDimensionColor(): Int = Color.parseColor("#8A90A2")

    private fun defaultDimensionColor(canonicalId: String?): Int {
        /** Default hex. */
        val defaultHex = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.defaultColorHex
            ?: return defaultUnknownDimensionColor()
        return Color.parseColor(defaultHex)
    }

    private fun isSystemDarkMode(context: Context): Boolean {
        /** Ui mode. */
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun handleToggleTracking(context: Context) {
        scope.launch {
            try {
                /** Active entry. */
                val activeEntry = timeEntryRepository.observeActiveTimeEntry().first()

                /** If. */
                if (activeEntry != null) {
                    context.startActivity(createOpenTimeStopTrackingIntent(context))
                    logger.i("TimeTrackingWidget.handleToggleTracking", "Redirected active stop to in-app focus dialog")
                } else {
                    // Open app quick picker (can't start without task/dimension selection)
                    context.startActivity(createOpenTimeQuickPickIntent(context))
                }

                // Request widget update
                /** Request update. */
                requestUpdate(context)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TimeTrackingWidget.handleToggleTracking", "Failed to toggle tracking", e)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        logger.i("TimeTrackingWidget.onEnabled", "First widget added")
        /** Request update. */
        requestUpdate(context)
        logger.d("TimeTrackingWidget.onEnabled", "Requested widget refresh after enable")
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        logger.i("TimeTrackingWidget.onDisabled", "Last widget removed")
    }

    private fun createOpenTimeQuickPickPendingIntent(
        /** Context. */
        context: Context,
        /** Request code. */
        requestCode: Int,
    ): PendingIntent = PendingIntent.getActivity(
        /** Context. */
        context,
        /** Request code. */
        requestCode,
        /** Create open time quick pick intent. */
        createOpenTimeQuickPickIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createOpenTimeStopTrackingPendingIntent(
        /** Context. */
        context: Context,
        /** Request code. */
        requestCode: Int,
    ): PendingIntent = PendingIntent.getActivity(
        /** Context. */
        context,
        /** Request code. */
        requestCode,
        /** Create open time stop tracking intent. */
        createOpenTimeStopTrackingIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createOpenTimeQuickPickIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        /** Put extra. */
        putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
        /** Put extra. */
        putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, true)
        /** Put extra. */
        putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, false)
        /** Put extra. */
        putExtra(MainActivity.EXTRA_NAV_SOURCE, WIDGET_NAV_SOURCE)
    }

    private fun createOpenTimeStopTrackingIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        /** Put extra. */
        putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
        /** Put extra. */
        putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
        /** Put extra. */
        putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
        /** Put extra. */
        putExtra(MainActivity.EXTRA_NAV_SOURCE, WIDGET_NAV_SOURCE)
    }
}

internal fun resolveWidgetDimensionLabel(
    /** Context. */
    context: Context,
    /** Dimension. */
    dimension: ConfiguredLifeDimension,
    canonicalId: String?,
    languageTag: String?,
): String {
    /** Trimmed label. */
    val trimmedLabel = dimension.label.trim()
    /** Localized label. */
    val localizedLabel = localizedWidgetCatalogLabel(context, canonicalId, languageTag)
    /** If. */
    if (canonicalId.isNullOrBlank()) {
        return trimmedLabel.ifBlank { localizedLabel ?: dimension.id }
    }
    /** Known app owned labels. */
    val knownAppOwnedLabels = buildSet {
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel?.let(::add)
        /** List of. */
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
    /** Context. */
    context: Context,
    canonicalId: String?,
    languageTag: String?,
): String? {
    /** Res id. */
    val resId = DimensionTextCatalog.labelResIdForCanonicalId(canonicalId) ?: return null
    return runCatching {
        /** If. */
        if (languageTag.isNullOrBlank()) {
            context.getString(resId)
        } else {
            /** Config. */
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(languageTag))
            context.createConfigurationContext(config).getString(resId)
        }
    }.getOrElse {
        runCatching { context.getString(resId) }.getOrNull()
            ?: DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel
    }
}
