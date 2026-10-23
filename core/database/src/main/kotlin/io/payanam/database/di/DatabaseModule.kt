//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.payanam.database.backfill.ScoreRollupCascadeService
import io.payanam.database.event.ScoreChangeEventBus
import io.payanam.database.repository.LensRepositoryImpl
import io.payanam.database.security.AndroidForgottenPassphraseReset
import io.payanam.database.security.AndroidPassphraseVault
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.domain.port.ScoreChangeStream
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.shared.security.PassphraseVault
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
/**
 * Hilt provider module that constructs the database infrastructure singletons
 * (encryption manager, session manager, and the LensRepository facade) from
 * their concrete classes.
 */
object DatabaseModule {
    @Provides
    @Singleton
    /**
     * Builds the [DatabaseEncryptionManager] singleton bound to the app
     * context, owning all at-rest encryption metadata.
     */
    fun provideDatabaseEncryptionManager(
        @ApplicationContext context: Context,
    ): DatabaseEncryptionManager = DatabaseEncryptionManager(context)

    @Provides
    @Singleton
    /**
     * Exposes Android's Keystore-backed vault as the shared [PassphraseVault].
     *
     * A `@Provides` rather than a `@Binds` for the same reason as [provideRecurrenceManager] above: the
     * adapter takes the manager as a constructor argument, so the wiring is one line here instead of a
     * `@Inject` annotation on a class in this module that nothing else needs.
     */
    fun providePassphraseVault(
        @ApplicationContext context: Context,
        encryptionManager: DatabaseEncryptionManager,
    ): PassphraseVault = AndroidPassphraseVault(context, encryptionManager)

    @Provides
    @Singleton
    /**
     * Exposes Android's data-deleting recovery path as the shared
     * [DatabaseForgotPassphraseReset] port.
     *
     * Over the SAME vault instance the unlock gate authenticates with, not a second one: the reset clears a
     * credential, and a credential that some other object still believes in is a reset the app half-performed.
     */
    fun provideForgottenPassphraseReset(
        @ApplicationContext context: Context,
        vault: PassphraseVault,
    ): DatabaseForgotPassphraseReset = AndroidForgottenPassphraseReset(context, vault)

    @Provides
    @Singleton
    /**
     * Builds the [DatabaseSessionManager] singleton, which opens/closes the
     * encrypted Room database using the supplied encryption manager.
     */
    fun provideDatabaseSessionManager(
        @ApplicationContext context: Context,
        encryptionManager: DatabaseEncryptionManager,
    ): DatabaseSessionManager = DatabaseSessionManager(context, encryptionManager)

    @Provides
    @Singleton
    /**
     * Assembles the [LensRepositoryImpl] facade, injecting the session manager
     * and the planning/reality data sources it reads from.
     */
    fun provideLensRepository(
        sessionManager: DatabaseSessionManager,
        taskRepository: TaskRepository,
        timeEntryRepository: TimeEntryRepository,
        taskOccurrenceRepository: TaskOccurrenceRepository,
        dayPlanRepository: DayPlanRepository,
    ): LensRepository =
        LensRepositoryImpl(
            sessionManager,
            taskRepository,
            timeEntryRepository,
            taskOccurrenceRepository,
            dayPlanRepository,
        )

    @Provides
    @Singleton
    /**
     * Exposes the score-rollup cascade as the [ScoreRollupTrigger] port, so the
     * shared state layer can recompute scores without depending on this module.
     */
    fun provideScoreRollupTrigger(cascade: ScoreRollupCascadeService): ScoreRollupTrigger = cascade

    @Provides
    @Singleton
    /**
     * Exposes the score-change bus as the [ScoreChangeStream] port, so a shared
     * ViewModel can refresh derived values without depending on this module.
     */
    fun provideScoreChangeStream(bus: ScoreChangeEventBus): ScoreChangeStream = bus
}
