//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbStore
import io.payanam.shared.logging.CrashBreadcrumbTrail
import io.payanam.shared.logging.CrashBreadcrumbs
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId

/**
 * Desktop entry point: enforces single-instance, initializes the session
 * logger, and launches the Compose window.
 */
fun main() {
    val singleInstanceResult = DesktopSingleInstanceGuard.acquire()
    if (singleInstanceResult is DesktopSingleInstanceAcquireResult.AlreadyRunning) {
        DesktopSingleInstanceGuard.showAlreadyRunningDialog(details = singleInstanceResult.details)
        return
    }

    val singleInstanceLease =
        (singleInstanceResult as DesktopSingleInstanceAcquireResult.Acquired).lease
    val sessionLogger = UnifiedLogger.initialize()
    // Hand the platform logger to the shared, platform-neutral modules (core/domain, core/scoring),
    // which read AppLogger.current rather than reaching for a platform singleton. Without this their
    // diagnostics would go to the no-op default and be discarded silently in the Linux/Windows app.
    AppLogger.install(sessionLogger)
    // The pre-session data layer, installed before the first frame. The shared graph composes the
    // database-init screen from the scaffold stage, which resolves before any passphrase or session
    // exists, and `databaseInitViewModel` reads this holder WHILE IT IS BEING CONSTRUCTED — so without
    // a layer in place the first composition throws and a fresh install never reaches the init screen.
    LocalDataLayer.install(DesktopBootstrapDataLayer)
    // Breadcrumbs next, at the same position Android uses (PayanamApp.onCreate:109 — right after the
    // logger, before anything that could fail). The trail is a file the NEXT process start replays, so a
    // breadcrumb recorded before a crash is the only record of the steps that led there; that makes the
    // install itself worth a breadcrumb, and makes the drain worth doing before the window is composed.
    val breadcrumbStore =
        DesktopCrashBreadcrumbStore(
            trailFile =
                DesktopAppPaths
                    .resolveRuntimeDirectory()
                    .resolve(CRASH_BREADCRUMB_FILE_NAME),
        )
    CrashBreadcrumbs.install(breadcrumbStore)
    runCatching { CrashBreadcrumbs.replayToLoggerAndClear("DesktopMain.main") }
        .onFailure { failure ->
            // Not allowed to stop startup: a store that cannot be read is a diagnostic gap, and losing
            // the window over it would turn "no breadcrumbs" into "no application".
            sessionLogger.w(
                source = "DesktopMain.main",
                message = "Could not replay the crash-safe breadcrumb trail",
                data = mapOf("reason" to (failure.message ?: "unknown")),
            )
        }
    CrashBreadcrumbs.record(
        source = "DesktopMain.main",
        stage = "desktop_process_start",
        data =
            mapOf(
                "processId" to ProcessHandle.current().pid(),
                "platformBuildNumber" to DesktopBuildInfo.PLATFORM_BUILD_NUMBER,
            ),
    )
    singleInstanceLease.recordSessionLogPath(sessionLogger.getLogPath())
    sessionLogger.i(
        source = "DesktopMain.main",
        message = "Desktop application starting",
        data =
            mapOf(
                "processId" to ProcessHandle.current().pid(),
                "platformBuildNumber" to DesktopBuildInfo.PLATFORM_BUILD_NUMBER,
                "overallBuildNumber" to DesktopBuildInfo.OVERALL_BUILD_NUMBER,
                "buildName" to DesktopBuildInfo.BUILD_NAME,
            ),
    )
    // The uncaught-exception handler, installed HERE and not later: after the logger and the
    // breadcrumb store exist (both above), and before `application { }` composes a single frame, so a
    // throw from window construction, layout, or any first composition is still captured rather than
    // killing the process with only a stderr line. This is Android's own position in substance —
    // `PayanamApp.onCreate` calls `installGlobalCrashLogging` at line 111, after the log/crash setup and
    // before anything that can fail, and the comment there names the same reason: a failure before the
    // handler exists crashes with no log export.
    //
    // Ordering constraint made explicit: installing it before `AppLogger.install` or before
    // `CrashBreadcrumbs.install` would produce a handler whose only record is a line in a logger that
    // does not exist yet, or a breadcrumb trail that nothing would replay.
    installDesktopUncaughtExceptionHandler(sessionLogger)
    try {
        application {
            val initialWindowSize = rememberDesktopWindowSize()
            val desktopWindowState = rememberWindowState(size = initialWindowSize)
            remember(initialWindowSize) {
                sessionLogger.i(
                    source = "DesktopMain.windowSize",
                    message = "Resolved phone-sized initial window size",
                    data =
                        mapOf(
                            "widthDp" to initialWindowSize.width.value,
                            "heightDp" to initialWindowSize.height.value,
                        ),
                )
            }
            val brandIcon = remember { loadBrandIcon() }
            Window(
                onCloseRequest = {
                    sessionLogger.i("DesktopMain.onCloseRequest", "Window close requested")
                    // The orderly quit. Android's ordering, mirrored: the durability work FIRST, then
                    // the exit. `MainActivity.onStop` (app/.../MainActivity.kt:682-685) folds the WAL
                    // and then flushes the logger, in that order, and calls the exit only after — a quit
                    // that exits first would race the very writes it is meant to make durable.
                    flushDurableSessionState(sessionLogger, "DesktopMain.onCloseRequest")
                    exitApplication()
                },
                title = "Payanam ${DesktopBuildInfo.VERSION_DISPLAY_NAME}",
                state = desktopWindowState,
                icon = brandIcon,
            ) {
                EnforceMinimumWindowSize(sessionLogger)
                desktopApp(onQuitRequested = {
                    sessionLogger.i("DesktopMain.onQuitRequested", "Quit requested from the UI")
                    flushDurableSessionState(sessionLogger, "DesktopMain.onQuitRequested")
                    exitApplication()
                })
            }
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
        sessionLogger.e("DesktopMain.main", "Desktop application terminated with error", error)
        throw error
    } finally {
        // The last gate of the same sequence, and the reason the ordering above is not enough on its
        // own: whatever ended the `application { }` block, the session is folded here. Android's
        // equivalent is the lifecycle callback that runs no matter how the process winds down.
        flushDurableSessionState(sessionLogger, "DesktopMain.finally")
        sessionLogger.close()
        singleInstanceLease.close()
    }
}

/**
 * Folds the database session and flushes the log, in that order, for a process that is ending.
 *
 * The ORDER is the point and it is Android's: `MainActivity.onStop` (app/.../MainActivity.kt:682-685)
 * checkpoints the WAL, THEN flushes the log buffer, and the desktop logger's own `close()` then writes
 * the session-end marker. A reverse order would flush a log before the final line describing the fold
 * existed, which is the same class of lie the exit path is meant to eliminate.
 *
 * The database fold itself is reached through the JVM shutdown hook the session controller installs
 * (`DesktopDatabaseSessionController.installProcessShutdownHook`) rather than through a reference held
 * here: the controller is built inside a Composable in `DesktopApp.kt`, so this file has no handle on
 * it. The hook covers every exit — this one, SIGTERM, and `System.exit` — and
 * `checkpointAndCloseForShutdown` is once-only, so calling the flush repeatedly is safe.
 *
 * Never throws. This runs on the way out of `main` and in a `finally`, where an exception would
 * replace the exit the user asked for with a stack trace.
 */
private fun flushDurableSessionState(
    sessionLogger: UnifiedLogger,
    source: String,
) {
    // FIRST the database, exactly as `MainActivity.onStop` (app/.../MainActivity.kt:682) checkpoints
    // the WAL before it flushes the log at line 685. The reverse order would flush a log that does not
    // yet contain the line describing the fold, which is the same class of lie the exit path exists to
    // eliminate. `checkpointAndCloseProcessSession` is a no-op when no session was ever opened.
    runCatching { DesktopDatabaseSessionController.checkpointAndCloseProcessSession() }
    // THEN the log. Desktop's appendEntry is already synchronous and @Synchronized
    // (UnifiedLogger.kt:180-212), so there is no async buffer to drain — the line below has already hit
    // the file by the time this returns, which is the guarantee Android's `logger.flush()` buys.
    runCatching {
        sessionLogger.i(
            source = source,
            message = "Orderly desktop process shutdown; the open session's log was folded first",
        )
    }
}

/**
 * Installs the desktop process's uncaught-exception handler.
 *
 * Mirrors `PayanamApp.installGlobalCrashLogging` (app/.../PayanamApp.kt:271-314) step for step:
 * capture the previous handler, `eSync` the throwable with thread and build context, then run the
 * previous handler so the process still dies the way the JVM would have. The crash is NOT swallowed —
 * swallowing it would leave a corrupt or half-dead AWT event thread and turn a crash into a hang.
 *
 * What differs, and why each difference is forced rather than chosen:
 *
 * - **The breadcrumb.** Android has no `CrashBreadcrumbs`; the desktop does, and the whole point of a
 *   file-forced store is that a line recorded here is still on the device when the process dies and is
 *   replayed by the NEXT start (`CrashBreadcrumbs.replayToLoggerAndClear`, already wired in this
 *   `main`). Recorded BEFORE the log write, so a failing log write cannot cost the breadcrumb.
 * - **Type and message only, never the stack.** The trail is a bounded 80-line ring
 *   (`CrashBreadcrumbTrail.MAX_LINES`) whose values are cut to 180 characters
 *   (`MAX_VALUE_LENGTH`); a full stack would be truncated into uselessness and would dominate the ring
 *   the previous entries occupy. `LogSanitizer` then redacts it on the way in. The full stack belongs
 *   in the log, where it is capped at `ErrorPayload.MAX_CRASH_STACK_FRAMES` and kept as a real record.
 * - **No `exportAllLogs` on a separate thread.** Android needs it because a crash loop leaves the app
 *   unable to start and the ZIP must land in a user-visible directory. On the desktop the session log
 *   is already a plain file at a known path (`getLogPath()`, and the lease records it), and the
 *   synchronous `eSync` above has already written it — the crash line and the breadcrumb are both
 *   durable before the handler returns, which is the guarantee the export was proxying for.
 * - **The fold is deliberately NOT called here.** A JVM shutdown hook fires for this death anyway, and
 *   `checkpointAndCloseForShutdown` is once-only, so calling it here would only arbitrate two calls in
 *   an order the JVM does not guarantee — and the losing one would be a silent no-op that makes the log
 *   read as though no fold happened. The hook is the single place the fold is owned.
 */
private fun installDesktopUncaughtExceptionHandler(sessionLogger: UnifiedLogger) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // FIRST, and the throwable's own type rather than a stack: this is the one line that has to
        // survive to the next process start, so it must not depend on the logger working.
        CrashBreadcrumbs.record(
            source = "DesktopMain.uncaughtException",
            stage = "unhandled_crash",
            data =
                mapOf(
                    "threadName" to thread.name,
                    "threadId" to thread.id,
                    "errorType" to (throwable::class.qualifiedName ?: "Throwable"),
                    "errorMessage" to (throwable.message ?: "No message"),
                ),
        )
        runCatching {
            sessionLogger.eSync(
                source = "DesktopMain.uncaughtException",
                message = "Unhandled crash captured",
                error = throwable,
                data =
                    mapOf(
                        "threadName" to thread.name,
                        "threadId" to thread.id,
                        "platformBuildNumber" to DesktopBuildInfo.PLATFORM_BUILD_NUMBER,
                        "buildName" to DesktopBuildInfo.BUILD_NAME,
                    ),
            )
        }.onFailure { loggingFailure ->
            // The crash is the finding; a log write that failed must not replace it in the output.
            System.err.println(
                "DesktopMain.uncaughtException: could not write the crash line: ${loggingFailure.message}",
            )
        }
        // Then the JVM's own behaviour, so the process still dies the way it would have: the default
        // handler prints the stack to stderr and exits, and a host that installed a handler of its own
        // gets it. Deliberately last, and deliberately unguarded — anything that swallowed this would
        // be the "swallow the crash" bug this exists to close.
        previousHandler?.uncaughtException(thread, throwable)
    }
    sessionLogger.i(
        source = "DesktopMain.installDesktopUncaughtExceptionHandler",
        message = "Installed default uncaught exception handler",
        data = mapOf("hasPreviousHandler" to (previousHandler != null)),
    )
}

