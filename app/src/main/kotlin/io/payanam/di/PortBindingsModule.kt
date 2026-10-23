//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.text.UiStrings
import io.payanam.database.security.AndroidEncryptionStateCheckpoint
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.AndroidDatabaseOpener
import io.payanam.domain.port.BackupStatusStore
import io.payanam.domain.port.DatabaseBackupCoordinator
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.port.HabitTimeEntryRecorder
import io.payanam.domain.port.PerformanceTracer
import io.payanam.domain.port.ProcessRestart
import io.payanam.domain.port.ScoreCalculator
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.platform.AndroidBackupStatusPort
import io.payanam.platform.AndroidDatabaseBackupPort
import io.payanam.platform.AndroidDatabasePathResolver
import io.payanam.platform.AndroidDatabaseSessionPort
import io.payanam.platform.AndroidDocumentSource
import io.payanam.platform.AndroidProcessRestartPort
import io.payanam.platform.appDatabaseDimensionSetupPersister
import io.payanam.platform.appDatabaseInitFlagReader
import io.payanam.platform.appDatabaseInitFlagWriter
import io.payanam.platform.appDatabaseInitWalConsolidator
import io.payanam.presentation.database.DatabaseDimensionSetupPersister
import io.payanam.presentation.database.DatabaseInitFlagReader
import io.payanam.presentation.database.DatabaseInitFlagWriter
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.scoring.ElegantTaskScoring
import io.payanam.scoring.RecurrenceManager
import io.payanam.scoring.port.TaskRecurrenceHandler
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.security.BiometricAuthentication
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricRefusalReason
import io.payanam.shared.security.BiometricUnavailableReason
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseVault
import io.payanam.ui.perf.PerfBaselineTelemetry
import io.payanam.usecase.AndroidTimeTrackingWidgetRefresher
import io.payanam.usecase.CreateTimeEntryForHabitUseCase
import io.payanam.usecase.TimeTrackingUseCase
import java.io.File
import javax.inject.Singleton

/**
 * Wires the app's platform-bound implementations to the domain ports the shared state layer drives, so a
 * ViewModel in `:core:presentation` can depend on the capability instead of on this app.
 *
 * Everything here follows one shape: the interface lives in a shared module, the implementation stays where the
 * platform work is, and this module is the only place that knows both.
 */
@Module
@InstallIn(SingletonComponent::class)
object PortBindingsModule {
    /**
     * Exposes the alarm/notification-backed scheduler as the reminder port.
     */
    @Provides
    @Singleton
    fun provideTaskReminderScheduler(impl: NotificationScheduler): TaskReminderScheduler = impl

    /**
     * Builds the recurrence manager as the port the shared ViewModels advance schedules through.
     *
     * A `@Provides` rather than a `@Binds`, because [RecurrenceManager] no longer carries `@Inject`: it
     * was promoted into `:core:scoring`, which is a plain JVM module with no dependency on Hilt. Its two
     * collaborators are both already-bound repositories, so the wiring is two lines rather than a DI
     * framework that the shared layer must not depend on.
     */
    @Provides
    @Singleton
    fun provideRecurrenceManager(
        taskRepository: TaskRepository,
        taskOccurrenceRepository: TaskOccurrenceRepository,
    ): RecurrenceManager = RecurrenceManager(taskRepository, taskOccurrenceRepository)

    /**
     * Exposes the same instance as the port, so a screen that advances schedules and a screen that reads
     * the score of an advanced schedule cannot observe two different managers.
     */
    @Provides
    @Singleton
    fun provideTaskRecurrenceHandler(manager: RecurrenceManager): TaskRecurrenceHandler = manager

    /**
     * Exposes the time-tracking use case as the port that stops tracking and completes the task.
     */
    @Provides
    @Singleton
    fun provideTimeTrackingController(impl: TimeTrackingUseCase): TimeTrackingController = impl

    /**
     * Exposes the widget refresher as the port that repaints the home-screen time widget.
     */
    @Provides
    @Singleton
    fun provideTimeTrackingWidgetRefresher(
        impl: AndroidTimeTrackingWidgetRefresher,
    ): TimeTrackingWidgetRefresher = impl

    /**
     * Exposes the habit time-entry creator as the port the shared ViewModels record through.
     */
    @Provides
    @Singleton
    fun provideHabitTimeEntryRecorder(impl: CreateTimeEntryForHabitUseCase): HabitTimeEntryRecorder = impl

    /**
     * Exposes Android's Room/SQLCipher session manager as the shared session port: open and close, the
     * passphrase handoff, and the owner-backed open state the contract pins (never a UI flag).
     */
    @Provides
    @Singleton
    fun provideDatabaseSessionManager(impl: AndroidDatabaseSessionPort): DatabaseSessionManager = impl

