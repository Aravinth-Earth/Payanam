//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbStore
import io.payanam.shared.logging.CrashBreadcrumbTrail

/**
 * Android's [CrashBreadcrumbStore]: the `SharedPreferences` trail, with the format and the ring bound
 * taken from [CrashBreadcrumbTrail] rather than from a second private copy of them.
 *
 * The desktop supplies `DesktopCrashBreadcrumbStore` for the same contract, and the reason this class
 * has to exist rather than "Android reuses `CrashSafeBreadcrumbs`" is module shape: `CrashSafeBreadcrumbs`
 * is a bare `object` in an `android.library` module that takes an `android.content.Context` at every
 * entry point, so a JVM target cannot depend on it. `:core:shared` is plain kotlin-jvm, which is where
 * the contract therefore lives; this class is the Android half of it, beside the [UnifiedLogger] that
 * already bridges the same way.
 *
 * **The preference name and key are unchanged from `CrashSafeBreadcrumbs`, on purpose.** `payanam_crash_breadcrumbs`
 * / `trail`, byte for byte. Both writers are still live during the migration off `CrashSafeBreadcrumbs` (the
 * remaining call sites drain in a later slice), and a trail written by one of them has to be replayable by
 * the other. Renaming the preference — the obvious tidy-up while a new class is being written — would
 * strand every breadcrumb recorded by a build between the rename and the release, and the crash that trail
 * exists to explain is the one where that matters. The old object's own format is `"<ts> | <source> |
 * <stage>[ | <details>]"` against this one's `CrashBreadcrumbTrail` rendering of the same fields, so the
 * payloads are interchangeable in both directions; `SharedPreferencesBreadcrumbStoreTest` pins that.
 *
 * **Durability is `commit()`, never `apply()`.** `apply()` returns immediately and the write is flushed by
 * the framework on a background thread, so a process killed in the window between the breadcrumb and that
 * flush loses exactly the step the breadcrumb was raised to record — and leaves a trail that looks complete.
 * `commit()` blocks until the value is on disk. It is affordable only because breadcrumbs are raised at
 * deliberate, coarse steps before a risky operation rather than per event; the desktop store pays the same
 * cost in an `fsync`-class `force(true)`.
 *
 * **Redaction comes from the shared formatter.** `CrashSafeBreadcrumbs` rendered `data` values through a
 * private `sanitize()` that stripped newlines and truncated but redacted nothing, so a breadcrumb could
 * carry a task title or a file path into a preference file outside the session log's review. Routing through
 * [CrashBreadcrumbTrail.formatEntry] means `LogSanitizer` applies here too, for every store implementing the
 * contract rather than only the ones that remember to.
 */
class SharedPreferencesBreadcrumbStore(
    private val context: Context,
) : CrashBreadcrumbStore {
    /**
     * Names the preference, for a human reading the startup log line that reports a replayed trail.
     *
     * A `SharedPreferences` store has no file path to print, so this is the location in the only terms that
     * apply: which preference file and which key, spelled the way a person would name it while grepping a
     * device backup. Never a secret and never a value anything parses.
     */
    override val location: String = "SharedPreferences '$PREFS_NAME' (key '$KEY_TRAIL')"

    /**
     * Appends one breadcrumb and returns only once the trail is on disk.
     *
     * [synchronized] because the sequence is a read-modify-write of the whole ring: two threads recording
     * concurrently would otherwise each read the same previous payload and the second `commit()` would
     * discard the first one's entry — a trail with a hole in it, which is worse than one with a gap because
     * nothing marks the loss. `SharedPreferences` itself is thread-safe; the compound operation is not, and
     * it is the compound operation that is the contract here.
     *
     * Never throws. A breadcrumb is recorded *before* a step is attempted, so the attempt must proceed
     * whether or not the note could be written — a recorder that could fail its caller would turn "the disk
     * is full" into "the app cannot continue". A failure is reported through [reportFailure] instead, which
     * is diagnosable rather than silent.
     */
    @Synchronized
    override fun record(
        source: String,
        stage: String,
        data: Map<String, Any?>,
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = CrashBreadcrumbTrail.parse(prefs.getString(KEY_TRAIL, "").orEmpty())
            val updated = CrashBreadcrumbTrail.trim(existing + CrashBreadcrumbTrail.formatEntry(source, stage, data))
            prefs.edit().putString(KEY_TRAIL, CrashBreadcrumbTrail.render(updated)).commit()
        }.onFailure { failure ->
            reportFailure(source, stage, failure)
        }
    }

    /**
     * Returns the stored trail oldest-first and clears it in the same call.
     *
     * Does not swallow, unlike [record], and that asymmetry is the shared contract's rather than this
     * store's: a trail that cannot be read or cleared during startup recovery IS the finding, and
     * [io.payanam.shared.logging.CrashBreadcrumbs.replayToLoggerAndClear] says so in its own KDoc. The
     * caller wraps it — `PayanamApp.onCreate` does — so a diagnostic gap is logged instead of stopping
     * startup, which is the same position the desktop's `main` takes.
     *
     * Trimmed on read as well as on write, so a payload that arrived over-long — a trail left by a build
     * whose bound was larger, or a hand-edited value — still replays within [CrashBreadcrumbTrail.MAX_LINES]
     * rather than flooding the session log on the start that finally reads it.
     */
    override fun readAndClear(): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = CrashBreadcrumbTrail.trim(CrashBreadcrumbTrail.parse(prefs.getString(KEY_TRAIL, "").orEmpty()))
        prefs.edit().remove(KEY_TRAIL).commit()
        return lines
    }

    /**
     * Reports a storage failure to the Android session logger, degrading rather than failing.
     *
     * The platform logger first, because that is the log an Android crash investigation reads and the one
     * the sibling `CrashSafeBreadcrumbs` used. [AppLogger.current] is the fallback for the window before
     * `PayanamApp.onCreate` installs it — a store failure with nowhere to report it is exactly the silent
     * discard this class exists to avoid, and [AppLogger.current] is guaranteed to be callable there
     * (the no-op default stands in until the real logger is installed). Both calls are wrapped because this
     * runs inside a failure handler: a logger that is itself unavailable must not replace a reported
     * breadcrumb failure with a thrown one.
     */
    private fun reportFailure(
        source: String,
        stage: String,
        failure: Throwable,
    ) {
        val data =
            mapOf(
                "breadcrumbSource" to source,
                "stage" to stage,
                "error" to (failure.message ?: "unknown"),
            )
        runCatching { UnifiedLogger.getInstance().w(LOG_SOURCE, "Failed to persist breadcrumb", data) }
            .onFailure {
                runCatching { AppLogger.current.w(LOG_SOURCE, "Failed to persist breadcrumb", data) }
            }
    }

    private companion object {
        /**
         * Unchanged from `CrashSafeBreadcrumbs`; see the class KDoc. Both must be altered together or not at
         * all — a mismatch makes a trail unreadable by the process that has to replay it.
         */
        private const val PREFS_NAME = "payanam_crash_breadcrumbs"
        private const val KEY_TRAIL = "trail"

        private const val LOG_SOURCE = "SharedPreferencesBreadcrumbStore.record"
    }
}