/**
 * The desktop window is sized to a real phone's LOGICAL resolution, not to a fraction of the monitor.
 *
 * The design is the phone design, and the whole point of sharing it is that the desktop shows the same
 * thing: at a phone's logical size the layout resolves exactly as it does on the device - same widths,
 * same wrapping, same everything - which is what makes the 27" monitor and the phone feel like one app
 * rather than two renderings of one codebase.
 *
 * Sizing it from the monitor (0.80 x 0.75, as this used to) produced a 2048x1080 window on a 2560x1440
 * screen: not phone-like at all, and the phone UI inside it looked stretched. Sizing it from a phone's
 * PHYSICAL pixel count (1080x2400) would be just as wrong in the other direction - a 1080x2400 dp window
 * renders the phone UI at roughly 2.6x scale, i.e. enormous. The logical size is the number that matters.
 *
 * 412x915 dp is Android Studio's "Medium Phone" (1080x2400 at 420dpi) and 411x891 is the M51 the Android
 * app is actually tested on, so this is a standard target rather than an invented one.
 */
private const val PHONE_WINDOW_WIDTH_DP = 412
private const val PHONE_WINDOW_HEIGHT_DP = 915

/**
 * Headroom kept out of the display so the window still fits together with its title bar and the desktop
 * panel, instead of opening taller or wider than the screen it is on. Only ever reached by a display too
 * small to hold a phone; on anything normal the phone size above is used as-is.
 */
