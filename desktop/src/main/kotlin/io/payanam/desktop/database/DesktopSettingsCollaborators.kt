//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.knownAppOwnedCatalogLabels
import io.payanam.core.ui.preferences.localizedCatalogLabel
import io.payanam.desktop.UnifiedLogger
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.preferences.AppPreferencesWriter
import io.payanam.presentation.preferences.DimensionLabelNormalizer
import io.payanam.presentation.settings.DatabaseArtifactUiModel
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.updates.UpdateChannel
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the settings destination needs from the SHELL, in one value.
 *
 * The same arrangement [DesktopPassphraseChangeCollaborators] uses, and for the same reason: this screen is
 * not a viewer of shell state, it WRITES it. A preferences source or a writer opened inside the destination
 * would be a second one - a second set of `app_settings` writes beside the source the rail, the backup
 * schedule and the launch-route rule all read, and a second set of `life_dimensions` updates beside the
 * catalog every other desktop dialog renders from.
 *
 * Three of the four members are therefore the shell's own values, handed in unchanged. The fourth -
 * [databaseFile] - is a path rather than an instance for the reason `DesktopDatabaseInitCollaborators` takes
 * a path: the artifact list is read off the filesystem, and resolving the host's real database directory from
 * inside a destination would let a render probe touch a developer's own data.
 */
