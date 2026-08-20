//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.mapper.toConfiguredLifeDimension
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * LifeDimensionCatalogRepositoryImpl.
 */
class LifeDimensionCatalogRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : LifeDimensionCatalogRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> {
            logger.d("LifeDimensionCatalogRepositoryImpl.observeAllDimensions", "Subscribing to life dimension catalog")
            return sessionManager
                .requireDatabase()
                .lifeDimensionDao()
                .observeAllDimensions()
                .map { entities -> entities.map { it.toConfiguredLifeDimension() } }
        }

        override suspend fun updateDimensionLabel(
            /** Dimension id. */
            dimensionId: String,
            /** Label. */
            label: String,
        ) {
            sessionManager.requireDatabase().lifeDimensionDao().updateLabel(
                dimensionId = dimensionId,
                label = label,
                updatedAt = now(),
            )
            logger.i(
                "LifeDimensionCatalogRepositoryImpl.updateDimensionLabel",
                "Updated life dimension label",
                /** Map of. */
                mapOf("dimensionId" to dimensionId),
            )
        }

        override suspend fun updateDimensionColor(
            /** Dimension id. */
            dimensionId: String,
            /** Color hex. */
            colorHex: String,
        ) {
            sessionManager.requireDatabase().lifeDimensionDao().updateColor(
                dimensionId = dimensionId,
                colorHex = colorHex,
                updatedAt = now(),
            )
            logger.i(
                "LifeDimensionCatalogRepositoryImpl.updateDimensionColor",
                "Updated life dimension color",
                /** Map of. */
                mapOf("dimensionId" to dimensionId, "colorHex" to colorHex),
            )
        }

        override suspend fun updateDimensionIcon(
            /** Dimension id. */
            dimensionId: String,
            /** Icon key. */
            iconKey: String,
        ) {
            sessionManager.requireDatabase().lifeDimensionDao().updateIcon(
                dimensionId = dimensionId,
                iconKey = iconKey,
                updatedAt = now(),
            )
            logger.i(
                "LifeDimensionCatalogRepositoryImpl.updateDimensionIcon",
                "Updated life dimension icon",
                /** Map of. */
                mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
            )
        }

        override suspend fun updateDimensionActiveState(
            /** Dimension id. */
            dimensionId: String,
            /** Is active. */
            isActive: Boolean,
        ) {
            sessionManager.requireDatabase().lifeDimensionDao().updateActiveState(
                dimensionId = dimensionId,
                isActive = if (isActive) 1 else 0,
                updatedAt = now(),
            )
            logger.i(
                "LifeDimensionCatalogRepositoryImpl.updateDimensionActiveState",
                "Updated life dimension active state",
                /** Map of. */
                mapOf("dimensionId" to dimensionId, "isActive" to isActive),
            )
        }

        override suspend fun updateDimensionWeight(
            /** Dimension id. */
            dimensionId: String,
            /** Weight. */
            weight: Double,
        ) {
            sessionManager.requireDatabase().lifeDimensionDao().updateWeight(
                dimensionId = dimensionId,
                weight = weight,
                updatedAt = now(),
            )
            logger.i(
                "LifeDimensionCatalogRepositoryImpl.updateDimensionWeight",
                "Updated life dimension weight",
                /** Map of. */
                mapOf("dimensionId" to dimensionId, "weight" to weight),
            )
        }

        private fun now(): String = LocalDateTime.now().format(dateTimeFormatter)
    }
