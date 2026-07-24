//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.feedback.FeedbackIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.inject.Inject

enum class ReportType { BUG, FEATURE, FEEDBACK }

sealed class SubmitResult {
    data object Success : SubmitResult()
    data class Error(val message: String) : SubmitResult()
}

data class FeedbackUiState(
    val reportType: ReportType = ReportType.BUG,
    val title: String = "",
    val description: String = "",
    val steps: String = "",
    val includeDeviceModel: Boolean = true,
    val includeOsVersion: Boolean = true,
    val includeLocale: Boolean = true,
    val includeBuild: Boolean = true,
    val isSubmitting: Boolean = false,
    val submitResult: SubmitResult? = null,
    val issues: List<FeedbackIssue> = emptyList(),
    val isLoadingIssues: Boolean = false,
    val issuesError: String? = null,
    val nextRefreshAllowedMs: Long = 0L,
    val submissionsRemainingToday: Int = SUBMISSION_MAX_PER_DAY,
)

private const val PREFS_FILE = "payanam_feedback_prefs"
private const val KEY_LAST_REFRESH_MS = "last_issues_refresh_ms"
private const val KEY_CACHED_ISSUES = "cached_issues_json"
private const val KEY_SUBMISSION_TIMESTAMPS = "submission_timestamps_json"

private const val DB_KEY_LAST_REFRESH_MS = "feedback_last_issues_refresh_ms"
private const val DB_KEY_CACHED_ISSUES = "feedback_cached_issues_json"

private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
private const val SUBMISSION_WINDOW_MS = 24L * 60 * 60 * 1000
private const val SUBMISSION_HOUR_WINDOW_MS = 60L * 60 * 1000
private const val CLOSED_REPORT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

internal const val SUBMISSION_MAX_PER_DAY = 12
internal const val SUBMISSION_MAX_PER_HOUR = 3

const val TITLE_MAX_CHARS = 150
const val BODY_MAX_CHARS = 2000

internal fun mergeIssues(local: List<FeedbackIssue>, remote: List<FeedbackIssue>): List<FeedbackIssue> {
    val byNumber = linkedMapOf<Int, FeedbackIssue>()
    local.forEach { byNumber[it.number] = it }
    remote.forEach { incoming ->
        val existing = byNumber[incoming.number]
        byNumber[incoming.number] = if (existing == null) {
            incoming
        } else {
            incoming.copy(
                body = incoming.body ?: existing.body,
                updatedAt = incoming.updatedAt ?: existing.updatedAt,
                closedAt = incoming.closedAt ?: existing.closedAt,
            )
        }
    }
    return byNumber.values.sortedByDescending { it.number }
}

internal fun pruneClosedIssues(issues: List<FeedbackIssue>, nowMs: Long): List<FeedbackIssue> {
    val cutoffMs = nowMs - CLOSED_REPORT_RETENTION_MS
    return issues.filter { issue ->
        if (!issue.state.equals("closed", ignoreCase = true)) {
            return@filter true
        }
        val closedOrUpdatedMs = parseDayToEpochMs(issue.closedAt)
            ?: parseDayToEpochMs(issue.updatedAt)
            ?: return@filter true
        closedOrUpdatedMs >= cutoffMs
    }
}

