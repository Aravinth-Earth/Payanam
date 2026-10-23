//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.net.Uri
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.database.DatabaseDimensionSetupPersister
import io.payanam.presentation.database.DatabaseInitFlagReader
import io.payanam.presentation.database.DatabaseInitFlagWriter
import io.payanam.presentation.database.DatabaseInitViewModel
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android's convenience overload: converts a SAF `Uri` to its string token and delegates to the
 * platform-neutral [DatabaseInitViewModel.importDatabase].
 *
 * The `Uri` stops at this ViewModel's own boundary (the screen and the SAF launcher are Android's);
 * everything below it speaks the shared token, so the same staged import can be resumed by a platform
 * that never had a `Uri` to begin with.
 */
fun DatabaseInitViewModel.importDatabase(sourceUri: Uri, onSuccess: () -> Unit) {
    importDatabase(sourceToken = sourceUri.toString(), onSuccess = onSuccess)
}

/**
 * Builds the [DatabaseInitViewModel] from the platform's own collaborators.
 *
 * Every collaborator is supplied by the caller, because they are precisely the things a shared module
 * cannot build: the document source, the two database-init file handles, the encryption checkpoint and the
 * passphrase vault are platform implementations of shared contracts, and the strings table and the
 * database path are the platform's own. A factory that reached for them itself would stop compiling the
 * moment the ViewModel moves into `:core:presentation`.
 */
fun databaseInitViewModel(
    appSettingsRepository: AppSettingsRepository,
    databaseSessionManager: DatabaseSessionManager,
    lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
    healthCheck: DatabaseHealthCheck,
    databasePathResolver: DatabasePathResolver,
    encryptionProbe: DatabaseEncryptionProbe,
    documentSource: DocumentSource,
    databaseInitFlagWriter: DatabaseInitFlagWriter,
    databaseInitFlagReader: DatabaseInitFlagReader,
    databaseInitWalConsolidator: DatabaseInitWalConsolidator,
    dimensionSetupPersister: DatabaseDimensionSetupPersister,
    encryptionStateCheckpoint: EncryptionStateCheckpoint,
    passphraseVault: PassphraseVault,
    strings: UiStrings,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): DatabaseInitViewModel =
    DatabaseInitViewModel(
        appSettingsRepository = appSettingsRepository,
        databaseSessionManager = databaseSessionManager,
        lifeDimensionCatalogRepository = lifeDimensionCatalogRepository,
        strings = strings,
        healthCheck = healthCheck,
        databasePathResolver = databasePathResolver,
        encryptionProbe = encryptionProbe,
        documentSource = documentSource,
        databaseInitFlagWriter = databaseInitFlagWriter,
        databaseInitFlagReader = databaseInitFlagReader,
        databaseInitWalConsolidator = databaseInitWalConsolidator,
        dimensionSetupPersister = dimensionSetupPersister,
        encryptionStateCheckpoint = encryptionStateCheckpoint,
        passphraseVault = passphraseVault,
        scope = scope,
        ioDispatcher = ioDispatcher,
    )
