//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.presentation.updates

import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.updates.ChannelStatus
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateCheckResult
import io.payanam.shared.updates.UpdateOutcome
import io.payanam.shared.updates.resolveUpdateResult
import kotlinx.coroutines.CancellationException
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

/** Map a GitHub tag name to a channel, or null for non-channel tags. */
private val PERSISTENT_TAG_REGEX = Regex("""^(dev|beta|stable)-v(\d+)$""")
private val STABLE_TAG_REGEX = Regex("""^v(\d+)$""")

/**
 * Reads the release channel out of a GitHub tag name.
 *
 * Two spellings are accepted, because this repo publishes both: a persistent release tag
 * (`dev-v12` / `beta-v12` / `stable-v12`) resolves to that channel, and a bare version tag
 * (`v12`) is treated as [UpdateChannel.STABLE] - it is a plain release with no channel prefix.
 * Any other tag returns `null`, so a caller can tell "not a release tag at all" apart from
 * "a release whose channel could not be read".
 */
fun channelFromTag(tagName: String): UpdateChannel? =
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
private val logger: UnifiedLogger by lazy { AppLogger.current }

/*
 * Trusted-URL policy.
 *
 * Two URLs in the update path come from the network and are attacker-influenced
 * if the response is tampered with: the pagination `next` URL out of the `Link`
 * header, and a release asset URL out of the release JSON. Both are re-checked
 * against this project's origin before they are fetched or handed to a
 * downloader. One rule, both call sites.
 *
 * Pure string parsing on purpose: no `android.*` and no `java.net`, because this
 * file is shared with the desktop build.
 */

/**
 * The releases list endpoint, and the single source of truth for the trusted
 * host and path: the policy below derives them from this constant, so the
 * expectation can never drift from the URL the checker actually fetches.
 */
internal const val RELEASES_API_URL = "https://api.github.com/repos/Aravinth-Earth/Payanam/releases?per_page=100"

/** Only TLS is trusted; `http`, `file`, `content` and every other scheme are refused. */
private const val TRUSTED_SCHEME = "https"

/** `/repos` prefixes a GitHub REST repo URL; stripping it yields the matching web path. */
private const val REPOS_PATH_SEGMENT = "/repos"

/**
 * Host that serves this project's release-asset downloads.
 *
 * Deliberately NOT derived from [RELEASES_API_URL]: the API lives on
 * `api.github.com` while an asset URL lives on `github.com`.
 */
private const val ASSET_HOST = "github.com"

/** The parts of an absolute URL this policy needs. */
private data class SimpleUrl(
    val scheme: String,
    val host: String,
    val path: String,
)

/** Why a URL was refused. These names are log-safe: they never carry URL text. */
internal enum class UrlRejection {
    /** Not an absolute URL, or shaped so it could read as one host and resolve to another. */
    MALFORMED,

    /** A scheme other than `https`. */
    NOT_HTTPS,

    /** The scheme and path may look right, but the host is not this project's. */
    FOREIGN_HOST,

    /** Right scheme and host, but the path leaves this project's releases tree. */
    OUTSIDE_PATH,
}

/**
 * Split [raw] into scheme, host and path, or null when it is not a plain URL.
 *
 * Refuses the shapes that let a URL read as one host and resolve to another:
 * embedded whitespace or backslashes (parser-differential smuggling), userinfo
 * (`https://api.github.com@evil.example`), and an explicit port.
 */
private fun parseSimpleUrl(raw: String): SimpleUrl? {
    val value = raw.trim()
    // Whitespace, control characters and backslashes are parser-differential
    // smuggling vectors (`https://api.github.com\@evil`), so refuse them outright.
    val hasUnsafeChar = value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F || it == '\\' }
    if (value.isEmpty() || hasUnsafeChar) return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val rest = value.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if (authority.isEmpty() || '@' in authority || ':' in authority) return null
    val remainder = rest.substring(authorityEnd)
    val pathEnd = remainder.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) remainder.length else it }
    return SimpleUrl(
        scheme = value.substring(0, schemeEnd).lowercase(),
        host = authority.lowercase(),
        path = remainder.substring(0, pathEnd).ifEmpty { "/" },
    )
}