private const val WINDOW_WIDTH_HEADROOM_DP = 40
private const val WINDOW_HEIGHT_HEADROOM_DP = 80

/**
 * The floor the window cannot be dragged below.
 *
 * A phone-shaped layout squeezed narrower than a phone stops being a phone layout: titles ellipsize,
 * the shared screens' two-column arrangements collapse into unreadable slivers, and a dialog ends up
 * wider than the window holding it. So the floor is the phone's own width, and a height at which a
 * list row and a dialog still have room to breathe. This is a floor on USER resizing only - it is
 * applied to the live window, never to the launch size, so the phone-sized window this file opens at
 * (see [rememberDesktopWindowSize]) is unchanged.
 *
 * The phone width doubles as the floor because that is the width the shared layouts were composed for:
 * below it they are not "small", they are broken. Whether phone-shape is the shipped design or a
 * first-run default is still an open design decision; this floor is deliberately
 * compatible with both answers, because the layout is unreadable under the minimum either way.
 */
private const val MIN_WINDOW_WIDTH_DP = 412
private const val MIN_WINDOW_HEIGHT_DP = 480

/**
 * The display space this window may occupy: the screen minus the headroom above, so the window fits
 * together with its title bar and the desktop panel.
 *
 * Shared by the launch size and the minimum so the two cannot disagree: on a display too small to hold
 * the phone, both are clamped by the same available space, and the minimum can never ask for a window
 * the screen cannot show. On any normal display the clamp is never reached.
 */
