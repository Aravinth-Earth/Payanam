//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DailyInsightDao
import io.payanam.database.dao.ImportBatchDao
import io.payanam.database.dao.TaskDao
import io.payanam.database.dao.TaskOccurrenceDao
import io.payanam.database.entity.ImportBatchEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.repository.markLensDayDirty
import io.payanam.domain.model.Frequency
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class UhabitsImporter(
    private val context: Context,
    private val taskDao: TaskDao,
    private val taskOccurrenceDao: TaskOccurrenceDao,
    private val importBatchDao: ImportBatchDao,
    private val dailyInsightDao: DailyInsightDao,
) {
    private val logger = UnifiedLogger.getInstance()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    suspend fun import(sourceUri: Uri): UhabitsImportSummary {
        val tempDb = File.createTempFile("uhabits_import_", ".db", context.cacheDir)
        val importedAt = LocalDateTime.now().format(dateTimeFormatter)
        val importBatchId = UUID.randomUUID().toString()
        importBatchDao.insert(
            ImportBatchEntity(
                id = importBatchId,
                source = IMPORT_SOURCE_UHABITS,
                importedAt = importedAt,
                notes = "Imported from uHabits SQLite backup",
            ),
        )
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempDb).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open uHabits file")

            val habits = readUhabitsHabits(tempDb)
            if (habits.isEmpty()) {
                throw IllegalStateException("No habits found in selected uHabits database")
            }

            var habitsUpserted = 0
            var repetitionsUpserted = 0
            val dirtyDayKeys = mutableSetOf<String>()
            habits.forEach { habit ->
                val importId = habit.uuid?.takeIf { it.isNotBlank() } ?: habit.id.toString()
                val existingTask = taskDao.getTaskByImportRef(IMPORT_SOURCE_UHABITS, importId)
                val taskId = existingTask?.id ?: UUID.randomUUID().toString()
                val dueDate = (habit.latestRepetitionDate ?: LocalDate.now()).atStartOfDay().format(dateTimeFormatter)
                val recurrenceRule =
                    Frequency(
                        numerator = habit.freqNum.coerceAtLeast(1),
                        denominator = habit.freqDen.coerceAtLeast(1),
                        anchorDate = habit.firstRepetitionDate ?: habit.latestRepetitionDate ?: LocalDate.now(),
                    ).serialize()
                val entity = TaskEntity(
                    id = taskId,
                    title = habit.name.ifBlank { "Imported Habit ${habit.id}" },
                    description = habit.description,
                    status = existingTask?.status ?: "pending",
                    dueDate = dueDate,
                    createdAt = existingTask?.createdAt ?: importedAt,
                    updatedAt = importedAt,
                    recurrenceEnabled = 1,
                    recurrenceRule = recurrenceRule,
                    durationMinutes = existingTask?.durationMinutes ?: 15,
                    impactLevel = existingTask?.impactLevel ?: "Moderate Impact",
                    goalAlignment = existingTask?.goalAlignment ?: "Moderate Alignment",
                    energyLevel = existingTask?.energyLevel ?: "Moderate",
                    controlLevel = existingTask?.controlLevel ?: "Office/Colleagues Dependent",
                    lifeIntentionCategory = existingTask?.lifeIntentionCategory ?: UNASSIGNED_LABEL,
                    dimensionId = existingTask?.dimensionId ?: UNASSIGNED_DIMENSION_ID,
                    dayKey = dueDate.take(10),
                    explicitUrgency = existingTask?.explicitUrgency,
                    focusRequired = existingTask?.focusRequired,
                    blockedReason = existingTask?.blockedReason,
                    completionRate = existingTask?.completionRate,
                    externalDependency = existingTask?.externalDependency,
                    notificationMode = existingTask?.notificationMode ?: "auto",
                    customNotificationMinutes = existingTask?.customNotificationMinutes,
                    taskScore = existingTask?.taskScore,
                    currentScore = existingTask?.currentScore ?: 1.0,
                    lastOccurrenceDate = existingTask?.lastOccurrenceDate,
                    importSource = IMPORT_SOURCE_UHABITS,
                    importId = importId,
                    importedAt = importedAt,
                    importBatchId = importBatchId,
                )
                taskDao.insert(entity)
                habitsUpserted++
                dirtyDayKeys += dueDate.take(10)

                habit.repetitions.forEach { repetition ->
                    val existingOccurrence = taskOccurrenceDao.getOccurrenceForTaskOnDate(
                        taskId = taskId,
                        date = repetition.date.format(dateFormatter),
                    )
                    if (existingOccurrence == null) {
                        taskOccurrenceDao.insert(
                            TaskOccurrenceEntity(
                                id = UUID.randomUUID().toString(),
                                taskId = taskId,
                                dueDate = repetition.date.atStartOfDay().format(dateTimeFormatter),
                                completedAt = repetition.completedAt?.format(dateTimeFormatter),
                                actualCompletedAt = repetition.completedAt?.format(dateTimeFormatter),
                                actualDurationMinutes = null,
                                status = repetition.status,
                                statusReason = null,
                                createdAt = importedAt,
                                completionRate = if (repetition.status == "completed") 1.0 else 0.0,
                                note = repetition.note,
                            ),
                        )
                    } else {
                        taskOccurrenceDao.updateOccurrence(
                            id = existingOccurrence.id,
                            status = repetition.status,
                            statusReason = null,
                            note = repetition.note,
                            completedAt = repetition.completedAt?.format(dateTimeFormatter),
                            actualCompletedAt = repetition.completedAt?.format(dateTimeFormatter),
                            actualDurationMinutes = null,
                        )
                    }
                    repetitionsUpserted++
                    dirtyDayKeys += repetition.date.format(dateFormatter)
                }
            }
            dirtyDayKeys.forEach { dayKey ->
                markLensDayDirty(
                    dailyInsightDao = dailyInsightDao,
                    logger = logger,
                    dayKey = dayKey,
                    changedModules = setOf("import", "task"),
                    reason = "uhabits_import",
                )
            }
            logger.i(
                "UhabitsImporter.import",
                "uHabits import completed",
                mapOf(
                    "habitsUpserted" to habitsUpserted,
                    "repetitionsUpserted" to repetitionsUpserted,
                ),
            )
            return UhabitsImportSummary(habitsUpserted = habitsUpserted, repetitionsUpserted = repetitionsUpserted)
        } finally {
            tempDb.delete()
        }
    }

    private fun readUhabitsHabits(databaseFile: File): List<UhabitsHabitRecord> {
        val habits = mutableListOf<UhabitsHabitRecord>()
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (!hasTable(db, "Habits") || !hasTable(db, "Repetitions")) {
                throw IllegalStateException("Selected file is not a valid uHabits (Loop) database")
            }

            val hasRepetitionValueColumn = hasColumn(db, "Repetitions", "value")
            val hasRepetitionNotesColumn = hasColumn(db, "Repetitions", "notes")
            val repetitionsQuery = buildRepetitionsQuery(hasRepetitionValueColumn, hasRepetitionNotesColumn)
            val repetitionsByHabit = mutableMapOf<Long, MutableList<UhabitsRepetitionRecord>>()
            db.rawQuery(repetitionsQuery, null).use { cursor ->
                val habitIndex = cursor.getColumnIndexOrThrow("habit")
                val timestampIndex = cursor.getColumnIndexOrThrow("timestamp")
                val valueIndex = cursor.getColumnIndexOrThrow("value")
                val notesIndex = cursor.getColumnIndexOrThrow("notes")
                while (cursor.moveToNext()) {
                    val habitId = cursor.getLong(habitIndex)
                    val timestampRaw = cursor.getLong(timestampIndex)
                    val timestampMillis = if (timestampRaw < 100_000_000_000L) timestampRaw * 1000 else timestampRaw
                    val dateTime = Instant.ofEpochMilli(timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                    val value = cursor.getInt(valueIndex)
                    val status = if (value > 0) "completed" else "skipped"
                    val note = if (!cursor.isNull(notesIndex)) cursor.getString(notesIndex) else null
                    repetitionsByHabit.getOrPut(habitId) { mutableListOf() }.add(
                        UhabitsRepetitionRecord(
                            date = dateTime.toLocalDate(),
                            status = status,
                            completedAt = if (status == "completed") dateTime else null,
                            note = note,
                        ),
                    )
                }
            }

            db.rawQuery("SELECT id, name, description, freq_num, freq_den, uuid FROM Habits", null).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val descriptionIndex = cursor.getColumnIndex("description")
                val freqNumIndex = cursor.getColumnIndex("freq_num")
                val freqDenIndex = cursor.getColumnIndex("freq_den")
                val uuidIndex = cursor.getColumnIndex("uuid")
                while (cursor.moveToNext()) {
                    val habitId = cursor.getLong(idIndex)
                    val repetitions = repetitionsByHabit[habitId]?.sortedBy { it.date } ?: emptyList()
                    habits.add(
                        UhabitsHabitRecord(
                            id = habitId,
                            name = cursor.getString(nameIndex) ?: "",
                            description = if (descriptionIndex >= 0 && !cursor.isNull(descriptionIndex)) {
                                cursor.getString(descriptionIndex)
                            } else {
                                null
                            },
                            freqNum = if (freqNumIndex >= 0) cursor.getInt(freqNumIndex) else 1,
                            freqDen = if (freqDenIndex >= 0) cursor.getInt(freqDenIndex) else 1,
                            uuid = if (uuidIndex >= 0 && !cursor.isNull(uuidIndex)) cursor.getString(uuidIndex) else null,
                            repetitions = repetitions,
                            firstRepetitionDate = repetitions.minOfOrNull { it.date },
                            latestRepetitionDate = repetitions.maxOfOrNull { it.date },
                        ),
                    )
                }
            }
        }
        return habits
    }

    private fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName),
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) > 0
        }
    }

    private fun hasColumn(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    data class UhabitsImportSummary(
        val habitsUpserted: Int,
        val repetitionsUpserted: Int,
    )

    private data class UhabitsHabitRecord(
        val id: Long,
        val name: String,
        val description: String?,
        val freqNum: Int,
        val freqDen: Int,
        val uuid: String?,
        val repetitions: List<UhabitsRepetitionRecord>,
        val firstRepetitionDate: LocalDate?,
        val latestRepetitionDate: LocalDate?,
    )

    private data class UhabitsRepetitionRecord(
        val date: LocalDate,
        val status: String,
        val completedAt: LocalDateTime?,
        val note: String?,
    )

    companion object {
        private const val IMPORT_SOURCE_UHABITS = "uhabits"
        private const val UNASSIGNED_DIMENSION_ID = "dim_unassigned"
        private const val UNASSIGNED_LABEL = "Unassigned"

        internal fun buildRepetitionsQuery(
            hasValueColumn: Boolean,
            hasNotesColumn: Boolean,
        ): String {
            val valueSql = if (hasValueColumn) "value" else "1 AS value"
            val notesSql = if (hasNotesColumn) "notes" else "NULL AS notes"
            return "SELECT habit, timestamp, $valueSql, $notesSql FROM Repetitions"
        }
    }
}
