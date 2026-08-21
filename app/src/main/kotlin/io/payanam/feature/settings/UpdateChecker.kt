//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 * UpdateCheckResult.
 */
data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val latestBuildNumber: Int?,
    val releaseUrl: String?,
    val error: UpdateCheckError?,
    /** Status of every channel parsed from the list endpoint. */
    val channelStatuses: List<ChannelStatus> = emptyList(),
    /** Epoch millis when this result was produced — staleness for the UI. */
    val checkedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Release channels. [tagSuffix] must match the rolling GitHub tag
 * ("latest-<tagSuffix>") produced by publish-release.ps1.
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

/** Per-channel status parsed from the GitHub releases list endpoint. */
data class ChannelStatus(
    val channel: UpdateChannel,
    val buildNumber: Int?,
    val releaseUrl: String?,
    /** Direct download URL of the APK asset (from the release's assets list). */
    val apkDownloadUrl: String? = null,
)

/** Map a GitHub tag name to a channel, or null for non-channel tags. */
internal fun channelFromTag(tagName: String): UpdateChannel? =
    UpdateChannel.entries.firstOrNull { tagName == "latest-${it.tagSuffix}" }

private val BUILD_NUMBER_REGEX = Regex("""#(\d+)""")

/**
 * Parse the GitHub releases list JSON body into per-channel statuses.
 * Pure function (no I/O) — unit-testable. Non-channel tags are ignored;
 * malformed entries are skipped. Returns an empty list for garbage bodies.
 */
internal fun parseReleases(body: String): List<ChannelStatus> {
    val releases = try {
        JSONArray(body)
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
        // Direct APK asset URL: assets[].browser_download_url (first .apk).
        val assets = release.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
        }
        statuses.add(ChannelStatus(channel = channel, buildNumber = buildNumber, releaseUrl = htmlUrl, apkDownloadUrl = apkUrl))
    }
    return statuses
}

/**
 * UpdateCheckError.
 * @property channel Channel.
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
 * UpdateChecker.
 */
object UpdateChecker {

    private const val RELEASES_LIST_URL =
        "https://api.github.com/repos/Aravinth-Earth/Payanam/releases?per_page=10"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 1_048_576 // 1MB safety cap
    private val logger = UnifiedLogger.getInstance()

    /**
     * Fetch release info for ALL channels in one call (list endpoint),
     * then derive the result for the [channel] the user has selected.
     */
    suspend fun check(currentBuildNumber: Int, channel: UpdateChannel = UpdateChannel.DEV): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            logger.d("UpdateChecker.check", "Starting update check", mapOf("currentBuild" to currentBuildNumber, "channel" to channel.name))
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
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.RATE_LIMITED,
                    )
                }
                if (responseCode == 404) {
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.GITHUB_UNAVAILABLE,
                    )
                }
                if (responseCode !in 200..299) {
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.GITHUB_UNAVAILABLE,
                    )
                }
                val body = readResponseWithLimit(connection.inputStream)
                    ?: return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.PARSE_ERROR,
                    )

                // List endpoint → JSON array of release objects. Pick out the
                // rolling channel tags (latest-*) we own; ignore everything else.
                val statuses = parseReleases(body)
                val selected = statuses.firstOrNull { it.channel == channel }
                val latestBuild = selected?.buildNumber

                logger.d("UpdateChecker.check", "Channels parsed", mapOf("found" to statuses.size, "selectedBuild" to (latestBuild ?: -1)))
                UpdateCheckResult(
                    isUpdateAvailable = latestBuild != null && latestBuild > currentBuildNumber,
                    latestBuildNumber = latestBuild,
                    releaseUrl = selected?.releaseUrl,
                    error = null,
                    channelStatuses = statuses,
                )
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
