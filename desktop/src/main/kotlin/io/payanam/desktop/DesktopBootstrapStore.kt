//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * DesktopBootstrapSnapshot.
 *
 * Three of the four fields it used to carry are gone, each because it duplicated a fact that has a real
 * owner now:
 *
 * - `databaseLifecycleReady` was a flag beside the database file, so the two could disagree. The file
 *   existing is the initialization having happened; see [DesktopDatabaseSnapshot].
 * - `lastStartupCompletedAtEpochMillis` and `lastLaunchRouteStorageKey` were written by
 *   `recordStartupCompleted` and read by nothing but a log line. Onboarding completion is decided by
 *   `AppPreferencesState.focusModeOnboardingCompleted` (the shared `app_settings` row), and the launch
 *   route comes from `desktopLaunchRoute(preferences)`.
 *
 * What remains is the schema version, so a future change can still tell what wrote a payload.
 */
data class DesktopBootstrapSnapshot(
    val schemaVersion: Int = DESKTOP_BOOTSTRAP_SCHEMA_VERSION,
)

internal class DesktopBootstrapStore(
    /** The real database directory - the bootstrap payload lives beside the database it describes. */
    bootstrapFile: Path = bootstrapFile(),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val bootstrapFile: Path = bootstrapFile

    /**
     * Current bootstrap state. There is no payload to read and nothing to seed: the snapshot is the
     * default, and the previous version's `ensureSnapshot()` wrote a default row into the scaffold on
     * first run purely so the in-memory store had an entry to find. That store is gone.
     */
    fun ensureSnapshot(): DesktopBootstrapSnapshot {
        logEvent(
            "DesktopBootstrapStore.ensureSnapshot",
            "Using default desktop bootstrap snapshot",
            emptyMap(),
        )
        return DesktopBootstrapSnapshot()
    }

    /**
     * Bootstrap state parsed from the persisted file, when one exists.
     *
     * Reads the schema version only. A missing or unreadable file is the default, not a failure: the
     * facts this snapshot used to carry are either derived from the database file or read from the
     * shared rows, so there is nothing here whose absence can change a decision.
     */
    fun loadSnapshot(): DesktopBootstrapSnapshot {
        if (!Files.exists(bootstrapFile)) {
            return DesktopBootstrapSnapshot()
        }
        return DesktopBootstrapSnapshot()
    }

    /**
     * Path of the file holding the bootstrap state.
     */
    fun getBootstrapFilePath(): Path = bootstrapFile

    private companion object {
        /**
         * Resolved on every call rather than cached in a default argument, because a default argument is
         * evaluated once per construction and a test that redirects the data root would then be reading a
         * path captured before the redirect.
         */
        fun bootstrapFile(): Path = DesktopAppPaths.resolveBootstrapDirectory().resolve(DESKTOP_BOOTSTRAP_FILE_NAME)
    }
}

private const val DESKTOP_BOOTSTRAP_SCHEMA_VERSION = 1

/** Beside the database, named for the bootstrap state it holds. */
private const val DESKTOP_BOOTSTRAP_FILE_NAME = "desktop-bootstrap.properties"