@Composable
private fun rememberDesktopAvailableDisplaySize(): DpSize {
    val density = LocalDensity.current
    return remember(density) {
        val graphicsConfiguration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = graphicsConfiguration.bounds
        with(density) {
            DpSize(
                width = bounds.width.toDp() - WINDOW_WIDTH_HEADROOM_DP.dp,
                height = bounds.height.toDp() - WINDOW_HEIGHT_HEADROOM_DP.dp,
            )
        }
    }
}

@Composable
private fun rememberDesktopWindowSize(): DpSize {
    val available = rememberDesktopAvailableDisplaySize()
    return remember(available) {
        // Clamped only for displays too small to hold a phone. The clamp is a safety net, not the
        // sizing rule - see the constants above for why the target is fixed.
        DpSize(
            width = min(PHONE_WINDOW_WIDTH_DP.dp, available.width),
            height = min(PHONE_WINDOW_HEIGHT_DP.dp, available.height),
        )
    }
}

/**
 * The minimum window size, in the same clamped dp space as the launch size.
 *
 * Clamped to the available display for the reason [rememberDesktopAvailableDisplaySize] gives: a
 * minimum larger than the screen is not a floor, it is a window that cannot be shown.
 */
@Composable
private fun rememberDesktopMinimumWindowSize(): DpSize {
    val available = rememberDesktopAvailableDisplaySize()
    return remember(available) {
        DpSize(
            width = min(MIN_WINDOW_WIDTH_DP.dp, available.width),
            height = min(MIN_WINDOW_HEIGHT_DP.dp, available.height),
        )
    }
}

