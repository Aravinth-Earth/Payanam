//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import io.payanam.BuildConfig
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
/**
 * Outcome of an update check: availability verdict, latest build/release
 * info, per-channel statuses, and the error reason when it failed.
 */
data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val latestBuildNumber: Int?,
    val releaseUrl: String?,
    val error: UpdateCheckError?,
    /** Status of every channel parsed from the list endpoint. */
    val channelStatuses: List<ChannelStatus> = emptyList(),
    /**
     * Fail-closed verdict flag: the selected channel's newest release ships no
     * `.apk` of the running build type. The UI shows the mismatch message for
     * this state — it must never read "up to date".
     */
    val typeMismatch: Boolean = false,
    /** Epoch millis when this result was produced — staleness for the UI. */
    val checkedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Release channels. [tagSuffix] is used in persistent GitHub release
 * tags ("{tagSuffix}-v{buildNumber}") produced by publish-release.ps1.
 */
enum class UpdateChannel(val tagSuffix: String) {
    DEV("dev"),
    BETA("beta"),
    STABLE("stable"),
    ;

    companion object {
        /** Parse a stored preference value; unknown/garbage falls back to DEV. */
        fun fromStorage(raw: String?): UpdateChannel =
            entries.firstOrNull { it.name == raw } ?: DEV
    }
}

/** Display label resource for a channel (used by dropdown + status rows). */
fun UpdateChannel.labelResId(): Int = when (this) {
    UpdateChannel.DEV -> R.string.settings_update_channel_dev
    UpdateChannel.BETA -> R.string.settings_update_channel_beta
    UpdateChannel.STABLE -> R.string.settings_update_channel_stable
}

/**
 * Build-type tokens used in artifact names:
 * `Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk`.
 */
internal object ApkBuildType {
    const val DEBUG = "debug"
    const val RELEASE = "release"

    /**
     * Build type of the APK this process is running. Callers pass this IN as a
     * parameter down the selection path (unit tests compile against the debug
     * variant, where reading BuildConfig here would be constant-true).
     */
    fun running(): String = if (BuildConfig.DEBUG) DEBUG else RELEASE
}

/**
 * Build type a channel ships — one artifact per channel (naming plan):
 * dev → debug, beta/stable → release.
 */
internal fun UpdateChannel.shippedApkType(): String = when (this) {
    UpdateChannel.DEV -> ApkBuildType.DEBUG
    UpdateChannel.BETA, UpdateChannel.STABLE -> ApkBuildType.RELEASE
}

/** Per-channel status parsed from the GitHub releases list endpoint. */
data class ChannelStatus(
    val channel: UpdateChannel,
    val buildNumber: Int?,
    val releaseUrl: String?,
    /** Direct download URL of the APK asset (from the release's assets list). */
    val apkDownloadUrl: String? = null,
    /** Download URL of the selected APK's paired `.sha256` asset, when present. */
    val apkSha256Url: String? = null,
    /**
     * True when this release ships no `.apk` asset for the running build type
     * (legacy/untyped or other-type only). Selection fails closed: no URL and
     * no update verdict for this release.
     */
    val typeMismatch: Boolean = false,
)

/** Map a GitHub tag name to a channel, or null for non-channel tags. */
private val PERSISTENT_TAG_REGEX = Regex("""^(dev|beta|stable)-v(\d+)$""")
private val STABLE_TAG_REGEX = Regex("""^v(\d+)$""")

internal fun channelFromTag(tagName: String): UpdateChannel? =
    PERSISTENT_TAG_REGEX.matchEntire(tagName)?.let { match ->
        UpdateChannel.entries.firstOrNull { it.tagSuffix == match.groupValues[1] }
    } ?: STABLE_TAG_REGEX.matchEntire(tagName)?.let {
        UpdateChannel.STABLE
    }

private val BUILD_NUMBER_REGEX = Regex("""#(\d+)""")

