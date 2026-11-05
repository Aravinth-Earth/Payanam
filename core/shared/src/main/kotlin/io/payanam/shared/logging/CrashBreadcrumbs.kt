//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

/**
 * Process-wide entry point for crash-safe breadcrumbs, and the seam an app installs at startup.
 *
 * This is the shared counterpart to Android's `io.payanam.common.logging.CrashSafeBreadcrumbs`, holding
 * the same job with none of the Android dependency: platform code calls [record] before a risky step, and
 * the process — if it dies — leaves a trail on disk that the NEXT start replays through [replayToLoggerAndClear].
 *
 * The [install]-once-then-read shape is deliberate and identical to [AppLogger]: a single mutable field
 * rather than a parameter, because the callers are scattered singletons and ViewModels deep in the graph
 * that cannot be handed an instance. It starts as [NoOpCrashBreadcrumbStore], so a call from library code
 * before the host installs a store is a silent no-op rather than a crash — and, importantly, rather than a
 * lie: the no-op store's [CrashBreadcrumbStore.location] is `disabled`, so "breadcrumbs were never
 * installed" is a state a log can report instead of something to guess at.
 *
 * Android is now on this contract too, and only part-way: `SharedPreferencesBreadcrumbStore`
 * (`:core:common`, beside the [AppLogger]-bridging `UnifiedLogger`) implements [CrashBreadcrumbStore] over
 * the same `SharedPreferences` preference `CrashSafeBreadcrumbs` has always used, `PayanamApp.onCreate`
 * installs it right after `AppLogger.install`, and startup drains through [replayToLoggerAndClear]. Both
 * platforms now share [CrashBreadcrumbTrail]'s format, ring bound and redaction, so a trail written on one
 * is readable by the other.
 *
 * What is NOT yet done is the call sites. The heavier Android breadcrumb callers -
 * `DatabaseEncryptionManager` (11), `DatabaseInitTempBackupSupport` (10), `DatabaseImportSupport` (6),
 * `DatabaseSessionManager` (2) - still record through the `CrashSafeBreadcrumbs` object, which keeps its own
 * private formatter and its own (unredacted) value handling. That object is not yet deleted, and must not be
 * until they are drained: the two write the same preference deliberately, so a trail from either is replayable
 * by either, and removing one writer before the others move would strand a trail mid-migration. Until that is
 * finished, Android records through one store by two paths, and the two paths differ in redaction.
 */
object CrashBreadcrumbs {
    /**
     * The store the process records into. Defaults to [NoOpCrashBreadcrumbStore]; an app installs a real
     * one with [install] from its startup path.
     */
    @Volatile
    var current: CrashBreadcrumbStore = NoOpCrashBreadcrumbStore
        private set

    /**
     * Installs [store] process-wide. Call once, early, from the app's startup path.
     *
     * Not idempotent by design. Unlike [AppLogger.install], a second call here would silently discard a
     * trail already recorded into the first store — breadcrumbs pending from the current session would
     * be stranded in a file nothing will ever read again. A process installs once; a test that must
     * re-install calls [resetForTests] first, which is why that method exists separately.
     */
    fun install(store: CrashBreadcrumbStore) {
        current = store
    }

    /**
     * Records one breadcrumb through [CrashBreadcrumbStore.record], reporting a storage failure to
     * [AppLogger.current] instead of propagating it.
     *
     * Swallowing is the contract, not an oversight: the callers are recording a step *about to be
     * attempted*, so the operation itself must proceed whether or not the breadcrumb could be written. A
     * breadcrumb recorder that could fail its caller would turn "the disk is full" into "the app cannot
     * save a note". The failure is reported, so it is diagnosable rather than silent.
     */
    fun record(
        source: String,
        stage: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        runCatching { current.record(source = source, stage = stage, data = data) }
            .onFailure { failure ->
                AppLogger.current.w(
                    source = "CrashBreadcrumbs.record",
                    message = "Failed to persist crash-safe breadcrumb",
                    data =
                        mapOf(
                            "breadcrumbSource" to source,
                            "stage" to stage,
                            "error" to (failure.message ?: "unknown"),
                        ),
                )
            }
    }

    /**
     * Replays the stored trail into [AppLogger.current] as INFO entries, then clears it.
     *
     * Call at startup, before the app does anything else, so the steps that preceded a crash are visible
     * in this session's log. Returns the entries replayed, which is what a startup log line reports as a
     * count; empty when the store holds nothing, which is the normal case for a clean exit.
     *
     * Unlike [record] this one does NOT swallow: it is called during startup recovery, where a store that
     * cannot be read or cleared is itself the finding — hiding it would produce a log with no explanation
     * of the gap. A caller that must not be interrupted wraps it.
     */
    fun replayToLoggerAndClear(source: String = "CrashBreadcrumbs.replayToLoggerAndClear"): List<String> {
        val lines = current.readAndClear()
        if (lines.isEmpty()) {
            return lines
        }
        val logger = AppLogger.current
        logger.i(
            source = source,
            message = "Recovered crash-safe breadcrumb trail",
            data = mapOf("lineCount" to lines.size, "store" to current.location),
        )
        lines.forEachIndexed { index, line ->
            logger.i(source, "Breadcrumb", mapOf("index" to (index + 1), "entry" to line))
        }
        logger.i(source, "Cleared recovered breadcrumb trail")
        return lines
    }

    /**
     * Restores the [NoOpCrashBreadcrumbStore] default.
     *
     * Test-only, for the same shared-JVM reason as [AppLogger.resetForTests]: one test class installing a
     * store would otherwise leak it into the next. Production code must not call this — the whole point
     * is that the store installed at process start is the one that survives.
     */
    fun resetForTests() {
        current = NoOpCrashBreadcrumbStore
    }
}
