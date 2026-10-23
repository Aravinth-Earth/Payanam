//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.ScoringConfigEntity
import io.payanam.database.mapper.ScoringConfigMapper
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.ResultSet

/**
 * JDBC implementation of [ScoringConfigRepository] over the encrypted database — the singleton row
 * (`id = 1`) holding the scoring engine's tunable weights and thresholds.
 *
 * Statements are copied from `ScoringConfigDao`. The behaviours that matter:
 *
 *  - **No saved row is not an error.** Every read falls back to `ScoringConfig.defaults()`, and
 *    [resetToDefaults] works by DELETING the row so the next read falls back again. A port that inserted a
 *    defaults row instead would leave a row behind that looks like a saved configuration, and the settings
 *    screen could no longer tell "never configured" from "configured to the defaults".
 *  - **The row is identified by a literal `id = 1`, never by a parameter.** Two writers cannot disagree
 *    about which row is the config, and the table cannot grow a second one.
 *  - **All 35 columns are written on every save**, because the entity IS the whole config: a column left
 *    out would silently keep its previous value while the UI showed the new one.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class ScoringConfigRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : ScoringConfigRepository {
    private val logger = AppLogger.current

    /** Returns the saved config, or `ScoringConfig.defaults()` when none has been saved. */
    override suspend fun getConfig(): ScoringConfig =
        JdbcFlows.onIo {
            logger.d("ScoringConfigRepositoryJvm.getConfig", "Fetching scoring config")
            val entity = readConfig()
            if (entity != null) {
                logger.i("ScoringConfigRepositoryJvm.getConfig", "Loaded saved config")
                ScoringConfigMapper.toDomain(entity)
            } else {
                logger.i("ScoringConfigRepositoryJvm.getConfig", "No saved config, using defaults")
                ScoringConfig.defaults()
            }
        }

    /** Emits the config, or `ScoringConfig.defaults()` while unsaved. */
    override fun observeConfig(): Flow<ScoringConfig> {
        logger.d("ScoringConfigRepositoryJvm.observeConfig", "Subscribing to scoring config")
        return JdbcFlows
            .observe(pollIntervalMs) { readConfig() }
            .map { entity ->
                if (entity != null) ScoringConfigMapper.toDomain(entity) else ScoringConfig.defaults()
            }
    }

    /** Persists [config] into the singleton row. */
    override suspend fun saveConfig(config: ScoringConfig) {
        JdbcFlows.onIo {
            logger.i(
                "ScoringConfigRepositoryJvm.saveConfig",
                "Saving scoring config",
                mapOf(
                    "dimensionWeight" to config.dimensionWeight,
                    "impactWeight" to config.impactWeight,
                ),
            )
            JdbcQueries.write(session, UPSERT_CONFIG_SQL, ScoringConfigMapper.toEntity(config).toBindings())
        }
    }

    /** Deletes the row, so the next read returns `ScoringConfig.defaults()`. */
    override suspend fun resetToDefaults() {
        JdbcFlows.onIo {
            logger.i("ScoringConfigRepositoryJvm.resetToDefaults", "Resetting to default config")
            JdbcQueries.write(session, DELETE_CONFIG_SQL, emptyArray<String?>())
        }
    }

    private fun readConfig(): ScoringConfigEntity? =
        JdbcQueries.read(session, CONFIG_SQL, emptyArray<String?>()) { resultSet ->
            if (resultSet.next()) resultSet.readConfig() else null
        }

    private fun ResultSet.readConfig(): ScoringConfigEntity =
        ScoringConfigEntity(
            id = getInt("id"),
            dimensionWeight = getDouble("dimensionWeight"),
            impactWeight = getDouble("impactWeight"),
            alignmentWeight = getDouble("alignmentWeight"),
            energyWeight = getDouble("energyWeight"),
            controlWeight = getDouble("controlWeight"),
            durationWeight = getDouble("durationWeight"),
            impactCritical = getDouble("impactCritical"),
            impactHigh = getDouble("impactHigh"),
            impactModerate = getDouble("impactModerate"),
            impactLow = getDouble("impactLow"),
            impactMinimal = getDouble("impactMinimal"),
            alignmentPerfect = getDouble("alignmentPerfect"),
            alignmentStrong = getDouble("alignmentStrong"),
            alignmentModerate = getDouble("alignmentModerate"),
            alignmentWeak = getDouble("alignmentWeak"),
            alignmentNone = getDouble("alignmentNone"),
            energyHigh = getDouble("energyHigh"),
            energyModerate = getDouble("energyModerate"),
            energyLow = getDouble("energyLow"),
            controlFull = getDouble("controlFull"),
            controlMostly = getDouble("controlMostly"),
            controlOffice = getDouble("controlOffice"),
            controlExternal = getDouble("controlExternal"),
            controlNone = getDouble("controlNone"),
            dimensionCareerWork = getDouble("dimensionCareerWork"),
            dimensionHealthWellness = getDouble("dimensionHealthWellness"),
            dimensionRelationships = getDouble("dimensionRelationships"),
            dimensionPersonalGrowth = getDouble("dimensionPersonalGrowth"),
            dimensionFinancial = getDouble("dimensionFinancial"),
            dimensionSpiritual = getDouble("dimensionSpiritual"),
            dimensionRecreation = getDouble("dimensionRecreation"),
            dimensionLearning = getDouble("dimensionLearning"),
            dimensionContribution = getDouble("dimensionContribution"),
            updatedAt = getString("updatedAt"),
        )

    /** The 35 columns, in the order [UPSERT_CONFIG_SQL] binds them. */
    private fun ScoringConfigEntity.toBindings(): Array<String?> =
        arrayOf(
            id.toString(),
            dimensionWeight.toString(),
            impactWeight.toString(),
            alignmentWeight.toString(),
            energyWeight.toString(),
            controlWeight.toString(),
            durationWeight.toString(),
            impactCritical.toString(),
            impactHigh.toString(),
            impactModerate.toString(),
            impactLow.toString(),
            impactMinimal.toString(),
            alignmentPerfect.toString(),
            alignmentStrong.toString(),
            alignmentModerate.toString(),
            alignmentWeak.toString(),
            alignmentNone.toString(),
            energyHigh.toString(),
            energyModerate.toString(),
            energyLow.toString(),
            controlFull.toString(),
            controlMostly.toString(),
            controlOffice.toString(),
            controlExternal.toString(),
            controlNone.toString(),
            dimensionCareerWork.toString(),
            dimensionHealthWellness.toString(),
            dimensionRelationships.toString(),
            dimensionPersonalGrowth.toString(),
            dimensionFinancial.toString(),
            dimensionSpiritual.toString(),
            dimensionRecreation.toString(),
            dimensionLearning.toString(),
            dimensionContribution.toString(),
            updatedAt,
        )

    private companion object {
        /**
         * Verbatim from `ScoringConfigDao.getConfig` / `observeConfig`. The literal `id = 1` identifies the
         * singleton row and is deliberately not a parameter.
         */
        const val CONFIG_SQL = "SELECT * FROM scoring_config WHERE id = 1"

        /** `ScoringConfigDao.upsertConfig` is `@Insert(REPLACE)`. */
        const val UPSERT_CONFIG_SQL =
            "INSERT OR REPLACE INTO scoring_config (id, dimensionWeight, impactWeight, alignmentWeight, energyWeight, controlWeight, durationWeight, impactCritical, impactHigh, impactModerate, impactLow, impactMinimal, alignmentPerfect, alignmentStrong, alignmentModerate, alignmentWeak, alignmentNone, energyHigh, energyModerate, energyLow, controlFull, controlMostly, controlOffice, controlExternal, controlNone, dimensionCareerWork, dimensionHealthWellness, dimensionRelationships, dimensionPersonalGrowth, dimensionFinancial, dimensionSpiritual, dimensionRecreation, dimensionLearning, dimensionContribution, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        /** Verbatim from `ScoringConfigDao.deleteConfig`. */
        const val DELETE_CONFIG_SQL = "DELETE FROM scoring_config WHERE id = 1"
    }
}
