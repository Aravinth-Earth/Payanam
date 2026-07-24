//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.tasks

import java.time.LocalDateTime

internal fun seededDesktopTaskCatalog(now: LocalDateTime): DesktopTaskCatalogSnapshot =
    DesktopTaskCatalogSnapshot(
        tasks =
            listOf(
                DesktopTaskRecord(
                    id = "desk-task-plan-week",
                    title = "Plan weekly outcomes",
                    status = "active",
                    dueAtIso = now.withHour(9).withMinute(0).toString(),
                    createdAtIso = now.minusDays(2).toString(),
                    lifeDimension = "Career & Work",
                    currentScore = 0.76,
                ),
                DesktopTaskRecord(
                    id = "desk-task-bill-pay",
                    title = "Pay utility bill",
                    status = "active",
                    dueAtIso = now.minusHours(3).toString(),
                    createdAtIso = now.minusDays(5).toString(),
                    lifeDimension = "Finance",
                    currentScore = 0.42,
                ),
                DesktopTaskRecord(
                    id = "desk-task-reading",
                    title = "Read planning notes",
                    status = "completed",
                    dueAtIso = now.minusDays(1).withHour(20).withMinute(0).toString(),
                    createdAtIso = now.minusDays(4).toString(),
                    lifeDimension = "Learning",
                    currentScore = 0.68,
                ),
                DesktopTaskRecord(
                    id = "desk-task-archive",
                    title = "Archive old receipts",
                    status = "archived",
                    dueAtIso = now.minusDays(10).toString(),
                    createdAtIso = now.minusDays(20).toString(),
                    lifeDimension = "Home",
                    currentScore = 0.20,
                ),
                DesktopTaskRecord(
                    id = "desk-habit-review",
                    title = "Review daily priorities",
                    status = "active",
                    recurrenceEnabled = true,
                    dueAtIso = now.withHour(8).withMinute(0).toString(),
                    createdAtIso = now.minusDays(12).toString(),
                    lifeDimension = "Career & Work",
                    currentScore = 0.88,
                    completedToday = true,
                ),
                DesktopTaskRecord(
                    id = "desk-habit-walk",
                    title = "Evening walk",
                    status = "active",
                    recurrenceEnabled = true,
                    dueAtIso = now.withHour(18).withMinute(30).toString(),
                    createdAtIso = now.minusDays(18).toString(),
                    lifeDimension = "Health",
                    currentScore = 0.64,
                ),
                DesktopTaskRecord(
                    id = "desk-habit-journal",
                    title = "Journal reflection",
                    status = "archived",
                    recurrenceEnabled = true,
                    dueAtIso = now.withHour(21).withMinute(0).toString(),
                    createdAtIso = now.minusDays(30).toString(),
                    lifeDimension = "Self",
                    currentScore = 0.51,
                ),
            ),
    )
