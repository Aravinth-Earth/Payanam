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
 * Hilt binding module that wires each concrete Room-backed repository
 * implementation to its domain-layer repository interface, so the rest of
 * the app depends only on the abstractions.
 */
abstract class RepositoryModule {
    @Binds
    @Singleton
    /**
     * Exposes the task repository (CRUD + queries over tasks) as its
     * domain contract.
     */
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    /**
     * Exposes the habit-metric repository (daily habit completion stats) as its
     * domain contract.
     */
    abstract fun bindHabitMetricRepository(impl: HabitMetricRepositoryImpl): HabitMetricRepository

    @Binds
    @Singleton
    /**
     * Exposes the score-window repository (rolling time windows used by
     * scoring) as its domain contract.
     */
    abstract fun bindScoreWindowRepository(impl: ScoreWindowRepositoryImpl): ScoreWindowRepository

    @Binds
    @Singleton
    /**
     * Exposes the time-entry repository (tracked time spans) as its
     * domain contract.
     */
    abstract fun bindTimeEntryRepository(impl: TimeEntryRepositoryImpl): TimeEntryRepository

    @Binds
    @Singleton
    /**
     * Exposes the note repository (notes + tags + links) as its
     * domain contract.
     */
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    /**
     * Exposes the journal repository (daily reflections + responses) as its
     * domain contract.
     */
    abstract fun bindJournalRepository(impl: JournalRepositoryImpl): JournalRepository

    @Binds
    @Singleton
    /**
     * Exposes the app-settings repository (key-value user preferences) as its
     * domain contract.
     */
    abstract fun bindAppSettingsRepository(impl: AppSettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @Singleton
    /**
     * Exposes the life-dimension catalog repository (the canonical dimension
     * taxonomy + user overrides) as its domain contract.
     */
    abstract fun bindLifeDimensionCatalogRepository(impl: LifeDimensionCatalogRepositoryImpl): LifeDimensionCatalogRepository

    @Binds
    @Singleton
    /**
     * Exposes the task-occurrence repository (scheduled instances of recurring
     * tasks) as its domain contract.
     */
    abstract fun bindTaskOccurrenceRepository(impl: TaskOccurrenceRepositoryImpl): TaskOccurrenceRepository

    @Binds
    @Singleton
    /**
     * Exposes the task-reschedule repository (missed-task deferral records) as
     * its domain contract.
     */
    abstract fun bindTaskRescheduleRepository(impl: TaskRescheduleRepositoryImpl): TaskRescheduleRepository

    @Binds
    @Singleton
    /**
     * Exposes the notification repository (scheduled reminders + history) as
     * its domain contract.
     */
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository

    @Binds
    @Singleton
    /**
     * Exposes the scoring-config repository (weights + thresholds driving the
     * score) as its domain contract.
     */
    abstract fun bindScoringConfigRepository(impl: ScoringConfigRepositoryImpl): ScoringConfigRepository

    @Binds
    @Singleton
    /**
     * Exposes the tag repository (note/task tagging) as its
     * domain contract.
     */
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    /**
     * Exposes the day-plan repository (planned time allocations per day/type)
     * as its domain contract.
     */
    abstract fun bindDayPlanRepository(impl: DayPlanRepositoryImpl): DayPlanRepository
}