/**
 * Holds the window at or above [rememberDesktopMinimumWindowSize] for as long as this composition is
 * alive.
 *
 * A [FrameWindowScope] extension rather than a plain call at the `Window(...)` site, because
 * `androidx.compose.ui.window.Window` takes NO minimumSize parameter in Compose Multiplatform 1.10.2
 * (verified against the artifact this module resolves): the guard has to reach the underlying AWT
 * frame. `FrameWindowScope.window` is a `ComposeWindow`, which extends `javax.swing.JFrame`, so
 * `setMinimumSize` is the supported mechanism - the window manager honours it as a resize constraint
 * rather than as a one-off `setBounds`, which is what a single corrective call after startup would be
 * (the user could drag straight past it again).
 *
 * `DisposableEffect` rather than a bare call, so the constraint is re-applied if the frame is
 * replaced and never outlives the composition that set it.
 */
@Composable
private fun FrameWindowScope.EnforceMinimumWindowSize(sessionLogger: UnifiedLogger) {
    val density = LocalDensity.current
    val minimumSize = rememberDesktopMinimumWindowSize()
    val minimumPixels =
        remember(density, minimumSize) {
            // `dp.roundToPx()` with the Density as the `with` dispatch receiver. The alternative
            // spellings do not compile against Compose 1.10.2: `with(density) { roundToPx(dp) }`
            // and `density.roundToPx(dp)` are both unresolved, because the real member is
            // `Dp.roundToPx()` and the Density-supplied overloads are not public API here.
            with(density) {
                Dimension(
                    minimumSize.width.roundToPx(),
                    minimumSize.height.roundToPx(),
                )
            }
        }
    DisposableEffect(window, minimumPixels) {
        window.minimumSize = minimumPixels
        sessionLogger.i(
            source = "DesktopMain.minimumWindowSize",
            message = "Minimum window size enforced",
            data =
                mapOf(
                    "widthDp" to minimumSize.width.value,
                    "heightDp" to minimumSize.height.value,
                    "widthPx" to minimumPixels.width,
                    "heightPx" to minimumPixels.height,
                ),
        )
        onDispose { }
    }
}

/**
 * Classpath name of the shared window icon. Wired by `resources.srcDir(rootProject.file("assets/branding"))`
 * in `desktop/build.gradle.kts`, so this single file also feeds the packaged installer icon.
 *
 * `internal` rather than file-private so [BrandIconAssetTest] can assert the wiring - the loader below
 * degrades to a log line rather than failing, so nothing else would catch a renamed asset.
 */
internal const val BRAND_ICON_RESOURCE = "payanam-icon-512.png"

/**
 * Loads the window/taskbar icon from the shared brand asset on the classpath.
 *
 * Returns null when the resource is missing or undecodable, so the window still opens with the platform
 * default instead of failing to start — but the miss is logged, never swallowed. A silently absent icon
 * is indistinguishable from a build that forgot to wire the assets, and this one previously *did* fall
 * back to the generic Java icon without anything saying so.
 */