/** The trusted API target, parsed once from [RELEASES_API_URL]. */
private val TRUSTED_API_TARGET: SimpleUrl? = parseSimpleUrl(RELEASES_API_URL)

/**
 * `<owner>/<repo>/releases/download/` web prefix, derived from the API path so
 * the two expectations stay one fact. Null when the constant is not shaped the
 * way this derivation assumes, which fails the asset check closed.
 */
private val TRUSTED_ASSET_PATH_PREFIX: String? =
    TRUSTED_API_TARGET
        ?.path
        ?.takeIf { it.startsWith("$REPOS_PATH_SEGMENT/") }
        ?.removePrefix(REPOS_PATH_SEGMENT)
        ?.trimEnd('/')
        ?.plus("/download/")

/**
 * Null when [url] may be fetched as a releases API page, otherwise why not.
 *
 * The query string is ignored: pagination appends `?page=N`, and a query is not
 * a place a URL can carry the request somewhere else.
 */
internal fun releasesApiRejection(url: String?): UrlRejection? {
    val target = TRUSTED_API_TARGET ?: return UrlRejection.MALFORMED
    return rejectionOf(url, target.host, target.path, atSegmentBoundary = true)
}

/** Null when [url] may be handed to a downloader as an asset, otherwise why not. */
internal fun assetRejection(url: String?): UrlRejection? {
    val prefix = TRUSTED_ASSET_PATH_PREFIX ?: return UrlRejection.MALFORMED
    return rejectionOf(url, ASSET_HOST, prefix, atSegmentBoundary = false)
}

/** Three-step check shared by both kinds of URL: scheme, then host, then path. */
private fun rejectionOf(
    url: String?,
    expectedHost: String,
    pathPrefix: String,
    atSegmentBoundary: Boolean,
): UrlRejection? {
    val parsed = parseSimpleUrl(url ?: return UrlRejection.MALFORMED) ?: return UrlRejection.MALFORMED
    if (parsed.scheme != TRUSTED_SCHEME) return UrlRejection.NOT_HTTPS
    if (parsed.host != expectedHost) return UrlRejection.FOREIGN_HOST
    val withinPath =
        if (atSegmentBoundary) {
            parsed.path == pathPrefix || parsed.path.startsWith("$pathPrefix/")
        } else {
            parsed.path.startsWith(pathPrefix)
        }
    return if (withinPath) null else UrlRejection.OUTSIDE_PATH
}

/**
 * True when [url] is a page of this project's releases API: `https` on
 * `api.github.com`, under `/repos/Aravinth-Earth/Payanam/releases`.
 */
fun isTrustedReleasesApiUrl(url: String?): Boolean = releasesApiRejection(url) == null

/**
 * True when [url] may be handed to a downloader as one of this project's release
 * assets: `https` on `github.com`, under
 * `/Aravinth-Earth/Payanam/releases/download/`.
 *
 * Deliberately narrow, and deliberately NOT extended to
 * `objects.githubusercontent.com` / `release-assets.githubusercontent.com`:
 * that is where GitHub's asset redirect lands, and the redirect is followed by
 * the downloader, not by this code. The only URL validated here is the one this
 * module hands over, so do not "widen" this check and do not chase the redirect.
 */
fun isTrustedAssetUrl(url: String?): Boolean = assetRejection(url) == null

/** What the page walk does with the `next` URL it was just handed. */
internal enum class NextPageDecision {
    /** The next URL is trusted: continue at it. */
    CONTINUE,

    /** No next URL at all: the list genuinely ended and the scan is complete. */
    END_OF_LIST,

    /** A next page was offered but is not trusted: stop, and the scan is TRUNCATED. */
    REFUSED,
}

