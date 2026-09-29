//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.mapAppPreferences
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.ForgottenPassphraseReset
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.settings.DesktopLanguage
import io.payanam.desktop.database.DesktopBackupStatusPort
import io.payanam.desktop.security.DesktopBiometricUnlockPrompt
import io.payanam.desktop.security.DesktopForgottenPassphraseReset
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.domain.port.BackupStatusStore
import io.payanam.shared.settings.DesktopSettingsContracts
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopThemeMode
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FocusModePreset
import io.payanam.shared.settings.FoundationReadiness
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.transfer.DataModuleSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

internal data class DesktopLifecycleState(
    val bootstrapSnapshot: DesktopBootstrapSnapshot,
    /**
     * Whether the real database file exists, and its size. Derived from the file, never a stored flag -
     * the settings screen used to print an "Initialized" line read from a bootstrap flag that could not
     * agree with the file, and the honest answer is one `Files.exists` away.
     */
    val databaseExists: Boolean,
    val databaseSizeKb: Long,
    val bootstrapFilePath: String,
    val securityFilePath: String,
    val databaseFilePath: String,
    val exportDirectoryPath: String,
)

internal data class DesktopShellRenderState(
    val navigationModel: DesktopNavigationModel,
    val activeRoute: DesktopTopLevelRoute,
    val foundationSnapshot: SettingsFoundationSnapshot,
    /** The SHARED preferences - what every settings surface reads and writes. */
    val preferences: AppPreferencesState,
    /** The unlock timeout in force, by Android's derive-and-clamp rule. */
    val unlockTimeoutMinutes: Int,
    /** The desktop-scoped scoring-model row, read through the shared source. */
    val scoringModel: String,
    /** The one-line write failure to surface, or null; its own slot, never the transfer status. */
    val writeFailure: String?,
    val desktopLifecycleState: DesktopLifecycleState,
    /** The result of the last database export/import, or null before one has run. */
    val transferStatus: String?,
    /**
     * The route to return to after an unlock, captured when the graph asks for a silent unlock.
     *
     * Android's own field, with the same name and the same job. Android ALSO mirrors it into an intent
     * extra; that exists to survive process death, which the desktop has no equivalent of, so this field is
     * the whole mechanism here rather than a reduced copy of it.
     */
    val resumeToRouteAfterUnlock: String? = null,
)

/** Named steps of the forgot-passphrase reset, so an abort can name where it stopped. */
internal enum class ResetStep(
    val label: String,
) {
    RESET_DATABASE_ARTIFACT("reset_database_artifact"),
    SAVE_DEFAULT_SETTINGS("save_default_settings"),
    RESET_SECURITY_STATE("reset_security_state"),
    UPDATE_DATABASE_LIFECYCLE("update_database_lifecycle"),
    RELOAD_SECURITY_SNAPSHOT("reload_security_snapshot"),
    RELOAD_BOOTSTRAP_SNAPSHOT("reload_bootstrap_snapshot"),
}

/**
 * Runs one step of the forgot-passphrase reset, attributing a failure to [stepName] and rethrowing
 * the original failure unchanged. Extracted from the callback so the attribution can be tested
 * without constructing the whole desktop shell.
 *
 * Nothing is logged when the step succeeds: the stores already log their own successes, so a success
 * entry here would duplicate them.
 *
 * The failure's **class name** is recorded but never its message: `IOException` vs
 * `IllegalStateException` is the difference between a disk problem and a bad state, while exception
 * messages routinely carry absolute paths.
 *
 * The catch is deliberately broad — the store calls raise unchecked `IllegalStateException` as well
 * as checked JDBC exceptions (`SQLException` from `DriverManager.getConnection`), and both must be
 * attributed.
 */
@Suppress("TooGenericExceptionCaught")
internal fun runResetStep(
    stepName: ResetStep,
    logger: UnifiedLogger,
    block: () -> Unit,
) {
    try {
        block()
    } catch (failure: Exception) {
        logger.w(
            source = "DesktopAppSupport.onForgotPassphraseReset",
            message = "Passphrase reset aborted",
            data =
                mapOf(
                    "step" to stepName.label,
                    "failure" to failure.javaClass.simpleName,
                    "outcome" to "aborted",
                ),
        )
        throw failure
    }
}

internal data class DesktopShellCallbacks(
    val onRouteSelected: (DesktopTopLevelRoute) -> Unit,
    /**
     * Writes ONE `app_settings` row through the shared preferences source - the settings route's only write
     * path. Separate from [onSettingsChanged], which is the onboarding scaffold's in-memory snapshot.
     */
    val onSettingChanged: (String, String?) -> Unit,
    val onSelectionChanged: (DataModuleSelection) -> Unit,
    val onConfigurePassphrase: (String) -> DesktopPassphraseActionResult,
    val onUnlockPassphrase: (String) -> DesktopPassphraseActionResult,
    val onForgotPassphraseReset: () -> Unit,
    /** SCAFFOLD: advance from the onboarding intro to the database choice. */
    val onOnboardingIntroAdvanced: () -> Unit,
    /** SCAFFOLD: record the chosen data path and continue to the dimension step. */
    val onDatabaseChoiceSelected: (DesktopDatabaseChoice) -> Unit,
    /**
     * The dimension step's rows were confirmed. NOT a success report: the write is suspend and happens
     * outside, so this fires before any row has landed, and the user is only told the setup is done once
     * the write has actually reported. See [buildDesktopShellCallbacks]'s `onDimensionSetupPersist`.
     */
    val onDimensionSetupSaved: (List<DesktopDimensionInput>) -> Unit,
    /**
     * Security-scoped reset offered from the unavailable startup state. Clears the passphrase entry
     * and its lockout counters only - deliberately not [onForgotPassphraseReset], which also wipes
     * the database artifact, settings and bootstrap lifecycle.
     */
    val onSecurityResetRequested: () -> Unit,
    /** Leaves the app. The unavailable state has no forward action by design. */
    val onQuitRequested: () -> Unit,
    val onInitializeDatabase: () -> Unit,
    /**
     * The outcome the Initialize card publishes, or null when the caller has none to publish.
     *
     * Separate from [onInitializeDatabase] and nullable so the gate can render a real answer and a UI
     * test can render the card without one: a caller that cannot produce an outcome publishes nothing
     * rather than a success string it did not earn.
     */
    val onInitializeDatabaseOutcome: (() -> String?)? = null,
    val onCompleteFocusModeOnboarding: (FocusModePreset) -> Unit,
    val onExportDatabase: () -> Unit,
    val onImportDatabase: () -> Unit,
    /** Clears the recorded auto-backup failure so the banner stops showing. */
    val onDismissBackupFailure: () -> Unit,
    /**
     * The graph needs re-auth for [route]; capture it so the user returns there after unlocking.
     *
     * Android's rule, unchanged: blank routes are ignored, and re-requesting the route already captured is
     * a no-op rather than a second write.
     */
    val onSilentUnlockRequested: (String) -> Unit,
    /** The captured route has been returned to; drop it so a later unlock does not reuse it. */
    val onUnlockReturnRouteConsumed: () -> Unit,
)

internal data class DesktopPersistenceStores(
    val bootstrapStore: DesktopBootstrapStore,
    /**
     * The shared backup-status port, the desktop's implementation of the contract Android's
     * `BackupStatusStore` satisfies.
     *
     * It lives here because BOTH the preferences source (which derives the failure banner's two fields
     * through the shared mapper) and the dismiss callback (which clears it) need the SAME instance - a
     * second one would show a banner that its own dismiss cannot clear.
     */
    val backupStatusStore: DesktopBackupStatusPort = DesktopBackupStatusPort(),
    val securityStore: DesktopSecurityStore,
    val databaseStore: DesktopDatabaseStore,
) {
    /**
     * The desktop's passphrase vault, over the same [securityStore] the lockout and the credential live in.
     *
     * It is derived here rather than constructed at each call site because the vault, the forgotten-passphrase
     * reset and the unlock gate must all be talking about ONE store: a second vault over a second store would
     * report a lockout the other does not apply.
     */
    val passphraseVault: PassphraseVault = DesktopPassphraseVault(securityStore)

    /**
     * The reset path, over the SAME vault, so a reset cannot clear a credential the vault still holds.
     *
     * It takes the database file because a forgotten passphrase means the data is unrecoverable without it;
     * the path comes from the store that already resolves it, never from a second guess.
     */
    val forgottenPassphraseReset: ForgottenPassphraseReset =
        DesktopForgottenPassphraseReset(passphraseVault, databaseStore.getDatabaseFilePath())

    /**
     * The desktop biometric seam: an object that refuses out loud rather than reporting a success it cannot
     * produce. Kept as a field so the unlock gate has no reason to reach for a second implementation.
     */
    val biometricUnlockPrompt: BiometricUnlockPrompt = DesktopBiometricUnlockPrompt
}

