//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.payanam.domain.assistant.HttpTransport
import io.payanam.domain.assistant.OpenCodeGoClient
import io.payanam.domain.assistant.UrlConnectionTransport
import io.payanam.domain.port.AssistantConfigurationStore
import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import javax.inject.Singleton

/**
 * Hilt wiring for the assistant's network stack.
 *
 * The transport and client are provided (rather than constructed inside the ViewModel) so
 * tests can substitute a fake transport and the client's lifetime is owned by the container.
 */
@Module
@InstallIn(SingletonComponent::class)
object AssistantModule {
    /** The production HTTP transport (HttpURLConnection — no third-party client). */
    @Provides
    @Singleton
    fun provideHttpTransport(): HttpTransport = UrlConnectionTransport()

    /**
     * The encrypted settings store, behind the shared contract the shared ViewModel names.
     *
     * Provided as the INTERFACE rather than injected by concrete type so the shared class never learns
     * that Android stores configuration in Room, and so a second platform binding is the same one line.
     */
    @Provides
    @Singleton
    fun provideAssistantConfigurationStore(store: AssistantSettingsStore): AssistantConfigurationStore = store

    /**
     * The assistant's read-only statement runner, answered by Room's read-only handle.
     *
     * Both ports come from ONE implementation because one verified read-only handle answers both, and
     * because two classes could answer them differently on one platform — which is exactly the divergence
     * the shared screen exists to remove.
     */
    @Provides
    @Singleton
    fun provideAssistantReadOnlyQuery(reads: AndroidReadOnlyAssistantReads): ReadOnlyDatabaseQuery = reads

    /** The same implementation's schema reader. See [provideAssistantReadOnlyQuery]. */
    @Provides
    @Singleton
    fun provideAssistantSchemaReader(reads: AndroidReadOnlyAssistantReads): DatabaseSchemaReader = reads

    /** The OpenCode Go chat client bound to the provided transport. */
    @Provides
    @Singleton
    fun provideOpenCodeGoClient(transport: HttpTransport): OpenCodeGoClient = OpenCodeGoClient(transport)
}
