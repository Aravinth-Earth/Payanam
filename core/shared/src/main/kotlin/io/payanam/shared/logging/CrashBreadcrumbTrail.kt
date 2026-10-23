//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting and ring-trimming rules shared by every [CrashBreadcrumbStore].
 *
 * The rule lives here rather than in an implementation so two stores cannot drift into trails that need
 * two parsers. Android's `SharedPreferences` store and the desktop's file store are then the same format
 * by construction, and a trail written by one process is readable by the other.
 *
 * Deliberately plain: the format is a line of `field | field | field`, matching the session logs both
 * platforms already write, so a breadcrumb is readable by eye in a log file and needs no tool.
 */
object CrashBreadcrumbTrail {
    /** Longest trail retained, oldest entries dropped beyond this. */
    const val MAX_LINES: Int = 80

    /** Longest single rendered value; longer values are cut so one blob cannot dominate the trail. */
    const val MAX_VALUE_LENGTH: Int = 180

    /** Field separator, chosen because it does not appear in a timestamp or an exception class name. */
    const val FIELD_SEPARATOR: String = " | "

    private const val LINE_SEPARATOR = "\n"

    /** `SimpleDateFormat` is the wrong tool here: it is not thread-safe and this is called on any thread. */
    private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Renders one breadcrumb: `<timestamp> | <source> | <stage>` plus ` | <key>=<value>` pairs when
     * [data] is non-empty.
     *
     * Every caller-supplied field goes through [LogSanitizer] first. A breadcrumb is a smaller log with
     * a weaker owner, not a lesser one: the trail is persisted to a file that outlives the process and is
     * read back during startup recovery, and on the desktop that file is not covered by the same review
     * that the session log gets. Redacting here rather than in the store means the rule holds for every
     * implementation of [CrashBreadcrumbStore], including an Android one written later.
     *
     * [sanitize] is then applied on top for the two properties [LogSanitizer] does not promise: strictly
     * one line (a raw newline or a `\r` in a value would forge a line break and destroy the
     * "one entry per line" property [parse] and the ring bound both rely on) and a length cap. That is a
     * correctness requirement for the trail format, not merely tidiness.
     */
    fun formatEntry(
        source: String,
        stage: String,
        data: Map<String, Any?>,
        recordedAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val head =
            timestampFormatter.format(recordedAt.atZone(zone)) +
                FIELD_SEPARATOR +
                sanitize(LogSanitizer.sanitizeMessage(source)) +
                FIELD_SEPARATOR +
                sanitize(LogSanitizer.sanitizeMessage(stage))
        if (data.isEmpty()) {
            return head
        }
        val details =
            LogSanitizer.sanitizeData(data).entries.joinToString(separator = ",") { (key, value) ->
                "${sanitize(key)}=${sanitize(value)}"
            }
        return "$head$FIELD_SEPARATOR$details"
    }

    /**
     * Keeps the newest [MAX_LINES] entries, preserving order.
     *
     * Newest-last is the invariant, so the retained window is a `takeLast` and not a `take` - dropping
     * the wrong end silently discards the crash-adjacent entries, which are the only ones worth having.
     */
    fun trim(lines: List<String>): List<String> = if (lines.size <= MAX_LINES) lines else lines.takeLast(MAX_LINES)

    /**
     * Renders [value] as a single bounded line: newlines and carriage returns become spaces, and the
     * result is cut to [MAX_VALUE_LENGTH]. Null becomes the literal `null`, matching what both platform
     * loggers print for an absent value.
     */
    fun sanitize(value: Any?): String {
        // A single `toString()` on purpose: the branches a `when` on type would need (CharSequence,
        // Char, Number, Boolean) all render identically, and a breadcrumb carries diagnostics, not
        // values anyone re-parses. A value whose `toString()` throws is the caller's bug, and
        // `CrashBreadcrumbs.record` is the place that reports it - not this formatter, which must never be
        // the reason a breadcrumb is lost.
        val text = value?.toString() ?: "null"
        val flattened =
            text
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
        return if (flattened.length > MAX_VALUE_LENGTH) {
            flattened.take(MAX_VALUE_LENGTH)
        } else {
            flattened
        }
    }

    /**
     * Splits a stored payload back into entries, dropping blank lines.
     *
     * Blank lines are dropped rather than preserved because a store may legitimately hold an empty file
     * (nothing recorded yet, or just drained), and `""` is what that reads as. An entry that is genuinely
     * blank carries no diagnostic, so nothing is lost.
     */
    fun parse(payload: String): List<String> = payload.split(LINE_SEPARATOR).filter { it.isNotBlank() }

    /**
     * Renders [lines] back into the single string a store persists, so the empty trail is stored as an
     * empty string rather than as a lone separator.
     */
    fun render(lines: List<String>): String = lines.joinToString(separator = LINE_SEPARATOR)
}