/**
 * The desktop's preferences source: the shared `app_settings` rows, read and written through the installed
 * data layer and mapped by the SHARED mapper ([mapAppPreferences]).
 *
 * This is the whole of the desktop's settings storage. It keeps no payload of its own - the rows ARE the
 * state - so a value the desktop writes is a value Android reads, and a row Android writes shows up here.
 *
 * It lives in this file rather than in a second owner because this file already owns the settings load, the
 * state holder, the saves and the shell callbacks; a separate class would be a second place to keep in step.
 *
 * Threading: ONE [writeMutex] guards the state on both sides. A write holds it across the repository write AND
 * the state assign, and the load holds the SAME mutex across its read-then-assign, so a slow IO load cannot
 * stamp its pre-write snapshot over a write that just landed. [generation] makes a write that was requested
 * before a database swap recognisable: it is dropped and logged as discarded, never reported as a failure -
 * the swap's own re-read already carries the truth, so a landed write reported as failed would be a lie.
 *
 * @param sessionLogger the desktop session log; keys and failure classes only, never values. Typed as the
 *   SHARED logger interface rather than this module's file-backed class, so a test can drive the source
 *   without a real log file.
 * @param systemLanguageTag the system language, used only when the stored language choice is SYSTEM.
 * @param defaultDebugLoggingEnabled the absent-row default for `debug_logging_enabled`; a released desktop
 *   build defaults to false, where Android's default is `BuildConfig.DEBUG`.
 */
/** Android's clamp for the unlock timeout, mirrored from `databaseEncryptionManager`. */
private const val MIN_UNLOCK_TIMEOUT_MINUTES = 1
private const val MAX_UNLOCK_TIMEOUT_MINUTES = 240

/** The absent-row default for the desktop-scoped scoring row. */
private const val DEFAULT_SCORING_MODEL = "balanced"

/**
 * The `app_settings` row Android sets once a new database's dimension setup is done.
 *
 * `AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED` on the constant rather than a re-declared literal, for the
 * reason [DESKTOP_DIRECTORY_UNWRITABLE]'s neighbours give: a rename or a re-typed value here would have to
 * be noticed in a place nothing else mentions it, while a changed constant is a compile error.
 */
private const val KEY_DATABASE_INIT_COMPLETED = AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED

/**
 * The refusal code for a first-run create with no open session to take the passphrase from.
 *
 * DESKTOP-LOCAL and prefixed, for the reason `DesktopStartupFlow.kt` states: it is not one of
 * `:core:database-jvm`'s `RefusalDetail` codes, and a rename on the other side must not be able to make
 * this message describe a different case.
 */
private const val NO_OPEN_SESSION_DETAIL = "desktop_no_open_session"

/**
 * What the user is told when the Initialize card has no passphrase to create with.
 *
 * A sentence with a next action, because the alternative - the old unconditional "Desktop database
 * initialized." - left a first-run install at a gate that appeared to be progressing and was not.
 */
private const val DESKTOP_NO_OPEN_SESSION_MESSAGE =
    "Unlock Payanam first: the database is created with the passphrase this session opened with."


