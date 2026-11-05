//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.data

import io.payanam.domain.port.HabitTimeEntryRecorder
import io.payanam.domain.port.PerformanceTracer
import io.payanam.domain.port.ScoreCalculator
import io.payanam.domain.port.ScoreChangeStream
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.domain.repository.JournalRepository
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.scoring.port.TaskRecurrenceHandler

/**
 * Android's [PayanamDataLayer]: the Room-backed repositories, grouped for shared code.
 *
 * The desktop's counterpart is `DesktopPayanamDataLayer` over JDBC. Neither is an adapter: both hand the
 * shared ViewModels the same `:core:domain` interfaces, which is what makes one state layer serve two apps.
 *
 * [notes] and [tags] arrive by constructor rather than being looked up, so this class holds no DI framework
 * reference and can be built directly in a test.
 */
class RoomPayanamDataLayer(
    override val notes: NoteRepository,
    override val tags: TagRepository,
    override val tasks: TaskRepository,
    override val timeEntries: TimeEntryRepository,
    override val dayPlans: DayPlanRepository,
    override val taskOccurrences: TaskOccurrenceRepository,
    override val journal: JournalRepository,
    override val habitMetrics: HabitMetricRepository,
    override val scoreWindows: ScoreWindowRepository,
    override val taskReschedules: TaskRescheduleRepository,
    override val appSettings: AppSettingsRepository,
    override val lifeDimensions: LifeDimensionCatalogRepository,
    override val reminderScheduler: TaskReminderScheduler,
    override val scoreRollupTrigger: ScoreRollupTrigger,
    override val recurrenceHandler: TaskRecurrenceHandler,
    override val habitTimeEntryRecorder: HabitTimeEntryRecorder,
    override val scoreCalculator: ScoreCalculator,
    override val tracer: PerformanceTracer,
    override val scoreChanges: ScoreChangeStream,
    override val lenses: LensRepository,
    override val scoringConfig: ScoringConfigRepository,
    override val timeTrackingController: TimeTrackingController,
    override val timeTrackingWidgetRefresher: TimeTrackingWidgetRefresher,
) : PayanamDataLayer
