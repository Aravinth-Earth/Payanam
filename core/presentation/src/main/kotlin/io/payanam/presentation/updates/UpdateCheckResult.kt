//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.presentation.updates

/**
 * Closed classification of an update check — consumers route on this enum, so no branch can classify
 * an absent result as "up to date": a verdict is either positive ([UPDATE_AVAILABLE]),
 * proven-neutral ([UP_TO_DATE]) or explicitly non-success.
 */
enum class UpdateOutcome {
    /** The selected channel's newest release is newer than this install. */
    UPDATE_AVAILABLE,

    /** The selected channel's newest release was FOUND and is not newer. */
    UP_TO_DATE,

    /** The channel's newest release ships no APK of this install's build type. */
    TYPE_MISMATCH,

    /** The scan completed: the selected channel has no release at all. */
    NO_RELEASE_ON_CHANNEL,

    /** The channel's newest release carries no parseable build number. */
    RELEASE_UNREADABLE,

    /** The page cap was reached before the channel was seen — cannot conclude. */
    INDETERMINATE,

    /** The check itself failed; the result's `error` carries the reason. */
    FAILED,
}

/** Error reasons for an update check failure. */
enum class UpdateCheckError {
    NO_INTERNET,
    TIMEOUT,
    GITHUB_UNAVAILABLE,
    RATE_LIMITED,
    PARSE_ERROR,
    UNKNOWN,
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
     * True when this release ships no `.apk` asset for the running build type. Selection fails
     * closed: no URL and no update verdict for this release.
     */
    val typeMismatch: Boolean = false,
)

/** Outcome of an update check: the verdict, latest build/release info, per-channel statuses. */
data class UpdateCheckResult(
    val outcome: UpdateOutcome,
    val latestBuildNumber: Int?,
    val releaseUrl: String?,
    /** Non-null iff [outcome] is [UpdateOutcome.FAILED]. */
    val error: UpdateCheckError?,
    /** Status of every channel parsed from the list endpoint. */
    val channelStatuses: List<ChannelStatus> = emptyList(),
    /** Epoch millis when this result was produced — staleness for the UI. */
    val checkedAtMs: Long = System.currentTimeMillis(),
)
