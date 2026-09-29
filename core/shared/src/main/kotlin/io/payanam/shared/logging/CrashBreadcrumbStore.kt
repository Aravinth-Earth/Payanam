//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

/**
 * Storage contract for the crash-safe breadcrumb trail.
 *
 * A breadcrumb is a short "what step was this process on" marker written *before* a risky operation, so
 * that when the process dies without warning the trail is still on disk afterwards. The only thing that
 * distinguishes this from ordinary logging is durability: a session log may be buffered, and a process
 * killed mid-write loses whatever had not been flushed, which is exactly the window a breadcrumb exists
 * to cover. So [record] MUST reach durable storage before it returns.
 *
 * This contract lives in `:core:shared` rather than in the Android module beside the original
 * `io.payanam.common.logging.CrashSafeBreadcrumbs`, for the reason that file's own dependencies dictate:
 * that implementation is `SharedPreferences`-backed (it takes an `android.content.Context` and calls
 * `commit()`), and `:core:common` is an Android library. Depending on it from a JVM target is what once
 * made `:core:ui` unresolvable for the desktop. `:core:shared` is plain kotlin-jvm and already carries the
 * `AppLogger` seam both platforms share, so a contract here is reachable from the desktop and from
 * platform-neutral library code alike.
 *
 * Storage is deliberately unspecified: Android satisfies this with `SharedPreferences.commit()` and the
 * desktop with a file forced to disk. What both must honour is the durability rule above, and the
 * read-and-clear lifecycle in [readAndClear].
 */
interface CrashBreadcrumbStore {
    /**
     * Where this store keeps its trail, as a human-readable string.
     *
     * Reported so a startup log line can name the file or preference the trail came from, which is the
     * first thing anyone debugging a desktop crash asks for. Never a secret, and never a value that has
     * to be parsed - it is for a human reading a log.
     */
    val location: String

    /**
     * Appends one breadcrumb for [stage] (raised by [source], optionally carrying [data]) and returns
     * only once it is durable.
     *
     * The trail is a bounded ring, so an implementation retains the most recent entries and drops the
     * oldest; [CrashBreadcrumbTrail.MAX_LINES] is the shared bound, and
     * [CrashBreadcrumbTrail.trim] is the shared trimming rule. Implementations MAY store less than is
     * passed if their own storage is failing, but MUST NOT throw: [CrashBreadcrumbs.record] is the entry
     * point that swallows and reports a storage failure, and a store that throws underneath it would put
     * a disk failure in the path of the operation the breadcrumb was recording.
     */
    fun record(
        source: String,
        stage: String,
        data: Map<String, Any?> = emptyMap(),
    )

    /**
     * Returns the stored trail oldest-first and clears it in the same call.
     *
     * One call rather than a read followed by a clear, because the two-step form is racy by construction:
     * between the read and the clear a crash loses the trail, and between them a restart records a
     * breadcrumb that then gets replayed as if it predated the crash. Callers drain at startup and then
     * clear, so a trail is attributed to exactly one session.
     */
    fun readAndClear(): List<String>
}

/**
 * A [CrashBreadcrumbStore] that records nothing.
 *
 * The default for platform-neutral library code, exactly as [NoOpUnifiedLogger] is the default for
 * [UnifiedLogger]: a shared module must be able to call `CrashBreadcrumbs.record(...)` without the host
 * app having installed a store, and must not reach for a platform singleton to do it. A read before an
 * install therefore yields an empty trail rather than an exception.
 */
object NoOpCrashBreadcrumbStore : CrashBreadcrumbStore {
    override val location: String = "disabled"

    override fun record(
        source: String,
        stage: String,
        data: Map<String, Any?>,
    ) = Unit

    override fun readAndClear(): List<String> = emptyList()
}
