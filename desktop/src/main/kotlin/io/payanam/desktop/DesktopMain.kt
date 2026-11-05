//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.platform.DesktopPlatformSeams
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbStore
import io.payanam.shared.logging.CrashBreadcrumbTrail
import io.payanam.shared.logging.CrashBreadcrumbs
import kotlinx.coroutines.launch
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
    // The one-time storage-root migration, run before anything resolves paths or opens the database.
    // An existing install's data lives in the legacy layout (~/.local/share/Payanam with a database/
    // child); the resolver now returns the new layout (~/Documents/Payanam with db/, logs/, ...).
    // Without this the app resolves the new location, finds no database, and behaves as a fresh
    // install — the user's data is still on disk but the app no longer looks at it. Idempotent:
    // isMigrationNeeded returns false once the new parent exists, so a second run is a no-op.
    val legacyStorageRoot = DesktopAppPaths.resolveLegacyRootDirectory()
    val newStorageRoot = DesktopAppPaths.resolveRootDirectory()
    if (DesktopStorageRootMigration.isMigrationNeeded(legacyStorageRoot, newStorageRoot)) {
        val migrationReport =
            DesktopStorageRootMigration.performMigration(
                legacyRoot = legacyStorageRoot,
                newParent = newStorageRoot,
                report = { line -> sessionLogger.i(source = "DesktopMain.storageMigration", message = line) },
            )
        sessionLogger.i(
            source = "DesktopMain.storageMigration",
            message = "Storage root migration completed",
            data =
                mapOf(
                    "legacyRoot" to legacyStorageRoot.toString(),
                    "newParent" to newStorageRoot.toString(),
                    "moved" to migrationReport.moved.size,
                    "skipped" to migrationReport.skipped.size,
                    "complete" to migrationReport.isComplete,
                ),
        )
        // Report any refused children honestly — a non-empty target means something already wrote there.
        migrationReport.skipped.forEach { child ->
            sessionLogger.w(
                source = "DesktopMain.storageMigration",
                message = "Migration child skipped: ${child.legacyChildName} → ${child.newChildName} (${child.status})",
                data =
                    mapOf(
                        "legacyChildName" to child.legacyChildName,
                        "newChildName" to child.newChildName,
                        "status" to child.status.name,
                        "detail" to (child.detail ?: ""),
                    ),
            )
        }
    }
    // The pre-session data layer, installed before the first frame. The shared graph composes the
    // database-init screen from the scaffold stage, which resolves before any passphrase or session
    // exists, and `databaseInitViewModel` reads this holder WHILE IT IS BEING CONSTRUCTED — so without
    // a layer in place the first composition throws and a fresh install never reaches the init screen.
    LocalDataLayer.install(DesktopBootstrapDataLayer)
    // The shared feature flags, installed before the window is composed. Android fills the same holder in
    // PayanamApp.onCreate from its build config, so this is the desktop's declared counterpart rather than a
    // second mechanism: without an install the graph and the shared ViewModels (Time, Tasks) read the
    // dataclass default for every flag, which is a value no build chose. The desktop is a full-feature
    // build and takes no build-time capability flag - a capability flag is a compile-time constant on
    // Android and an installed value on every installed platform - so minimal mode is declared here as the
    // false it always resolves to, stated at the one place that installs the holder rather than generated
    // into the build. The rest keep the full-feature-set default, which is what this platform renders.
    PlatformFeatureFlags.install(
        FeatureFlagsState(
            minimalModeEnabled = false,
            // Off, because reminders are not implemented on the desktop - they are documented as a
            // limitation of this platform. The shared `AddTaskScreen` renders its reminder controls
            // behind this flag, so leaving it on would offer a control that does nothing. Honest
            // hiding, not a fake.
            remindersEnabled = false,
            // Both settings-section decisions below are declared rather than left to the dataclass default,
            // because a mount site reading a holder that nothing filled is the failure this whole install
            // exists to prevent. The values match Android's own build config, which carries both as true.
            focusModeSettingsEnabled = true,
            scoreSettingsEnabled = true,
        ),
    )
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
                "version" to DesktopBuildInfo.VERSION_DISPLAY_NAME,
            ),
    )
    singleInstanceLease.recordSessionLogPath(sessionLogger.getLogPath())
    sessionLogger.i(
        source = "DesktopMain.main",
        message = "Desktop application starting",
        data =
            mapOf(
                "processId" to ProcessHandle.current().pid(),
                "version" to DesktopBuildInfo.VERSION_DISPLAY_NAME,
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
                enforceMinimumWindowSize(sessionLogger)
                ReconcileOnWindowResume(sessionLogger)
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
 * capture the previous handler, hand both it and the session logger to
 * [desktopUncaughtExceptionHandler], and install what comes back. The crash policy itself — what gets
 * recorded, in what order, and what the process does next — lives in that factory and is documented
 * there; this function owns only the capture-and-install, including the one log line reporting whether a
 * previous handler existed at all.
 */
private fun installDesktopUncaughtExceptionHandler(sessionLogger: UnifiedLogger) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(desktopUncaughtExceptionHandler(previousHandler, sessionLogger))
    sessionLogger.i(
        source = "DesktopMain.installDesktopUncaughtExceptionHandler",
        message = "Installed default uncaught exception handler",
        data = mapOf("hasPreviousHandler" to (previousHandler != null)),
    )
}

/**
 * Builds the handler that [installDesktopUncaughtExceptionHandler] installs, holding the crash policy
 * itself rather than burying it inside the installer.
 *
 * ## WHY IT IS `internal` RATHER THAN PRIVATE, AND WHY IT IS SEPARATE AT ALL
 *
 * The two halves of this handler's promise are only ever visible together — a breadcrumb the NEXT
 * process replays, and a process that still dies — and neither is observable from outside the JVM once
 * the real default handler has run, because the thing under test would be the test JVM dying with it. So
 * the handler is a value a test can be HANDED, built over a fake [previous] handler that records the call
 * instead of printing a stack and exiting, with the breadcrumb half observed through an in-memory
 * [CrashBreadcrumbStore] installed via [CrashBreadcrumbs.install]. That is the same seam style as
 * [io.payanam.desktop.port.DesktopProcessRestartPort]'s injected `commandOf` / `spawn` / `checkpointAndClose`
 * constructor parameters: the decision and its ordering are what is under test, and the environment that
 * would otherwise end the test JVM is injected rather than invoked.
 *
 * `internal` and not `public`: nothing outside the `:desktop` module has any business building the
 * desktop's crash handler, and the visibility is widened only as far as the test source set needs.
 *
 * ## WHAT IT DOES, IN ORDER, AND WHY THE ORDER IS THE CONTRACT
 *
 * 1. **The breadcrumb, FIRST** — [CrashBreadcrumbs.record] with the throwable's *type and message*, never
 *    its stack. Recorded before the log write on purpose: the trail is forced to the storage device and is
 *    the one record that survives, so a log write that fails must not cost it. A full stack would be
 *    truncated to uselessness by the bounded ring (`CrashBreadcrumbTrail.MAX_LINES`, `MAX_VALUE_LENGTH`) and
 *    would evict the entries that led up to it; `LogSanitizer` redacts what does go in. The full stack
 *    belongs in the log, where it is a real record.
 * 2. **The synchronous crash line**, guarded so a logger that cannot write reports to stderr instead of
 *    replacing the crash with its own failure — the crash is the finding.
 * 3. **[previous] last, unguarded**, so the process still dies the way the JVM would have: the default
 *    handler prints the stack to stderr and exits. Nullable, because a host JVM with no handler at all is
 *    a real case and the installer logs which of the two it found (`hasPreviousHandler`).
 *
 * Deliberately NOT called from here: the database fold. A JVM shutdown hook fires for this death anyway and
 * `checkpointAndCloseForShutdown` is once-only, so folding here would only arbitrate two calls in an order
 * the JVM does not guarantee, and the losing one would be a silent no-op that makes the log read as though
 * no fold happened.
 *
 * @param previous the handler to re-dispatch to, or null when the process had none.
 * @param sessionLogger the process's session logger; tests pass a capturing [UnifiedLogger].
 */
internal fun desktopUncaughtExceptionHandler(
    previous: Thread.UncaughtExceptionHandler?,
    sessionLogger: UnifiedLogger,
): Thread.UncaughtExceptionHandler =
    Thread.UncaughtExceptionHandler { thread, throwable ->
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
                        "version" to DesktopBuildInfo.VERSION_DISPLAY_NAME,
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
        previous?.uncaughtException(thread, throwable)
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
 * 411x891 dp is the viewport the Android app is actually tested on (the M51, and the SM-A176B at its
 * screen-zoom setting), and the desktop pins to it so the two platforms lay out identically; 412x915 dp
 * (Android Studio's "Medium Phone") was the earlier target and is 24 dp taller.
 */
internal const val PHONE_WINDOW_WIDTH_DP = 411

// `internal` rather than `private` so the tests that render the app in-process can size their surface to the
// shipped window instead of a copy of it: a test that hardcodes 891 keeps passing when this line changes, which
// is the one thing a resolution pin must not do. The width above is already `internal` for the same reason.
internal const val PHONE_WINDOW_HEIGHT_DP = 891

/**
 * The desktop's text scale, as a multiplier on `sp` sizes - the counterpart of Android's `font_scale`.
 *
 * Pinned to 0.8 so the desktop renders text at the SAME size the Android app is tested at (the owner's device
 * runs `font_scale = 0.8`, its smallest). Without this the desktop drew every glyph 25% larger than the phone
 * at the same dp window, so the two platforms were never laid out under the same text metrics and a
 * layout comparison measured the difference rather than the app. Applied once at the root
 * (`desktopRootSurface`), so every shared screen - and the copy inside it - scales together.
 */
internal const val DESKTOP_TEXT_SCALE = 0.8f

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
private const val MIN_WINDOW_WIDTH_DP = PHONE_WINDOW_WIDTH_DP
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
private fun FrameWindowScope.enforceMinimumWindowSize(sessionLogger: UnifiedLogger) {
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
 * Re-runs the day-boundary and schedule reconciliation every time this window comes back to the foreground.
 *
 * **The gap is a suspended laptop, and nothing about it looks like a bug.** Before this, the desktop ran
 * `runStartupMaintenance` from exactly one place - the session open - so a machine that suspended across
 * midnight woke up still showing yesterday's recurring tasks and would keep showing them until it was
 * restarted. Android has no such window because `MainActivity.onStart` re-runs its maintenance every time
 * the app returns to the foreground; this is that call, on the desktop's equivalent event.
 *
 * **A [FrameWindowScope] extension, and placed next to [enforceMinimumWindowSize], for one reason: this is
 * the only composition in the app that lives ABOVE `desktopApp`.** The session controller and the platform
 * seams are both built inside `remember` blocks in `DesktopApp.kt`, so a composable under `desktopApp`
 * could not reach either without threading two new parameters through every signature between here and
 * there — and the collaborators object on that path is constructed FIELD BY FIELD in tests, so every one of
 * those constructors would break. Both collaborators publish a process-wide handle instead (the session
 * controller's own shutdown-hook registry, and `DesktopPlatformSeams.install`), which is the pattern this
 * module already established for exactly this problem.
 *
 * **[LifecycleEventObserver] rather than a bare AWT listener**, because Compose Desktop already owns a
 * `Lifecycle` for the window and drives it from the frame's own focus and iconify/deiconify events (verified
 * in `androidx.compose.ui.scene.ComposeContainer`, whose `updateLifecycleState` is called from
 * `windowIconified`, `windowDeiconified`, `addNotify`/`removeNotify`, focus change and dispose). Observing
 * that lifecycle rather than adding a second listener means suspend/resume is reported the way the rest of
 * the Compose stack already experiences it, instead of via a parallel mechanism that could disagree.
 *
 * **ON_RESUME, not ON_START.** `addNotify`/`removeNotify` fire once per window attach, so ON_START is
 * effectively the launch event — and launch already reconciles through `onSessionEstablished`. ON_RESUME is
 * the one that fires again after the frame loses and regains focus, which is what a suspend-then-resume
 * looks like from inside AWT. `updateLifecycleState` moves the registry to RESUMED on focus and STARTED on
 * focus loss, so ON_RESUME is precisely the "we are back" signal here.
 *
 * **`DisposableEffect(window, ...)`, and the observer is removed in `onDispose`**, matching the shape
 * [enforceMinimumWindowSize] uses for the same reason: a window replaced by a new one must not leave an
 * observer bound to the frame that is gone, or a leaked observer would keep reconciling against a window
 * nobody can see.
 *
 * **The work is launched, not awaited.** An observer callback is not a suspend context, and blocking the
 * window's focus handler on a WAL-backed rollover would freeze the frame the user just came back to. Both
 * collaborators are independently guarded - the session maintenance by an in-flight flag, the backup
 * reconcile by `reconcileMutex` - so two overlapping resumes cannot double-run either one.
 */
@Composable
private fun FrameWindowScope.ReconcileOnWindowResume(sessionLogger: UnifiedLogger) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(window, lifecycleOwner, scope) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch {
                        // BOTH, and both are no-ops when their collaborator does not exist yet: on a fresh
                        // launch this observer fires on the window's first focus, which is before the user
                        // has unlocked anything. The backup reconcile also protects the pre-unlock window
                        // by treating an unreadable rows read as "no schedule" rather than failing.
                        DesktopDatabaseSessionController.reconcileProcessSessionOnResume()
                        DesktopPlatformSeams.reconcileInstalledBackupSchedule()
                        sessionLogger.d(
                            source = "DesktopMain.windowResume",
                            message = "Window resumed; day-boundary maintenance and backup schedule reconciled",
                        )
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
     * I/O failures propagate out of this method, and that is a **documented deviation from
     * [CrashBreadcrumbStore.record]**, whose KDoc says an implementation MUST NOT throw. Stated here
     * because the port is the contract and a reader of either file is entitled to know which one this
     * side sits on.
     *
     * The deviation is safe on every call path in this repository, and the reason is named rather than
     * assumed: `record` is never called directly. Every caller — this file's `main` and its uncaught
     * -exception handler, `DesktopDatabaseSessionController`, and the shared database and presentation
     * modules — goes through `CrashBreadcrumbs.record`, whose own KDoc states that it swallows a
     * storage failure and reports it to `AppLogger.current`. So the harm the port's rule exists to
     * prevent, "a disk failure in the path of the operation the breadcrumb was recording", cannot occur
     * through that entry point.
     *
     * Why the store does not swallow anyway: the entry point above is the only layer that knows the
     * breadcrumb's own [source] and [stage], so a failure swallowed HERE would have to be re-reported
     * with data this method no longer has. A store that swallowed its own errors would also make a store
     * that silently stopped recording indistinguishable from one that never had anything to record.
     *
     * (The Android store's own choice is the other one — it swallows and reports through a private
     * `reportFailure`. Both are defensible; this asymmetry is deliberate and unchanged, because
     * changing it would move the reporting point and is not this method's defect.)
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
     * than one that is occasionally replayed once.
     *
     * **A read that succeeded is never thrown away because the clear then failed.** The two are separate
     * `runCatching` blocks, deliberately. Handing the caller an empty list when the file held a trail
     * this process had just read would report the crash the trail exists to explain as one that left no
     * breadcrumbs behind — the caller then blames the absence instead of investigating the trail, and the
     * real evidence is in the file, already drained and discarded. So a failed clear is a *separate*
     * report ([reportDrainFailure]) and never changes what the caller is handed.
     *
     * If the truncate genuinely fails, the trail stays in the file and is reported again next start, as
     * the KDoc above long claimed. That is the honest degradation: replaying a trail is a nuisance,
     * losing one silently is a defect, and the two together would be worse than either.
     *
     * A failed *read* does still answer with an empty list, because there is nothing to return; it is
     * reported through the same channel rather than thrown, matching the store's [record] path. Throwing
     * here would contradict [io.payanam.shared.logging.CrashBreadcrumbs.replayToLoggerAndClear]'s own
     * statement that a store which cannot be read or cleared "is itself the finding" — this store's
     * choice is to *find and report* it rather than to interrupt startup recovery.
     */
    @Synchronized
    override fun readAndClear(): List<String> {
        if (!Files.exists(trailFile)) {
            return emptyList()
        }
        val drained =
            runCatching {
                CrashBreadcrumbTrail.trim(
                    CrashBreadcrumbTrail.parse(Files.readString(trailFile, StandardCharsets.UTF_8)),
                )
            }.getOrElse { failure ->
                reportDrainFailure("read", failure)
                return emptyList()
            }

        // Deliberately its own `runCatching`, and deliberately NOT able to change `drained`: a clear
        // failure is a fact about the file, reported to the session log, while the entries this process
        // demonstrably read are still the caller's answer. Swallowing both in one block was the defect.
        runCatching {
            RandomAccessFile(trailFile.toFile(), "rw").use { file ->
                file.setLength(0)
                file.channel.force(true)
            }
        }.onFailure { failure ->
            reportDrainFailure("clear", failure)
        }
        return drained
    }

    /**
     * Reports a failed drain step to the session log, degrading rather than failing.
     *
     * `AppLogger.current`, not `System.err`. This store holds no logger of its own — the surrounding
     * desktop code reports through the `AppLogger` seam (`AppLogger.current` at this file's
     * `loadBrandIcon`, and in `DesktopSessionControllerOpenerAdapter`), and that is the channel a desktop
     * crash investigation reads: the session log file, which survives the process and which the
     * single-instance lease records the path of. `System.err` is the console a user might have closed, and
     * on a desktop launched from a file manager it goes nowhere a log is ever read. The Android store
     * reports the same class of failure through its session logger too
     * (`SharedPreferencesBreadcrumbStore.reportFailure`), so the two halves of this port now put the
     * failure where a crash investigation can see it.
     *
     * Wrapped because this runs inside a failure handler: a logger that is itself unavailable must not
     * replace a reported drain failure with a thrown one.
     */
    private fun reportDrainFailure(
        step: String,
        failure: Throwable,
    ) {
        runCatching {
            AppLogger.current.w(
                source = LOG_SOURCE,
                message = "Failed to $step the crash-safe breadcrumb trail",
                data =
                    mapOf(
                        "step" to step,
                        "location" to location,
                        "error" to (failure.message ?: "unknown"),
                    ),
            )
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
         * Log source for this store's own failure reports.
         *
         * A constant rather than the class name so a grep of a session log for `DesktopCrashBreadcrumbStore`
         * finds the line the same way it finds every other entry in the file. Named for the class rather
         * than for the method, because the drain reports both a failed read and a failed clear and an
         * investigation should not have to know which one it is reading to know where to look.
         */
        private const val LOG_SOURCE = "DesktopCrashBreadcrumbStore"

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
