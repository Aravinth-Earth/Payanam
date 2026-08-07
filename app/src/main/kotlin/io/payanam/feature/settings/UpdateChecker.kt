//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val latestBuildNumber: Int?,
    val releaseUrl: String?,
    val error: UpdateCheckError?,
)

enum class UpdateCheckError {
    NO_INTERNET,
    TIMEOUT,
    GITHUB_UNAVAILABLE,
    RATE_LIMITED,
    PARSE_ERROR,
    UNKNOWN,
}

object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/Aravinth-Earth/Payanam/releases/tags/latest-dev"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 1_048_576 // 1MB safety cap
    private val BUILD_NUMBER_REGEX = Regex("""#(\d+)""")
    private val logger = UnifiedLogger.getInstance()

    suspend fun check(currentBuildNumber: Int): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            logger.d("UpdateChecker.check", "Starting update check", mapOf("currentBuild" to currentBuildNumber))
            try {
                val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
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

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val htmlUrl = json.optString("html_url", "")
                val title = json.optString("name", tagName)
                logger.d("UpdateChecker.check", "Parsed release", mapOf("title" to title, "tag" to tagName))

                val match = BUILD_NUMBER_REGEX.find(title)
                    ?: return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.PARSE_ERROR,
                    )

                val latestBuild = match.groupValues[1].toIntOrNull()
                    ?: return@withContext UpdateCheckResult(
                        isUpdateAvailable = false,
                        latestBuildNumber = null,
                        releaseUrl = null,
                        error = UpdateCheckError.PARSE_ERROR,
                    )

                logger.d("UpdateChecker.check", "Compare builds", mapOf("latest" to latestBuild, "current" to currentBuildNumber))
                UpdateCheckResult(
                    isUpdateAvailable = latestBuild > currentBuildNumber,
                    latestBuildNumber = latestBuild,
                    releaseUrl = htmlUrl,
                    error = null,
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