internal class DesktopPreferencesSource(
    private val sessionLogger: io.payanam.shared.logging.UnifiedLogger,
    private val systemLanguageTag: String?,
    private val defaultDebugLoggingEnabled: Boolean = false,
    /**
     * The shared backup-status port, so the SHARED mapper can derive the failure banner's two fields.
     *
     * `AppPreferencesMapper` reads `autoBackupLastErrorMessage` / `autoBackupLastErrorAt` from
     * `backupStatus?.latestFailure`, NOT from a stored row. The desktop passed `backupStatus = null` there,
     * which is the actual reason the graph's `backupFailureMessage` had nothing to show - passing the
     * parameter alone would have wired a banner that is permanently empty. `DesktopBackupStatusPort`
     * implements this interface and is the desktop's implementation of the same contract Android's
     * `BackupStatusStore` satisfies, so the mapper is unchanged.
     */
    private val backupStatusStore: BackupStatusStore? = null,
    /**
     * Applied every time the rows are (re)read, so the logger gate follows `debug_logging_enabled`.
     *
     * A callback rather than a direct call: the flags live in `platform/`, and a settings source naming them
     * would be the second owner this change exists to remove. It also keeps the gate out of the pre-unlock
     * path, where there are no rows to follow - until a session is open the logger keeps whatever
     * [UnifiedLogger] was initialized with.
     */
    private val onDebugLoggingChanged: (Boolean) -> Unit = {},
    /**
     * The configured `life_dimensions` rows, read through the SHARED dimension repository and mapped by the
     * SHARED mapper — the same two things Android's `AppPreferencesViewModel` does.
     *
     * Injected rather than reached through `LocalDataLayer` for the same reason [backupStatusStore] is: the
     * read is a repository call the caller owns, and a repository that does not exist yet on this platform
     * must be visible as a missing parameter here rather than as an empty list that reads like "the user has
     * no dimensions". Empty is a legitimate value ONLY for a genuinely empty table.
     *
     * SUSPEND because the only real implementation is `observeAllDimensions().first()` — a suspending flow
     * read, the same shape as the `appSettings` read two lines above it. It is called from [reload] and
     * [runDatabaseSwap] under [writeMutex], and the returned rows are held in [dimensions] rather than
     * collected from a Flow, so a catalog edit surfaces on the next load rather than mid-compose.
     */
    private val dimensionRows: suspend () -> List<ConfiguredLifeDimension> = { emptyList() },
) {
    private val writeMutex = Mutex()

    /** Bumped by every database swap, so a write queued behind one can tell that it was overtaken. */
    private var generation = 0L

    /** The rows last read (or last written). A write applies to this map; the rows are never re-read for it. */
    private var rows: Map<String, String?> = emptyMap()

    /**
     * The dimension rows the last (re)load read, alongside [rows].
     *
     * Held rather than read twice, for the reason [rows] is: the state published must be built from the
     * snapshot the same read produced, so `dimensionPreferences` and the settings rows can never describe
     * two different moments.
     */
    private var dimensions: List<ConfiguredLifeDimension> = emptyList()

    private val preferencesState = MutableStateFlow(stateFor(rows))

    private val writeFailureState = MutableStateFlow<String?>(null)

    /**
     * The shared state every desktop surface renders from.
     *
     * Combined with the backup port's own flow, because the mapper derives the failure banner from
     * `backupStatus.latestFailure` rather than from a row - so a recorded failure has to be able to reach
     * the state on its own, with no row write to trigger a re-read. Without this the banner would only ever
     * be correct at the moment the rows were last read.
     */
    val preferences: StateFlow<AppPreferencesState> =
        if (backupStatusStore == null) {
            preferencesState
        } else {
            // Re-derives from the CURRENT rows, so a recorded failure actually lands in the banner's two
            // fields. Combining without re-mapping would emit a new value that is still the old one.
            combine(preferencesState, backupStatusStore.status) { _, status -> stateFor(rows, status) }
                .stateIn(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    started = SharingStarted.Eagerly,
                    initialValue = preferencesState.value,
                )
        }

    /** The one-line failure the settings screen shows, or null. Cleared by the next successful write. */
    val writeFailure: StateFlow<String?> = writeFailureState.asStateFlow()

    private val unlockTimeoutState = MutableStateFlow(resolveUnlockTimeoutMinutes(emptyMap()))

    private val scoringModelState = MutableStateFlow(DEFAULT_SCORING_MODEL)

    /**
     * The unlock timeout in force, by Android's rule: the `session_timeout_minutes` row when it is a positive
     * number, otherwise twice `auto_backup_interval`, clamped to 1..240.
     *
     * Resolved from the rows already in hand rather than from a second read: the row arrives inside
     * [reload]'s settings map, and a second repository call would be a second source of the same fact.
     */
    val unlockTimeoutMinutes: StateFlow<Int> = unlockTimeoutState.asStateFlow()

    /**
     * The desktop-scoped scoring-model row (see the plan's Amendment): Android has no named scoring model, so
     * this value is the desktop's own and does not live in [AppPreferencesState].
     */
    val scoringModel: StateFlow<String> = scoringModelState.asStateFlow()

    /** Reads every row and publishes the mapped state. Called once a session is open. */
    suspend fun reload() {
        writeMutex.withLock {
            val loaded =
                LocalDataLayer.current.appSettings
                    .getAllSettings()
                    .first()
            // Read INSIDE the same lock as the settings rows: the two are published as ONE state, so a
            // swap that lands between two reads would publish the new rows against the old catalog.
            val loadedDimensions = dimensionRows()
            rows = loaded
            dimensions = loadedDimensions
            publish(loaded)
            sessionLogger.i(
                source = "DesktopPreferencesSource.reload",
                message = "Preferences loaded from the encrypted database",
                data = mapOf("rows" to loaded.size, "dimensions" to loadedDimensions.size),
            )
        }
    }

    /**
     * Reports the absent-row defaults because there is no session, and says so.
     *
     * Not silent and not an error: before unlock there are no rows to read, and a settings screen rendering
     * stale values would be worse than one rendering defaults. The data layer is deliberately NOT touched
     * here - it throws when uninstalled, and that throw must stay visible rather than be swallowed.
     */
    fun reportNoSession() {
        rows = emptyMap()
        // The catalog is cleared with the rows, for the same reason: an uninstalled layer must not leave
        // the PREVIOUS session's dimensions on screen. The read is deliberately not attempted.
        dimensions = emptyList()
        // The debug-logging gate is deliberately NOT moved here: there is no row to follow before unlock, and
        // the logger keeps what it was initialized with (the launch-time env override included).
        preferencesState.value = stateFor(rows)
        sessionLogger.i(
            source = "DesktopPreferencesSource.reportNoSession",
            message = "Preferences read without a session - reporting defaults",
            data = mapOf("reason" to "no session"),
        )
    }

    /**
     * Writes ONE row and republishes the state from it.
     *
     * The written row is applied to the last-read map, so the published state is always exactly what
     * [mapAppPreferences] produces for those rows - there is no per-key update path that could drift from the
     * mapper. On failure nothing is applied, the previous state stands (there is no optimistic assign to roll
     * back), the key and the failure CLASS are logged - never the value - and one line is surfaced to the user.
     *
     * The catch is deliberately broad: a failed JDBC execute raises checked `SQLException` as well as
     * unchecked `IllegalStateException`, and both are the same event from here - the row did not land.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun setSetting(
        key: String,
        value: String?,
    ) {
        val requestedGeneration = generation
        writeMutex.withLock {
            if (requestedGeneration != generation) {
                sessionLogger.i(
                    source = "DesktopPreferencesSource.setSetting",
                    message = "Setting write discarded",
                    data = mapOf("key" to key, "cancelled" to "layer re-install"),
                )
                return
            }
            try {
                LocalDataLayer.current.appSettings.setSetting(key, value)
            } catch (failure: Exception) {
                writeFailureState.value = "$key could not be saved (${failure.javaClass.simpleName})"
                sessionLogger.w(
                    source = "DesktopPreferencesSource.setSetting",
                    message = "Setting write failed",
                    data = mapOf("key" to key, "failure" to failure.javaClass.simpleName),
                )
                return
            }
            rows = rows + (key to value)
            publish(rows)
            writeFailureState.value = null
            sessionLogger.i(
                source = "DesktopPreferencesSource.setSetting",
                message = "Setting saved",
                data = mapOf("key" to key),
            )
        }
    }

    /**
     * Records a focus-mode preset selection, by exactly the rule Android applies.
     *
     * Android's onboarding handler is TWO calls (`MainActivity.kt:482-483`):
     * `setActivePreset(preset)` then `markFocusModeOnboardingCompleted()`. The first writes the preset row
     * AND a `tab_visible_<tab>` row for every one of the seven tabs, with `settings` forced true
     * (`AppPreferencesViewModel.setActivePreset`); the second writes the one-time completion row. So this
     * method writes the same NINE rows in the same order, under the SAME mutex as every other write.
     *
     * Why one method rather than two: the two Android calls are not independent - a crash between them
     * leaves the rail narrowed by the preset while the gate still asks for the preset again. One write
     * that either lands as a whole or is reported as a whole is the shape Android's pair lacks, and the
     * desktop gets it for free by owning one method instead of two call sites.
     *
     * Partial application is still possible at the row level (the repository has no batch primitive), so
     * the published state is built from the map this method accumulated itself - never from a re-read that
     * could disagree with what was actually written.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun setFocusModePreset(preset: FocusModePreset) {
        val requestedGeneration = generation
        writeMutex.withLock {
            if (requestedGeneration != generation) {
                sessionLogger.i(
                    source = "DesktopPreferencesSource.setFocusModePreset",
                    message = "Focus preset write discarded",
                    data = mapOf("cancelled" to "layer re-install"),
                )
                return
            }
            // Every tab, from the desktop's own route enum rather than a re-declared list. Android writes
            // seven rows from its own tab list; iterating the enum means a route added there is covered here
            // without a second edit, and a row is written for EVERY tab (true or false) so a preset never
            // leaves a stale row behind for a tab the new preset hides.
            val written = mutableMapOf<String, String?>()
            val attempts =
                buildList {
                    add(AppPreferenceKeys.KEY_ACTIVE_PRESET to preset.presetId)
                    // Settings is always visible, whatever the preset says - Android's rule, not a desktop
                    // exception, and the mapper enforces it again on the read side.
                    DesktopTopLevelRoute.entries.forEach { route ->
                        val visible = route == DesktopTopLevelRoute.SETTINGS || preset.visibleRoutes.contains(route)
                        add("${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}${route.storageKey}" to visible.toString())
                    }
                    add(AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED to "true")
                }
            var firstFailure: Exception? = null
            attempts.forEach { (key, value) ->
                try {
                    LocalDataLayer.current.appSettings.setSetting(key, value)
                    written[key] = value
                } catch (failure: Exception) {
                    // Keep going: the completion row is the one that unblocks the user, and abandoning the
                    // preset rows because it failed would leave the rail narrowed and the gate still asking.
                    if (firstFailure == null) {
                        firstFailure = failure
                    }
                    sessionLogger.w(
                        source = "DesktopPreferencesSource.setFocusModePreset",
                        message = "Focus preset row failed",
                        data = mapOf("key" to key, "failure" to failure.javaClass.simpleName),
                    )
                }
            }

            rows = rows + written
            publish(rows)
            val failure = firstFailure
            if (failure == null) {
                writeFailureState.value = null
                sessionLogger.i(
                    source = "DesktopPreferencesSource.setFocusModePreset",
                    message = "Focus preset saved",
                    data = mapOf("preset" to preset.presetId, "rows" to written.size),
                )
            } else {
                // Named by what did not save, never by a value: the key is the useful half.
                writeFailureState.value =
                    "focus preset could not be fully saved (${failure.javaClass.simpleName})"
            }
        }
    }

    /**
     * Writes the first-run life-dimension catalog, through the SHARED repository, and republishes the state
     * from what was actually written.
     *
     * **This replaces a mock.** The onboarding dimension step used to log its rows and call it done, which
     * is why a fresh Linux install finished with a `life_dimensions` table holding NOTHING: the app-owned
     * seed is written by the first-run setup step (never by `PayanamDatabaseOpener.create`, whose contract
     * is to create an empty database), and the desktop's only first-run setup step was that log line. Every
     * dimension-aware dialog then fell back to the hardcoded canonical literal, stamping notes and time
     * entries with an id no row resolves.
     *
     * The write is the SAME atomic swap Android's `persistNewDatabaseDimensionSetup` performs -
     * `LifeDimensionCatalogRepository.replaceAllDimensions`, which deletes and re-inserts inside one
     * transaction - rather than a hand-rolled DELETE plus a loop of INSERTs that would leave the table
     * empty in between. The rows are built by `buildDesktopFirstRunDimensionSeed` in
     * `DesktopStartupFlow.kt`, i.e. from the app's own canonical defaults, never from rows declared here.
     *
     * **An already-populated table is left exactly as it is.** This is not a shortcut: a desktop launch
     * re-runs onboarding every time (the scaffold stage is deliberately not persisted), so seeding on every
     * confirmation would overwrite a catalog the user has since edited, on every launch. A fresh install is
     * the only state that writes, and "rows already there" is exactly the state that is not fresh. That is
     * also what makes an imported Android database keep its own dimensions.
     *
     * The read that decides this and the write that follows it share [writeMutex] with every other read and
     * write here, so the emptiness answer cannot be overtaken by a database swap or by a settings write
     * landing between them. A swap that DID land first is caught by the same [generation] check every other
     * write uses, and the write is DISCARDED rather than aimed at the new file: the swap's own re-read
     * already carries the truth.
     *
     * `database_init_completed` is written only after the catalog landed, for the same reason Android
     * writes it there - it is the flag meaning "this database's dimension setup is done", and a flag set
     * before the rows would let a later gate skip a setup that never happened.
     *
     * The catch is deliberately broad, for the reason [setSetting] gives: JDBC raises checked
     * `SQLException` and the repositories raise unchecked `IllegalStateException`, and from here both are
     * the same event - the catalog did not land.
     *
     * @param seed the rows to write and which answer they are. The rows are written verbatim; no label,
     *   id or ordering is adjusted here.
     * @return what actually happened, so the caller can say so rather than assuming a write.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun seedFirstRunDimensions(seed: DesktopFirstRunDimensionSeed): DesktopFirstRunDimensionWriteOutcome {
        val requestedGeneration = generation
        return writeMutex.withLock {
            if (requestedGeneration != generation) {
                sessionLogger.i(
                    source = "DesktopPreferencesSource.seedFirstRunDimensions",
                    message = "First-run dimension write discarded",
                    data = mapOf("cancelled" to "layer re-install", "rows" to seed.rows.size),
                )
                return@withLock DesktopFirstRunDimensionWriteOutcome.Discarded
            }
            val existing =
                try {
                    LocalDataLayer.current.lifeDimensions
                        .observeAllDimensions()
                        .first()
                } catch (failure: Exception) {
                    // Logged as a WRITE failure, not treated as "empty": an unreadable catalog is not
                    // evidence of a fresh install, and seeding over a database it could not read would
                    // replace a user's dimensions with the defaults.
                    sessionLogger.w(
                        source = "DesktopPreferencesSource.seedFirstRunDimensions",
                        message = "Existing life dimension catalog could not be read",
                        data = mapOf("failure" to failure.javaClass.simpleName),
                    )
                    return@withLock DesktopFirstRunDimensionWriteOutcome.Failed
                }
            if (existing.isNotEmpty()) {
                sessionLogger.i(
                    source = "DesktopPreferencesSource.seedFirstRunDimensions",
                    message = "Life dimension catalog already present; left unchanged",
                    data = mapOf("rows" to existing.size),
                )
                return@withLock DesktopFirstRunDimensionWriteOutcome.AlreadyPresent
            }
            val written =
                try {
                    LocalDataLayer.current.lifeDimensions.replaceAllDimensions(seed.rows)
                } catch (failure: Exception) {
                    // No optimistic assign to roll back: the published state keeps the last read's rows, so
                    // the settings screen cannot offer dimensions that are not in the table.
                    writeFailureState.value =
                        "life dimensions could not be saved (${failure.javaClass.simpleName})"
                    sessionLogger.w(
                        source = "DesktopPreferencesSource.seedFirstRunDimensions",
                        message = "First-run life dimension write failed",
                        data =
                            mapOf(
                                "failure" to failure.javaClass.simpleName,
                                "requested" to seed.rows.size,
                                "source" to seed.source.name,
                            ),
                    )
                    return@withLock DesktopFirstRunDimensionWriteOutcome.Failed
                }
            // Published from what the repository reported, not from a re-read: the write is one transaction,
            // so a re-read could only disagree by being slower.
            dimensions = seed.rows
            publish(rows)
            writeFailureState.value = null
            try {
                LocalDataLayer.current.appSettings.setSetting(KEY_DATABASE_INIT_COMPLETED, "true")
            } catch (failure: Exception) {
                // The CATALOG is what makes the ids resolve, and it landed; the flag is Android's
                // database-init marker and the desktop does not gate on it, so a failure here is reported
                // rather than allowed to describe a successful seed as a failure.
                sessionLogger.w(
                    source = "DesktopPreferencesSource.seedFirstRunDimensions",
                    message = "database_init_completed not marked after a successful dimension seed",
                    data = mapOf("failure" to failure.javaClass.simpleName),
                )
            }
            sessionLogger.i(
                source = "DesktopPreferencesSource.seedFirstRunDimensions",
                message = "First-run life dimension catalog written",
                data =
                    mapOf(
                        "written" to written,
                        "requested" to seed.rows.size,
                        "seedSource" to seed.source.name,
                    ),
            )
            DesktopFirstRunDimensionWriteOutcome.Written
        }
    }

    /**
     * Runs [block] - the whole close, replace and re-open swap - under the write mutex, then re-reads the rows
     * from the NEW session before releasing it.
     *
     * The re-read has to happen inside the lock: the swap replaces the data layer, so a read outside it could
     * land before the new layer is installed and publish the OLD database's rows over the imported ones.
     */
    suspend fun <T> runDatabaseSwap(block: suspend () -> T): T =
        writeMutex.withLock {
            val result = block()
            // Bumped AFTER the swap, not before: a write that was REQUESTED while this lock was held was
            // made against the database that has just been replaced, so it must not land on the new one.
            generation += 1
            val loaded =
                LocalDataLayer.current.appSettings
                    .getAllSettings()
                    .first()
            // Re-read together with the rows, for the same reason reload() does: the imported database has
            // its OWN life_dimensions, so keeping the previous session's catalog here would offer the user
            // dimensions that the file they just imported no longer contains.
            val loadedDimensions = dimensionRows()
            rows = loaded
            dimensions = loadedDimensions
            publish(loaded)
            result
        }

    /**
     * The forgot-passphrase reset: the database artifact is gone, so there are no rows left to render and the
     * state must stop showing the deleted database's values.
     *
     * Named here so the reset writes under the SAME mutex as every other write - a reset that deleted the
     * artifact while a write was in flight would otherwise leave the two disagreeing.
     */
    suspend fun resetToDefaults() {
        writeMutex.withLock {
            generation += 1
            rows = emptyMap()
            // The catalog goes with the artifact it lived in, or the settings screen would offer dimensions
            // from a database that no longer exists.
            dimensions = emptyList()
            publish(rows)
            writeFailureState.value = null
            sessionLogger.i(
                source = "DesktopPreferencesSource.resetToDefaults",
                message = "Preferences reset to defaults with the database artifact",
                data = emptyMap<String, Any?>(),
            )
        }
    }

    /**
     * Publishes the mapped state and hands the debug-logging answer to the platform, so the logger's gate can
     * never lag the row it is supposed to follow.
     */
    private fun publish(settings: Map<String, String?>) {
        val state = stateFor(settings)
        preferencesState.value = state
        unlockTimeoutState.value = resolveUnlockTimeoutMinutes(settings)
        scoringModelState.value = settings[DESKTOP_SCORING_MODEL_KEY] ?: DEFAULT_SCORING_MODEL
        onDebugLoggingChanged(state.debugLoggingEnabled)
    }

    /** Android's derive-and-clamp rule for `session_timeout_minutes`, over the rows in hand. */
    private fun resolveUnlockTimeoutMinutes(settings: Map<String, String?>): Int {
        val stored = settings[AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES]?.toIntOrNull()?.takeIf { it > 0 }
        val derived =
            (BackupInterval.fromKey(settings[AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL]) ?: BackupInterval.SIXTY_MIN)
                .minutes
                .times(2)
                .toInt()
        return (stored ?: derived).coerceIn(MIN_UNLOCK_TIMEOUT_MINUTES, MAX_UNLOCK_TIMEOUT_MINUTES)
    }

    private fun stateFor(
        settings: Map<String, String?>,
        backupStatus: BackupStatusSnapshot? = null,
    ): AppPreferencesState =
        mapAppPreferences(
            settings = settings,
            // The REAL life_dimensions rows this session read - the same rows Android's
            // `AppPreferencesViewModel` passes here, through the same shared mapper. They used to be
            // `emptyList()`, which is why every dimension-aware desktop dialog offered no options and fell
            // back to a hardcoded canonical id: a dimension the user never chose, and one with no row in a
            // migrated `life_dimensions` table. An empty list is now only reachable through
            // [dimensionRows] returning empty, which means the TABLE is empty.
            catalogRows = dimensions,
            systemLanguageTag = systemLanguageTag,
            backupStatus = backupStatus,
            defaultDebugLoggingEnabled = defaultDebugLoggingEnabled,
        )
}

