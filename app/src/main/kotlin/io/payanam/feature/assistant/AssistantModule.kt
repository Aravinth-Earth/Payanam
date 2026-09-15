//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

    /** The OpenCode Go chat client bound to the provided transport. */
    @Provides
    @Singleton
    fun provideOpenCodeGoClient(transport: HttpTransport): OpenCodeGoClient = OpenCodeGoClient(transport)
}
