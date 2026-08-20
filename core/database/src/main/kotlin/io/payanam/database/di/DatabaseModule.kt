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
 * DatabaseModule.
 */
object DatabaseModule {
    @Provides
    @Singleton
    /**
     * Provide database encryption manager.
     */
    fun provideDatabaseEncryptionManager(
        @ApplicationContext context: Context,
    ): DatabaseEncryptionManager = DatabaseEncryptionManager(context)

    @Provides
    @Singleton
    /**
     * Provide database session manager.
     */
    fun provideDatabaseSessionManager(
        @ApplicationContext context: Context,
        /** Encryption manager. */
        encryptionManager: DatabaseEncryptionManager,
    ): DatabaseSessionManager = DatabaseSessionManager(context, encryptionManager)

    @Provides
    @Singleton
    /**
     * Provide lens repository.
     */
    fun provideLensRepository(
        /** Session manager. */
        sessionManager: DatabaseSessionManager,
        /** Task repository. */
        taskRepository: TaskRepository,
        /** Time entry repository. */
        timeEntryRepository: TimeEntryRepository,
        /** Task occurrence repository. */
        taskOccurrenceRepository: TaskOccurrenceRepository,
        /** Day plan repository. */
        dayPlanRepository: DayPlanRepository,
    ): LensRepository =
        /** Lens repository impl. */
        LensRepositoryImpl(
            /** Session manager. */
            sessionManager,
            /** Task repository. */
            taskRepository,
            /** Time entry repository. */
            timeEntryRepository,
            /** Task occurrence repository. */
            taskOccurrenceRepository,
            /** Day plan repository. */
            dayPlanRepository,
        )
}
