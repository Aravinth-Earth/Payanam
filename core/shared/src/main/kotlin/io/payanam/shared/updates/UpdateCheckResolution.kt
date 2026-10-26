//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.updates

/**
 * The release CHECK: the installed version code plus every parsed release in, one verdict out, on every
 * platform.
 *
 * Fail-closed contract: only a release that was actually found and parsed can produce a verdict. A scan
 * that saw no release is [UpdateOutcome.NO_RELEASE] (complete) or [UpdateOutcome.INDETERMINATE] (page cap
 * hit) — never "up to date".
 */
fun resolveUpdateResult(
    installedVersionCode: Int,
    releases: List<ReleaseStatus>,
    scanComplete: Boolean = true,
): UpdateCheckResult {
    val newest = releases.maxByOrNull { it.versionCode }
    val outcome =
        when {
            newest == null && !scanComplete -> UpdateOutcome.INDETERMINATE
            newest == null -> UpdateOutcome.NO_RELEASE
            newest.versionCode > installedVersionCode && newest.typeMismatch -> UpdateOutcome.TYPE_MISMATCH
            newest.versionCode > installedVersionCode -> UpdateOutcome.UPDATE_AVAILABLE
            else -> UpdateOutcome.UP_TO_DATE
        }
    return UpdateCheckResult(
        outcome = outcome,
        latestVersionName = newest?.versionName,
        latestVersionCode = newest?.versionCode,
        releaseUrl = newest?.releaseUrl,
        error = null,
        apkDownloadUrl = newest?.apkDownloadUrl,
        apkSha256Url = newest?.apkSha256Url,
        typeMismatch = newest?.typeMismatch == true,
    )
}