/** Build-type token inside an artifact name (`_debug_` / `_release_`). */
private val ARTIFACT_TYPE_REGEX = Regex("""_(debug|release)_""")

/** File-level logger for top-level helpers that live outside [UpdateChecker].
 *  Lazy so pure helpers (parseReleases et al.) stay usable in plain JVM tests
 *  without UnifiedLogger.initialize(). */
private val logger: UnifiedLogger by lazy { UnifiedLogger.getInstance() }

/** Outcome of the type-aware asset selection within one release. */
internal data class ApkAssetSelection(
    /** Download URL of the `.apk` asset carrying the expected build type, or null. */
    val apkUrl: String? = null,
    /** Download URL of that APK's paired `.sha256` sibling, when the release ships one. */
    val sha256Url: String? = null,
    /** True when the release ships no `.apk` of the expected build type (fail-closed). */
    val typeMismatch: Boolean = false,
    /** Distinct build-type tokens seen across the release's asset names (for fail-closed logs). */
    val assetTypesSeen: List<String> = emptyList(),
)

/**
 * Select the APK + checksum assets for [expectedType] from a release's assets.
 *
 * Pure function (no I/O) — the running build type is passed IN so unit tests
 * (which compile against the debug variant) can exercise both types.
 *
 * The APK matcher requires the `_<type>_` token AND a `.apk` suffix: the
 * `.sha256` sibling carries the same type token and must never be selectable.
 * A release with no matching APK yields a fail-closed [ApkAssetSelection.typeMismatch];
 * downstream that becomes `isUpdateAvailable = false`, never just a null URL.
 */
internal fun selectApkAssets(assets: JSONArray?, expectedType: String): ApkAssetSelection {
    if (assets == null) return ApkAssetSelection(typeMismatch = true)
    val entries = mutableListOf<Pair<String, String>>()
    for (a in 0 until assets.length()) {
        val asset = assets.optJSONObject(a) ?: continue
        val name = asset.optString("name", "")
        if (name.isNotEmpty()) {
            entries.add(name to asset.optString("browser_download_url", ""))
        }
    }
    val apkEntry = entries.firstOrNull { (name, _) -> name.endsWith(".apk") && name.contains("_${expectedType}_") }
    val shaEntry = apkEntry?.let { (apkName, _) -> entries.firstOrNull { (name, _) -> name == "$apkName.sha256" } }
    return ApkAssetSelection(
        apkUrl = apkEntry?.second,
        sha256Url = shaEntry?.second,
        typeMismatch = apkEntry == null,
        assetTypesSeen = entries.mapNotNull { (name, _) -> ARTIFACT_TYPE_REGEX.find(name)?.groupValues?.get(1) }.distinct(),
    )
}

/**
 * True when [fileName] names an APK built for [buildType] (`_<type>_` token +
 * `.apk` suffix). Predicate for validating a persisted download URL's stored
 * name before a retry enqueue — legacy/untyped and other-type names fail
 * closed, and a `.sha256` sibling never passes.
 */
internal fun artifactNameMatchesBuildType(fileName: String, buildType: String): Boolean =
    fileName.endsWith(".apk") && fileName.contains("_${buildType}_")

/** Fail-closed selection trace: the release has no APK for the running build
 *  type. Logged at `i` because release builds turn `d` off; guarded because the
 *  parse tests call [parseReleases] on a bare JVM without the logger. */
private fun logFailClosedSelection(
    channel: UpdateChannel,
    buildNumber: Int?,
    runningType: String,
    assetTypesSeen: List<String>,
) {
    if (!UnifiedLogger.isInitialized()) return
    logger.i(
        "UpdateChecker.parseReleases",
        "Update check failed closed: release ships no APK for the running build type",
        mapOf(
            "channel" to channel.name,
            "build" to (buildNumber ?: -1),
            "runningType" to runningType,
            "expectedType" to runningType,
            "assetTypes" to assetTypesSeen.joinToString(",").ifEmpty { "none" },
        ),
    )
}