    /**
     * Exposes Android's own database-path resolution — the platform's authority for the file name.
     */
    @Provides
    @Singleton
    fun provideDatabasePathResolver(impl: AndroidDatabasePathResolver): DatabasePathResolver = impl

    /**
     * Exposes Android's persisted backup status as the read port the shared screens render.
     */
    @Provides
    @Singleton
    fun provideBackupStatusStore(impl: AndroidBackupStatusPort): BackupStatusStore = impl

    /**
     * Exposes Android's backup execution as the shared coordinator port.
     */
    @Provides
    @Singleton
    fun provideDatabaseBackupCoordinator(impl: AndroidDatabaseBackupPort): DatabaseBackupCoordinator = impl

    /**
     * Exposes Android's database opener as the shared [PayanamDatabaseOpener] the unlock gate opens its
     * session through.
     *
     * A `@Provides` for the same reason as [provideRecurrenceManager] above: [AndroidDatabaseOpener] has no
     * `@Inject` constructor - it takes the session port and the app context - so the wiring is one line
     * here. Singleton-scoped because the gate opens the session that every other shared ViewModel then
     * reads through, and two openers would be two answers to "is the database open".
     */
    @Provides
    @Singleton
    fun providePayanamDatabaseOpener(
        sessionManager: io.payanam.database.session.DatabaseSessionManager,
        @ApplicationContext context: Context,
    ): PayanamDatabaseOpener = AndroidDatabaseOpener(sessionManager, context)

    /**
     * The app's own database file, resolved through the platform's [DatabasePathResolver].
     *
     * A `File` rather than a path String because the shared ViewModel stats it and the reset lists the
     * artifact family around it, and the file name is the platform's decision - `DatabaseModule` binds the
     * resolver, so a path spelled here would be a second answer to "where does the database live".
     */
    @Provides
    @Singleton
    fun provideDatabaseFile(resolver: DatabasePathResolver): File = File(resolver.databaseFilePath())

    /**
     * Exposes the encryption-checkpoint port over the SAME [DatabaseEncryptionManager] the app already binds.
     *
     * WHY IT TAKES THE MANAGER AND NOT A CONTEXT: a second `DatabaseEncryptionManager` would open a second
     * handle on the same preferences blob, and the checkpoint's whole job is to be about the state that manager
     * is concurrently writing. Two handles on one blob is a race that only shows up during the failure this
     * port exists to make recoverable, which is the worst possible time to find it.
     *
     * A `@Provides` rather than a `@Binds` for the same reason as [provideRecurrenceManager]: the adapter is
     * constructed here so the shared layer can name the port without knowing the adapter exists.
     */
    @Provides
    @Singleton
    fun provideEncryptionStateCheckpoint(manager: DatabaseEncryptionManager): EncryptionStateCheckpoint = AndroidEncryptionStateCheckpoint(manager)

    /**
     * Exposes Android's SAF-backed document source as the shared [DocumentSource] port.
     */
    @Provides
    @Singleton
    fun provideDocumentSource(@ApplicationContext context: Context): DocumentSource = AndroidDocumentSource(context)

    /**
     * Exposes Android's database-init flag writer as the shared [DatabaseInitFlagWriter] port.
     */
    @Provides
    @Singleton
    fun provideDatabaseInitFlagWriter(@ApplicationContext context: Context): DatabaseInitFlagWriter = appDatabaseInitFlagWriter(context)

    /** The new database's life-dimension seed writer; needs no context, only the shared repositories. */
    @Provides
    fun provideDatabaseDimensionSetupPersister(): DatabaseDimensionSetupPersister = appDatabaseDimensionSetupPersister()

    /**
     * Exposes Android's read-only app_settings reader as the shared [DatabaseInitFlagReader] port.
     */
    @Provides
    @Singleton
    fun provideDatabaseInitFlagReader(): DatabaseInitFlagReader = appDatabaseInitFlagReader()

    /**
     * Exposes Android's WAL checkpoint-on-a-temp-copy as the shared [DatabaseInitWalConsolidator] port.
     *
     * The logger is resolved here rather than in the ViewModel: the shared module cannot see
     * `UnifiedLogger` at all, and reaching for the singleton from a field initialiser would throw in any
     * test that constructs the ViewModel without initialising logging first.
     */
    @Provides
    @Singleton
    fun provideDatabaseInitWalConsolidator(): DatabaseInitWalConsolidator = appDatabaseInitWalConsolidator(UnifiedLogger.getInstance())

