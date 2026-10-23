//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.updates.UpdateChannel
import io.payanam.presentation.updates.UpdateCheckResult
import io.payanam.presentation.updates.UpdateOutcome
import io.payanam.shared.platform.ApkUpdateSource
import io.payanam.shared.platform.InstallHandoff
import io.payanam.shared.platform.InstallRefusal
import io.payanam.shared.platform.SupportState
import io.payanam.shared.platform.UpdateAvailability
import io.payanam.shared.platform.UpdateDownload
import kotlinx.coroutines.delay
import java.io.File

/** Mime type the system package installer answers for. */
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Suffix of the authority this app publishes its downloaded artifacts under. */
private const val PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

/** Interval between progress queries against a download that is still running. */
private const val DOWNLOAD_POLL_INTERVAL_MS = 1_000L

/**
 * How many polls a single fetch may spend waiting before it gives up and reports no artifact.
 *
 * A poll costs one system query, so this is a wall-clock ceiling of roughly [DOWNLOAD_POLL_INTERVAL_MS]
 * multiplied by this count. It is bounded on purpose: an unbounded wait would turn a download the system
 * silently abandons into a call that never returns, which is worse than refusing to install.
 */
private const val MAX_DOWNLOAD_POLLS = 1_800

/**
 * Android's answer to the shared update-source port: a real self-install handoff, wrapped around the
 * pieces this app already ships.
 *
 * WHAT THIS PLATFORM CAN DO, IN ONE LINE: check a release channel, fetch an APK into its own storage and
 * hand that file to the system installer. Every other answer below is derived from that fact, never from
 * the platform's name.
 *
 * THE TWO ANSWERS THE PORT ASKS FOR ARE SEPARATE ON PURPOSE. [state] is about the INSTALLER, so a build
 * installed by a store answers [SupportState.DECLINES]: the client that installed this app owns its
 * updates, and an app that replaced itself behind that client's back is the thing the store's signature
 * check exists to prevent. [isNewerBuildAvailable] is about the RELEASE CHANNEL, so it still answers for
 * such a build - knowing a newer release exists is not the same as being allowed to install it, and a
 * screen that can only render one of the two would have to show a lie for a store-managed build.
 *
 * WHY IT WRAPS WHAT ALREADY EXISTS INSTEAD OF REIMPLEMENTING IT: the release walk, the type-aware asset
 * selection, the app-private download directory and the installer's own handoff are all already in this
 * module, already logged, already tested. A second copy of the same walk would be a second answer to
 * compare and a second thing to keep correct; this class owns only the mapping between the platform's
 * vocabulary and the port's, which is the part the port actually needed and did not have.
 *
 * WHY THE CHECK FAILS CLOSED: five of [UpdateOutcome]'s values mean "the check could not conclude". Each
 * maps to [UpdateAvailability.Inconclusive] rather than [UpdateAvailability.UpToDate], because rendering
 * "could not tell" as "you have the latest build" tells a user their build is current when the channel was
 * never read - the exact quiet lie the port's vocabulary keeps the two apart to prevent.
 *
 * WHY THE HANDLE IS A PATH: [UpdateDownload.platformHandle] is opaque to shared code, and this platform
 * needs the artifact's location BEFORE it can ask the system anything - [InstallRefusal.FILE_MISSING] is
 * only answerable by looking at the file. The handle therefore carries the absolute path of the fetched
 * APK, and this class turns that path into the content URI the installer is handed at the last moment.
 *
 * @param context the application context, used for storage, the package manager and the install handoff.
 * @param channel the release channel to ask. Defaults to [UpdateChannel.DEV], the channel this app's
 *   pre-unlock update hatch already treats as its rescue channel: the port asks about the channel without
 *   naming one, and the rescue channel is the one worth answering when nothing has been chosen yet.
 * @param isStoreManaged whether an installer rather than this app answers for updates on this build.
 *   Defaults to the platform's own installer lookup.
 * @param checkForUpdates how the release channel is read. Defaults to the real check; a caller supplies
 *   its own only to answer for a channel this build cannot reach.
 */
