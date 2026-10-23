//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.common.logging.UnifiedLogger
import io.payanam.presentation.updates.parseSha256Sidecar
import io.payanam.presentation.updates.sha256Matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The answer to "does this downloaded APK match the digest the release published?".
 *
 * [reason] values are machine codes (`no_sha256_asset`, `sidecar_unavailable`, `apk_unreadable`),
 * never user-facing text: presenting a verdict is the caller's job, so this file holds no copy.
 */
sealed interface ChecksumVerdict {
    /** The downloaded bytes hash to the published digest. */
    data object Verified : ChecksumVerdict

    /** The downloaded bytes hash to something else — the file must not be installed. */
    data object Mismatch : ChecksumVerdict

    /** The release publishes no `.sha256` asset, so there is nothing to compare against. */
    data class Skipped(
        val reason: String,
    ) : ChecksumVerdict

    /** The check could not be completed: sidecar unfetchable/unparseable, or the file is missing/unreadable. */
    data class Unavailable(
        val reason: String,
    ) : ChecksumVerdict
}

/**
 * The install gate: verifies a downloaded update APK against the SHA-256 sidecar the release publishes.
 *
 * An interface rather than a Kotlin `object` so the gate is a normal dependency: the four
 * [ChecksumVerdict] branches are decided by the caller, and a hard-wired singleton made every one of
 * them unreachable from a unit test. Plain `java.io.File` in, verdict out — no `android.*` here.
 */
interface ApkChecksumVerifier {
    /**
     * Verify [apkFile] against the digest published at [sha256Url].
     *
     * A null or blank [sha256Url] means the release publishes no sidecar, reported as
     * [ChecksumVerdict.Skipped]. Never throws: every failure becomes a verdict.
     */
    suspend fun verify(
        apkFile: File,
        sha256Url: String?,
    ): ChecksumVerdict
}

/**
 * The production [ApkChecksumVerifier].
 *
 * The sidecar is a tiny text file beside the APK holding one hex digest (see [parseSha256Sidecar]).
 * "No sidecar published" is deliberately [ChecksumVerdict.Skipped] rather than a failure, so a
 * channel that publishes none still works; every other problem is reported as a verdict, never as
 * an exception. Logging carries the verdict and its reason code only — never the file path, the
 * URL, or the digest.
 */
@Singleton
class DefaultApkChecksumVerifier @Inject constructor() : ApkChecksumVerifier {
    private companion object {
        /** Short connect timeout: a sidecar is a tiny CDN fetch, never a long poll. */
        const val CONNECT_TIMEOUT_MS = 10_000

        /** Short read timeout, for the same reason as [CONNECT_TIMEOUT_MS]. */
        const val READ_TIMEOUT_MS = 10_000

        /** A sidecar is one line of 64 hex characters; this cap only bounds a hostile response. */
        const val MAX_SIDECAR_BYTES = 8 * 1024

        /** Streaming buffer, so neither the APK nor the sidecar is ever held in memory whole. */
        const val BUFFER_SIZE = 8 * 1024

        const val DIGEST_ALGORITHM = "SHA-256"

        const val NO_ASSET = "no_sha256_asset"
        const val SIDECAR_UNAVAILABLE = "sidecar_unavailable"
        const val APK_UNREADABLE = "apk_unreadable"
    }

    private val logger = UnifiedLogger.getInstance()

    /**
     * Verify [apkFile] against the digest published at [sha256Url].
     *
     * A null or blank [sha256Url] means the release publishes no sidecar, reported as
     * [ChecksumVerdict.Skipped]. Never throws: every failure becomes a verdict.
     */
    override suspend fun verify(
        apkFile: File,
        sha256Url: String?,
    ): ChecksumVerdict =
        report(
            if (sha256Url.isNullOrBlank()) ChecksumVerdict.Skipped(NO_ASSET) else compare(sha256Url, apkFile),
        )

    /** Fetch the published digest, then hash the file; the first step that yields nothing decides the verdict. */
    private suspend fun compare(
        sha256Url: String,
        apkFile: File,
    ): ChecksumVerdict =
        when (val expected = expectedDigest(sha256Url)) {
            null -> ChecksumVerdict.Unavailable(SIDECAR_UNAVAILABLE)
            else ->
                when (val actual = digestOf(apkFile)) {
                    null -> ChecksumVerdict.Unavailable(APK_UNREADABLE)
                    else -> if (sha256Matches(expected, actual)) ChecksumVerdict.Verified else ChecksumVerdict.Mismatch
                }
        }