internal data class DesktopRememberedState(
    val selectionState: MutableState<DataModuleSelection>,
    /**
     * SCAFFOLD progress. Held here rather than in a store so it cannot outlive the process: a restart
     * must show onboarding again while the data layer is mocked.
     */
    val scaffoldOnboardingStageState: MutableState<DesktopScaffoldOnboardingStage>,
    /** SCAFFOLD: the dimension rows as edited during onboarding. In-memory only. */
    val dimensionInputsState: MutableState<List<DesktopDimensionInput>>,
    /**
     * The catalog the onboarding step confirmed, waiting for a session to write it through.
     *
     * Held because of the ORDER the desktop starts in, which is not the order the write needs:
     * `resolveDesktopStartupMode` puts the scaffold stages before the passphrase, session and database
     * gates, so the user confirms their dimensions while there is still NO database - and
     * `LocalDataLayer.current` throws on any read until a session is in force. The rows are therefore
     * kept here from the confirmation and written the moment a session exists (see the flush in
     * `DesktopApp.kt`), rather than the step refusing a write it could not make.
     *
     * In-memory, like [scaffoldOnboardingStageState] and for the same reason: a restart re-runs
     * onboarding, which asks again and re-seeds. A row written by a session that is gone is not a fact
     * this process can re-establish, so it is not cached beyond the one that holds it.
     */
    val pendingDimensionSeedState: MutableState<DesktopFirstRunDimensionSeed?>,
    /**
     * The desktop's settings source: the shared `app_settings` rows, read and written through the installed
     * data layer. Held here (not in a store) so it lives exactly as long as the shell that owns it.
     */
    val preferencesSource: DesktopPreferencesSource,
    val bootstrapSnapshotState: MutableState<DesktopBootstrapSnapshot>,
    val securitySnapshotState: MutableState<DesktopSecuritySnapshot>,
    val databaseSnapshotState: MutableState<DesktopDatabaseSnapshot>,
    val sessionOpenState: MutableState<Boolean>,
    /**
     * The result of the last database export/import.
     *
     * App-level rather than per-screen: the onboarding import runs in the startup gate and the user reads the
     * outcome beside the buttons in Data management, so both need the same one-line answer.
     */
    val transferStatusState: MutableState<String?>,
    /** The route captured for return-after-unlock, per Android's `resumeToRouteAfterUnlock`. */
    val resumeToRouteAfterUnlockState: MutableState<String?>,
)