    /**
     * Exposes Android's re-launch-then-kill as the shared [ProcessRestart] port.
     *
     * A `@Provides` over the `@Inject` adapter for the same reason as [provideEncryptionStateCheckpoint]:
     * the shared layer names the port without knowing the adapter exists. The four calls it makes are the
     * ones `DatabasePassphraseChangeViewModel.restartProcess` made inline, unchanged - see
     * `AndroidProcessRestartPort`.
     */
    @Provides
    @Singleton
    fun provideProcessRestart(impl: AndroidProcessRestartPort): ProcessRestart = impl
}

/**
 * Hilt entry point for the unlock gate's collaborators, resolved at the Compose call site.
 *
 * WHY AN ENTRY POINT AND NOT `hiltViewModel()`: the unlock ViewModel is a plain class in
 * `:core:presentation` now, and `hiltViewModel<T>()` resolves through a factory that only knows
 * `@HiltViewModel` classes - which a shared module cannot carry, because the desktop has no Hilt at all.
 * So the platform builds the object at the one call site, and this is how it gets the collaborators Hilt
 * already binds. The same shape as `PayanamApp.DataLayerEntryPoint` and `BackupWorkerEntryPoint`: a list of
 * accessors, not a second graph.
 *
 * [vault] and [forgottenPassphraseReset] are the SAME singletons `DatabaseModule` binds, deliberately - the
 * reset clears a credential the gate authenticates with, and a second vault would be a reset the app
 * half-performed. [uiStrings] is the generated copy table from `UiStringsModule`.
 *
 * WHY THE ENCRYPTION MANAGER IS RESOLVED RATHER THAN THE PROMPT: [AndroidBiometricUnlockPrompt] takes a
 * `FragmentActivity`, and an Activity is not in the SingletonComponent, so it cannot be a `@Provides` at
 * all. The caller - which is inside the Activity's composition - supplies it, which is the same division
 * of labour the desktop has: the shared layer names a port, the platform owns the Activity.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PassphraseUnlockEntryPoint {
    /** The Keystore-backed vault, shared with the biometric adapter and the reset. */
    fun passphraseVault(): PassphraseVault

    /** The data-deleting recovery path, over the same vault. */
    fun forgottenPassphraseReset(): DatabaseForgotPassphraseReset

    /** The generated copy table for the language in force. */
    fun uiStrings(): UiStrings

    /** Android's opener, which opens the session the gate is unlocking for. */
    fun payanamDatabaseOpener(): PayanamDatabaseOpener

    /** The app's own database file, at the path the platform resolves. */
    fun databaseFile(): File

    /** The Keystore-backed manager the biometric prompt needs to unwrap the stored passphrase. */
    fun databaseEncryptionManager(): DatabaseEncryptionManager
}

/**
 * Android's answer when there is no `FragmentActivity` to show a prompt from.
 *
 * The refusal is stated, not faked: a no-op that reported success would render "unlocked" over a database
 * nobody opened, and the UI's appearance would be the whole of the evidence. It reports
 * [BiometricUnavailableReason.UNKNOWN] rather than a sensor verdict because no sensor was surveyed - the
 * platform could not answer - and the shared copy already renders a reason it cannot explain. Same rule the
 * desktop's `DesktopBiometricUnlockPrompt` follows for the same situation.
 *
 * It lives here rather than beside `AndroidBiometricUnlockPrompt` because the activity-less case is a
 * wiring fact of the unlock gate, not a property of the adapter.
 */
internal object UnavailableBiometricUnlockPrompt : BiometricUnlockPrompt {
    override fun availability(): BiometricAvailability = BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN)

    /**
     * Refuses rather than returning a passphrase, and is unreachable in practice - [availability] already
     * refuses, and the screen reads it before offering the affordance. Implemented rather than left to throw
     * because a port method that throws is a crash every caller must defend against, and this one has an
     * honest answer.
     */
    override suspend fun requestAuthentication(): BiometricAuthentication = BiometricAuthentication.Refused(BiometricRefusalReason.FAILED)
}

/**
 * Provider-side counterparts to [PortBindingsModule]: the two implementations that are Kotlin `object`s have
 * no injectable constructor for `@Binds` to consume, so they are returned directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object PortProvidersModule {
    /**
     * Exposes the elegant task-scoring object as the score calculator port.
     */
    @Provides
    @Singleton
    fun provideScoreCalculator(): ScoreCalculator = ElegantTaskScoring

    /**
     * Exposes the perf-baseline object as the tracer port.
     */
    @Provides
    @Singleton
    fun providePerformanceTracer(): PerformanceTracer = PerfBaselineTelemetry
}
