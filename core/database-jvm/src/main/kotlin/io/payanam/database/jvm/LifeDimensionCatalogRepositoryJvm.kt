//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.LifeDimensionEntity
import io.payanam.database.mapper.toConfiguredLifeDimension
import io.payanam.database.mapper.toLifeDimensionEntity
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JDBC implementation of [LifeDimensionCatalogRepository] over the encrypted database — the life-intention
 * dimensions' user-customisable label, colour, icon, active flag and scoring weight.
 *
 * Statements are copied from `LifeDimensionDao`. Every update writes `updatedAt` as well as its own column:
 * a port that set only the field would leave the row's modification time claiming nothing had changed, and
 * the desktop would be the platform that produced timestamps Android never writes.
 *
 * Each update is a targeted `UPDATE`, not a read-copy-write: two of these fields (label, weight) are edited
 * from a text field and a slider, and rewriting the whole row from a stale read would revert whichever of
 * the other four the user changed in between.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class LifeDimensionCatalogRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : LifeDimensionCatalogRepository {
    private val logger = AppLogger.current

    /** Emits all dimensions ordered by display position. */
    override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> {
        logger.d(
            "LifeDimensionCatalogRepositoryJvm.observeAllDimensions",
            "Subscribing to life dimension catalog",
        )
        return JdbcFlows
            .observe(pollIntervalMs) { readAllDimensions() }
            .map { entities -> entities.map { it.toConfiguredLifeDimension() } }
    }

    /** Updates the user-visible [label] of [dimensionId]. */
    override suspend fun updateDimensionLabel(
        dimensionId: String,
        label: String,
    ) {
        update(UPDATE_LABEL_SQL, arrayOf(label, now(), dimensionId), "updateDimensionLabel", dimensionId)
    }

    /** Updates the [colorHex] used to render [dimensionId]. */
    override suspend fun updateDimensionColor(
        dimensionId: String,
        colorHex: String,
    ) {
        update(UPDATE_COLOR_SQL, arrayOf(colorHex, now(), dimensionId), "updateDimensionColor", dimensionId)
    }

    /** Updates the [iconKey] identifier for [dimensionId]. */
    override suspend fun updateDimensionIcon(
        dimensionId: String,
        iconKey: String,
    ) {
        update(UPDATE_ICON_SQL, arrayOf(iconKey, now(), dimensionId), "updateDimensionIcon", dimensionId)
    }

    /** Sets whether [dimensionId] counts toward scoring. */
    override suspend fun updateDimensionActiveState(
        dimensionId: String,
        isActive: Boolean,
    ) {
        update(
            UPDATE_ACTIVE_SQL,
            arrayOf(if (isActive) "1" else "0", now(), dimensionId),
            "updateDimensionActiveState",
            dimensionId,
        )
    }

    /** Updates the scoring [weight] of [dimensionId]. */
    override suspend fun updateDimensionWeight(
        dimensionId: String,
        weight: Double,
    ) {
        update(UPDATE_WEIGHT_SQL, arrayOf(weight.toString(), now(), dimensionId), "updateDimensionWeight", dimensionId)
    }

    /**
     * Replaces the whole catalog in ONE transaction, and returns the row count written.
     *
     * The transaction is the whole point: a `DELETE` followed by a loop of `INSERT`s leaves the table
     * empty in between, and this file's own `observeAllDimensions` polls — so a subscriber would read an
     * empty catalog, and scoring would weigh the day by nothing. `autoCommit = false` plus an explicit
     * commit/rollback makes the delete and the inserts one unit of work, so a failure leaves the previous
     * catalog exactly as it was.
     */
    override suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int {
        val timestamp = now()
        val entities = dimensions.map { it.toLifeDimensionEntity(timestamp) }
        val connection = session.requireConnection()
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(DELETE_ALL_SQL)
                if (entities.isNotEmpty()) {
                    connection.prepareStatement(INSERT_ALL_SQL).use { prepared ->
                        entities.forEach { entity ->
                            bindInsert(prepared, entity)
                            prepared.addBatch()
                        }
                        prepared.executeBatch()
                    }
                }
            }
            connection.commit()
        } catch (error: SQLException) {
            // Narrow on purpose: every statement in this block is JDBC, so SQLException is the whole
            // surface. A broad catch here would also swallow a cancellation and commit a half-written
            // catalog on the way out.
            // Rolled back explicitly, because autoCommit is still false: committing here would persist a
            // half-written catalog, which is the exact state this method exists to prevent.
            runCatching { connection.rollback() }
                .onFailure { rollbackError ->
                    logger.e(
                        "LifeDimensionCatalogRepositoryJvm.replaceAllDimensions",
                        "Rollback failed after a failed catalog replace",
                        rollbackError,
                        mapOf("rollbackError" to (rollbackError.message ?: "Unknown error")),
                    )
                }
            logger.e(
                "LifeDimensionCatalogRepositoryJvm.replaceAllDimensions",
                "Replacing the life dimension catalog failed; the previous catalog is unchanged",
                error,
                mapOf("error" to (error.message ?: "Unknown error")),
            )
            throw error
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }
        }
        logger.i(
            "LifeDimensionCatalogRepositoryJvm.replaceAllDimensions",
            "Replaced the life dimension catalog",
            mapOf("requested" to entities.size, "written" to entities.size),
        )
        return entities.size
    }

    private fun bindInsert(
        prepared: java.sql.PreparedStatement,
        entity: LifeDimensionEntity,
    ) {
        prepared.setString(COLUMN_ID, entity.id)
        prepared.setString(COLUMN_KEY, entity.key)
        prepared.setString(COLUMN_LABEL, entity.label)
        prepared.setString(COLUMN_DESCRIPTION, entity.description)
        prepared.setString(COLUMN_COLOR, entity.color)
        prepared.setString(COLUMN_ICON, entity.icon)
        prepared.setInt(COLUMN_SORT_ORDER, entity.sortOrder)
        prepared.setInt(COLUMN_IS_ACTIVE, entity.isActive)
        prepared.setDouble(COLUMN_WEIGHT, entity.weight)
        prepared.setString(COLUMN_CREATED_AT, entity.createdAt)
        prepared.setString(COLUMN_UPDATED_AT, entity.updatedAt)
    }

    private suspend fun update(
        sql: String,
        parameters: Array<String?>,
        operation: String,
        dimensionId: String,
    ) {
        JdbcFlows.onIo {
            JdbcQueries.write(session, sql, parameters)
            logger.i(
                "LifeDimensionCatalogRepositoryJvm.$operation",
                "Updated life dimension",
                mapOf("dimensionId" to dimensionId),
            )
        }
    }

    private fun readAllDimensions(): List<LifeDimensionEntity> =
        JdbcQueries.read(session, ALL_DIMENSIONS_SQL, emptyArray<String?>()) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        LifeDimensionEntity(
                            id = resultSet.getString("id"),
                            key = resultSet.getString("key"),
                            label = resultSet.getString("label"),
                            description = resultSet.getString("description"),
                            color = resultSet.getString("color"),
                            icon = resultSet.getString("icon"),
                            sortOrder = resultSet.getInt("sortOrder"),
                            isActive = resultSet.getInt("isActive"),
                            weight = resultSet.getDouble("weight"),
                            createdAt = resultSet.getString("createdAt"),
                            updatedAt = resultSet.getString("updatedAt"),
                        ),
                    )
                }
            }
        }

    private fun now(): String = LocalDateTime.now().format(ISO_FORMATTER)

    private companion object {
        val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** Verbatim from `LifeDimensionDao.observeAllDimensions`. */
        const val ALL_DIMENSIONS_SQL = "SELECT * FROM life_dimensions ORDER BY sortOrder ASC, id ASC"

        /** Verbatim from `LifeDimensionDao.deleteAll`. */
        const val DELETE_ALL_SQL = "DELETE FROM life_dimensions"

        /**
         * The insert, column for column as `LifeDimensionEntity` and as the raw SQL the Android
         * `persistNewDatabaseDimensionSetup` used to issue. Column ORDER matters: the bindings in
         * `bindInsert` are positional, so a reordered column here would silently write a label into a
         * colour column rather than fail to compile.
         *
         * Plain `INSERT`, deliberately NOT `INSERT OR REPLACE`. `key` carries a unique index, and a
         * replace would silently overwrite an existing dimension's row if a caller passed a duplicate —
         * turning a caller bug into a quietly wrong catalog with no error anywhere. A plain insert makes
         * the constraint report itself, which is what lets the transaction roll back.
         */
        const val INSERT_ALL_SQL =
            "INSERT INTO life_dimensions " +
                "(id, key, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        // 1-based JDBC parameter positions, named because they are POSITIONAL: a value pasted into the
        // wrong slot writes a label into a colour column and still compiles. They must stay in the exact
        // order of INSERT_ALL_SQL's column list, which is why the two live side by side.
        private const val COLUMN_ID = 1
        private const val COLUMN_KEY = 2
        private const val COLUMN_LABEL = 3
        private const val COLUMN_DESCRIPTION = 4
        private const val COLUMN_COLOR = 5
        private const val COLUMN_ICON = 6
        private const val COLUMN_SORT_ORDER = 7
        private const val COLUMN_IS_ACTIVE = 8
        private const val COLUMN_WEIGHT = 9
        private const val COLUMN_CREATED_AT = 10
        private const val COLUMN_UPDATED_AT = 11

        /** Verbatim from `LifeDimensionDao.updateLabel`; value first, then the timestamp, then the id. */
        const val UPDATE_LABEL_SQL = "UPDATE life_dimensions SET label = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `LifeDimensionDao.updateColor`. */
        const val UPDATE_COLOR_SQL = "UPDATE life_dimensions SET color = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `LifeDimensionDao.updateIcon`. */
        const val UPDATE_ICON_SQL = "UPDATE life_dimensions SET icon = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `LifeDimensionDao.updateActiveState`. */
        const val UPDATE_ACTIVE_SQL = "UPDATE life_dimensions SET isActive = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `LifeDimensionDao.updateWeight`. */
        const val UPDATE_WEIGHT_SQL = "UPDATE life_dimensions SET weight = ?, updatedAt = ? WHERE id = ?"
    }
}
