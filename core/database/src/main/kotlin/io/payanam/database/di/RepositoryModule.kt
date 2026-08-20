//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.payanam.database.repository.AppSettingsRepositoryImpl
import io.payanam.database.repository.DayPlanRepositoryImpl
import io.payanam.database.repository.HabitMetricRepositoryImpl
import io.payanam.database.repository.ScoreWindowRepositoryImpl
import io.payanam.database.repository.JournalRepositoryImpl
import io.payanam.database.repository.LifeDimensionCatalogRepositoryImpl
import io.payanam.database.repository.NoteRepositoryImpl
import io.payanam.database.repository.NotificationRepositoryImpl
import io.payanam.database.repository.ScoringConfigRepositoryImpl
import io.payanam.database.repository.TagRepositoryImpl
import io.payanam.database.repository.TaskOccurrenceRepositoryImpl
import io.payanam.database.repository.TaskRepositoryImpl
import io.payanam.database.repository.TaskRescheduleRepositoryImpl
import io.payanam.database.repository.TimeEntryRepositoryImpl
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.domain.repository.JournalRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.domain.repository.TimeEntryRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
/**
 * RepositoryModule.
 */
abstract class RepositoryModule {
    @Binds
    @Singleton
    /**
     * Bind task repository.
     */
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    /**
     * Bind habit metric repository.
     */
    abstract fun bindHabitMetricRepository(impl: HabitMetricRepositoryImpl): HabitMetricRepository

    @Binds
    @Singleton
    /**
     * Bind score window repository.
     */
    abstract fun bindScoreWindowRepository(impl: ScoreWindowRepositoryImpl): ScoreWindowRepository

    @Binds
    @Singleton
    /**
     * Bind time entry repository.
     */
    abstract fun bindTimeEntryRepository(impl: TimeEntryRepositoryImpl): TimeEntryRepository

    @Binds
    @Singleton
    /**
     * Bind note repository.
     */
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    /**
     * Bind journal repository.
     */
    abstract fun bindJournalRepository(impl: JournalRepositoryImpl): JournalRepository

    @Binds
    @Singleton
    /**
     * Bind app settings repository.
     */
    abstract fun bindAppSettingsRepository(impl: AppSettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @Singleton
    /**
     * Bind life dimension catalog repository.
     */
    abstract fun bindLifeDimensionCatalogRepository(impl: LifeDimensionCatalogRepositoryImpl): LifeDimensionCatalogRepository

    @Binds
    @Singleton
    /**
     * Bind task occurrence repository.
     */
    abstract fun bindTaskOccurrenceRepository(impl: TaskOccurrenceRepositoryImpl): TaskOccurrenceRepository

    @Binds
    @Singleton
    /**
     * Bind task reschedule repository.
     */
    abstract fun bindTaskRescheduleRepository(impl: TaskRescheduleRepositoryImpl): TaskRescheduleRepository

    @Binds
    @Singleton
    /**
     * Bind notification repository.
     */
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository

    @Binds
    @Singleton
    /**
     * Bind scoring config repository.
     */
    abstract fun bindScoringConfigRepository(impl: ScoringConfigRepositoryImpl): ScoringConfigRepository

    @Binds
    @Singleton
    /**
     * Bind tag repository.
     */
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    /**
     * Bind day plan repository.
     */
    abstract fun bindDayPlanRepository(impl: DayPlanRepositoryImpl): DayPlanRepository
}