private fun parseDayToEpochMs(day: String?): Long? {
    if (day.isNullOrBlank()) return null
    return try {
        LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        FeedbackUiState(
            issues = loadCachedIssuesFromPrefs(),
            submissionsRemainingToday = computeSubmissionsRemaining(),
            nextRefreshAllowedMs = prefs.getLong(KEY_LAST_REFRESH_MS, 0L) + REFRESH_INTERVAL_MS,
        ),
    )
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    init {
        logger.i(
            "FeedbackViewModel.init",
            "ViewModel initialized",
            mapOf(
                "cachedIssues" to _uiState.value.issues.size,
                "submissionsRemaining" to _uiState.value.submissionsRemainingToday,
            ),
        )
        viewModelScope.launch {
            hydrateIssuesFromDb()
        }
    }

    fun onReportTypeChange(type: ReportType) {
        _uiState.update { it.copy(reportType = type) }
    }
    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value.take(TITLE_MAX_CHARS)) }
    }
    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value.take(BODY_MAX_CHARS)) }
    }
    fun onStepsChange(value: String) {
        _uiState.update { it.copy(steps = value.take(BODY_MAX_CHARS)) }
    }
    fun onIncludeDeviceModelChange(v: Boolean) {
        _uiState.update { it.copy(includeDeviceModel = v) }
    }
    fun onIncludeOsVersionChange(v: Boolean) {
        _uiState.update { it.copy(includeOsVersion = v) }
    }
    fun onIncludeLocaleChange(v: Boolean) {
        _uiState.update { it.copy(includeLocale = v) }
    }
    fun onIncludeBuildChange(v: Boolean) {
        _uiState.update { it.copy(includeBuild = v) }
    }
    fun onSubmitResultDismissed() {
        _uiState.update { it.copy(submitResult = null) }
    }

    fun submitFeedback() {
        val state = _uiState.value
        if (state.title.isBlank() || state.description.isBlank()) {
            logger.w("FeedbackViewModel.submitFeedback", "Validation failed: blank fields")
            _uiState.update { it.copy(submitResult = SubmitResult.Error("EMPTY_FIELDS")) }
            return
        }
        val remaining = computeSubmissionsRemaining()
        if (remaining <= 0) {
            logger.w("FeedbackViewModel.submitFeedback", "Daily submission limit reached")
            _uiState.update { it.copy(submitResult = SubmitResult.Error("SUBMISSION_LIMIT_REACHED")) }
            return
        }
        val prefix = when (state.reportType) {
            ReportType.BUG -> "[Bug] "
            ReportType.FEATURE -> "[Feature] "
            ReportType.FEEDBACK -> "[Feedback] "
        }
        logger.i(
            "FeedbackViewModel.submitFeedback",
            "Submitting",
            mapOf(
                "type" to state.reportType,
                "titleLen" to state.title.length,
                "descLen" to state.description.length,
                "hasSteps" to state.steps.isNotBlank(),
                "includeDevice" to state.includeDeviceModel,
                "includeOs" to state.includeOsVersion,
                "includeLocale" to state.includeLocale,
                "includeBuild" to state.includeBuild,
                "submissionsRemaining" to remaining,
            ),
        )
        _uiState.update { it.copy(isSubmitting = true, submitResult = null) }
        val preparedTitle = prefix + state.title.trim()
        val preparedBody = buildIssueBody(state)
        val issueUrl = "https://github.com/Aravinth-Earth/Payanam/issues/new?title=${Uri.encode(preparedTitle)}&body=${Uri.encode(preparedBody)}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl)))
            recordSubmissionTimestamp()
            val newRemaining = computeSubmissionsRemaining()
            logger.i("FeedbackViewModel.submitFeedback", "Browser opened for issue creation")
            _uiState.update {
                it.copy(
                    reportType = ReportType.BUG,
                    title = "",
                    description = "",
                    steps = "",
                    isSubmitting = false,
                    submitResult = SubmitResult.Success,
                    submissionsRemainingToday = newRemaining,
                )
            }
        } catch (e: Exception) {
            logger.e("FeedbackViewModel.submitFeedback", "Failed to open browser", e)
            _uiState.update {
                it.copy(isSubmitting = false, submitResult = SubmitResult.Error(e.message ?: "UNKNOWN"))
            }
        }
    }

    fun loadIssues() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val cached = pruneClosedIssues(loadCachedIssues(), now)
            _uiState.update { it.copy(issues = cached, isLoadingIssues = false) }
            saveCachedIssues(cached)
            logger.d("FeedbackViewModel.loadIssues", "Loaded from cache", mapOf("count" to cached.size))
        }
    }

    private suspend fun hydrateIssuesFromDb() {
        val cached = pruneClosedIssues(loadCachedIssues(), System.currentTimeMillis())
        val lastRefresh = loadLastRefreshMs()
        _uiState.update {
            it.copy(
                issues = cached,
                nextRefreshAllowedMs = if (lastRefresh > 0L) lastRefresh + REFRESH_INTERVAL_MS else it.nextRefreshAllowedMs,
            )
        }
    }

    private suspend fun loadCachedIssues(): List<FeedbackIssue> {
        val rawDbJson = readDbSetting(DB_KEY_CACHED_ISSUES)
        if (rawDbJson != null) {
            val parsedDb = parseIssuesJsonOrNull(rawDbJson)
            if (parsedDb != null) {
                return parsedDb
            }
            logger.w("FeedbackViewModel.loadCachedIssues", "DB cache unreadable; falling back to prefs")
        }
        return loadCachedIssuesFromPrefs()
    }

    private fun loadCachedIssuesFromPrefs(): List<FeedbackIssue> = parseIssuesJson(prefs.getString(KEY_CACHED_ISSUES, null))

    private fun parseIssuesJson(json: String?): List<FeedbackIssue> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FeedbackIssue(
                    number = obj.getInt("number"),
                    title = obj.getString("title"),
                    state = obj.getString("state"),
                    createdAt = obj.getString("createdAt"),
                    htmlUrl = obj.getString("htmlUrl"),
                    body = obj.optString("body").ifBlank { null },
                    updatedAt = obj.optString("updatedAt").ifBlank { null },
                    closedAt = obj.optString("closedAt").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            logger.w("FeedbackViewModel.parseIssuesJson", "Cache parse failed; clearing")
            prefs.edit().remove(KEY_CACHED_ISSUES).apply()
            emptyList()
        }
    }

    private fun parseIssuesJsonOrNull(json: String?): List<FeedbackIssue>? {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FeedbackIssue(
                    number = obj.getInt("number"),
                    title = obj.getString("title"),
                    state = obj.getString("state"),
                    createdAt = obj.getString("createdAt"),
                    htmlUrl = obj.getString("htmlUrl"),
                    body = obj.optString("body").ifBlank { null },
                    updatedAt = obj.optString("updatedAt").ifBlank { null },
                    closedAt = obj.optString("closedAt").ifBlank { null },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun saveCachedIssues(issues: List<FeedbackIssue>) {
        try {
            val arr = JSONArray()
            issues.forEach { issue ->
                arr.put(
                    JSONObject().apply {
                        put("number", issue.number)
                        put("title", issue.title)
                        put("state", issue.state)
                        put("createdAt", issue.createdAt)
                        put("htmlUrl", issue.htmlUrl)
                        put("body", issue.body)
                        put("updatedAt", issue.updatedAt)
                        put("closedAt", issue.closedAt)
                    },
                )
            }
            val json = arr.toString()
            prefs.edit().putString(KEY_CACHED_ISSUES, json).apply()
            writeDbSetting(DB_KEY_CACHED_ISSUES, json)
            logger.d("FeedbackViewModel.saveCachedIssues", "Cache saved", mapOf("count" to issues.size))
        } catch (e: Exception) {
            logger.w("FeedbackViewModel.saveCachedIssues", "Cache save failed")
        }
    }

    private fun computeSubmissionsRemaining(): Int {
        val now = System.currentTimeMillis()
        return try {
            val json = prefs.getString(KEY_SUBMISSION_TIMESTAMPS, null) ?: return SUBMISSION_MAX_PER_DAY
            val arr = JSONArray(json)
            val timestamps = (0 until arr.length()).map { arr.getLong(it) }
            val recentDay = timestamps.count { now - it < SUBMISSION_WINDOW_MS }
            val recentHour = timestamps.count { now - it < SUBMISSION_HOUR_WINDOW_MS }
            val remainingDay = (SUBMISSION_MAX_PER_DAY - recentDay).coerceAtLeast(0)
            val remainingHour = (SUBMISSION_MAX_PER_HOUR - recentHour).coerceAtLeast(0)
            minOf(remainingDay, remainingHour)
        } catch (e: Exception) {
            SUBMISSION_MAX_PER_DAY
        }
    }

    private fun recordSubmissionTimestamp() {
        val now = System.currentTimeMillis()
        try {
            val existing = prefs.getString(KEY_SUBMISSION_TIMESTAMPS, null)
            val all = if (existing != null) JSONArray(existing) else JSONArray()
            val filtered = JSONArray()
            (0 until all.length()).forEach { i ->
                if (now - all.getLong(i) < SUBMISSION_WINDOW_MS) filtered.put(all.getLong(i))
            }
            filtered.put(now)
            prefs.edit().putString(KEY_SUBMISSION_TIMESTAMPS, filtered.toString()).apply()
            logger.d(
                "FeedbackViewModel.recordSubmissionTimestamp",
                "Timestamp recorded",
                mapOf("totalInWindow" to filtered.length()),
            )
        } catch (e: Exception) {
            logger.w("FeedbackViewModel.recordSubmissionTimestamp", "Failed to record timestamp")
        }
    }

    private suspend fun loadLastRefreshMs(): Long {
        val fromDb = readDbSetting(DB_KEY_LAST_REFRESH_MS)?.toLongOrNull()
        return if (fromDb != null && fromDb >= 0L) {
            fromDb
        } else {
            prefs.getLong(KEY_LAST_REFRESH_MS, 0L)
        }
    }

    private suspend fun saveLastRefreshMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_REFRESH_MS, value).apply()
        writeDbSetting(DB_KEY_LAST_REFRESH_MS, value.toString())
    }

    private suspend fun readDbSetting(key: String): String? = runCatching {
        appSettingsRepository.getSetting(key)
    }.onFailure {
        logger.w("FeedbackViewModel.readDbSetting", "DB setting read failed", mapOf("key" to key))
    }.getOrNull()

    private suspend fun writeDbSetting(key: String, value: String?) {
        runCatching {
            appSettingsRepository.setSetting(key, value)
        }.onFailure {
            logger.w("FeedbackViewModel.writeDbSetting", "DB setting write failed", mapOf("key" to key))
        }
    }

    private fun buildIssueBody(state: FeedbackUiState): String = buildString {
        appendLine("## Description")
        appendLine()
        appendLine(state.description.trim())
        if (state.reportType == ReportType.BUG && state.steps.isNotBlank()) {
            appendLine()
            appendLine("## Steps to Reproduce")
            appendLine()
            appendLine(state.steps.trim())
        }
        appendLine()
        appendLine("## Included Metadata")
        appendLine()
        if (state.includeDeviceModel) appendLine("- Device model: ${Build.MANUFACTURER} ${Build.MODEL}")
        if (state.includeOsVersion) appendLine("- OS version: Android ${Build.VERSION.RELEASE}")
        if (state.includeLocale) appendLine("- App locale: ${context.resources.configuration.locales[0].toLanguageTag()}")
        if (state.includeBuild) appendLine("- Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("---")
        append("*Submitted via Payanam in-app feedback*")
    }
}
