//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.updates

/**
 * Closed classification of an update check — consumers route on this enum, so no branch can classify
 * an absent result as "up to date": a verdict is either positive ([UPDATE_AVAILABLE]),
 * proven-neutral ([UP_TO_DATE]) or explicitly non-success.
 */
enum class UpdateOutcome {
    /** The newest release found is newer than this install and ships an APK this build can install. */
    UPDATE_AVAILABLE,

    /** The newest release found is not newer than this install. */
    UP_TO_DATE,

    /** The newest release found is newer, but ships no APK of this install's build type. */
    TYPE_MISMATCH,

    /** The scan completed and found no release whose tag carries a version. */
    NO_RELEASE,

    /** The page cap was reached before any release was seen — cannot conclude. */
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

/** One release parsed from the GitHub releases list: the version its tag declares and its asset selection. */
data class ReleaseStatus(
    /** Canonical `major.minor.patch`, taken from the tag. */
    val versionName: String,
    /** [versionName] packed as `major * 1_000_000 + minor * 1_000 + patch`, the same rule as the build. */
    val versionCode: Int,
    val releaseUrl: String?,
    /** Direct download URL of the APK asset (from the release's assets list). */
    val apkDownloadUrl: String? = null,
    /** Download URL of the selected APK's paired `.sha256` asset, when present. */
    val apkSha256Url: String? = null,
    /**
     * True when this release ships no `.apk` asset for the running build type. Selection fails
     * closed: no URL, and no update is offered for it.
     */
    val typeMismatch: Boolean = false,
)

/** Outcome of an update check: the verdict, the newest release found, and its downloadable artifact. */
data class UpdateCheckResult(
    val outcome: UpdateOutcome,
    /** `major.minor.patch` of the newest release found; null when none was found or the check failed. */
    val latestVersionName: String?,
    /** [latestVersionName] packed as `major * 1_000_000 + minor * 1_000 + patch`. */
    val latestVersionCode: Int?,
    val releaseUrl: String?,
    /** Non-null iff [outcome] is [UpdateOutcome.FAILED]. */
    val error: UpdateCheckError?,
    /** APK URL of the newest release; null when it ships no APK for the running build type. */
    val apkDownloadUrl: String? = null,
    /** Checksum sibling of [apkDownloadUrl], when the release ships one. */
    val apkSha256Url: String? = null,
    /** True when the newest release ships no APK for the running build type, so no fetch is possible. */
    val typeMismatch: Boolean = false,
    /** Epoch millis when this result was produced — staleness for the UI. */
    val checkedAtMs: Long = System.currentTimeMillis(),
)