private fun loadBrandIcon(): BitmapPainter? {
    val bytes =
        Thread.currentThread().contextClassLoader?.getResourceAsStream(BRAND_ICON_RESOURCE)?.use {
            it.readBytes()
        }
    if (bytes == null) {
        AppLogger.current.w(
            "DesktopMain.loadBrandIcon",
            "Shared brand icon is not on the classpath; using the platform default window icon",
            mapOf("resource" to BRAND_ICON_RESOURCE),
        )
        return null
    }
    return runCatching { BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap()) }
        .onFailure { failure ->
            AppLogger.current.w(
                "DesktopMain.loadBrandIcon",
                "Shared brand icon could not be decoded; using the platform default window icon",
                mapOf(
                    "resource" to BRAND_ICON_RESOURCE,
                    "error" to (failure::class.simpleName ?: "Throwable"),
                    "reason" to failure.message,
                ),
            )
        }.getOrNull()
}

/**
 * The trail file's name inside `DesktopAppPaths.resolveRuntimeDirectory()`.
 *
 * In the runtime directory rather than the logs directory, deliberately: breadcrumbs are transient crash
 * state that the next start consumes and clears, not a session record anyone reads by browsing. The
 * session log is the durable artefact and lives in logs/; putting a file that is rewritten every launch in
 * a directory the logger also prunes would make the two lifecycles interfere.
 */
private const val CRASH_BREADCRUMB_FILE_NAME = "desktop-crash-breadcrumbs.txt"

/**
 * The desktop's [CrashBreadcrumbStore]: a plain-text trail file forced to disk on every breadcrumb.
 *
 * This exists because Android's `CrashSafeBreadcrumbs` cannot be reached from here. That class is
 * `SharedPreferences`-backed — it takes an `android.content.Context` and calls `commit()` — and it lives in
 * `:core:common`, an Android library module. Depending on it from a JVM target is exactly what once made
 * `:core:ui` unresolvable for the desktop, so the contract went into `:core:shared` (plain kotlin-jvm,
 * already the home of the `AppLogger` seam) and this class supplies the desktop half of it.
 *
 * **The durability mechanism is `force(true)`, and it is the whole point.** The trail only earns its
 * existence by surviving a process that dies without warning: a `kill -9`, an OOM kill, a segfault in
 * native code. A plain `Files.writeString` returns once the bytes are in the page cache, which a power
 * loss or a kernel panic loses; `RandomAccessFile.getChannel().force(true)` blocks until the data and its
 * metadata are on the storage device. A breadcrumb written without it is a breadcrumb that may not be
 * there when it is needed, which is worse than having none because the trail would look complete.
 *
 * Cost is one `fsync`-class barrier per breadcrumb, so this is only affordable because breadcrumbs are
 * recorded at deliberate, coarse steps (before a risky operation) and not per event. The Android store pays
 * the same cost in `commit()`.
 *
 * **Write protocol: append-only, not rewrite.** Each breadcrumb appends one line and forces it. Rewriting
 * the whole ring on every call would be O(trail) per breadcrumb and would need the same guarantee on a much
 * larger write, to protect against a crash *during* the write — the larger surface is the worse trade.
 *
 * **Two independent bounds, for two different failure modes.** The line count is bounded on *read*
 * ([readAndClear] returns the newest [CrashBreadcrumbTrail.MAX_LINES] and then truncates), because the ring
 * is only consumed by a recovery pass. The file size is bounded on *write*: a record that would push the
 * file past [MAX_FILE_BYTES] is skipped outright. A process that is killed daily and never gets far enough
 * to read the trail would otherwise grow the file forever, and the bound has to be enforced somewhere that
 * does not require a successful startup. Both keep the *newest* entries, which are the crash-adjacent ones.
 *
 * Writes are [synchronized] because breadcrumbs are recorded from any thread (a Compose callback, a
 * coroutine, a shutdown hook) and a shared append is not atomic. The lock is on the instance, so two
 * stores over different files never block each other.
 */
