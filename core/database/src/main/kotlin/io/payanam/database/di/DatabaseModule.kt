//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.payanam.database.repository.LensRepositoryImpl
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
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
}
