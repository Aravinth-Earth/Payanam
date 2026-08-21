//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "LongMethod", "MagicNumber", "UseCheckOrError")


package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from v0.0.2 (Capacitor/React version) to v0.0.3 (Kotlin version).
 *
 * Key changes:
 * - Converts durationHours (REAL) to durationMinutes (INTEGER) by multiplying by 60
 * - Adds new required fields with defaults
 * - Fixes nullable/required field mismatches
 * - Adds missing columns like notificationMode, customNotificationMinutes
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.1_2", "Starting migration from version 1 to 2")

            try {
                // Check if durationHours column exists (v0.0.2 database)
                val cursor = database.query("PRAGMA table_info(tasks)")
                var hasDurationHours = false
                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(cursor.getColumnIndex("name"))
                    if (columnName == "durationHours") {
                        hasDurationHours = true
                        break
                    }
                }
                cursor.close()

                logger.d("Migration.1_2", "Schema check", mapOf("hasDurationHours" to hasDurationHours))
                if (hasDurationHours) {
                    logger.i("Migration.1_2", "Detected v0.0.2 database, converting schema")

                    // Create new tasks table with correct schema
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS tasks_new (
                            id TEXT PRIMARY KEY NOT NULL,
                            title TEXT NOT NULL,
                            description TEXT,
                            status TEXT NOT NULL DEFAULT 'pending',
                            dueDate TEXT,
                            durationMinutes INTEGER NOT NULL DEFAULT 60,
                            impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact',
                            goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
                            energyLevel TEXT NOT NULL DEFAULT 'Moderate',
                            controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
                            lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work',
                            taskScore REAL,
                            createdAt TEXT NOT NULL,
                            updatedAt TEXT NOT NULL,
                            completedAt TEXT,
                            archivedAt TEXT,
                            recurrenceEnabled INTEGER NOT NULL DEFAULT 0,
                            recurrenceRule TEXT,
                            explicitUrgency REAL,
                            focusRequired REAL,
                            recurrenceStrategy TEXT,
                            blockedReason TEXT,
                            completionRate REAL,
                            externalDependency TEXT,
                            notificationMode TEXT,
                            customNotificationMinutes INTEGER
                        )
                        """.trimIndent(),
                    )

                    logger.d("Migration.1_2", "New table created, copying data")

                    // Copy data with conversions
                    database.execSQL(
                        """
                        INSERT INTO tasks_new (
                            id, title, description, status, dueDate, 
                            durationMinutes,
                            impactLevel, goalAlignment, energyLevel, controlLevel, lifeIntentionCategory,
                            taskScore, createdAt, updatedAt, completedAt, archivedAt,
                            recurrenceEnabled, recurrenceRule, 
                            explicitUrgency, focusRequired, recurrenceStrategy,
                            blockedReason, completionRate, externalDependency,
                            notificationMode, customNotificationMinutes
                        )
                        SELECT 
                            id, title, description,
                            COALESCE(status, 'pending'),
                            dueDate,
                            CAST(COALESCE(durationHours, 1.0) * 60 AS INTEGER),
                            COALESCE(impactLevel, 'Moderate Impact'),
                            COALESCE(goalAlignment, 'Moderate Alignment'),
                            COALESCE(energyLevel, 'Moderate'),
                            COALESCE(controlLevel, 'Office/Colleagues Dependent'),
                            COALESCE(lifeIntentionCategory, 'Career & Work'),
                            taskScore, createdAt, updatedAt, completedAt, archivedAt,
                            COALESCE(recurrenceEnabled, 0),
                            recurrenceRule,
                            explicitUrgency, focusRequired, recurrenceStrategy,
                            blockedReason, completionRate, externalDependency,
                            NULL as notificationMode,
                            NULL as customNotificationMinutes
                        FROM tasks
                        """.trimIndent(),
                    )
                    val rowCount =
                        database.query("SELECT COUNT(*) FROM tasks_new").use { cursor ->
                            if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }

                    logger.i("Migration.1_2", "Data copied", mapOf("rowCount" to rowCount))

                    // Drop old table and rename new one
                    database.execSQL("DROP TABLE tasks")
                    database.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

                    logger.i("Migration.1_2", "Table renamed, migration complete")
                } else {
                    logger.d("Migration.1_2", "Modern schema detected, skipping v0.0.2 conversion")
                }

                logger.i("Migration.1_2", "Migration from 1 to 2 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.1_2",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

/**
 * Migration from version 2 to 3.
 *
 * Adds:
 * - day_journal_entries table for daily journal entries
 * - day_journal_responses table for journal responses
 * - app_settings table for key-value settings
 * - scheduled_notifications table for notification scheduling
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.2_3", "Starting migration from version 2 to 3")

            try {
                // Create day_journal_entries table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS day_journal_entries (
                        id TEXT PRIMARY KEY NOT NULL,
                        entryDate TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                logger.d("Migration.2_3", "Created day_journal_entries table")

                // Create unique index on entryDate
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_day_journal_entries_entryDate 
                    ON day_journal_entries(entryDate)
                    """.trimIndent(),
                )

                // Create day_journal_responses table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS day_journal_responses (
                        id TEXT PRIMARY KEY NOT NULL,
                        entryId TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        dimensionKey TEXT,
                        promptKey TEXT NOT NULL,
                        responseText TEXT NOT NULL,
                        FOREIGN KEY(entryId) REFERENCES day_journal_entries(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                logger.d("Migration.2_3", "Created day_journal_responses table")

                // Create index on entryId for faster lookups
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_day_journal_responses_entryId 
                    ON day_journal_responses(entryId)
                    """.trimIndent(),
                )

                // Create app_settings table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        key TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL,
                        updatedAt TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                logger.d("Migration.2_3", "Created app_settings table")

                // Create scheduled_notifications table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_notifications (
                        id TEXT PRIMARY KEY NOT NULL,
                        taskId TEXT NOT NULL,
                        scheduledAt TEXT NOT NULL,
                        notificationType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        isDelivered INTEGER NOT NULL DEFAULT 0,
                        deliveredAt TEXT,
                        FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                logger.d("Migration.2_3", "Created scheduled_notifications table")

                // Create indices for scheduled_notifications
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_scheduled_notifications_taskId 
                    ON scheduled_notifications(taskId)
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_scheduled_notifications_scheduledAt 
                    ON scheduled_notifications(scheduledAt)
                    """.trimIndent(),
                )

                logger.i("Migration.2_3", "Migration from 2 to 3 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.2_3",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

/**
 * Migration from version 3 to 4.
 * Adds scoring_config table for configurable scoring weights.
 */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.3_4", "Starting migration from version 3 to 4")

            try {
                // Create scoring_config table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scoring_config (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        dimensionWeight REAL NOT NULL DEFAULT 2.0,
                        impactWeight REAL NOT NULL DEFAULT 1.5,
                        alignmentWeight REAL NOT NULL DEFAULT 1.3,
                        energyWeight REAL NOT NULL DEFAULT 1.0,
                        controlWeight REAL NOT NULL DEFAULT 1.2,
                        durationWeight REAL NOT NULL DEFAULT 0.8,
                        impactCritical REAL NOT NULL DEFAULT 1.0,
                        impactHigh REAL NOT NULL DEFAULT 0.85,
                        impactModerate REAL NOT NULL DEFAULT 0.6,
                        impactLow REAL NOT NULL DEFAULT 0.35,
                        impactMinimal REAL NOT NULL DEFAULT 0.15,
                        alignmentPerfect REAL NOT NULL DEFAULT 1.0,
                        alignmentStrong REAL NOT NULL DEFAULT 0.8,
                        alignmentModerate REAL NOT NULL DEFAULT 0.5,
                        alignmentWeak REAL NOT NULL DEFAULT 0.25,
                        alignmentNone REAL NOT NULL DEFAULT 0.1,
                        energyHigh REAL NOT NULL DEFAULT 1.0,
                        energyModerate REAL NOT NULL DEFAULT 0.7,
                        energyLow REAL NOT NULL DEFAULT 0.4,
                        controlFull REAL NOT NULL DEFAULT 1.0,
                        controlMostly REAL NOT NULL DEFAULT 0.85,
                        controlOffice REAL NOT NULL DEFAULT 0.6,
                        controlExternal REAL NOT NULL DEFAULT 0.35,
                        controlNone REAL NOT NULL DEFAULT 0.1,
                        dimensionCareerWork REAL NOT NULL DEFAULT 0.8,
                        dimensionHealthWellness REAL NOT NULL DEFAULT 0.9,
                        dimensionRelationships REAL NOT NULL DEFAULT 0.85,
                        dimensionPersonalGrowth REAL NOT NULL DEFAULT 0.8,
                        dimensionFinancial REAL NOT NULL DEFAULT 0.75,
                        dimensionSpiritual REAL NOT NULL DEFAULT 0.6,
                        dimensionRecreation REAL NOT NULL DEFAULT 0.7,
                        dimensionLearning REAL NOT NULL DEFAULT 0.8,
                        dimensionContribution REAL NOT NULL DEFAULT 0.65,
                        updatedAt TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                logger.d("Migration.3_4", "Created scoring_config table")

                logger.i("Migration.3_4", "Migration from 3 to 4 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.3_4",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

/**
 * Migration from version 4 to 5 - Recurrence Redesign.
 *
 * Adds:
 * - tasks.currentScore (REAL DEFAULT 1.0) - Decaying score for recurring tasks
 * - tasks.lastOccurrenceDate (TEXT) - Date of last completion/skip
 * - tasks.dayBoundaryHour (INTEGER DEFAULT 0) - Day cutoff hour (0-5)
 * - task_occurrences.autoGenerated (INTEGER DEFAULT 0) - System-created missed entries
 */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.4_5", "Starting migration from version 4 to 5 (Recurrence Redesign)")

            try {
                // Add new columns to tasks table
                database.execSQL("ALTER TABLE tasks ADD COLUMN currentScore REAL NOT NULL DEFAULT 1.0")
                logger.d("Migration.4_5", "Added tasks.currentScore column")

                database.execSQL("ALTER TABLE tasks ADD COLUMN lastOccurrenceDate TEXT")
                logger.d("Migration.4_5", "Added tasks.lastOccurrenceDate column")

                database.execSQL("ALTER TABLE tasks ADD COLUMN dayBoundaryHour INTEGER NOT NULL DEFAULT 0")
                logger.d("Migration.4_5", "Added tasks.dayBoundaryHour column")

                // Add autoGenerated column to task_occurrences
                database.execSQL("ALTER TABLE task_occurrences ADD COLUMN autoGenerated INTEGER NOT NULL DEFAULT 0")
                logger.d("Migration.4_5", "Added task_occurrences.autoGenerated column")

                // Initialize lastOccurrenceDate from existing occurrences where available
                database.execSQL(
                    """
                    UPDATE tasks SET lastOccurrenceDate = (
                        SELECT MAX(dueDate) FROM task_occurrences 
                        WHERE task_occurrences.taskId = tasks.id 
                        AND status IN ('completed', 'skipped', 'missed')
                    )
                    WHERE recurrenceEnabled = 1
                    """.trimIndent(),
                )
                logger.d("Migration.4_5", "Initialized lastOccurrenceDate from existing occurrences")

                logger.i("Migration.4_5", "Migration from 4 to 5 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.4_5",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

/**
 * Migration from version 5 to 6.
 *
 * Adds actual completion tracking to task_occurrences:
 * - actualCompletedAt: Actual completion timestamp
 * - actualDurationMinutes: Actual time spent in minutes
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.5_6", "Starting migration from version 5 to 6")

            try {
                // Add new columns to task_occurrences table
                database.execSQL(
                    """
                    ALTER TABLE task_occurrences 
                    ADD COLUMN actualCompletedAt TEXT
                    """.trimIndent(),
                )
                logger.d("Migration.5_6", "Added actualCompletedAt column")

                database.execSQL(
                    """
                    ALTER TABLE task_occurrences 
                    ADD COLUMN actualDurationMinutes INTEGER
                    """.trimIndent(),
                )
                logger.d("Migration.5_6", "Added actualDurationMinutes column")

                logger.i("Migration.5_6", "Migration from 5 to 6 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.5_6",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

/**
 * Migration from version 6 to 7.
 *
 * Adds focus-tracking columns to time_entries:
 * - focusRating: user-selected focus value in [0.00, 1.00]
 * - focusNote: optional user note for why focus level was low/high
 * - focusRatedAt: timestamp when focus value was set
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.6_7", "Starting migration from version 6 to 7")

            try {
                database.execSQL(
                    """
                    ALTER TABLE time_entries
                    ADD COLUMN focusRating REAL
                    """.trimIndent(),
                )
                logger.d("Migration.6_7", "Added time_entries.focusRating column")

                database.execSQL(
                    """
                    ALTER TABLE time_entries
                    ADD COLUMN focusNote TEXT
                    """.trimIndent(),
                )
                logger.d("Migration.6_7", "Added time_entries.focusNote column")

                database.execSQL(
                    """
                    ALTER TABLE time_entries
                    ADD COLUMN focusRatedAt TEXT
                    """.trimIndent(),
                )
                logger.d("Migration.6_7", "Added time_entries.focusRatedAt column")

                logger.i("Migration.6_7", "Migration from 6 to 7 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.6_7",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

/**
 * Migration from version 7 to 8.
 *
 * Adds:
 * - life_dimensions table (with seeded defaults + Unassigned)
 * - user_preferences typed settings table
 * - dimension_id columns + backfill + indexes
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.7_8", "Starting migration from version 7 to 8")

            try {
                createLifeDimensionsTable(database)
                seedDefaultLifeDimensions(database)
                createUserPreferencesTable(database)
                addDimensionIdColumns(database)
                backfillDimensionIds(database)
                createDimensionIdIndexes(database)

                logger.i("Migration.7_8", "Migration from 7 to 8 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.7_8",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

private fun createLifeDimensionsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS life_dimensions (
            id TEXT PRIMARY KEY NOT NULL,
            key TEXT NOT NULL,
            label TEXT NOT NULL,
            description TEXT,
            color TEXT NOT NULL,
            icon TEXT,
            sortOrder INTEGER NOT NULL,
            isActive INTEGER NOT NULL DEFAULT 1,
            createdAt TEXT NOT NULL,
            updatedAt TEXT NOT NULL
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_life_dimensions_key
        ON life_dimensions(key)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_life_dimensions_sortOrder
        ON life_dimensions(sortOrder)
        """.trimIndent(),
    )
}

private fun seedDefaultLifeDimensions(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        INSERT OR IGNORE INTO life_dimensions
        (id, key, label, description, color, icon, sortOrder, isActive, createdAt, updatedAt)
        VALUES
        ('dim_career_work', 'career_work', 'Career & Work', 'Professional development and work-related tasks', '#3F51B5', 'work', 10, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_health_wellness', 'health_wellness', 'Health & Wellness', 'Physical and mental health activities', '#4CAF50', 'favorite', 20, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_relationships', 'relationships', 'Relationships', 'Family, friends, and social connections', '#E91E63', 'people', 30, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_personal_growth', 'personal_growth', 'Personal Growth', 'Self-improvement and personal development', '#009688', 'trending_up', 40, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_financial', 'financial', 'Financial', 'Financial planning and management', '#FF9800', 'account_balance_wallet', 50, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_spiritual', 'spiritual', 'Spiritual', 'Spiritual practices and mindfulness', '#9C27B0', 'self_improvement', 60, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_recreation', 'recreation', 'Recreation', 'Hobbies, leisure, and entertainment', '#00BCD4', 'sports_esports', 70, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_learning', 'learning', 'Learning', 'Education and skill acquisition', '#673AB7', 'menu_book', 80, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_contribution', 'contribution', 'Contribution', 'Giving back, volunteering, community', '#8BC34A', 'volunteer_activism', 90, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
        ('dim_unassigned', 'unassigned', 'Unassigned', 'System fallback for uncategorized/imported records', '#9E9E9E', 'help_outline', 9999, 1, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now'))
        """.trimIndent(),
    )
}

private fun createUserPreferencesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS user_preferences (
            key TEXT PRIMARY KEY NOT NULL,
            valueType TEXT NOT NULL,
            stringValue TEXT,
            intValue INTEGER,
            doubleValue REAL,
            boolValue INTEGER,
            updatedAt TEXT NOT NULL
        )
        """.trimIndent(),
    )
}

private fun addDimensionIdColumns(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN dimension_id TEXT REFERENCES life_dimensions(id)")
    database.execSQL("ALTER TABLE time_entries ADD COLUMN dimension_id TEXT REFERENCES life_dimensions(id)")
    database.execSQL("ALTER TABLE notes ADD COLUMN dimension_id TEXT REFERENCES life_dimensions(id)")
    database.execSQL("ALTER TABLE day_journal_responses ADD COLUMN dimension_id TEXT REFERENCES life_dimensions(id)")
}

private fun backfillDimensionIds(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        UPDATE tasks
        SET dimension_id = CASE lifeIntentionCategory
            WHEN 'Career & Work' THEN 'dim_career_work'
            WHEN 'Health & Wellness' THEN 'dim_health_wellness'
            WHEN 'Relationships' THEN 'dim_relationships'
            WHEN 'Personal Growth' THEN 'dim_personal_growth'
            WHEN 'Financial' THEN 'dim_financial'
            WHEN 'Spiritual' THEN 'dim_spiritual'
            WHEN 'Recreation' THEN 'dim_recreation'
            WHEN 'Learning' THEN 'dim_learning'
            WHEN 'Contribution' THEN 'dim_contribution'
            ELSE 'dim_unassigned'
        END
        WHERE dimension_id IS NULL
        """.trimIndent(),
    )
    database.execSQL(
        """
        UPDATE time_entries
        SET dimension_id = CASE lifeIntentionCategory
            WHEN 'Career & Work' THEN 'dim_career_work'
            WHEN 'Health & Wellness' THEN 'dim_health_wellness'
            WHEN 'Relationships' THEN 'dim_relationships'
            WHEN 'Personal Growth' THEN 'dim_personal_growth'
            WHEN 'Financial' THEN 'dim_financial'
            WHEN 'Spiritual' THEN 'dim_spiritual'
            WHEN 'Recreation' THEN 'dim_recreation'
            WHEN 'Learning' THEN 'dim_learning'
            WHEN 'Contribution' THEN 'dim_contribution'
            ELSE 'dim_unassigned'
        END
        WHERE dimension_id IS NULL
        """.trimIndent(),
    )
    database.execSQL(
        """
        UPDATE notes
        SET dimension_id = CASE lifeIntentionCategory
            WHEN 'Career & Work' THEN 'dim_career_work'
            WHEN 'Health & Wellness' THEN 'dim_health_wellness'
            WHEN 'Relationships' THEN 'dim_relationships'
            WHEN 'Personal Growth' THEN 'dim_personal_growth'
            WHEN 'Financial' THEN 'dim_financial'
            WHEN 'Spiritual' THEN 'dim_spiritual'
            WHEN 'Recreation' THEN 'dim_recreation'
            WHEN 'Learning' THEN 'dim_learning'
            WHEN 'Contribution' THEN 'dim_contribution'
            ELSE 'dim_unassigned'
        END
        WHERE dimension_id IS NULL
        """.trimIndent(),
    )
    database.execSQL(
        """
        UPDATE day_journal_responses
        SET dimension_id = CASE dimensionKey
            WHEN 'CAREER_WORK' THEN 'dim_career_work'
            WHEN 'HEALTH_WELLNESS' THEN 'dim_health_wellness'
            WHEN 'RELATIONSHIPS' THEN 'dim_relationships'
            WHEN 'PERSONAL_GROWTH' THEN 'dim_personal_growth'
            WHEN 'FINANCIAL' THEN 'dim_financial'
            WHEN 'SPIRITUAL' THEN 'dim_spiritual'
            WHEN 'RECREATION' THEN 'dim_recreation'
            WHEN 'LEARNING' THEN 'dim_learning'
            WHEN 'CONTRIBUTION' THEN 'dim_contribution'
            WHEN 'career' THEN 'dim_career_work'
            WHEN 'health' THEN 'dim_health_wellness'
            WHEN 'relationships' THEN 'dim_relationships'
            WHEN 'growth' THEN 'dim_personal_growth'
            WHEN 'financial' THEN 'dim_financial'
            WHEN 'spiritual' THEN 'dim_spiritual'
            WHEN 'recreation' THEN 'dim_recreation'
            WHEN 'learning' THEN 'dim_learning'
            WHEN 'contribution' THEN 'dim_contribution'
            ELSE dimension_id
        END
        WHERE dimension_id IS NULL AND dimensionKey IS NOT NULL
        """.trimIndent(),
    )
}

private fun createDimensionIdIndexes(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_dimension_id ON tasks(dimension_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_entries_dimension_id ON time_entries(dimension_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_notes_dimension_id ON notes(dimension_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_day_journal_responses_dimension_id ON day_journal_responses(dimension_id)")
}

/**
 * Migration from version 16 to 17 — Habit recurrence system overhaul.
 *
 * Changes:
 * - task_occurrences: drops autoGenerated column (absence = implicitly missed)
 * - tasks: keeps deprecated recurrenceStrategy and dayBoundaryHour columns for Room compatibility
 * - tasks: converts all recurrenceRule values from CONFIG:/RRULE: to "num/den" format
 * - task_reschedules: retained for schema compatibility until the entity is removed
 *
 * SQLite does not support DROP COLUMN before 3.35.0, so we rebuild affected tables.
 */
val MIGRATION_16_17 =
    object : Migration(16, 17) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.16_17", "Starting habit recurrence system migration")

            try {
                migrateTaskOccurrences(database)
                migrateTasksRecurrence(database)
                retainTaskReschedules(database)
                logger.i("Migration.16_17", "Migration 16→17 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("Migration.16_17", "Migration failed", e, mapOf("error" to (e.message ?: "unknown")))
                throw e
            }
        }

        private fun migrateTaskOccurrences(database: SupportSQLiteDatabase) {
            logger.d("Migration.16_17", "Rebuilding task_occurrences without autoGenerated")

            database.execSQL("""
                CREATE TABLE task_occurrences_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    taskId TEXT NOT NULL,
                    dueDate TEXT NOT NULL,
                    completedAt TEXT,
                    actualCompletedAt TEXT,
                    actualDurationMinutes INTEGER,
                    status TEXT NOT NULL,
                    statusReason TEXT,
                    createdAt TEXT NOT NULL,
                    completionRate REAL,
                    note TEXT,
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
                )
            """.trimIndent())

            database.execSQL("""
                INSERT INTO task_occurrences_new
                    (id, taskId, dueDate, completedAt, actualCompletedAt, actualDurationMinutes,
                     status, statusReason, createdAt, completionRate, note)
                SELECT
                    id, taskId, dueDate, completedAt, actualCompletedAt, actualDurationMinutes,
                    status, statusReason, createdAt, completionRate, note
                FROM task_occurrences
            """.trimIndent())

            database.execSQL("DROP TABLE task_occurrences")
            database.execSQL("ALTER TABLE task_occurrences_new RENAME TO task_occurrences")

            database.execSQL("CREATE INDEX IF NOT EXISTS index_task_occurrences_taskId ON task_occurrences(taskId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_task_occurrences_dueDate ON task_occurrences(dueDate)")

            logger.d("Migration.16_17", "task_occurrences rebuilt without autoGenerated")
        }

        private fun migrateTasksRecurrence(database: SupportSQLiteDatabase) {
            logger.d("Migration.16_17", "Rebuilding tasks table with canonical recurrenceRule and deprecated compat columns")

            // First, convert recurrenceRule values in-place using a temp column
            // We'll use a cursor-based approach to convert each row
            convertRecurrenceRules(database)

            // Now rebuild the tasks table while retaining deprecated compatibility columns.
            database.execSQL("""
                CREATE TABLE tasks_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT,
                    status TEXT NOT NULL DEFAULT 'pending',
                    dueDate TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    completedAt TEXT,
                    archivedAt TEXT,
                    recurrenceEnabled INTEGER NOT NULL DEFAULT 0,
                    recurrenceRule TEXT,
                    recurrenceStrategy TEXT,
                    durationMinutes INTEGER NOT NULL DEFAULT 60,
                    impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact',
                    goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
                    energyLevel TEXT NOT NULL DEFAULT 'Moderate',
                    controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
                    lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work',
                    dimension_id TEXT,
                    day_key TEXT,
                    explicitUrgency REAL,
                    focusRequired REAL,
                    blockedReason TEXT,
                    completionRate REAL,
                    externalDependency TEXT,
                    notificationMode TEXT,
                    customNotificationMinutes INTEGER,
                    taskScore REAL,
                    currentScore REAL NOT NULL DEFAULT 1.0,
                    lastOccurrenceDate TEXT,
                    dayBoundaryHour INTEGER NOT NULL DEFAULT 0,
                    import_source TEXT,
                    import_id TEXT,
                    imported_at TEXT,
                    import_batch_id TEXT,
                    FOREIGN KEY(dimension_id) REFERENCES life_dimensions(id),
                    FOREIGN KEY(import_batch_id) REFERENCES import_batches(id)
                )
            """.trimIndent())

            database.execSQL("""
                INSERT INTO tasks_new
                    (id, title, description, status, dueDate, createdAt, updatedAt, completedAt, archivedAt,
                     recurrenceEnabled, recurrenceRule, recurrenceStrategy, durationMinutes, impactLevel, goalAlignment,
                     energyLevel, controlLevel, lifeIntentionCategory, dimension_id, day_key,
                     explicitUrgency, focusRequired, blockedReason, completionRate, externalDependency,
                     notificationMode, customNotificationMinutes, taskScore, currentScore,
                     lastOccurrenceDate, dayBoundaryHour, import_source, import_id, imported_at, import_batch_id)
                SELECT
                    id, title, description, status, dueDate, createdAt, updatedAt, completedAt, archivedAt,
                    recurrenceEnabled, recurrenceRule, recurrenceStrategy, durationMinutes, impactLevel, goalAlignment,
                    energyLevel, controlLevel, lifeIntentionCategory, dimension_id, day_key,
                    explicitUrgency, focusRequired, blockedReason, completionRate, externalDependency,
                    notificationMode, customNotificationMinutes, taskScore, currentScore,
                    lastOccurrenceDate, dayBoundaryHour, import_source, import_id, imported_at, import_batch_id
                FROM tasks
            """.trimIndent())

            database.execSQL("DROP TABLE tasks")
            database.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

            // Recreate indexes (most were lost during table rename)
            database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_dimension_id ON tasks(dimension_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_day_key ON tasks(day_key)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_import_batch_id ON tasks(import_batch_id)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_import_source_import_id ON tasks(import_source, import_id)")

            logger.d("Migration.16_17", "tasks rebuilt with deprecated compat columns retained")
        }

        private fun convertRecurrenceRules(database: SupportSQLiteDatabase) {
            logger.d("Migration.16_17", "Converting recurrenceRule values to num/den format")

            // Helper: parse CONFIG: format
            /**
             * Performs the parse config.
             */
            fun parseConfig(config: String): String {
                val parts = config.split("|").associate { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) kv[0].trim() to kv[1].trim() else kv[0].trim() to ""
                }
                val type = parts["type"] ?: return "1/1"
                return when (type) {
                    "DAILY" -> "1/1"
                    "WEEKDAYS_ONLY" -> "5/7"
                    "SPECIFIC_WEEKDAYS" -> {
                        val days = parts["weekdays"]?.split(",")
                            ?.mapNotNull { it.toIntOrNull() }?.size ?: 1
                        "${days.coerceAtLeast(1)}/7"
                    }
                    "MONTHLY_DATES" -> {
                        val dates = parts["monthlyDates"]?.split(",")
                            ?.mapNotNull { it.toIntOrNull() }?.size ?: 1
                        "${dates.coerceAtLeast(1)}/30"
                    }
                    "INTERVAL" -> {
                        val interval = parts["interval"]?.toIntOrNull() ?: 1
                        "1/${interval.coerceAtLeast(1)}"
                    }
                    "FREQUENCY" -> {
                        val freq = parts["freq"]?.split("/")
                        val num = freq?.getOrNull(0)?.toIntOrNull() ?: 1
                        val den = freq?.getOrNull(1)?.toIntOrNull() ?: 7
                        "${num.coerceAtLeast(1)}/${den.coerceAtLeast(1)}"
                    }
                    "YEARLY" -> "1/365"
                    else -> "1/1"
                }
            }

            // Helper: parse RRULE format
            /**
             * Performs the parse rrule.
             */
            fun parseRRule(rrule: String): String {
                val parts = rrule.uppercase().split(";").associate { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
                }
                val freq = parts["FREQ"] ?: "DAILY"
                val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
                val byDay = parts["BYDAY"]
                val byMonthDay = parts["BYMONTHDAY"]

                return when {
                    byMonthDay != null -> {
                        val dates = byMonthDay.split(",").mapNotNull { it.toIntOrNull() }.size
                        "${dates.coerceAtLeast(1)}/30"
                    }
                    byDay != null -> {
                        val days = byDay.split(",").mapNotNull { day ->
                            when (day.trim()) {
                                "MO" -> 1; "TU" -> 2; "WE" -> 3; "TH" -> 4
                                "FR" -> 5; "SA" -> 6; "SU" -> 7; else -> null
                            }
                        }.toSet()
                        if (days == setOf(1, 2, 3, 4, 5)) "5/7"
                        else "${days.size.coerceAtLeast(1)}/7"
                    }
                    freq == "YEARLY" -> "1/365"
                    freq == "MONTHLY" -> "1/30"
                    freq == "WEEKLY" -> "1/${7 * interval}"
                    freq == "DAILY" -> "1/${interval.coerceAtLeast(1)}"
                    else -> "1/1"
                }
            }

            // Process each row with a recurrence rule
            val cursor = database.query(
                "SELECT id, recurrenceRule FROM tasks WHERE recurrenceEnabled = 1 AND recurrenceRule IS NOT NULL",
            )
            var converted = 0
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndex("id"))
                val rule = cursor.getString(cursor.getColumnIndex("recurrenceRule"))
                val newRule = when {
                    rule.startsWith("CONFIG:") -> parseConfig(rule.removePrefix("CONFIG:"))
                    rule.startsWith("FREQ=") -> parseRRule(rule)
                    else -> rule // Already in num/den format or unknown
                }
                if (newRule != rule) {
                    database.execSQL("UPDATE tasks SET recurrenceRule = ? WHERE id = ?", arrayOf(newRule, id))
                    converted++
                }
            }
            cursor.close()
            logger.i("Migration.16_17", "Converted $converted recurrenceRule values")
        }

        @Suppress("UnusedParameter")
        private fun retainTaskReschedules(database: SupportSQLiteDatabase) {
            logger.d("Migration.16_17", "Retaining task_reschedules table for schema compatibility")
        }
    }

/**
 * Schema v18 — self-governance score roll-up foundation.
 *
 * Adds three metric tables (SQL only — no data backfill here):
 *   habit_metrics      (L1: sparse due-day rows per habit)
 *   dimension_metrics  (L2: dense per-dimension rows)
 *   day_metrics        (L3: dense per-day rows)
 *
 * Rule conversion (num/den → CONFIG) and backfill happen in the
 * post-open backfill service (Inc 3), not inside this migration.
 */
val MIGRATION_17_18 =
    object : Migration(17, 18) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.17_18", "Creating score roll-up metric tables")

            try {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS habit_metrics (
                        habitId TEXT NOT NULL,
                        dayKey TEXT NOT NULL,
                        score REAL NOT NULL,
                        runningAvg REAL NOT NULL,
                        progress REAL NOT NULL,
                        streakPos INTEGER NOT NULL,
                        streakNet INTEGER NOT NULL,
                        posContinue INTEGER NOT NULL,
                        PRIMARY KEY (habitId, dayKey)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_metrics_habitId ON habit_metrics(habitId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_metrics_dayKey ON habit_metrics(dayKey)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dimension_metrics (
                        dimensionId TEXT NOT NULL,
                        dayKey TEXT NOT NULL,
                        score REAL NOT NULL,
                        runningAvg REAL NOT NULL,
                        progress REAL NOT NULL,
                        streakPos INTEGER NOT NULL,
                        streakNet INTEGER NOT NULL,
                        posContinue INTEGER NOT NULL,
                        PRIMARY KEY (dimensionId, dayKey)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_dimension_metrics_dimensionId ON dimension_metrics(dimensionId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_dimension_metrics_dayKey ON dimension_metrics(dayKey)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS day_metrics (
                        dayKey TEXT NOT NULL PRIMARY KEY,
                        dayScore REAL NOT NULL,
                        runningAvg REAL NOT NULL,
                        progress REAL NOT NULL,
                        streakPos INTEGER NOT NULL,
                        streakNet INTEGER NOT NULL,
                        posContinue INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )

                // Post-migration verification: confirm all three tables exist
                val tables = mutableListOf<String>()
                database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('habit_metrics', 'dimension_metrics', 'day_metrics') ORDER BY name").use { cursor ->
                    while (cursor.moveToNext()) {
                        tables += cursor.getString(0)
                    }
                }
                val expected = listOf("day_metrics", "dimension_metrics", "habit_metrics")
                if (tables != expected) {
                    throw IllegalStateException(
                        "Migration 17→18 table verification failed: expected $expected, found $tables",
                    )
                }

                logger.i(
                    "Migration.17_18",
                    "Metric tables created and verified",
                    mapOf(
                        "tables" to tables,
                        "expected" to expected,
                        "verified" to true,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.17_18",
                    "Migration failed",
                    e,
                    mapOf("error" to (e.message ?: "unknown")),
                )
                throw e
            }
        }
    }

/**
 * 18 → 19: drop the decay `currentScore` column from tasks.
 * The score roll-up (Inc 3/4) replaced decay; consumers read L1 metrics
 * directly (HabitMetricRepository). SQLite < 3.35 lacks DROP COLUMN, so
 * rebuild the tasks table without the column (16→17 pattern).
 */
val MIGRATION_18_19 =
    object : Migration(18, 19) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.18_19", "Dropping decay currentScore column from tasks")

            try {
                database.execSQL(
                    """
                    CREATE TABLE tasks_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        description TEXT,
                        status TEXT NOT NULL DEFAULT 'pending',
                        dueDate TEXT,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        completedAt TEXT,
                        archivedAt TEXT,
                        recurrenceEnabled INTEGER NOT NULL DEFAULT 0,
                        recurrenceRule TEXT,
                        recurrenceStrategy TEXT,
                        durationMinutes INTEGER NOT NULL DEFAULT 60,
                        impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact',
                        goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
                        energyLevel TEXT NOT NULL DEFAULT 'Moderate',
                        controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
                        lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work',
                        dimension_id TEXT,
                        day_key TEXT,
                        explicitUrgency REAL,
                        focusRequired REAL,
                        blockedReason TEXT,
                        completionRate REAL,
                        externalDependency TEXT,
                        notificationMode TEXT,
                        customNotificationMinutes INTEGER,
                        taskScore REAL,
                        lastOccurrenceDate TEXT,
                        dayBoundaryHour INTEGER NOT NULL DEFAULT 0,
                        import_source TEXT,
                        import_id TEXT,
                        imported_at TEXT,
                        import_batch_id TEXT,
                        FOREIGN KEY(dimension_id) REFERENCES life_dimensions(id),
                        FOREIGN KEY(import_batch_id) REFERENCES import_batches(id)
                    )
                    """.trimIndent(),
                )

                database.execSQL(
                    """
                    INSERT INTO tasks_new
                        (id, title, description, status, dueDate, createdAt, updatedAt, completedAt, archivedAt,
                         recurrenceEnabled, recurrenceRule, recurrenceStrategy, durationMinutes, impactLevel, goalAlignment,
                         energyLevel, controlLevel, lifeIntentionCategory, dimension_id, day_key,
                         explicitUrgency, focusRequired, blockedReason, completionRate, externalDependency,
                         notificationMode, customNotificationMinutes, taskScore,
                         lastOccurrenceDate, dayBoundaryHour, import_source, import_id, imported_at, import_batch_id)
                    SELECT
                        id, title, description, status, dueDate, createdAt, updatedAt, completedAt, archivedAt,
                        recurrenceEnabled, recurrenceRule, recurrenceStrategy, durationMinutes, impactLevel, goalAlignment,
                        energyLevel, controlLevel, lifeIntentionCategory, dimension_id, day_key,
                        explicitUrgency, focusRequired, blockedReason, completionRate, externalDependency,
                        notificationMode, customNotificationMinutes, taskScore,
                        lastOccurrenceDate, dayBoundaryHour, import_source, import_id, imported_at, import_batch_id
                    FROM tasks
                    """.trimIndent(),
                )

                database.execSQL("DROP TABLE tasks")
                database.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

                // Recreate indexes lost during the rename
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_dimension_id ON tasks(dimension_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_day_key ON tasks(day_key)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_import_batch_id ON tasks(import_batch_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_import_source_import_id ON tasks(import_source, import_id)")

                // Post-migration verification: currentScore must be gone
                val columns = mutableListOf<String>()
                database.query("PRAGMA table_info(tasks)").use { cursor ->
                    while (cursor.moveToNext()) {
                        columns += cursor.getString(1)
                    }
                }
                if ("currentScore" in columns) {
                    throw IllegalStateException("Migration 18→19 failed: currentScore column still present in tasks")
                }

                logger.i(
                    "Migration.18_19",
                    "tasks rebuilt without currentScore",
                    mapOf(
                        "columnCount" to columns.size,
                        "currentScoreDropped" to true,
                        "verified" to true,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.18_19",
                    "Migration failed",
                    e,
                    mapOf("error" to (e.message ?: "unknown")),
                )
                throw e
            }
        }
    }

/**
 * 19 → 20: add user-editable `weight` to life_dimensions (C2, Inc 4 Part C).
 * SQLite ADD COLUMN with a NOT NULL DEFAULT keeps existing rows at 1.0
 * (equal weights — the pre-C2 behavior). L3 day scores become a weighted
 * average of dimension scores once weights are set; changing a weight
 * triggers an L3-only recalc (self-gov `dim_weight_change` path).
 */
val MIGRATION_19_20 =
    object : Migration(19, 20) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.19_20", "Adding weight column to life_dimensions")

            try {
                database.execSQL("ALTER TABLE life_dimensions ADD COLUMN weight REAL NOT NULL DEFAULT 1.0")

                // Post-migration verification: weight must exist
                val columns = mutableListOf<String>()
                database.query("PRAGMA table_info(life_dimensions)").use { cursor ->
                    while (cursor.moveToNext()) {
                        columns += cursor.getString(1)
                    }
                }
                if ("weight" !in columns) {
                    throw IllegalStateException("Migration 19→20 failed: weight column missing in life_dimensions")
                }

                logger.i(
                    "Migration.19_20",
                    "life_dimensions.weight added",
                    mapOf(
                        "columnCount" to columns.size,
                        "weightAdded" to true,
                        "verified" to true,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.19_20",
                    "Migration failed",
                    e,
                    mapOf("error" to (e.message ?: "unknown")),
                )
                throw e
            }
        }
    }

/**
 * Migration 20 → 21: guard task_occurrences against duplicate rows.
 *
 * Background: the auto-miss path (RecurrenceManager.createMissedOccurrence)
 * previously inserted a fresh row unconditionally, while the user path
 * (toggleOccurrence) checks-then-updates. A habit marked by the user on a day
 * could end up with TWO rows for that (taskId, dueDate) — the user's row and
 * an auto "Auto-detected missed" twin — and the LIMIT-1 read could surface
 * the auto row, making the previous day appear "not done".
 *
 * Fix (mirrors the self-governance ledger rule):
 *   1. Dedupe: for each (taskId, day) with multiple rows, keep the user row
 *      (non-auto note) and delete the auto-missed twins. If a day somehow has
 *      only auto rows, keep the newest and drop older duplicates.
 *   2. Add a unique index on (taskId, date(dueDate)) so duplicates are
 *      impossible at the DB level from now on.
 *   3. Post-migration verification: no remaining duplicates, index exists.
 */
val MIGRATION_20_21 =
    object : Migration(20, 21) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.20_21", "Deduplicating task_occurrences and adding unique (taskId, day) index")

            try {
                // ── 0. Count duplicates before (for the log) ──
                val dupBefore = database.query(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT taskId, date(dueDate) AS d, COUNT(*) AS c
                        FROM task_occurrences
                        GROUP BY taskId, date(dueDate)
                        HAVING c > 1
                    )
                    """.trimIndent(),
                ).use { cursor ->
                    var count = 0L
                    if (cursor.moveToFirst()) count = cursor.getLong(0)
                    count
                }

                // ── 1. Dedupe: delete auto-missed twins where a user row exists ──
                database.execSQL(
                    """
                    DELETE FROM task_occurrences
                    WHERE id IN (
                        SELECT o.id
                        FROM task_occurrences o
                        JOIN task_occurrences u
                          ON u.taskId = o.taskId
                         AND date(u.dueDate) = date(o.dueDate)
                         AND u.id != o.id
                        WHERE o.note = 'Auto-detected missed'
                          AND (u.note IS NULL OR u.note != 'Auto-detected missed')
                    )
                    """.trimIndent(),
                )

                // ── 2. Any remaining duplicates per (taskId, day): keep the
                // newest row (most recent write wins). Step 1 already removed
                // auto twins where a user row exists, so groups left here are
                // all-auto or all-user (e.g. different dueDate string formats
                // from different write paths) — newest is the safe survivor.
                database.execSQL(
                    """
                    DELETE FROM task_occurrences
                    WHERE id IN (
                        SELECT o.id
                        FROM task_occurrences o
                        JOIN task_occurrences u
                          ON u.taskId = o.taskId
                         AND date(u.dueDate) = date(o.dueDate)
                         AND u.id != o.id
                        WHERE o.createdAt < u.createdAt
                    )
                    """.trimIndent(),
                )

                // ── 3. Unique index on (taskId, dueDate) ──
                // Room requires this index to be declared in the entity and
                // created with Room's generated name (index_<table>_<col>_<col>)
                // or schema validation fails after migration. Both the user
                // toggle path and the auto-miss path write dueDate as
                // yyyy-MM-ddTHH:mm:ss (atStartOfDay), so plain-column
                // uniqueness catches the duplicate pair.
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_task_occurrences_taskId_dueDate " +
                        "ON task_occurrences(taskId, dueDate)",
                )

                // ── 4. Post-migration verification ──
                val dupCount = database.query(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT taskId, date(dueDate) AS d, COUNT(*) AS c
                        FROM task_occurrences
                        GROUP BY taskId, date(dueDate)
                        HAVING c > 1
                    )
                    """.trimIndent(),
                ).use { cursor ->
                    var count = -1L
                    if (cursor.moveToFirst()) count = cursor.getLong(0)
                    count
                }
                if (dupCount != 0L) {
                    throw IllegalStateException("Migration 20→21 failed: $dupCount duplicate rows remain")
                }
                val indexExists = database.query("PRAGMA index_list(task_occurrences)").use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "index_task_occurrences_taskId_dueDate") found = true
                    }
                    found
                }
                if (!indexExists) {
                    throw IllegalStateException("Migration 20→21 failed: unique index not created")
                }

                logger.i(
                    "Migration.20_21",
                    "task_occurrences deduped and unique index added",
                    mapOf(
                        "duplicatesBefore" to dupBefore,
                        "remainingDuplicates" to dupCount,
                        "uniqueIndexCreated" to indexExists,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "Migration.20_21",
                    "Migration failed",
                    e,
                    mapOf("error" to (e.message ?: "unknown")),
                )
                throw e
            }
        }
    }