/**
 * Parse the GitHub releases list JSON body into per-channel statuses, with the
 * downloadable APK + checksum selected for [runningType].
 *
 * Pure function (no I/O) — unit-testable. Non-channel tags are ignored;
 * malformed entries are skipped. Returns an empty list for garbage bodies.
 */
@Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
internal fun parseReleases(body: String, runningType: String): List<ChannelStatus> {
    val releases = try {
        JSONArray(body)
    } catch (e: JSONException) {
        logger.w("UpdateChecker.parseReleases", "Failed to parse release JSON", mapOf("error" to (e.message ?: "unknown")))
        return emptyList()
    }
    val statuses = mutableListOf<ChannelStatus>()
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        val tagName = release.optString("tag_name", "")
        val channel = channelFromTag(tagName) ?: continue
        val htmlUrl = release.optString("html_url", "")
        val title = release.optString("name", tagName)
        val match = BUILD_NUMBER_REGEX.find(title)
        val buildNumber = match?.groupValues?.get(1)?.toIntOrNull()
        // Type-aware selection: the running build type decides which asset is
        // downloadable. No match ⇒ fail closed (no URL, verdict false) —
        // never a silent skip that later reads as "up to date".
        val selection = selectApkAssets(release.optJSONArray("assets"), runningType)
        if (selection.typeMismatch) {
            logFailClosedSelection(channel, buildNumber, runningType, selection.assetTypesSeen)
        }
        statuses.add(
            ChannelStatus(
                channel = channel,
                buildNumber = buildNumber,
                releaseUrl = htmlUrl,
                apkDownloadUrl = selection.apkUrl,
                apkSha256Url = selection.sha256Url,
                typeMismatch = selection.typeMismatch,
            ),
        )
    }
    return statuses
}

/**
 * Derive the selected channel's verdict from parsed [statuses] (pure, no I/O).
 *
 * Fail-closed contract: when the channel's newest release carries no APK for
 * the running build type ([ChannelStatus.typeMismatch]), the verdict is
 * `isUpdateAvailable = false` — the mismatch gates the VERDICT, not just the
 * download URL.
 */
internal fun resolveUpdateResult(
    currentBuildNumber: Int,
    channel: UpdateChannel,
    statuses: List<ChannelStatus>,
): UpdateCheckResult {
    val selected = statuses.firstOrNull { it.channel == channel }
    val latestBuild = selected?.buildNumber
    val typeMismatch = selected?.typeMismatch == true
    return UpdateCheckResult(
        isUpdateAvailable = !typeMismatch && latestBuild != null && latestBuild > currentBuildNumber,
        latestBuildNumber = latestBuild,
        releaseUrl = selected?.releaseUrl,
        error = null,
        channelStatuses = statuses,
        typeMismatch = typeMismatch,
    )
}

/**
 * Error reasons for an update check failure.
 */
enum class UpdateCheckError {
    NO_INTERNET,
    TIMEOUT,
    GITHUB_UNAVAILABLE,
    RATE_LIMITED,
    PARSE_ERROR,
    UNKNOWN,
}

/**
 * Checks GitHub releases for app updates across all channels.
 * Fetches the releases list endpoint, parses per-channel statuses with a
 * type-aware asset selection for [ApkBuildType.running], and compares the
 * latest build number against the installed version. A channel whose newest
 * release ships no APK of the running build type fails the verdict closed.
 */
object UpdateChecker {

    private const val RELEASES_LIST_URL =
        "https://api.github.com/repos/Aravinth-Earth/Payanam/releases?per_page=50"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 1_048_576 // 1MB safety cap
    private val logger = UnifiedLogger.getInstance()