internal data class DesktopSettingsCollaborators(
    /**
     * The shell's own mapped preferences, as a parameter to the destination rather than a read of the
     * ambient `LocalAppPreferences`.
     *
     * `AppPreferencesState.isLoading` defaults to `true`, and `SettingsAppearanceSection` and
     * `SettingsDefaultLandingSection` both early-return on it - so a state built from a default constructor
     * renders those two sections permanently empty, with no error and no log line. Passing the shell's own
     * value is what makes them render at all, and it is the same value the shell root installs ambiently, so
     * the two cannot describe two different moments.
     */
    val preferences: AppPreferencesState,
    /** The unlock timeout in force, from the shell's own derive-and-clamp rule over the rows it read. */
    val unlockTimeoutMinutes: Int,
    /** The database file whose artifact family the database section lists. */
    val databaseFile: File,
    /**
     * Runs a suspend write on the caller's behalf, because this holder is built during composition and the
     * shared screen takes its callbacks as plain function types.
     *
     * The shell owns the scope, so a write is not abandoned when this destination leaves the composition -
     * the same rule the transfer controller's own flows follow, and the reason these callbacks cannot be bare
     * `suspend` functions.
     */
    val launchWrite: suspend (suspend () -> Unit) -> Unit = {},
    /**
     * Writes ONE `app_settings` row through the shell's own single-row path.
     *
     * Not the writer: `AppPreferencesWriter` has no session-timeout method, because on Android that row is
     * written from `databaseEncryptionManager` (an Android capability) rather than from the preferences
     * writer. So this one control goes through `DesktopShellCallbacks.onSettingChanged` - the same callback
     * the shell uses for every other single-row write - which means it also runs the shell's own backup
     * reconcile for the keys that trigger it.
     */
    val setSetting: (String, String?) -> Unit = { _, _ -> },
    /**
     * Exports the live database through the SHELL's flow: the AWT destination picker and then the export
     * gateway, with the outcome on the shell's own transfer-status line.
     *
     * Supplied rather than rebuilt because the shell's version already runs on the scope that owns the
     * transfer controller. A picker opened here would be a second modal competing with it.
     */
    val onExportDatabase: () -> Unit = {},
    /**
     * Imports a database file through the SHELL's flow, which verifies the passphrase against the file
     * being copied and republishes the rows inside the swap lock.
     *
     * Absent would be wrong here rather than honest: the desktop has a real import, it is already reachable
     * from its own startup surface, and a row that did nothing while a working flow existed would be the
     * exact gap this destination exists to close.
     */
    val onImportDatabase: () -> Unit = {},
    /**
     * Runs one scheduled-style backup now, through the shell's own in-process backup runner.
     *
     * This is a REAL answer and not a stub: the shell's backup is an in-process run over the export
     * gateway, so "back up now" is one invocation of that runner rather than a request to a scheduler. The
     * shared screen pairs it with [SettingsScreenActions.manualBackupInProgress], which stays false below
     * because the runner returns synchronously.
     */
    val onManualBackupRequested: () -> Unit = {},
    /**
     * Clears the backup-failure banner the SHELL draws above the graph.
     *
     * The banner is the shell's own - it is mounted from `preferences.autoBackupLastErrorMessage`, which the
     * shared mapper derives from the backup status port rather than from a row - so this destination's
     * dismiss has to reach the shell's dismiss. Clearing a desktop-local flag instead would close a dialog
     * and leave the banner on screen, which is the state this callback exists to end.
     */
    val onDismissBackupFailure: () -> Unit = {},
) {
    /**
     * The database card's artifact rows, and the total size behind them.
     *
     * Enumerated by PREFIX from [databaseFile]'s own directory rather than by a declared suffix list,
     * which is Android's rule (`listDatabaseArtifactFiles`): an orphaned `.bak` or leftover temp file
     * beside the live database is exactly what the stale list and the clean button exist for, and a suffix
     * enumeration could never surface one. [DatabaseArtifactUiModel.isActive] is then the declared family
     * test, so the two groups the shared section splits on are the same two groups Android shows.
     */
    fun artifactRows(): List<DatabaseArtifactUiModel> {
        val activeNames = DatabaseArtifacts.filesFor(databaseFile).map { it.name }.toSet()
        return listedArtifacts()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                DatabaseArtifactUiModel(
                    fileName = file.name,
                    sizeKb = file.length() / 1024,
                    lastModifiedLabel =
                        Instant
                            .ofEpochMilli(file.lastModified())
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                            .format(ARTIFACT_DATE_FORMATTER),
                    isActive = file.name in activeNames,
                )
            }
    }

    /** The total size of the listed artifacts in KB - WAL included, exactly as Android measures it. */
    fun databaseSizeKb(): Long = listedArtifacts().sumOf { it.length() } / BYTES_PER_KB

    /**
     * The shared write path, built over the shell's own repositories on every write rather than held.
     *
     * Built per write because [LocalDataLayer.current] is a process-wide holder the desktop RE-INSTALLS on
     * every session swap: a writer built once would keep the repositories of the session it was built over,
     * and a settings write after an unlock would land on the previous database. This is that swap arriving at
     * the settings screen, and it is the reason [DesktopPreferencesSource.setSetting] republishes rather than
     * this holding a second source.
     *
     * [scoreRollupTrigger] is the layer's own, so a weight change recomputes roll-ups the way it does on
     * Android. [labelNormalizer] is the comparison Android's `AppPreferencesViewModel` installs: a rename to a
     * label the app already ships is stored as "revert to the default" rather than as a custom label that
     * stops following translations.
     */
    fun writer(): AppPreferencesWriter =
        AppPreferencesWriter(
            appSettings = LocalDataLayer.current.appSettings,
            lifeDimensions = LocalDataLayer.current.lifeDimensions,
            scoreRollupTrigger = LocalDataLayer.current.scoreRollupTrigger,
            labelNormalizer =
                DimensionLabelNormalizer { canonicalId, candidateLabel, effectiveLanguageTag ->
                    localizedCatalogLabel(canonicalId, effectiveLanguageTag) == candidateLabel ||
                        candidateLabel in knownAppOwnedCatalogLabels(canonicalId)
                },
        )

    /**
     * Builds the shared screen's state data class from THIS platform's measured values.
     *
     * Every value has a desktop source and none is left to a default that would report something the app
     * never checked. The two the shared screen reads in the database card are the same constants the rest of
     * the desktop uses: [PayanamDatabaseSchema.VERSION] is what `DesktopSessionControllerOpenerAdapter`
     * reports as this build's schema, and [PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION] is the shared floor
     * below which both platforms refuse.
     *
     * The three counts are the same three reads Android's `SettingsViewModel.loadDatabaseStats` makes, over
     * the shell's own repositories through the installed layer, and the artifacts are the files on disk -
     * so the section reports what exists rather than a remembered flag.
     */
    suspend fun uiState(): SettingsUiState =
        SettingsUiState(
            currentDatabaseSchemaVersion = PayanamDatabaseSchema.VERSION,
            minimumSupportedSchemaVersion = PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION,
            appVersion = io.payanam.desktop.DesktopBuildInfo.VERSION_DISPLAY_NAME,
            buildNumber = io.payanam.desktop.DesktopBuildInfo.VERSION_CODE,
            // The desktop's in-place update path reports no channel of its own, so STABLE is the channel
            // that names what this platform can actually check. It is a named answer, not a copy of Android's
            // stored row - the desktop has no update-channel row and writes none.
            updateChannel = UpdateChannel.STABLE,
            taskCount =
                LocalDataLayer.current.tasks
                    .getAllTasks()
                    .first()
                    .size,
            timeEntryCount =
                LocalDataLayer.current.timeEntries
                    .getAllTimeEntries()
                    .first()
                    .size,
            noteCount =
                LocalDataLayer.current.notes
                    .getAllNotes()
                    .first()
                    .size,
            databaseSizeKb = databaseSizeKb(),
            databaseArtifacts = artifactRows(),
            // The desktop has no uHabits importer, so there is no imported-habit count to report. The map row
            // stays disabled on the shared section's own rule (`importedUhabitsHabitCount > 0`), which is the
            // honest state rather than a fabricated one.
            importedUhabitsHabitCount = 0,
            unlockSessionTimeoutMinutes = unlockTimeoutMinutes,
            // The biometric slot is left empty on this platform, so the toggle has nothing to switch and
            // reports off - the same reason, not a second reading.
            biometricUnlockEnabled = false,
        )

    /**
     * The files the database section is willing to list: the declared family where it exists, plus the
     * same-directory files sharing the database's name.
     *
     * The family is listed even when [databaseFile] itself does not exist, because a WAL-only database is a
     * readable state on this platform and reporting it as empty would hide the only file there is.
     */
    private fun listedArtifacts(): List<File> {
        val directory = databaseFile.parentFile
        val byName = LinkedHashMap<String, File>()
        DatabaseArtifacts.filesFor(databaseFile).forEach { candidate ->
            if (candidate.exists()) byName[candidate.name] = candidate
        }
        directory
            ?.listFiles()
            ?.filter { it.isFile && it.name.startsWith(databaseFile.name) }
            ?.forEach { byName[it.name] = it }
        return byName.values.toList()
    }

    /**
     * Deletes one STALE artifact, by Android's rule: never a member of the live family.
     *
     * The guard is the whole function. A name in [DatabaseArtifacts]' own family resolves to the live
     * database and is refused with a log line rather than deleted, because "delete" on a row the shared
     * section renders as active is unreachable by construction and a row that could still reach it would be
     * a row that deletes the user's database.
     */
    fun deleteStaleArtifact(fileName: String) {
        val activeNames = DatabaseArtifacts.filesFor(databaseFile).map { it.name }
        val target = databaseFile.parentFile?.resolve(fileName)
        val logger = UnifiedLogger.getInstance()
        if (target == null || fileName in activeNames || !target.isFile || target.parentFile != databaseFile.parentFile) {
            logger.w(
                "DesktopSettingsCollaborators.deleteStaleArtifact",
                "Artifact delete refused: not a stale file in the database directory",
                mapOf("fileName" to fileName),
            )
            return
        }
        runCatching { target.delete() }
            .onSuccess { deleted ->
                logger.i(
                    "DesktopSettingsCollaborators.deleteStaleArtifact",
                    "Stale artifact deleted",
                    mapOf("fileName" to fileName, "deleted" to deleted),
                )
            }.onFailure { failure ->
                logger.e(
                    "DesktopSettingsCollaborators.deleteStaleArtifact",
                    "Stale artifact delete failed",
                    failure,
                    mapOf("fileName" to fileName),
                )
            }
    }

    /**
     * Removes every stale artifact beside the live family, by the same per-file rule as one delete.
     *
     * Reported as a count because that is what the shared section's success line reports, and because a
     * cleanup that deleted nothing is a real outcome rather than a failure.
     */
    fun cleanStaleArtifacts(): Int {
        val activeNames = DatabaseArtifacts.filesFor(databaseFile).map { it.name }.toSet()
        val stale = listedArtifacts().filter { it.name !in activeNames }
        var removed = 0
        stale.forEach { file ->
            deleteStaleArtifact(file.name)
            if (!file.exists()) removed++
        }
        UnifiedLogger
            .getInstance()
            .i(
                "DesktopSettingsCollaborators.cleanStaleArtifacts",
                "Stale artifacts cleaned",
                mapOf("stale" to stale.size, "removed" to removed),
            )
        return removed
    }

    private companion object {
        /** The same stamp format `File.toDatabaseArtifactUiModel` writes on Android. */
        private val ARTIFACT_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

        /** Bytes in a kilobyte, as the size column measures it. */
        private const val BYTES_PER_KB: Long = 1024
    }
}
