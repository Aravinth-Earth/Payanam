//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.platform.AndroidDatabaseEncryptionProbe
import io.payanam.platform.AndroidDatabaseHealthCheck
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseHealthCheck
import javax.inject.Singleton

/**
 * Provides the shared copy table to Hilt.
 *
 * [UiStrings] is a generated interface, so it cannot be constructed by a framework — it has to come from
 * the generated table for the user's language. This is the one place Android resolves which language that
 * is, so a shared ViewModel can take the table as a plain constructor parameter instead of reaching for
 * `Context.getString`, which is what lets it move into a plain JVM module.
 *
 * Singleton-scoped so every consumer reads the same table rather than re-resolving per injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object UiStringsModule {
    /**
     * The generated copy table for the language Android has in force, which honours the per-app language
     * override rather than the device default.
     */
    @Provides
    @Singleton
    fun provideUiStrings(@ApplicationContext context: Context): UiStrings = uiStringsFor(context)
}

/**
 * Provides the database health check.
 *
 * A question rather than an implementation: the health rules are shared and the platform supplies the
 * handle, which is why the Android half is a thin adapter over the Room-backed checker rather than a second
 * implementation of the rules. `DatabasePathResolver` is NOT provided here - `PortBindingsModule` already
 * binds it, and a second provider for the same type is a duplicate binding, not a fallback.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseProbeModule {
    /** The health check, over the Room session Android already holds. */
    @Provides
    @Singleton
    fun provideDatabaseHealthCheck(@ApplicationContext context: Context): DatabaseHealthCheck = AndroidDatabaseHealthCheck(context)

    /**
     * The encryption probe, over Room.
     *
     * Same shape as the health check: the questions are shared, the handle is Android's. It is a separate
     * provider rather than a method on the health check because it answers a different question - whether
     * a file is encrypted and whether a passphrase opens it - and the desktop REFUSES re-encryption rather
     * than approximating Android's conversion.
     */
    @Provides
    @Singleton
    fun provideDatabaseEncryptionProbe(@ApplicationContext context: Context): DatabaseEncryptionProbe = AndroidDatabaseEncryptionProbe(context)
}
