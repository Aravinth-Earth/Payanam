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
    /** Is update available. */
    val isUpdateAvailable: Boolean,
    /** Latest build number. */
    val latestBuildNumber: Int?,
    /** Release url. */
    val releaseUrl: String?,
    /** Error. */
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
    /** Dev. */
    DEV("dev"),
    /** Beta. */
    BETA("beta"),
    /** Stable. */
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
    /** Channel. */
    val channel: UpdateChannel,
    /** Build number. */
    val buildNumber: Int?,
    /** Release url. */
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
    /** Releases. */
    val releases = try {
        /** Jsonarray. */
        JSONArray(body)
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        return emptyList()
    }
    /** Statuses. */
    val statuses = mutableListOf<ChannelStatus>()
    /** For. */
    for (i in 0 until releases.length()) {
        /** Release. */
        val release = releases.optJSONObject(i) ?: continue
        /** Tag name. */
        val tagName = release.optString("tag_name", "")
        /** Channel. */
        val channel = channelFromTag(tagName) ?: continue
        /** Html url. */
        val htmlUrl = release.optString("html_url", "")
        /** Title. */
        val title = release.optString("name", tagName)
        /** Match. */
        val match = BUILD_NUMBER_REGEX.find(title)
        /** Build number. */
        val buildNumber = match?.groupValues?.get(1)?.toIntOrNull()
        // Direct APK asset URL: assets[].browser_download_url (first .apk).
        /** Assets. */
        val assets = release.optJSONArray("assets")
        /** Apk url. */
        var apkUrl: String? = null
        /** If. */
        if (assets != null) {
            /** For. */
            for (a in 0 until assets.length()) {
                /** Asset. */
                val asset = assets.optJSONObject(a) ?: continue
                /** Asset name. */
                val assetName = asset.optString("name", "")
                /** If. */
                if (assetName.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    /** Break. */
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
    /** No internet. */
    NO_INTERNET,
    /** Timeout. */
    TIMEOUT,
    /** Github unavailable. */
    GITHUB_UNAVAILABLE,
    /** Rate limited. */
    RATE_LIMITED,
    /** Parse error. */
    PARSE_ERROR,
    /** Unknown. */
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
        /** With context. */
        withContext(Dispatchers.IO) {
            logger.d("UpdateChecker.check", "Starting update check", mapOf("currentBuild" to currentBuildNumber, "channel" to channel.name))
            try {
                /** Connection. */
                val connection = URL(RELEASES_LIST_URL).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    /** Set request property. */
                    setRequestProperty("Accept", "application/vnd.github+json")
                    /** Set request property. */
                    setRequestProperty("User-Agent", "Payanam/$currentBuildNumber")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }

                /** Response code. */
                val responseCode = connection.responseCode
                logger.d("UpdateChecker.check", "Response received", mapOf("code" to responseCode))

                /** If. */
                if (responseCode == 403) {
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.RATE_LIMITED,
                    )
                }

                /** If. */
                if (responseCode == 404) {
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.GITHUB_UNAVAILABLE,
                    )
                }

                /** If. */
                if (responseCode !in 200..299) {
                    return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.GITHUB_UNAVAILABLE,
                    )
                }

                /** Body. */
                val body = readResponseWithLimit(connection.inputStream)
                    ?: return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.PARSE_ERROR,
                    )

                // List endpoint → JSON array of release objects. Pick out the
                // rolling channel tags (latest-*) we own; ignore everything else.
                /** Statuses. */
                val statuses = parseReleases(body)

                /** Selected. */
                val selected = statuses.firstOrNull { it.channel == channel }
                /** Latest build. */
                val latestBuild = selected?.buildNumber

                logger.d("UpdateChecker.check", "Channels parsed", mapOf("found" to statuses.size, "selectedBuild" to (latestBuild ?: -1)))
                /** Update check result. */
                UpdateCheckResult(
                    isUpdateAvailable = latestBuild != null && latestBuild > currentBuildNumber,
                    latestBuildNumber = latestBuild,
                    releaseUrl = selected?.releaseUrl,
                    error = null,
                    channelStatuses = statuses,
                )
            } catch (e: UnknownHostException) {
                logger.w("UpdateChecker.check", "No internet", mapOf("exception" to (e.message ?: "unknown")))
                /** Update check result. */
                UpdateCheckResult(false, null, null, UpdateCheckError.NO_INTERNET)
            } catch (e: SocketTimeoutException) {
                logger.w("UpdateChecker.check", "Timeout", mapOf("exception" to (e.message ?: "unknown")))
                /** Update check result. */
                UpdateCheckResult(false, null, null, UpdateCheckError.TIMEOUT)
            } catch (e: SSLException) {
                logger.w("UpdateChecker.check", "SSL error", mapOf("exception" to (e.message ?: "unknown")))
                /** Update check result. */
                UpdateCheckResult(false, null, null, UpdateCheckError.NO_INTERNET)
            } catch (e: IOException) {
                logger.w("UpdateChecker.check", "IO error", mapOf("exception" to (e.message ?: "unknown")))
                /** Update check result. */
                UpdateCheckResult(false, null, null, UpdateCheckError.NO_INTERNET)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("UpdateChecker.check", "Unexpected error", e)
                /** Update check result. */
                UpdateCheckResult(false, null, null, UpdateCheckError.UNKNOWN)
            }
        }

    /**
     * Read response body with a size cap. Compatible with API 28+.
     * Returns null if response exceeds MAX_RESPONSE_BYTES.
     */
    private fun readResponseWithLimit(input: InputStream): String? {
        /** Buffer. */
        val buffer = ByteArray(8192)
        /** Output. */
        val output = ByteArrayOutputStream()
        /** Total read. */
        var totalRead = 0
        /** Read. */
        var read: Int
        input.buffered().use { buffered ->
            /** While. */
            while (buffered.read(buffer).also { read = it } != -1) {
                totalRead += read
                /** If. */
                if (totalRead > MAX_RESPONSE_BYTES) {
                    return null
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString("UTF-8")
    }
}
