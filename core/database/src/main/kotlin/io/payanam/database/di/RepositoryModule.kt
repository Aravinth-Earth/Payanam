//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.payanam.database.repository.AppSettingsRepositoryImpl
import io.payanam.database.repository.DayPlanRepositoryImpl
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
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindTimeEntryRepository(impl: TimeEntryRepositoryImpl): TimeEntryRepository

    @Binds
    @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    abstract fun bindJournalRepository(impl: JournalRepositoryImpl): JournalRepository

    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(impl: AppSettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @Singleton
    abstract fun bindLifeDimensionCatalogRepository(impl: LifeDimensionCatalogRepositoryImpl): LifeDimensionCatalogRepository

    @Binds
    @Singleton
    abstract fun bindTaskOccurrenceRepository(impl: TaskOccurrenceRepositoryImpl): TaskOccurrenceRepository

    @Binds
    @Singleton
    abstract fun bindTaskRescheduleRepository(impl: TaskRescheduleRepositoryImpl): TaskRescheduleRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository

    @Binds
    @Singleton
    abstract fun bindScoringConfigRepository(impl: ScoringConfigRepositoryImpl): ScoringConfigRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    abstract fun bindDayPlanRepository(impl: DayPlanRepositoryImpl): DayPlanRepository
}
