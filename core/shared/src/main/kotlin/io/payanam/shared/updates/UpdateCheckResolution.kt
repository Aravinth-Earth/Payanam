//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.updates

/**
 * The release CHECK: one channel state in, one three-way answer out, on every platform.
 *
 * WHY THIS LIVES IN SHARED CODE: the comparison is the part the platforms must not be able to disagree
 * about. Each platform owns how it FETCHES and PARSES a releases page; what "newer", "not newer" and
 * "cannot tell" MEAN is one rule, and this is it. It was extracted from the Android settings feature,
 * where the desktop had no way to reach it and a second copy was the only alternative.
 *
 * Fail-closed contract: only a channel release that was actually FOUND and
 * parsed can produce a verdict. A channel absent from the scan is
 * [UpdateOutcome.NO_RELEASE_ON_CHANNEL] (scan complete) or
 * [UpdateOutcome.INDETERMINATE] (page cap hit) — never "up to date". A found
 * release without a parseable build number is [UpdateOutcome.RELEASE_UNREADABLE],
 * likewise never "up to date".
 */
fun resolveUpdateResult(
    currentBuildNumber: Int,
    channel: UpdateChannel,
    statuses: List<ChannelStatus>,
    scanComplete: Boolean = true,
): UpdateCheckResult {
    val selected = statuses.firstOrNull { it.channel == channel }
    val latestBuild = selected?.buildNumber
    val outcome =
        when {
            selected == null && !scanComplete -> UpdateOutcome.INDETERMINATE
            selected == null -> UpdateOutcome.NO_RELEASE_ON_CHANNEL
            selected.typeMismatch -> UpdateOutcome.TYPE_MISMATCH
            latestBuild == null -> UpdateOutcome.RELEASE_UNREADABLE
            latestBuild > currentBuildNumber -> UpdateOutcome.UPDATE_AVAILABLE
            else -> UpdateOutcome.UP_TO_DATE
        }
    return UpdateCheckResult(
        outcome = outcome,
        latestBuildNumber = latestBuild,
        releaseUrl = selected?.releaseUrl,
        error = null,
        channelStatuses = statuses,
    )
}