/**
 * Writes the catalog the onboarding step confirmed, if one is still waiting, and clears it.
 *
 * **Called from the session-open paths, because that is the first moment a write is possible.** The desktop
 * starts at the onboarding scaffold - before the passphrase, session and database gates - so the user
 * confirms their dimensions before any database exists, and `LocalDataLayer.current` throws until one is
 * open. This is what makes the confirmation mean anything on a FRESH install: without it the rows are
 * merely held, the app opens an empty `life_dimensions` table, and every note and time entry falls back to
 * the hardcoded canonical id that resolves to nothing.
 *
 * The write goes through [DesktopPreferencesSource.seedFirstRunDimensions], so it inherits that method's
 * rules: an already-populated catalog is left alone (an imported or already-seeded database keeps its own),
 * an unreadable one is a failure rather than licence to seed, and a swap that landed first discards it.
 *
 * The pending seed is cleared only when the catalog is there - [DesktopFirstRunDimensionWriteOutcome.Written]
 * or [AlreadyPresent] - so a failed attempt is retried on the next session rather than silently dropped: a
 * session that cannot write must not lose the user's choices.
 *
 * The stage advances to [DesktopScaffoldOnboardingStage.Finished] only in that same case, which is the
 * point of the whole change: the scaffold used to advance over a save no database had ever seen.
 *
 * @return what actually happened, for the caller to log. [AlreadyPresent] when nothing was pending, which
 *   is the ordinary answer for every launch that did not re-confirm dimensions.
 */
internal suspend fun DesktopRememberedState.flushPendingFirstRunDimensions(
    sessionLogger: UnifiedLogger,
): DesktopFirstRunDimensionWriteOutcome {
    val seed = pendingDimensionSeedState.value
        ?: return DesktopFirstRunDimensionWriteOutcome.AlreadyPresent
    val outcome = preferencesSource.seedFirstRunDimensions(seed)
    if (outcome == DesktopFirstRunDimensionWriteOutcome.Written ||
        outcome == DesktopFirstRunDimensionWriteOutcome.AlreadyPresent
    ) {
        pendingDimensionSeedState.value = null
        scaffoldOnboardingStageState.value = DesktopScaffoldOnboardingStage.Finished
    } else {
        sessionLogger.w(
            source = "DesktopAppSupport.flushPendingFirstRunDimensions",
            message = "Pending first-run dimensions not written; kept for the next session",
            data = mapOf("outcome" to outcome.name, "requested" to seed.rows.size),
        )
    }
    return outcome
}

internal data class DesktopAppModels(
    val selection: DataModuleSelection,
    val startupMode: DesktopStartupMode,
    val startupRuntimeState: DesktopStartupRuntimeState,
    val startupSnapshot: io.payanam.shared.startup.DesktopStartupSnapshot,
    val databaseSnapshot: DesktopDatabaseSnapshot,
    /** SCAFFOLD: onboarding dimension rows, surfaced so the gate can render them. */
    val dimensionInputs: List<DesktopDimensionInput>,
    val navigationModel: DesktopNavigationModel,
    val desktopLifecycleState: DesktopLifecycleState,
    val shellRenderState: DesktopShellRenderState,
)

internal data class DesktopShellRenderInputs(
    val startupSnapshot: io.payanam.shared.startup.DesktopStartupSnapshot,
    val navigationModel: DesktopNavigationModel,
    val activeRoute: DesktopTopLevelRoute,
    val foundationSnapshot: SettingsFoundationSnapshot,
    val preferences: AppPreferencesState,
    val unlockTimeoutMinutes: Int,
    val scoringModel: String,
    val writeFailure: String?,
    val desktopLifecycleState: DesktopLifecycleState,
    val transferStatus: String?,
    /** The route captured for return-after-unlock; threaded into the render state. */
    val resumeToRouteAfterUnlock: String? = null,
)