class DesktopCrashBreadcrumbStore(
    private val trailFile: Path,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : CrashBreadcrumbStore {
    override val location: String = trailFile.toString()

    /**
     * Appends one breadcrumb line and forces it to the device before returning.
     *
     * I/O failures propagate. The swallowing lives one level up, in `CrashBreadcrumbs.record`, which is
     * the single place that reports a storage failure to the logger — a store that swallowed its own
     * errors would make a store that silently stopped recording indistinguishable from one that never had
     * anything to record.
     */
    @Synchronized
    override fun record(
        source: String,
        stage: String,
        data: Map<String, Any?>,
    ) {
        trailFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val line = CrashBreadcrumbTrail.formatEntry(source = source, stage = stage, data = data, recordedAt = clock(), zone = zone)
        val payload = (line + LINE_TERMINATOR).toByteArray(StandardCharsets.UTF_8)

        // Checked before opening, not after: a write that would exceed the cap must be SKIPPED, not
        // truncated. The tail is where the crash-adjacent breadcrumbs are.
        if (Files.exists(trailFile) && Files.size(trailFile) + payload.size > MAX_FILE_BYTES) {
            return
        }

        RandomAccessFile(trailFile.toFile(), "rw").use { file ->
            file.seek(file.length())
            file.write(payload)
            // force(true) = data AND the metadata needed to read it back (the length). force(false)
            // would leave a line the file's size does not account for, i.e. one readAndClear() would not
            // see — durable-looking and wrong.
            file.channel.force(true)
        }
        restrictToOwner(trailFile)
    }

    /**
     * Reads the trail oldest-first, clears the file, and returns at most [CrashBreadcrumbTrail.MAX_LINES]
     * entries.
     *
     * Clearing is a truncate-and-force rather than a delete: a delete would leave a window where a
     * breadcrumb recorded by the recovering process races the removal and is destroyed, while the
     * truncate is ordered behind the read by the same monitor. A breadcrumb written after the drain is
     * kept — that is the new session's trail, not the old one's.
     *
     * The clear is forced for the same reason the write is: a truncate that has not reached the device
     * can be undone by a crash, and a trail that is silently replayed on every subsequent start is worse
     * than one that is occasionally replayed once. If the truncate genuinely fails, the trail survives and
     * is reported again next start — the honest degradation, since the alternative is losing it silently.
     */
    @Synchronized
    override fun readAndClear(): List<String> {
        if (!Files.exists(trailFile)) {
            return emptyList()
        }
        return runCatching {
            val drained =
                CrashBreadcrumbTrail.trim(
                    CrashBreadcrumbTrail.parse(Files.readString(trailFile, StandardCharsets.UTF_8)),
                )
            RandomAccessFile(trailFile.toFile(), "rw").use { file ->
                file.setLength(0)
                file.channel.force(true)
            }
            drained
        }.getOrElse { failure ->
            // An unreadable or un-truncatable trail is reported, not hidden, but the caller still gets a
            // list: returning empty would read as "no breadcrumbs" and blame the wrong thing.
            System.err.println("DesktopCrashBreadcrumbStore: failed to drain ${trailFile.fileName}: ${failure.message}")
            emptyList()
        }
    }

    /**
     * Tightens the trail file to `0600`, matching the desktop's own analyser for app-private state.
     *
     * Best-effort per attribute: a filesystem that cannot express POSIX modes (Windows, some network
     * mounts) must not make breadcrumbs unwritable, and there the platform ACLs are the boundary. The
     * trail is diagnostic data, so this is hygiene rather than a secret-handling requirement — but the
     * entries are redacted through `LogSanitizer` and still describe what the user was doing, which is
     * worth keeping to the user who owns it.
     */
    private fun restrictToOwner(target: Path) {
        runCatching { Files.setPosixFilePermissions(target, OWNER_ONLY) }
    }

    private companion object {
        private const val LINE_TERMINATOR = "\n"

        /**
         * Hard ceiling on the on-disk trail, in bytes.
         *
         * A ring bound of 80 entries is the *read* bound; this is the independent bound that stops a
         * process killed daily without ever reading the trail from growing the file forever. 80 entries of
         * at most ~180 characters per value sits well inside it, so a healthy file never triggers either.
         */
        private const val MAX_FILE_BYTES: Long = 512L * 1024L

        private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}