/**
 * Decide the walk's next step from a response's `next` URL.
 *
 * [NextPageDecision.REFUSED] is deliberately not [NextPageDecision.END_OF_LIST]:
 * refusing to follow an untrusted URL must never let a truncated scan produce a
 * "no release" or "up to date" verdict, so the caller keeps `scanComplete` false.
 */
internal fun nextPageDecision(nextUrl: String?): NextPageDecision = when {
    nextUrl == null -> NextPageDecision.END_OF_LIST
    isTrustedReleasesApiUrl(nextUrl) -> NextPageDecision.CONTINUE
    else -> NextPageDecision.REFUSED
}

/** Outcome of the type-aware asset selection within one release. */
data class ApkAssetSelection(
    /** Download URL of the `.apk` asset carrying the expected build type, or null. */
    val apkUrl: String? = null,
    /** Download URL of that APK's paired `.sha256` sibling, when the release ships one. */
    val sha256Url: String? = null,
    /** True when the release ships no downloadable `.apk` for the expected build type —
     *  absent, wrong type, or carrying a download URL that failed the trusted-URL
     *  policy (fail-closed). */
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
 * downstream that becomes [UpdateOutcome.TYPE_MISMATCH], never just a null URL.
 */
fun selectApkAssets(
    assets: JSONArray?,
    expectedType: String,
): ApkAssetSelection {
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
 * Apply the asset URL policy to a selection: a URL that is not one of this
 * project's release assets is never handed on. A refused APK URL means the
 * release offers no update at all (fail-closed, [ApkAssetSelection.typeMismatch]);
 * a refused checksum URL alone only drops the checksum.
 *
 * No other asset is tried: swapping to a different asset would be guessing which
 * file the attacker meant.
 */
private fun ApkAssetSelection.withTrustedAssetUrls(
    channel: UpdateChannel,
    buildNumber: Int?,
    runningType: String,
): ApkAssetSelection {
    val apk = apkUrl
    if (apk != null && !isTrustedAssetUrl(apk)) {
        logRefusedAssetUrl("apk", channel, buildNumber, runningType, apk)
        return copy(apkUrl = null, sha256Url = null, typeMismatch = true)
    }
    val sha = sha256Url
    if (sha != null && !isTrustedAssetUrl(sha)) {
        logRefusedAssetUrl("sha256", channel, buildNumber, runningType, sha)
        return copy(sha256Url = null)
    }
    return this
}

/** Fail-closed trace for a refused asset URL: the reason only, never the URL itself. */
private fun logRefusedAssetUrl(
    kind: String,
    channel: UpdateChannel,
    buildNumber: Int?,
    runningType: String,
    url: String,
) {
    logger.w(
        "UpdateChecker.parseReleases",
        "Refused a release asset URL outside the project download path; no update offered (fail closed)",
        mapOf(
            "asset" to kind,
            "channel" to channel.name,
            "build" to (buildNumber ?: -1),
            "runningType" to runningType,
            "reason" to (assetRejection(url)?.name ?: UrlRejection.MALFORMED.name),
        ),
    )
}

/**
 * True when [fileName] names an APK built for [buildType] (`_<type>_` token +
 * `.apk` suffix). Predicate for validating a persisted download URL's stored
 * name before a retry enqueue — legacy/untyped and other-type names fail
 * closed, and a `.sha256` sibling never passes.
 */
fun artifactNameMatchesBuildType(
    fileName: String,
    buildType: String,
): Boolean = fileName.endsWith(".apk") && fileName.contains("_${buildType}_")

/** Fail-closed selection trace: the release has no APK for the running build
 *  type. Logged at `i` because release builds turn `d` off; guarded because the
 *  parse tests call [parseReleases] on a bare JVM without the logger. */
private fun logFailClosedSelection(
    channel: UpdateChannel,
    buildNumber: Int?,
    runningType: String,
    assetTypesSeen: List<String>,
) {
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
 * malformed entries are skipped. Returns NULL when the body is not a JSON
 * array at all (the caller fails closed as a parse error); a valid empty
 * array returns an empty list.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
fun parseReleases(
    body: String,
    runningType: String,
): List<ChannelStatus>? {
    val releases =
        try {
            JSONArray(body)
        } catch (e: JSONException) {
            logger.w("UpdateChecker.parseReleases", "Failed to parse release JSON", mapOf("error" to (e.message ?: "unknown")))
            return null
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
        // A selection whose URL is not one of this project's assets offers no update.
        val trusted = selection.withTrustedAssetUrls(channel, buildNumber, runningType)
        statuses.add(
            ChannelStatus(
                channel = channel,
                buildNumber = buildNumber,
                releaseUrl = htmlUrl,
                apkDownloadUrl = trusted.apkUrl,
                apkSha256Url = trusted.sha256Url,
                typeMismatch = trusted.typeMismatch,
            ),
        )
    }
    return statuses
}

/** One page fetch outcome: [Ok] carries the body plus the next-page URL. */
private sealed interface PageFetch {
    data class Ok(
        val body: String,
        val nextUrl: String?,
    ) : PageFetch

    data class Failed(
        val error: UpdateCheckError,
    ) : PageFetch
}

/** Extract the `rel="next"` URL from a GitHub `Link` header, or null. */
fun nextPageUrl(linkHeader: String?): String? =
    linkHeader?.let { Regex("""<([^>]+)>;[\s]*rel=[\x22]next[\x22]""").find(it)?.groupValues?.get(1) }

/**
 * Checks GitHub releases for app updates across all channels.
 * Walks the releases list endpoint (paginated, up to [MAX_PAGES] pages),
 * parses per-channel statuses with a type-aware asset selection for
 * the running build type, and compares the selected channel's newest build
 * number against the installed version. Every absence is an explicit
 * non-success [UpdateOutcome] — never "up to date".
 */
object UpdateChecker {
    /**
     * Page cap for the walk: the selected channel's newest release is searched
     * page by page (newest first) and the scan stops as soon as it is seen.
     * Reaching the cap without seeing it yields [UpdateOutcome.INDETERMINATE] —
     * a "no release" verdict is never emitted from an incomplete scan.
     */
    private const val MAX_PAGES = 3
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 1_048_576 // 1MB safety cap
    private val logger = AppLogger.current

    /**
     * Fetch release info for ALL channels (paginated list endpoint), then
     * derive the result for the [channel] the user has selected.
     *
     * [runningType] is the build type of this install; it decides which asset
     * each release offers. Passed IN (defaulting to the real running type) so
     * tests can exercise both types explicitly.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
    suspend fun check(
        currentBuildNumber: Int,
        channel: UpdateChannel = UpdateChannel.DEV,
        runningType: String,
    ): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            logger.d(
                "UpdateChecker.check",
                "Starting update check",
                mapOf("currentBuild" to currentBuildNumber, "channel" to channel.name, "runningType" to runningType),
            )
            try {
                // Walk pages (newest first) until the selected channel is seen or
                // the list ends. A verdict from an unfinished scan would be a
                // guess, so scanComplete gates the resolve below.
                val collected = mutableListOf<ChannelStatus>()
                var scanComplete = false
                var url: String? = RELEASES_API_URL
                var page = 0
                while (url != null && page < MAX_PAGES) {
                    page++
                    when (val fetched = fetchPage(url, currentBuildNumber, page)) {
                        is PageFetch.Failed -> {
                            logger.w("UpdateChecker.check", "Page fetch failed", mapOf("page" to page, "error" to fetched.error.name))
                            return@withContext failureResult(fetched.error)
                        }

                        is PageFetch.Ok -> {
                            val statuses = parseReleases(fetched.body, runningType)
                            if (statuses == null) {
                                logger.w("UpdateChecker.check", "Response body is not a release list (fail closed)")
                                return@withContext failureResult(UpdateCheckError.PARSE_ERROR)
                            }
                            collected += statuses
                            if (collected.any { it.channel == channel }) {
                                scanComplete = true
                                break
                            }
                            when (nextPageDecision(fetched.nextUrl)) {
                                NextPageDecision.CONTINUE -> url = fetched.nextUrl
                                NextPageDecision.END_OF_LIST -> {
                                    url = null
                                    scanComplete = true
                                }
                                NextPageDecision.REFUSED -> {
                                    // Stop, but the walk did not reach the end of the list, so
                                    // scanComplete stays false: no verdict is derived from it.
                                    logger.w(
                                        "UpdateChecker.check",
                                        "Refused a pagination URL outside the releases API; walk stopped (fail closed)",
                                        mapOf(
                                            "page" to page,
                                            "reason" to (releasesApiRejection(fetched.nextUrl)?.name ?: UrlRejection.MALFORMED.name),
                                        ),
                                    )
                                    url = null
                                }
                            }
                        }
                    }
                }

                logger.d(
                    "UpdateChecker.check",
                    "Channels parsed",
                    mapOf(
                        "pages" to page,
                        "found" to collected.size,
                        "scanComplete" to scanComplete,
                        "selectedBuild" to (collected.firstOrNull { it.channel == channel }?.buildNumber ?: -1),
                        "selectedTypeMismatch" to (collected.firstOrNull { it.channel == channel }?.typeMismatch == true),
                    ),
                )
                resolveUpdateResult(currentBuildNumber, channel, collected, scanComplete)
            } catch (e: UnknownHostException) {
                logger.w("UpdateChecker.check", "No internet", mapOf("exception" to (e.message ?: "unknown")))
                failureResult(UpdateCheckError.NO_INTERNET)
            } catch (e: SocketTimeoutException) {
                logger.w("UpdateChecker.check", "Timeout", mapOf("exception" to (e.message ?: "unknown")))
                failureResult(UpdateCheckError.TIMEOUT)
            } catch (e: SSLException) {
                logger.w("UpdateChecker.check", "SSL error", mapOf("exception" to (e.message ?: "unknown")))
                failureResult(UpdateCheckError.NO_INTERNET)
            } catch (e: IOException) {
                logger.w("UpdateChecker.check", "IO error", mapOf("exception" to (e.message ?: "unknown")))
                failureResult(UpdateCheckError.NO_INTERNET)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("UpdateChecker.check", "Unexpected error", e)
                failureResult(UpdateCheckError.UNKNOWN)
            }
        }

    /** One page fetch: the body plus the next-page URL, or the failure reason. */
    private fun fetchPage(
        url: String,
        currentBuildNumber: Int,
        page: Int,
    ): PageFetch {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Payanam/$currentBuildNumber")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val responseCode = connection.responseCode
        logger.d("UpdateChecker.check", "Response received", mapOf("code" to responseCode, "page" to page))
        if (responseCode == 403) {
            logger.w("UpdateChecker.check", "Rate limited by GitHub")
            return PageFetch.Failed(UpdateCheckError.RATE_LIMITED)
        }
        if (responseCode == 404) {
            logger.w("UpdateChecker.check", "GitHub returned 404")
            return PageFetch.Failed(UpdateCheckError.GITHUB_UNAVAILABLE)
        }
        if (responseCode !in 200..299) {
            logger.w("UpdateChecker.check", "Unexpected HTTP status", mapOf("code" to responseCode))
            return PageFetch.Failed(UpdateCheckError.GITHUB_UNAVAILABLE)
        }
        val body = readResponseWithLimit(connection.inputStream)
        if (body == null) {
            logger.w("UpdateChecker.check", "Response body exceeds size limit")
            return PageFetch.Failed(UpdateCheckError.PARSE_ERROR)
        }
        return PageFetch.Ok(body, nextPageUrl(connection.getHeaderField("Link")))
    }

    /** Result shape for every non-success path — FAILED with its reason. */
    private fun failureResult(error: UpdateCheckError) =
        UpdateCheckResult(
            outcome = UpdateOutcome.FAILED,
            latestBuildNumber = null,
            releaseUrl = null,
            error = error,
        )

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