@Composable
internal fun rememberDesktopPersistenceStores(sessionLogger: UnifiedLogger): DesktopPersistenceStores =
    remember(sessionLogger) {
        DesktopPersistenceStores(
            bootstrapStore =
                DesktopBootstrapStore(
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            securityStore =
                DesktopSecurityStore(
                    logEvent = { level, source, message, data ->
                        when (level) {
                            DesktopSecurityLogLevel.WARN -> {
                                sessionLogger.w(source = source, message = message, data = data)
                            }

                            DesktopSecurityLogLevel.INFO -> {
                                sessionLogger.i(source = source, message = message, data = data)
                            }
                        }
                    },
                ),
            databaseStore =
                DesktopDatabaseStore(
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
        )
    }

@Composable
internal fun rememberDesktopMutableState(
    stores: DesktopPersistenceStores,
    sessionLogger: UnifiedLogger,
    /** Handed to the settings source so the logger gate follows `debug_logging_enabled`. */
    onDebugLoggingChanged: (Boolean) -> Unit = {},
    /**
     * The `life_dimensions` rows, supplied by the caller that owns the open session.
     *
     * A parameter rather than a read from inside this file because the desktop has no dimension repository
     * yet: `PayanamDataLayer` (core/presentation) has no `LifeDimensionCatalogRepository` entry, and
     * `DesktopPayanamDataLayer` builds none. The default is therefore "no rows", which is the honest
     * pre-session state AND the current one - it must be wired at the same time that entry is added, or
     * every dimension-aware dialog goes back to offering a dimension the user never chose.
     *
     * NOT a `remember` key, because a lambda is not a stable key and would rebuild the source - dropping the
     * rows it has already published - on every recomposition. The value is read once per load instead.
     */
    dimensionRows: suspend () -> List<ConfiguredLifeDimension> = { emptyList() },
): DesktopRememberedState =
    DesktopRememberedState(
        selectionState = remember { mutableStateOf(DataModuleSelection()) },
        scaffoldOnboardingStageState =
            remember { mutableStateOf(DesktopScaffoldOnboardingStage.Intro) },
        dimensionInputsState =
            remember { mutableStateOf(desktopDefaultDimensionInputs()) },
        pendingDimensionSeedState = remember { mutableStateOf(null) },
        preferencesSource =
            // Keyed on the logger alone: a lambda is not a stable key, and keying on it would rebuild this
            // source - and lose the rows it has published - on every recomposition. The lambda itself is
            // read only inside a load, so a caller passing a fresh instance per composition is harmless.
            remember(sessionLogger) {
                DesktopPreferencesSource(
                    sessionLogger = sessionLogger,
                    backupStatusStore = stores.backupStatusStore,
                    systemLanguageTag = Locale.getDefault().language,
                    onDebugLoggingChanged = onDebugLoggingChanged,
                    // The SAME mapper call, now given the real catalog, so the desktop's dimension options
                    // are the table's rows rather than a hardcoded fallback.
                    dimensionRows = dimensionRows,
                )
            },
        bootstrapSnapshotState =
            remember {
                mutableStateOf(
                    readStoreOrFallback("bootstrap", sessionLogger, read = { stores.bootstrapStore.ensureSnapshot() }) {
                        DesktopBootstrapSnapshot()
                    },
                )
            },
        securitySnapshotState =
            remember {
                mutableStateOf(
                    readStoreOrFallback(
                        "security",
                        sessionLogger,
                        read = { stores.securityStore.ensureSnapshot() },
                    ) {
                        // Unreachable while the store's reads are non-throwing, and fail-closed if
                        // that ever regresses: a default snapshot here would report "no passphrase
                        // configured" and offer setup on an install that may already have one.
                        DesktopSecuritySnapshot(
                            availability = DesktopSecurityAvailability.UNAVAILABLE,
                        )
                    },
                )
            },
        databaseSnapshotState =
            remember {
                mutableStateOf(
                    readStoreOrFallback("database", sessionLogger, read = { stores.databaseStore.loadSnapshot() }) {
                        DesktopDatabaseSnapshot(
                            databaseFilePath = stores.databaseStore.getDatabaseFilePath().toString(),
                            hasArtifacts = false,
                            initCompleted = false,
                            databaseSizeKb = 0L,
                            databaseLastModifiedMs = 0L,
                        )
                    },
                )
            },
        sessionOpenState = remember { mutableStateOf(false) },
        transferStatusState = remember { mutableStateOf(null) },
        resumeToRouteAfterUnlockState = remember { mutableStateOf(null) },
    )

/**
 * Whether the user's theme mode means the dark appearance.
 *
 * `SYSTEM` answers dark, and that is a recorded platform difference rather than a guess: the desktop has no
 * system dark-mode signal it can trust across Linux and Windows, and its appearance has always been dark, so
 * SYSTEM keeps that. Leaving SYSTEM on a signal that always answers `false` would silently move every SYSTEM
 * user to a light appearance they never asked for. LIGHT and DARK now have an effect, which they did not
 * before — the palette was hardcoded dark and the setting changed nothing.
 */
internal fun DesktopThemeMode.resolveDarkTheme(): Boolean =
    when (this) {
        DesktopThemeMode.DARK -> true
        DesktopThemeMode.LIGHT -> false
        DesktopThemeMode.SYSTEM -> true
    }

internal fun FoundationReadiness.swatch(): Color =
    when (this) {
        FoundationReadiness.SharedReady -> {
            @Suppress("MagicNumber")
            Color(0xFF3E7B5A)
        }

        FoundationReadiness.ExtractionNext -> {
            @Suppress("MagicNumber")
            Color(0xFFB47B2A)
        }

        FoundationReadiness.AndroidOnly -> {
            @Suppress("MagicNumber")
            Color(0xFF9C4F4F)
        }
    }

@Composable
internal fun rememberDesktopAppModels(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    activeRoute: DesktopTopLevelRoute,
): DesktopAppModels {
    val selection = rememberedState.selectionState.value
    val preferences by rememberedState.preferencesSource.preferences.collectAsState()
    val unlockTimeoutMinutes by rememberedState.preferencesSource.unlockTimeoutMinutes.collectAsState()
    val scoringModel by rememberedState.preferencesSource.scoringModel.collectAsState()
    val writeFailure by rememberedState.preferencesSource.writeFailure.collectAsState()
    val bootstrapSnapshot = rememberedState.bootstrapSnapshotState.value
    val securitySnapshot = rememberedState.securitySnapshotState.value
    val databaseSnapshot = rememberedState.databaseSnapshotState.value
    val sessionOpen = rememberedState.sessionOpenState.value
    val foundationSnapshot = remember(selection) { desktopFoundationSnapshot(selection) }
    val startupRuntimeState =
        rememberDesktopStartupRuntimeState(
            stores = stores,
            scaffoldOnboardingStage = rememberedState.scaffoldOnboardingStageState.value,
            // From the shared state, which is the row Android reads. The desktop snapshot is deliberately
            // not consulted: it is the copy this work is retiring, and a gate that read it would keep
            // asking for a preset the user already chose.
            focusModeOnboardingCompleted = preferences.focusModeOnboardingCompleted,
            bootstrapSnapshot = bootstrapSnapshot,
            securitySnapshot = securitySnapshot,
            databaseSnapshot = databaseSnapshot,
            sessionOpen = sessionOpen,
        )
    val navigationModel = remember(preferences) { desktopNavigationModel(preferences) }
    val startupSnapshot =
        rememberDesktopStartupSnapshot(
            stores = stores,
            launchRoute = navigationModel.launchRoute,
            startupRuntimeState = startupRuntimeState,
        )
    val startupMode = remember(startupRuntimeState) { resolveDesktopStartupMode(startupRuntimeState) }
    val desktopLifecycleState =
        rememberDesktopLifecycleState(stores = stores, bootstrapSnapshot = bootstrapSnapshot)
    val shellRenderInputs =
        DesktopShellRenderInputs(
            startupSnapshot = startupSnapshot,
            navigationModel = navigationModel,
            activeRoute = activeRoute,
            foundationSnapshot = foundationSnapshot,
            preferences = preferences,
            unlockTimeoutMinutes = unlockTimeoutMinutes,
            scoringModel = scoringModel,
            writeFailure = writeFailure,
            desktopLifecycleState = desktopLifecycleState,
            transferStatus = rememberedState.transferStatusState.value,
            resumeToRouteAfterUnlock = rememberedState.resumeToRouteAfterUnlockState.value,
        )
    val shellRenderState =
        rememberDesktopShellRenderState(
            stores = stores,
            inputs = shellRenderInputs,
        )
    return DesktopAppModels(
        selection = selection,
        startupMode = startupMode,
        startupRuntimeState = startupRuntimeState,
        startupSnapshot = startupSnapshot,
        databaseSnapshot = databaseSnapshot,
        dimensionInputs = rememberedState.dimensionInputsState.value,
        navigationModel = navigationModel,
        desktopLifecycleState = desktopLifecycleState,
        shellRenderState = shellRenderState,
    )
}

@Composable
private fun rememberDesktopStartupRuntimeState(
    stores: DesktopPersistenceStores,
    scaffoldOnboardingStage: DesktopScaffoldOnboardingStage,
    focusModeOnboardingCompleted: Boolean,
    bootstrapSnapshot: DesktopBootstrapSnapshot,
    securitySnapshot: DesktopSecuritySnapshot,
    databaseSnapshot: DesktopDatabaseSnapshot,
    sessionOpen: Boolean,
): DesktopStartupRuntimeState =
    remember(
        // The shared value, not the desktop snapshot's copy: the gate and the settings screen must answer
        // this from the SAME source, or the user is shown the preset picker again after having chosen.
        focusModeOnboardingCompleted,
        bootstrapSnapshot,
        securitySnapshot,
        databaseSnapshot,
        sessionOpen,
        scaffoldOnboardingStage,
        stores.securityStore,
        stores.databaseStore,
    ) {
        buildDesktopStartupRuntimeState(
            focusModeOnboardingCompleted = focusModeOnboardingCompleted,
            bootstrapSnapshot = bootstrapSnapshot,
            securitySnapshot = securitySnapshot,
            databaseSnapshot = databaseSnapshot,
            sessionOpen = sessionOpen,
            nowEpochMillis = System.currentTimeMillis(),
            scaffoldOnboardingStage = scaffoldOnboardingStage,
        ).copy(
            securityFilePath = stores.securityStore.getSecurityFilePath().toString(),
            databaseFilePath = stores.databaseStore.getDatabaseFilePath().toString(),
        )
    }

@Composable
private fun rememberDesktopStartupSnapshot(
    stores: DesktopPersistenceStores,
    launchRoute: DesktopTopLevelRoute,
    startupRuntimeState: DesktopStartupRuntimeState,
): io.payanam.shared.startup.DesktopStartupSnapshot =
    remember(launchRoute, stores.bootstrapStore, stores.securityStore, startupRuntimeState) {
        desktopStartupSnapshot(
            launchRoute = launchRoute,
            appDataRoot = DesktopAppPaths.resolveRootDirectory().toString(),
            bootstrapFilePath = stores.bootstrapStore.getBootstrapFilePath().toString(),
            securityFilePath = stores.securityStore.getSecurityFilePath().toString(),
            databaseFilePath = startupRuntimeState.databaseFilePath,
            runtimeState = startupRuntimeState,
        )
    }

@Composable
private fun rememberDesktopLifecycleState(
    stores: DesktopPersistenceStores,
    bootstrapSnapshot: DesktopBootstrapSnapshot,
): DesktopLifecycleState =
    remember(bootstrapSnapshot, stores.bootstrapStore, stores.databaseStore) {
        DesktopLifecycleState(
            bootstrapSnapshot = bootstrapSnapshot,
            databaseExists = Files.exists(stores.databaseStore.getDatabaseFilePath()),
            databaseSizeKb = stores.databaseStore.loadSnapshot().databaseSizeKb,
            bootstrapFilePath = stores.bootstrapStore.getBootstrapFilePath().toString(),
            securityFilePath = stores.securityStore.getSecurityFilePath().toString(),
            databaseFilePath = stores.databaseStore.getDatabaseFilePath().toString(),
            exportDirectoryPath = DesktopAppPaths.resolveExportDirectory().toString(),
        )
    }

@Composable
private fun rememberDesktopShellRenderState(
    stores: DesktopPersistenceStores,
    inputs: DesktopShellRenderInputs,
): DesktopShellRenderState =
    remember(
        inputs,
        // A KEY as well as a value: the status changes without any store changing, so without this the state
        // is computed once and the message never appears.
        inputs.transferStatus,
        // A KEY as well as a value: the captured route changes with no store and no row write, so
        // without this the state is computed once and the graph is never told which route to return to.
        inputs.resumeToRouteAfterUnlock,
    ) {
        DesktopShellRenderState(
            navigationModel = inputs.navigationModel,
            activeRoute = inputs.activeRoute,
            foundationSnapshot = inputs.foundationSnapshot,
            preferences = inputs.preferences,
            unlockTimeoutMinutes = inputs.unlockTimeoutMinutes,
            scoringModel = inputs.scoringModel,
            writeFailure = inputs.writeFailure,
            desktopLifecycleState = inputs.desktopLifecycleState,
            transferStatus = inputs.transferStatus,
            resumeToRouteAfterUnlock = inputs.resumeToRouteAfterUnlock,
        )
    }

internal fun buildDesktopShellCallbacks(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    sessionLogger: UnifiedLogger,
    onQuitRequested: () -> Unit,
    onRouteSelected: (DesktopTopLevelRoute) -> Unit,
    /**
     * The last first-run create outcome, held so the gate can ask for it AFTER the click.
     *
     * A holder defaulted to a fresh one per call, rather than a field on [DesktopRememberedState]: it is
     * the one fact that belongs to this handler pair alone and has no business surviving a recomposition
     * of the shell, and the gate reads it through the outcome callback rather than from a state holder it
     * does not own.
     */
    lastDatabaseCreateOutcome: MutableState<DesktopDatabaseCreateOutcome?> = mutableStateOf(null),
    /**
     * Called once a passphrase has been ACCEPTED, with that passphrase.
     *
     * The shell does not open the database itself: opening is suspend, and this function is plain. The
     * caller that owns a coroutine scope supplies the open-and-install step, so the order stays visible —
     * the session is established (and the shared data layer installed) before anything reads through the
     * holder. Defaulted to a no-op so the existing tests of this function keep compiling unchanged.
     */
    onSessionEstablished: (CharArray) -> Unit = {},
    /** Picks a destination and exports the live database; supplied by the caller that owns the scope. */
    onExportDatabase: () -> Unit = {},
    /** Picks a source and imports it over the live database; supplied by the caller that owns the scope. */
    onImportDatabase: () -> Unit = {},
    /** Writes one `app_settings` row; supplied by the caller that owns the preferences source. */
    onSettingChanged: (String, String?) -> Unit = { _, _ -> },
    /**
     * Writes one focus-mode preset selection; supplied by the caller that owns the preferences source.
     *
     * Separate from [onSettingChanged] because it is NINE rows written as one unit under the source's own
     * mutex, not a single-key write - and because the preset write is the one that must not be split, since
     * a half-applied preset narrows the nav rail while the startup gate still asks for a preset.
     */
    onFocusModePresetSelected: (FocusModePreset) -> Unit = {},
    /**
     * Writes the first-run dimension catalog the onboarding step confirmed.
     *
     * The write is REAL and suspend, so the caller that owns a coroutine scope supplies it - the same rule
     * [onFocusModePresetSelected] follows and for the same reason: this function is plain, and a function
     * that cannot await its own write is how "the save is mocked" happened in the first place.
     *
     * **The caller owns the outcome, and must not advance the scaffold on a failure.** The write happens
     * off this call, so its [DesktopFirstRunDimensionWriteOutcome] is not available here and is NOT
     * fabricated: advancing the stage to [DesktopScaffoldOnboardingStage.Finished] is correct only after
     * [DesktopFirstRunDimensionWriteOutcome.Written] or [AlreadyPresent], which is why the stage transition
     * lives with the write rather than here. Defaulted to a no-op, so a caller that supplies nothing writes
     * nothing - and a no-op that reported success is the mock this replaced.
     */
    onDimensionSetupPersist: (DesktopFirstRunDimensionSeed) -> Unit = {},
    /**
     * The target file for a first-run create, supplied by the caller that owns the session controller.
     *
     * A parameter rather than a path derived here so the create lands exactly where the open and the
     * transfer gateway already look - one answer to "where does the database live", not two.
     */
    firstRunDatabasePath: () -> Path = { Paths.get(DesktopAppPaths.resolveDatabaseDirectory().toString(), DesktopAppPaths.DATABASE_FILE_NAME) },
    /**
     * The passphrase the OPEN session was established with, or null when none is.
     *
     * A copy handed over by the session controller, never a re-prompt: `resolveDesktopStartupMode` only
     * reaches the Initialize gate after `hasPassphraseConfigured && sessionOpen`, so the desktop still
     * holds it, and asking again would mean two ways to be wrong. Null is a real case, not a bug - a
     * session that closed leaves the gate with no passphrase, and that is reported as a refusal.
     */
    openSessionPassphrase: () -> CharArray? = { null },
    /**
     * Re-opens the app's own session on a database a first-run create just produced.
     *
     * `PayanamDatabaseOpener.create` returns an open session as its proof and closes it again before
     * returning, so nothing is installed when a create succeeds - without this the gate would move past
     * "database ready" with the shared data layer UNINSTALLED and every read would throw.
     */
    onFirstRunDatabaseCreated: (CharArray) -> Unit = {},
): DesktopShellCallbacks =
    DesktopShellCallbacks(
        onRouteSelected = onRouteSelected,
        onSettingChanged = onSettingChanged,
        onOnboardingIntroAdvanced = {
            sessionLogger.i(
                source = "DesktopAppSupport.onOnboardingIntroAdvanced",
                message = "Onboarding intro acknowledged",
                data = emptyMap<String, Any?>(),
            )
            rememberedState.scaffoldOnboardingStageState.value =
                DesktopScaffoldOnboardingStage.DatabaseChoice
        },
        onDatabaseChoiceSelected = { choice ->
            // Logged rather than stored: the choice is real intent, but there is no data layer to act
            // on it yet, so recording it is what makes the mocked run reviewable afterwards.
            sessionLogger.i(
                source = "DesktopAppSupport.onDatabaseChoiceSelected",
                message = "Onboarding database choice recorded",
                data = mapOf("choice" to choice.name),
            )
            rememberedState.scaffoldOnboardingStageState.value =
                DesktopScaffoldOnboardingStage.DimensionSetup
        },
        onDimensionSetupSaved = { rows ->
            // The rows are REAL and so is everything downstream of them. The seed is built by the SHARED
            // builders in `DesktopStartupFlow.kt` - the user's confirmed rows, or the app's canonical
            // defaults when they confirmed nothing usable - and it is the real
            // `LifeDimensionCatalogRepository.replaceAllDimensions` swap that finally runs, not a log line.
            //
            // It is PARKED here rather than written, because of the desktop's start order: the scaffold
            // stages resolve BEFORE the passphrase, session and database gates, so the user confirms their
            // dimensions while there is still no database and `LocalDataLayer.current` throws on any read.
            // `onDimensionSetupPersist` is the caller's chance to write it now if it ever can; holding the
            // rows is the fallback, and the session-open path flushes whatever is still waiting.
            val seed = buildDesktopFirstRunDimensionSeed(rows)
            rememberedState.dimensionInputsState.value = rows
            rememberedState.pendingDimensionSeedState.value = seed
            sessionLogger.i(
                source = "DesktopAppSupport.onDimensionSetupSaved",
                message = "First-run dimension setup confirmed",
                data =
                    mapOf(
                        "dimensions" to seed.rows.size,
                        "enabled" to seed.rows.count { it.isActive },
                        "seedSource" to seed.source.name,
                    ),
            )
            // **The stage must advance here, or a FRESH install can never leave this step.**
            //
            // The start order puts the scaffold BEFORE the passphrase, session and database gates, so on
            // any launch whose database is not open yet the layer is still [UninstalledDataLayer] and
            // `seedFirstRunDimensions`' emptiness read throws. Its catch reports [Failed], the caller
            // therefore declines to advance, and the step stays open - while the ONLY writers of
            // [DesktopScaffoldOnboardingStage.Finished] are the two success paths, both of which need a
            // session. A fresh install has no session to get: the passphrase gate that would open one
            // sits BEHIND this step, so "keep the step open for the next session" is a wait for a session
            // that can never arrive. The user clicks "Save and continue" and the same card returns, forever,
            // with no message - and the copy above promising the write "is applied as soon as the database
            // exists" is never given the chance to be true.
            //
            // "No layer installed" is decidable HERE, and it needs no write outcome: a write against an
            // uninstalled layer cannot have landed, so parking the seed for
            // [flushPendingFirstRunDimensions] and letting the gates take over is unconditionally correct.
            // [resolveDesktopStartupMode] gives [DesktopScaffoldOnboardingStage.Finished] no branch of its
            // own, so advancing here skips the scaffold and NOTHING else - the passphrase, session, database
            // and focus gates still run, which is what makes this safe rather than a fail-open.
            //
            // The opposite case is left exactly as it was: with a layer installed the write can land now, so
            // the outcome is the caller's to read and a real failure must keep the step open.
            if (LocalDataLayer.current === UninstalledDataLayer) {
                rememberedState.scaffoldOnboardingStageState.value =
                    DesktopScaffoldOnboardingStage.Finished
                sessionLogger.i(
                    source = "DesktopAppSupport.onDimensionSetupSaved",
                    message =
                        "No database is open yet, so the confirmed dimensions are held for the first " +
                            "session that opens one",
                    data =
                        mapOf(
                            "dimensions" to seed.rows.size,
                            "seedSource" to seed.source.name,
                            "writeOutcome" to DesktopFirstRunDimensionWriteOutcome.Failed.name,
                        ),
                )
            }
            onDimensionSetupPersist(seed)
        },
        onSelectionChanged = { updatedSelection ->
            rememberedState.selectionState.value = updatedSelection
        },
        onConfigurePassphrase = { passphrase ->
            val result = stores.securityStore.configurePassphrase(passphrase)
            rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            if (result is DesktopPassphraseActionResult.Success) {
                rememberedState.sessionOpenState.value = true
                // The database is opened and the shared data layer installed HERE, on acceptance: a session
                // that is "open" for the app-unlock but has no repositories behind it is the state that made
                // the desktop show an empty app.
                onSessionEstablished(passphrase.toCharArray())
            }
            result
        },
        onUnlockPassphrase = { passphrase ->
            val result = stores.securityStore.verifyPassphrase(passphrase)
            rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            if (result is DesktopPassphraseActionResult.Success) {
                rememberedState.sessionOpenState.value = true
                onSessionEstablished(passphrase.toCharArray())
            }
            result
        },
        onForgotPassphraseReset = {
            // Each store logs its own success, so an aborted sequence used to read exactly like an
            // idle app: the last step that worked, then silence. runResetStep attributes a failure so
            // the log names where the sequence stopped. The request itself is already logged by the
            // startup gate, so nothing here duplicates it.
            runResetStep(ResetStep.RESET_DATABASE_ARTIFACT, sessionLogger) {
                rememberedState.databaseSnapshotState.value = stores.databaseStore.resetDatabaseArtifact()
            }
            runResetStep(ResetStep.RESET_SECURITY_STATE, sessionLogger) {
                stores.securityStore.resetSecurityState()
            }
            // No lifecycle flag to clear: readiness is derived from the file, so re-reading it is the
            // reset. The step name stays so the log still says what the reset sequence does.
            runResetStep(ResetStep.UPDATE_DATABASE_LIFECYCLE, sessionLogger) {
                rememberedState.databaseSnapshotState.value = stores.databaseStore.loadSnapshot()
            }
            rememberedState.sessionOpenState.value = false
            runResetStep(ResetStep.RELOAD_SECURITY_SNAPSHOT, sessionLogger) {
                rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            }
            runResetStep(ResetStep.RELOAD_BOOTSTRAP_SNAPSHOT, sessionLogger) {
                rememberedState.bootstrapSnapshotState.value = stores.bootstrapStore.loadSnapshot()
            }
        },
        onSecurityResetRequested = {
            // Security-scoped on purpose: the refusal state means only the security entry is
            // unreadable, so wiping the database artifact, settings and bootstrap lifecycle (which
            // onForgotPassphraseReset does) would discard healthy state for no reason.
            runResetStep(ResetStep.RESET_SECURITY_STATE, sessionLogger) {
                stores.securityStore.resetSecurityState()
            }
            runResetStep(ResetStep.RELOAD_SECURITY_SNAPSHOT, sessionLogger) {
                rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            }
        },
        onQuitRequested = onQuitRequested,
        onInitializeDatabase = {
            // The REAL first-run create, through the SAME `PayanamDatabaseOpener.create` every other
            // platform would use, at the path the open and transfer paths already use. It used to re-read
            // the snapshot and change nothing, so a brand-new Linux install had no way forward.
            //
            // The passphrase is the OPEN session's, never re-prompted, and is wiped here once the create
            // has it - the same rule the import path follows.
            val passphrase = openSessionPassphrase()
            val outcome =
                if (passphrase == null) {
                    // A create with no passphrase is not a create, and reporting it as anything else is
                    // the lie this wiring exists to remove. The gate shows this refusal verbatim.
                    sessionLogger.w(
                        source = "DesktopAppSupport.onInitializeDatabase",
                        message = "First-run create not attempted: no open session holds a passphrase",
                    )
                    DesktopDatabaseCreateOutcome.Refused(
                        issue = DatabaseBootIssue(DatabaseBootIssueType.OPEN_FAILED, NO_OPEN_SESSION_DETAIL),
                        userMessage = DESKTOP_NO_OPEN_SESSION_MESSAGE,
                    )
                } else {
                    val created =
                        createDesktopFirstRunDatabase(
                            databasePath = firstRunDatabasePath(),
                            passphrase = passphrase,
                            log = { entry ->
                                sessionLogger.i(
                                    source = "DesktopAppSupport.createDesktopFirstRunDatabase",
                                    message = "First-run database create reported",
                                    data = entry,
                                )
                            },
                        )
                    passphrase.fill('\u0000')
                    created
                }
            lastDatabaseCreateOutcome.value = outcome
            sessionLogger.i(
                source = "DesktopAppSupport.onInitializeDatabase",
                message = "First-run database create finished",
                data = mapOf("created" to (outcome is DesktopDatabaseCreateOutcome.Created)),
            )
            // Re-read the facts, which is what moves the gate: `initCompleted` is derived from this very
            // file, so there is still exactly one way past it.
            rememberedState.databaseSnapshotState.value = stores.databaseStore.ensureInitialized()
            // Only a real success re-opens the app's session - and it must, or the gate advances with the
            // data layer uninstalled and every read throws. A refusal leaves the state exactly as it was.
            if (outcome is DesktopDatabaseCreateOutcome.Created) {
                openSessionPassphrase()?.let(onFirstRunDatabaseCreated)
            }
        },
        onInitializeDatabaseOutcome = {
            lastDatabaseCreateOutcome.value?.let(::desktopDatabaseCreateStatusMessage)
        },
        onCompleteFocusModeOnboarding = { preset ->
            // The SHARED rows, through the same path the settings screen uses - the only write. This is what
            // makes the fact readable by Android and by the startup gate, which renders from the shared
            // state. The gate reads AppPreferencesState.focusModeOnboardingCompleted, so without this row
            // the user is asked to pick a preset again on every launch.
            onFocusModePresetSelected(preset)
        },
        onExportDatabase = onExportDatabase,
        onImportDatabase = onImportDatabase,
        onDismissBackupFailure = {
            // Android's dismiss, on the desktop's implementation of the same port. The banner's two fields
            // are DERIVED from `latestFailure` by the shared mapper, so clearing that is what makes the
            // banner disappear - there is no row to clear.
            stores.backupStatusStore.dismissLatestFailure()
        },
        onSilentUnlockRequested = silentUnlock@{ route ->
            // Android's guard, unchanged: a blank route names nothing to return to, and re-requesting the
            // route already held is not a second write.
            if (route.isBlank()) return@silentUnlock
            if (rememberedState.resumeToRouteAfterUnlockState.value == route) return@silentUnlock
            rememberedState.resumeToRouteAfterUnlockState.value = route
        },
        onUnlockReturnRouteConsumed = {
            rememberedState.resumeToRouteAfterUnlockState.value = null
        },
    )
