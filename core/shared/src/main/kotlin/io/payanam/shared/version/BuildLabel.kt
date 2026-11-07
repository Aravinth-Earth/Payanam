//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.version

/**
 * The one build label both platforms draw, on every surface that shows what this install is.
 *
 * Shape: `1.0.0 · f75faced · 2026-11-07 16:24` - the declared `major.minor.patch`, the git short SHA,
 * and the build script's own `yyyyMMdd_HHmmss` stamp, joined by ` · `.
 *
 * WHY IT LIVES HERE: the version row used to be spelled twice and differently - Android's About rendered
 * `VERSION_NAME` alone while the pre-unlock hatch rendered the packed version code as `#1000000`, and
 * the desktop rendered `1.0.0 (f75faced)`. Three shapes for one fact, and the packed number is the one a
 * person cannot read. One function, called by both platforms, is what keeps them from drifting again.
 *
 * A part the build did not record is DROPPED, never filled in: a source-tarball build (no git, no build
 * script stamp) reads `1.0.0`, so the label never claims a date or a commit that does not exist.
 */
object BuildLabel {
    private const val SEPARATOR = " \u00B7 "

    /** The desktop's placeholder for a build made without `-PbuildTimestamp`. */
    private const val MISSING_TIMESTAMP = "00000000_000000"

    /** The root build script's fallback when git is unavailable (source tarball, extracted zip). */
    private const val NO_GIT_SHA = "nogit"

    /**
     * The label for one build. Empty/placeholder parts are omitted; the result is never blank unless
     * every part is missing, which cannot happen for a real artefact.
     */
    fun of(
        versionName: String,
        shortSha: String,
        rawTimestamp: String,
    ): String {
        val parts =
            listOfNotNull(
                versionName.trim().takeIf { it.isNotEmpty() },
                shortSha.trim().takeIf { it.isNotEmpty() && it != NO_GIT_SHA },
                displayTimestamp(rawTimestamp.trim()),
            )
        return parts.joinToString(SEPARATOR)
    }

    /**
     * `20261107_162458` -> `2026-11-07 16:24`, dropping the seconds because a build is identified by the
     * minute. An unexpected shape is shown untouched rather than hidden: a stamp this cannot parse is
     * still more truth than no stamp at all.
     */
    private fun displayTimestamp(raw: String): String? {
        if (raw.isEmpty() || raw == MISSING_TIMESTAMP) return null
        val match = TIMESTAMP.matchEntire(raw) ?: return raw
        val (year, month, day, hour, minute) = match.destructured
        return "$year-$month-$day $hour:$minute"
    }

    private val TIMESTAMP = Regex("""(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})\d{2}""")
}