    /** The sidecar's digest; null when it cannot be fetched, or when it carries no parseable digest. */
    private suspend fun expectedDigest(sha256Url: String): String? {
        val body = fetchSidecar(sha256Url) ?: return null
        return parseSha256Sidecar(body)
    }

    /**
     * GET the sidecar body. Null on any transport, redirect-target or HTTP-status problem.
     *
     * Redirects are followed: a release asset URL redirects to the CDN, and this GET carries no
     * credentials or body, so following one leaks nothing.
     */
    @Suppress("SwallowedException") // Intentional: the throwable can quote the URL, so only a reason code is logged
    private suspend fun fetchSidecar(url: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val opened = URL(url).openConnection() as? HttpURLConnection
                if (opened != null) {
                    connection = opened
                    opened.requestMethod = "GET"
                    opened.connectTimeout = CONNECT_TIMEOUT_MS
                    opened.readTimeout = READ_TIMEOUT_MS
                    opened.instanceFollowRedirects = true
                }
                when {
                    opened == null -> null
                    opened.responseCode !in 200..299 -> null
                    else -> readCapped(opened.inputStream)
                }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } finally {
                connection?.disconnect()
            }
        }

    /** Read [stream] as UTF-8, or null when it exceeds [MAX_SIDECAR_BYTES]. Never buffers the stream whole. */
    @Suppress("SwallowedException") // Intentional: a read failure is a null body, reported by the caller as a reason code
    private fun readCapped(stream: InputStream): String? =
        try {
            stream.use { input ->
                val collected = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (collected.size() + read > MAX_SIDECAR_BYTES) return null
                    collected.write(buffer, 0, read)
                }
                collected.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            null
        }

    /** Lower-case hex SHA-256 of [apkFile], streamed; null when it is missing or unreadable. */
    @Suppress("SwallowedException") // Intentional: the throwable can quote the path, so only a reason code is logged
    private suspend fun digestOf(apkFile: File): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!apkFile.isFile || !apkFile.canRead()) return@withContext null
                val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                apkFile.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                digest.digest().toHex()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } catch (e: NoSuchAlgorithmException) {
                null
            }
        }

    /** Log the verdict and its reason code, then hand the verdict back so the caller has one return path. */
    private fun report(verdict: ChecksumVerdict): ChecksumVerdict {
        logger.i("ApkChecksumVerifier.verify", "Checksum verdict", verdict.logData())
        return verdict
    }
}

/** Hex-encode every byte; the same `%02x` convention the app's other digest helper uses. */
private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

/**
 * The log-safe name of a verdict: a label only, never a path, URL or digest.
 *
 * One copy for every install gate, so a log reader sees the same three words whatever path refused
 * the install.
 */
internal fun ChecksumVerdict.logName(): String = when (this) {
    ChecksumVerdict.Verified -> "verified"
    ChecksumVerdict.Mismatch -> "mismatch"
    is ChecksumVerdict.Skipped -> "skipped"
    is ChecksumVerdict.Unavailable -> "unavailable"
}

/**
 * The user-visible failure key for a verdict that must not reach the installer, or null when the
 * download is [ChecksumVerdict.Verified] and may be installed.
 *
 * One key per verdict, because a tampered download and an unconfirmable one are different things to
 * tell the user, and each key already has copy in both string files.
 */
internal fun ChecksumVerdict.installBlockKey(): String? = when (this) {
    ChecksumVerdict.Verified -> null
    ChecksumVerdict.Mismatch -> "checksum_mismatch"
    is ChecksumVerdict.Skipped -> "checksum_absent"
    is ChecksumVerdict.Unavailable -> "checksum_unavailable"
}

/** The log-safe description of a verdict: its name, plus the reason code when it is not decisive. */
private fun ChecksumVerdict.logData(): Map<String, Any?> =
    when (this) {
        ChecksumVerdict.Verified -> mapOf("verdict" to "verified")
        ChecksumVerdict.Mismatch -> mapOf("verdict" to "mismatch")
        is ChecksumVerdict.Skipped -> mapOf("verdict" to "skipped", "reason" to reason)
        is ChecksumVerdict.Unavailable -> mapOf("verdict" to "unavailable", "reason" to reason)
    }