    /**
     * Fetch release info for ALL channels in one call (list endpoint),
     * then derive the result for the [channel] the user has selected.
     *
     * [runningType] is the build type of this install; it decides which asset
     * each release offers. Passed IN (defaulting to the real running type) so
     * tests can exercise both types explicitly.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
    suspend fun check(
        currentBuildNumber: Int,
        channel: UpdateChannel = UpdateChannel.DEV,
        runningType: String = ApkBuildType.running(),
    ): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            logger.d(
                "UpdateChecker.check",
                "Starting update check",
                mapOf("currentBuild" to currentBuildNumber, "channel" to channel.name, "runningType" to runningType),
            )
            try {
                val connection = URL(RELEASES_LIST_URL).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Payanam/$currentBuildNumber")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                val responseCode = connection.responseCode
                logger.d("UpdateChecker.check", "Response received", mapOf("code" to responseCode))
                if (responseCode == 403) {
                    logger.w("UpdateChecker.check", "Rate limited by GitHub")
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.RATE_LIMITED,
                    )
                }
                if (responseCode == 404) {
                    logger.w("UpdateChecker.check", "GitHub returned 404")
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.GITHUB_UNAVAILABLE,
                    )
                }
                if (responseCode !in 200..299) {
                    logger.w("UpdateChecker.check", "Unexpected HTTP status", mapOf("code" to responseCode))
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.GITHUB_UNAVAILABLE,
                    )
                }
                val body = readResponseWithLimit(connection.inputStream)
                if (body == null) {
                    logger.w("UpdateChecker.check", "Response body exceeds size limit")
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.PARSE_ERROR,
                    )
                }

                // List endpoint → JSON array of release objects. Pick out the
                // persistent channel tags ({channel}-v{build}) we own; ignore
                // everything else. Asset selection is type-aware: only the
                // running build type's APK is downloadable, and its absence
                // fails the verdict closed.
                val statuses = parseReleases(body, runningType)
                val selected = statuses.firstOrNull { it.channel == channel }

                logger.d(
                    "UpdateChecker.check",
                    "Channels parsed",
                    mapOf(
                        "found" to statuses.size,
                        "selectedBuild" to (selected?.buildNumber ?: -1),
                        "selectedTypeMismatch" to (selected?.typeMismatch == true),
                    ),
                )
                resolveUpdateResult(currentBuildNumber, channel, statuses)
            } catch (e: UnknownHostException) {
                logger.w("UpdateChecker.check", "No internet", mapOf("exception" to (e.message ?: "unknown")))
                UpdateCheckResult(false, null, null, UpdateCheckError.NO_INTERNET)
            } catch (e: SocketTimeoutException) {
                logger.w("UpdateChecker.check", "Timeout", mapOf("exception" to (e.message ?: "unknown")))
                UpdateCheckResult(false, null, null, UpdateCheckError.TIMEOUT)
            } catch (e: SSLException) {
                logger.w("UpdateChecker.check", "SSL error", mapOf("exception" to (e.message ?: "unknown")))
                UpdateCheckResult(false, null, null, UpdateCheckError.NO_INTERNET)
            } catch (e: IOException) {
                logger.w("UpdateChecker.check", "IO error", mapOf("exception" to (e.message ?: "unknown")))
                UpdateCheckResult(false, null, null, UpdateCheckError.NO_INTERNET)
            } catch (e: Exception) {
                logger.e("UpdateChecker.check", "Unexpected error", e)
                UpdateCheckResult(false, null, null, UpdateCheckError.UNKNOWN)
            }
        }

    /**
     * Read response body with a size cap. Compatible with API 28+.
     * Returns null if response exceeds MAX_RESPONSE_BYTES.
     */
    private fun readResponseWithLimit(input: InputStream): String? {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        var totalRead = 0
        var read: Int
        input.buffered().use { buffered ->
            while (buffered.read(buffer).also { read = it } != -1) {
                totalRead += read
                if (totalRead > MAX_RESPONSE_BYTES) {
                    return null
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString("UTF-8")
    }
}
