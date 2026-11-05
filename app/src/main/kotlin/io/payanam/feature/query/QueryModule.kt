//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.query

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import javax.inject.Singleton

/**
 * Hilt wiring for read-only database access.
 *
 * Only the two read-only ports are provided here: the verified read-only handle can answer both, and a
 * model/client stack is no longer part of this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object QueryModule {
    /**
     * The read-only statement runner, answered by Room's read-only handle.
     *
     * Both ports come from ONE implementation because one verified read-only handle answers both, and
     * because two classes could answer them differently on one platform — which is exactly the divergence
     * the shared screen exists to remove.
     */
    @Provides
    @Singleton
    fun provideReadOnlyDatabaseQuery(reads: AndroidReadOnlyDatabaseReads): ReadOnlyDatabaseQuery = reads

    /** The same implementation's schema reader. See [provideReadOnlyDatabaseQuery]. */
    @Provides
    @Singleton
    fun provideSchemaReader(reads: AndroidReadOnlyDatabaseReads): DatabaseSchemaReader = reads
}