class AndroidApkUpdateSource(
    private val context: Context,
    private val channel: UpdateChannel = UpdateChannel.DEV,
    private val isStoreManaged: Boolean = InstallerChecker.isFDroidBuild(context),
    private val checkForUpdates: suspend (currentBuildNumber: Int, channel: UpdateChannel) -> UpdateCheckResult =
        { currentBuildNumber, requested -> UpdateChecker.check(currentBuildNumber, requested) },
) : ApkUpdateSource {
    private val logger = UnifiedLogger.getInstance()

    /**
     * The most recent channel read, kept so a fetch can resolve the artifact the CHECK found.
     *
     * The port hands [downloadNewerBuild] an availability answer, and an answer names a build but not an
     * asset: the URL to fetch lives in the check's own per-channel statuses. Retaining that read is what
     * lets the fetch address the artifact the check actually verified instead of reconstructing a URL from
     * a tag, which is the step that fails closed on an unexpected release name.
     */
    @Volatile
    private var lastChecked: UpdateCheckResult? = null

    /**
     * [SupportState.SUPPORTED] for a build that installs itself, [SupportState.DECLINES] for one whose
     * installer answers for updates.
     *
     * DECLINES rather than NOT_APPLICABLE: the self-install concept exists perfectly well on this platform,
     * and this build simply is not allowed to use it - which is what declining means. Reporting the
     * concept as absent would also hide the real reason, sending a reader to look for a missing feature
     * instead of the store that installed the app.
     */
    override val state: SupportState =
        if (isStoreManaged) SupportState.DECLINES else SupportState.SUPPORTED

    /**
     * The version code the build tooling stamped into this APK, which is the number release tags are
     * compared against.
     *
     * Real rather than a sentinel: a fabricated 0 would read as "always out of date" to anything comparing
     * it, and null would hide a build that genuinely knows its own number.
     */
    override val currentBuildNumber: Int? = BuildConfig.VERSION_CODE

    /**
     * Asks the release channel, and answers in the port's vocabulary without ever reporting an absent
     * result as "up to date".
     *
     * A check that throws is treated exactly like a check that could not conclude: an exception means this
     * platform did not learn anything, which is [UpdateAvailability.Inconclusive] and not a verdict.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: any check failure means "could not conclude", not a verdict
    override suspend fun isNewerBuildAvailable(): UpdateAvailability {
        val current = currentBuildNumber
        if (current == null) {
            logger.w(
                "$SOURCE.isNewerBuildAvailable",
                "No build number to compare against: the channel was not read",
                mapOf("channel" to channel.name),
            )
            return UpdateAvailability.Inconclusive
        }
        val result =
            try {
                checkForUpdates(current, channel)
            } catch (e: Exception) {
                logger.w(
                    "$SOURCE.isNewerBuildAvailable",
                    "The update check did not complete; reporting inconclusive rather than a verdict",
                    mapOf("channel" to channel.name, "error" to (e.message ?: e::class.java.simpleName)),
                )
                return UpdateAvailability.Inconclusive
            }
        lastChecked = result
        return result.toAvailability()
    }

    /**
     * Fetches the artifact the check found, and reports nothing it cannot install from.
     *
     * Every way this can end without an artifact returns null rather than a [UpdateDownload]: a handle to
     * a file that was never fetched is worse than no handle, because it moves the failure to the install
     * step where the user is already waiting on an installer that cannot open it.
     */
    override suspend fun downloadNewerBuild(availability: UpdateAvailability): UpdateDownload? {
        val newer =
            availability as? UpdateAvailability.Newer
                ?: return refuseFetch(
                "Only a newer-build answer names something to fetch",
                mapOf("askedWith" to availability::class.java.simpleName),
            )
        if (!newer.installable) {
            return refuseFetch(
                "The answer says this platform could not install the newer build; nothing was fetched",
                mapOf("newerBuild" to newer.buildNumber),
            )
        }
        if (isStoreManaged) {
            return refuseFetch(
                "This build's installer answers for updates, so this app fetches no artifact to replace itself",
                mapOf("newerBuild" to newer.buildNumber),
            )
        }
        val url = artifactUrlFor(newer.buildNumber)
        if (url == null) {
            return refuseFetch(
                "The check left no downloadable artifact for this build, so there was nothing to fetch",
                mapOf("newerBuild" to newer.buildNumber, "channel" to channel.name),
            )
        }
        val fileName = url.substringAfterLast('/')
        val downloadId =
            AutoDownloadManager.enqueue(context, url, fileName, wifiOnly = false)
                ?: return refuseFetch(
                    "The system download service refused the request, so no artifact was fetched",
                    mapOf("newerBuild" to newer.buildNumber, "fileName" to fileName),
                )
        val fetched = awaitFetchedArtifact(downloadId, newer.buildNumber) ?: return null
        logger.i(
            "$SOURCE.downloadNewerBuild",
            "Fetched a newer build into this app's own storage",
            mapOf("newerBuild" to newer.buildNumber, "fileName" to fetched.name),
        )
        return UpdateDownload(buildNumber = newer.buildNumber, platformHandle = fetched.absolutePath)
    }

    /**
     * Hands the fetched artifact to the system installer and reports whether the handoff actually started.
     *
     * The order is deliberate: the file is checked before any URI is built, so an artifact that is gone
     * costs nothing and is reported as the precise [InstallRefusal.FILE_MISSING] instead of surfacing as
     * an installer failure. The launch is then attempted and its outcome reported - this platform cannot
     * know whether the user completed the install, only whether the system accepted the handoff, so
     * [InstallHandoff.Launched] claims nothing beyond that.
     *
     * EVERY failure past that point is [InstallRefusal.NO_INSTALLER], and deliberately so: whether no
     * activity accepted the artifact or this build cannot publish that location at all, the file is present
     * and nothing can open it, which is exactly what that reason declares. Splitting those cases would
     * name distinctions the contract does not draw.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: any handoff failure means no installer took the artifact
    override fun startInstall(download: UpdateDownload): InstallHandoff {
        if (isStoreManaged) {
            return refuseInstall(
                download,
                InstallRefusal.PLATFORM_NOT_APPLICABLE,
                "This build's installer answers for updates, so a self-install handoff does not apply to it",
            )
        }
        val handle = download.platformHandle
        if (handle.isBlank()) {
            return refuseInstall(
                download,
                InstallRefusal.NO_DOWNLOAD,
                "The download carries no artifact location, so nothing was ever fetched",
            )
        }
        val file = File(handle)
        if (!file.exists()) {
            return refuseInstall(
                download,
                InstallRefusal.FILE_MISSING,
                "The fetched artifact was gone before the installer could be asked for it",
                mapOf("path" to handle),
            )
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_AUTHORITY_SUFFIX", file)
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            logger.i(
                "$SOURCE.startInstall",
                "Handed the fetched build to the system installer",
                mapOf("build" to download.buildNumber, "fileName" to file.name),
            )
            InstallHandoff.Launched
        } catch (e: Exception) {
            refuseInstall(
                download,
                InstallRefusal.NO_INSTALLER,
                "The artifact could not be handed to any installer on this device",
                mapOf("path" to handle),
                error = e,
            )
        }
    }

    /**
     * The artifact URL for [buildNumber], or null when the last channel read did not carry one.
     *
     * Matched on BOTH the channel and the build number: a read may hold several channels and a channel may
     * hold a different build than the one the answer named, and either mismatch would fetch the wrong file.
     */
    private fun artifactUrlFor(buildNumber: Int): String? =
        lastChecked
            ?.channelStatuses
            ?.firstOrNull { it.channel == channel && it.buildNumber == buildNumber }
            ?.apkDownloadUrl

    /**
     * Polls the system download until it reports a finished file, and returns null for every other ending.
     *
     * Terminal states are exactly the two that can never become a file: a failed download, and the poll
     * ceiling above. A pause is not terminal - the system resumes it - so it keeps waiting.
     */
    private suspend fun awaitFetchedArtifact(
        downloadId: Long,
        buildNumber: Int,
    ): File? {
        var poll = 0
        while (poll < MAX_DOWNLOAD_POLLS) {
            when (val state = AutoDownloadManager.queryProgress(context, downloadId)) {
                is DownloadUiState.Downloaded -> {
                    val path = state.localPath
                    if (path == null) {
                        logger.w(
                            "$SOURCE.downloadNewerBuild",
                            "The download finished without a location, so no artifact can be installed",
                            mapOf("build" to buildNumber, "fileName" to state.fileName),
                        )
                        return null
                    }
                    val file = File(path)
                    return if (file.exists()) {
                        file
                    } else {
                        logger.w(
                            "$SOURCE.downloadNewerBuild",
                            "The download reported success but the artifact is not on disk",
                            mapOf("build" to buildNumber, "path" to path),
                        )
                        null
                    }
                }

                is DownloadUiState.Failed -> {
                    logger.w(
                        "$SOURCE.downloadNewerBuild",
                        "The download failed, so no artifact was fetched",
                        mapOf("build" to buildNumber, "reason" to state.message),
                    )
                    return null
                }

                else -> {
                    poll++
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                }
            }
        }
        logger.w(
            "$SOURCE.downloadNewerBuild",
            "The download did not finish within the wait ceiling, so no artifact is offered for install",
            mapOf("build" to buildNumber, "downloadId" to downloadId),
        )
        return null
    }

    /**
     * Maps one channel read onto the port's vocabulary, naming the outcome that produced it.
     *
     * The five non-verdict outcomes each get their own branch on purpose: they are five different reasons a
     * user is not being told their build is current, and collapsing them into one catch-all would make the
     * next added outcome silently join whichever group it was written next to.
     */
    private fun UpdateCheckResult.toAvailability(): UpdateAvailability =
        when (outcome) {
            UpdateOutcome.UPDATE_AVAILABLE -> newerOrInconclusive()

            // The only outcome that was actually read and proved there is nothing newer.
            UpdateOutcome.UP_TO_DATE -> UpdateAvailability.UpToDate

            // The release exists and is newer, but ships no artifact this build could install: a verdict
            // about a file that is not there.
            UpdateOutcome.TYPE_MISMATCH ->
                inconclusive("the release ships no artifact this build could install", outcome.name)

            // A completed scan that found no release for the channel at all.
            UpdateOutcome.NO_RELEASE_ON_CHANNEL ->
                inconclusive("the channel has no release to compare against", outcome.name)

            // A release that was found and carries no comparable build number.
            UpdateOutcome.RELEASE_UNREADABLE ->
                inconclusive("the release carries no comparable build number", outcome.name)

            // The walk hit its page ceiling, so the channel may simply not have been reached yet.
            UpdateOutcome.INDETERMINATE ->
                inconclusive("the scan stopped before the channel was reached", outcome.name)

            // The check itself failed; the reason is logged rather than swallowed.
            UpdateOutcome.FAILED ->
                inconclusive("the check failed", outcome.name, mapOf("error" to (error?.name ?: "unspecified")))
        }

    /**
     * The newer-build answer, or inconclusive when the verdict cannot be backed by a fetchable artifact.
     *
     * A check can report an available update without naming a build or without carrying an asset URL, and
     * neither supports a fetch. Reporting [UpdateAvailability.Newer] anyway would hand shared code an
     * installable build this platform cannot produce a file for.
     *
     * [installable] is also forced to false for a store-managed build. The check knows a newer release
     * exists and that it ships an artifact - it knows nothing about who may install it - so leaving this at
     * true would have this source contradict its own [state] and then refuse the fetch it just advertised.
     * The release still gets reported, which is the part a store-managed build is allowed to know.
     */
    private fun UpdateCheckResult.newerOrInconclusive(): UpdateAvailability {
        val verdictName = outcome.name
        val build = latestBuildNumber
        if (build == null) {
            return inconclusive("the update verdict carries no build number", verdictName)
        }
        val artifact = channelStatuses.firstOrNull { it.channel == channel && it.buildNumber == build }
        if (artifact?.apkDownloadUrl == null) {
            return inconclusive(
                "the update verdict carries no downloadable artifact",
                verdictName,
                mapOf("newerBuild" to build),
            )
        }
        return UpdateAvailability.Newer(buildNumber = build, installable = !isStoreManaged)
    }

    /** Logs why this platform could not conclude, and returns the answer that keeps it off "up to date". */
    private fun inconclusive(
        why: String,
        outcomeName: String,
        data: Map<String, Any?> = emptyMap(),
    ): UpdateAvailability.Inconclusive {
        logger.w(
            "$SOURCE.isNewerBuildAvailable",
            "Update check did not conclude: $why. Reporting inconclusive, not up to date",
            data + mapOf("outcome" to outcomeName, "channel" to channel.name),
        )
        return UpdateAvailability.Inconclusive
    }

    /** Logs a refusal to fetch, and returns the null the port expects for "no artifact". */
    private fun refuseFetch(
        why: String,
        data: Map<String, Any?> = emptyMap(),
    ): UpdateDownload? {
        logger.w("$SOURCE.downloadNewerBuild", "No artifact was fetched: $why", data)
        return null
    }

    /**
     * Logs a refusal to install, and returns it as the port's refusal vocabulary.
     *
     * The underlying exception, when there was one, is carried as data rather than as a throwable: this
     * platform already answers the caller with a refusal, and logging it as a throwable would report an
     * error for a handoff that was correctly declined.
     */
    private fun refuseInstall(
        download: UpdateDownload,
        reason: InstallRefusal,
        why: String,
        data: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ): InstallHandoff {
        val details =
            data +
                mapOf(
                    "build" to download.buildNumber,
                    "reason" to reason.name,
                    "error" to (error?.message ?: error?.let { it::class.java.simpleName }),
                )
        logger.w("$SOURCE.startInstall", "No installer was launched: $why", details)
        return InstallHandoff.Refused(reason)
    }

    /** Log-source prefix, so every line from this class is attributable to it in an export. */
    private companion object {
        const val SOURCE = "AndroidApkUpdateSource"
    }
}