//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.assistant.OpenCodeGoClient
import io.payanam.domain.port.AssistantConfigurationStore
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.database.DatabaseDimensionSetupPersister
import io.payanam.presentation.database.DatabaseInitFlagReader
import io.payanam.presentation.database.DatabaseInitFlagWriter
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseVault

/**
 * Hilt entry point for the database-init screen's collaborators.
 *
 * The [io.payanam.presentation.database.DatabaseInitViewModel] is a plain class now - it no longer extends
 * `androidx.lifecycle.ViewModel` and therefore cannot be resolved by `hiltViewModel()`. So the platform
 * builds the object at the one call site, and this is how it gets the collaborators Hilt already binds.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DatabaseInitEntryPoint {
    /** The app settings repository, for persisting the init-completed flag. */
    fun appSettingsRepository(): AppSettingsRepository

    /** The database session manager, for opening/closing the session. */
    fun databaseSessionManager(): DatabaseSessionManager

    /** The life-dimension catalog repository, for persisting the mandatory setup. */
    fun lifeDimensionCatalogRepository(): LifeDimensionCatalogRepository

    /** The database health check, for the status screen. */
    fun healthCheck(): DatabaseHealthCheck

    /** The database path resolver, for the canonical file path. */
    fun databasePathResolver(): DatabasePathResolver

    /** The encryption probe, for detecting encrypted imports. */
    fun encryptionProbe(): DatabaseEncryptionProbe

    /** The SAF document source, for reading picked files. */
    fun documentSource(): DocumentSource

    /** The database-init flag writer, for marking init complete in the file. */
    fun databaseInitFlagWriter(): DatabaseInitFlagWriter

    /** The read-only app_settings reader, for the check that runs before a session exists. */
    fun databaseInitFlagReader(): DatabaseInitFlagReader

    /** The WAL checkpoint on a temp copy, for the fold that precedes deleting the production -wal. */
    fun databaseInitWalConsolidator(): DatabaseInitWalConsolidator

    /** The new database's mandatory life-dimension seed writer. */
    fun appDimensionSetupPersister(): DatabaseDimensionSetupPersister

    /** The encryption state checkpoint, for backup/restore/discard/wipe. */
    fun encryptionStateCheckpoint(): EncryptionStateCheckpoint

    /** The passphrase vault, for encryption state and passphrase configuration. */
    fun passphraseVault(): PassphraseVault

    /** The generated copy table for the language in force. */
    fun uiStrings(): UiStrings
}

/**
 * Hilt entry point for the assistant screen's collaborators.
 *
 * A fourth sibling for the reason the other three exist: the assistant's state is a SHARED class
 * (`:core:presentation`) now, so it cannot be a `@HiltViewModel` and `hiltViewModel()` cannot build it. The
 * four things it needs are the encrypted settings store, the two read-only ports, and the provider client
 * — every one of them already bound as a singleton, so this only names them.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantEntryPoint {
    /** The encrypted `app_settings` store, behind the shared [AssistantConfigurationStore] contract. */
    fun assistantSettingsStore(): AssistantConfigurationStore

    /** The one statement a model-authored query may run, over Room's read-only handle. */
    fun assistantReadOnlyQuery(): ReadOnlyDatabaseQuery

    /** The live schema reader, which takes no SQL and therefore cannot be handed a model's. */
    fun assistantSchemaReader(): DatabaseSchemaReader

    /** The OpenCode Go chat client, bound to the provided transport. */
    fun openCodeGoClient(): OpenCodeGoClient
}

/**
 * Hilt entry point for the passphrase-CHANGE screen's collaborators.
 *
 * A third sibling for the reason the setup gate is one: this screen re-keys an ALREADY-ENCRYPTED database,
 * which is neither the init screen's thirteen collaborators nor the setup gate's four. [databaseRekey] and
 * [processRestart] are the two the other two entry points do not carry, and both are already bound as
 * singletons (`AndroidDatabaseRekey`, `AndroidProcessRestartPort`) - this only names them so the shared
 * ViewModel can be constructed at the one call site.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PassphraseChangeEntryPoint {
    /** The database path resolver, for the canonical file path this screen re-keys. */
    fun databasePathResolver(): DatabasePathResolver

    /** The Keystore-backed vault, the same one the unlock gate authenticates against. */
    fun passphraseVault(): PassphraseVault

    /** Android's answer to "rekey this already-encrypted file", over SQLCipher. */
    fun databaseRekey(): io.payanam.domain.port.DatabaseRekey

    /** The SHARED session contract, so the write-ahead log fold before a re-key happens here too. */
    fun databaseSessionManager(): DatabaseSessionManager

    /** The process-end sequence, so a changed passphrase is authenticated against on the next cold boot. */
    fun processRestart(): io.payanam.domain.port.ProcessRestart
}

/**
 * Hilt entry point for the passphrase-SETUP gate's collaborators.
 *
 * A sibling interface rather than four more members on [DatabaseInitEntryPoint] because it is a different
 * screen drawing a different half of the same graph: the setup gate never asks the init screen's thirteen
 * collaborators, and every platform implementing that entry point would be made to carry four it never uses.
 *
 * WHY IT IS NEEDED AT ALL: the setup ViewModel is a plain class in `:core:presentation` now, and
 * `hiltViewModel<T>()` resolves through a factory that only knows `@HiltViewModel` classes - which a shared
 * module cannot carry, because the desktop has no Hilt at all. So the platform builds the object at the one
 * call site and this is how it gets the collaborators Hilt already binds. The same shape as
 * `PassphraseUnlockEntryPoint`, for the same reason.
 *
 * [encryptionProbe] and [encryptionStateCheckpoint] are the SAME singletons the init screen and the unlock gate
 * use, deliberately: the setup gate re-encrypts the same file and forgets the same encryption state, and a
 * second instance of either would be an operation the app half-performed.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PassphraseSetupEntryPoint {
    /** The database path resolver, for the canonical file path this gate encrypts in place. */
    fun databasePathResolver(): DatabasePathResolver

    /** Android's answer to "is this file encrypted, and make it so", over Room and SQLCipher. */
    fun encryptionProbe(): DatabaseEncryptionProbe

    /** The Keystore-backed vault, the same one the unlock gate authenticates against. */
    fun passphraseVault(): PassphraseVault

    /** The abandon path for encryption state, over the same `DatabaseEncryptionManager`. */
    fun encryptionStateCheckpoint(): EncryptionStateCheckpoint
}
