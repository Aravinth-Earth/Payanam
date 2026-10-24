//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress(
    "TooGenericExceptionCaught",
    "SwallowedException",
    "MagicNumber",
    "TooManyFunctions",
)

package io.payanam.presentation.assistant

import io.payanam.domain.assistant.AssistantBusy
import io.payanam.domain.assistant.AssistantChatMessage
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantError
import io.payanam.domain.assistant.AssistantErrorKind
import io.payanam.domain.assistant.AssistantHttpException
import io.payanam.domain.assistant.AssistantProtocol
import io.payanam.domain.assistant.AssistantProvider
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.domain.assistant.AssistantSystemPrompt
import io.payanam.domain.assistant.ChatReply
import io.payanam.domain.assistant.ChatTurn
import io.payanam.domain.assistant.GuardResult
import io.payanam.domain.assistant.OpenAiCompatibleClient
import io.payanam.domain.assistant.OpenCodeGoClient
import io.payanam.domain.assistant.ReadOnlySqlGuard
import io.payanam.domain.assistant.UrlConnectionTransport
import io.payanam.domain.port.AssistantConfigurationStore
import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.DatabaseSchemaResult
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import io.payanam.domain.port.ReadOnlyQueryOutcome
import io.payanam.domain.port.ReadOnlyRefusal
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Full UI state of the assistant screen. */
data class AssistantUiState(
    val loading: Boolean = true,
    /** True while a stored key exists — including during a pending reconfigure (setup shown, chat disabled). */
    val hasKey: Boolean = false,
    /**
     * The provider the assistant is set up for, or null when none is chosen — including the load where a
     * stored provider name could not be recognised, since no host can be named for the stored key.
     */
    val provider: AssistantProvider? = null,
    /** The endpoint address as it is shown in the address field, exactly as it was typed or loaded. */
    val baseUrl: String = "",
    /** True while the entered provider + address + key + model is being checked against the real endpoint. */
    val verifying: Boolean = false,
    /** True once the last verification succeeded; any edit to what was checked clears it. */
    val verified: Boolean = false,
    /**
     * True when saving the entered endpoint would drop the stored key and the user has to confirm first.
     * Nothing has been written and no key has been removed while this is true.
     */
    val keyRemovalPending: Boolean = false,
    val keyDraft: String = "",
    val keyInvalid: Boolean = false,
    val modelsLoading: Boolean = false,
    val modelOptions: List<String> = emptyList(),
    val settings: AssistantSettings = AssistantSettings(),
    val messages: List<AssistantChatMessage> = emptyList(),
    val busy: AssistantBusy? = null,
    val error: AssistantErrorKind? = null,
    /**
     * The precise classification of that same failure, when the client could supply one.
     *
     * [error] is the older, coarser vocabulary and stays exactly as it was, because the screen's
     * banner reads it. It cannot tell a rate limit from a timeout from a provider outage, so this
     * field carries the truthful [AssistantError] behind the kind — including its [AssistantError.httpStatus]
     * — and the screen can say which of those actually happened.
     *
     * Null means "nothing finer is known": either no failure at all, or a failure raised before the
     * client classified (or one that never came from an endpoint, such as a locked database). A
     * consumer must therefore treat null as "fall back to [error]", never as "no error".
     */
    val failure: AssistantError? = null,
    /** Provider-side error text for the banner — redacted (submitted-key echoes) and truncated like the log. */
    val errorDetail: String? = null,
    val noticeVisible: Boolean = false,
)

/**
 * Drives the in-app assistant: config (key/model/settings), the agentic-SQL turn loop
 * (port of the Ring A harness), receipts, and the elaborate local trace the user asked
 * for. Security: the API key lives only in this class + the encrypted settings store;
 * logs carry fingerprints, never values.
 */
class AssistantViewModel(
    /** The stored configuration, behind the shared contract rather than the platform's own store. */
    private val settingsStore: AssistantConfigurationStore,
    /** The live schema for the prompt, read through the shared reader, which takes no SQL. */
    private val schemaReader: DatabaseSchemaReader,
    /** The one statement a model-authored query may run, over a handle proven unable to write. */
    private val query: ReadOnlyDatabaseQuery,
    private val client: OpenCodeGoClient,
    /** The scope this ViewModel's work runs on. `scope` is not available in this module. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Dispatcher for the blocking work - the socket reads and the database statements. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Builds the client for one endpoint address.
     *
     * The default answers the address this app shipped with using [client] — the transport the platform
     * supplied, and the one every test drives — and any other address with a client of its own, because a
     * client that carries its own base URL can only ever reach the one endpoint it was built for. A
     * parameter so a test can verify an address without a socket.
     */
    private val endpointClientFactory: (String) -> AssistantEndpointClient = { baseUrl ->
        endpointClientFor(client, baseUrl)
    },
) {
    private val logger = AppLogger.current
    private val sessionId = UUID.randomUUID().toString()
    private var apiKey = ""

    /** The endpoint as it was last loaded or stored, so an edited one can be compared and reverted. */
    private var persistedProvider: AssistantProvider? = null
    private var persistedBaseUrl = ""
    private val conversation = mutableListOf<ChatTurn>()
    private var saveJob: Job? = null
    private var messageSeq = 0L

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        logger.i(
            "AssistantViewModel.init",
            "Assistant view model created",
            mapOf("session" to sessionId),
        )
        refresh()
    }

    /** Loads the stored key + settings + endpoint + reconfigure flag (DB must be open). */
    fun refresh() {
        scope.launch {
            try {
                val config = settingsStore.loadConfig()
                // The stored endpoint, carried into the state rather than discarded: the screen shows the
                // provider and the address the assistant will actually talk to. On the load where the
                // stored provider name could not be recognised the store reports no provider, no address
                // and no key, so the screen reads as unconfigured through `hasKey` alone — no special
                // case is needed here.
                val connection = config.connection
                // No key ⇒ no model, EXCEPT where the provider does not need one: a local server
                // answers with no credential, so an empty key there is a finished setup and not a
                // missing one. A pending reconfigure ⇒ no model either: the stored model must never
                // surface before the user completes setup again ("Save & start" / key removal are
                // the only paths that clear the flag).
                val keyMissing = config.apiKey.isEmpty() && connection.provider?.requiresKey != false
                val settings =
                    if (keyMissing || config.setupRequired) {
                        config.settings.copy(model = "")
                    } else {
                        config.settings
                    }
                apiKey = config.apiKey
                persistedProvider = connection.provider
                persistedBaseUrl = connection.baseUrl
                _uiState.update {
                    it.copy(
                        loading = false,
                        hasKey = config.apiKey.isNotEmpty(),
                        provider = connection.provider,
                        baseUrl = connection.baseUrl,
                        verifying = false,
                        verified = false,
                        failure = null,
                        keyRemovalPending = false,
                        settings = settings,
                        noticeVisible = !settings.noticeShown,
                    )
                }
                logger.d(
                    "AssistantViewModel.refresh",
                    "Assistant state loaded",
                    mapOf(
                        "hasKey" to config.apiKey.isNotEmpty(),
                        "provider" to (connection.provider?.name ?: "-"),
                        "addressStored" to connection.baseUrl.isNotBlank(),
                        "model" to settings.model,
                        "setupRequired" to config.setupRequired,
                    ),
                )
                if (config.setupRequired) {
                    logger.i(
                        "AssistantViewModel.refresh",
                        "Setup required flag set; model suppressed (key retained)",
                        mapOf("setupRequired" to true, "hasKey" to config.apiKey.isNotEmpty()),
                    )
                }
            } catch (closed: IllegalStateException) {
                handleDbClosed("AssistantViewModel.refresh", closed, resetKey = false)
                _uiState.update { it.copy(loading = false, hasKey = false, provider = null, baseUrl = "", failure = null) }
            }
        }
    }

    /** Tracks the pasted key without persisting it. */
    fun updateKeyDraft(text: String) {
        _uiState.update { it.copy(keyDraft = text, keyInvalid = false) }
    }

    /** The key the next provider call should use: the trimmed draft, else the stored key. */
    private fun resolveKey(): String =
        _uiState.value.keyDraft
            .trim()
            .ifEmpty { apiKey }

    /**
     * Validates the pasted key with a real chat call, then loads the selectable models.
     * A key rejection is surfaced as [AssistantErrorKind.KEY_REJECTED].
     *
     * A KEY IS REQUIRED ONLY WHERE THE PROVIDER REQUIRES ONE: [AssistantProvider.requiresKey] is the
     * single authority here too, so a local server that answers with no credential can list its models
     * instead of being stopped before the first call.
     */
    fun loadModels() {
        scope.launch {
            val usedDraft =
                _uiState.value.keyDraft
                    .trim()
                    .isNotEmpty()
            val source = if (usedDraft) "draft" else "stored"
            // Falls back to the stored key so "Change provider or key" can reload the
            // models without re-pasting a key the app already holds. The value stays a
            // LOCAL val — it never enters keyDraft/uiState/error payloads.
            val key = resolveKey()
            // A KEY IS REQUIRED ONLY WHERE THE PROVIDER REQUIRES ONE — the same rule
            // verifyConnection and saveAndStart already apply. A local server (Ollama, LM Studio,
            // the user's own custom local) answers with no credential at all and still serves a
            // model list, so gating on `key.isEmpty()` alone made its list unreachable.
            val provider = _uiState.value.provider
            if (key.isEmpty() && provider?.requiresKey != false) {
                logger.d("AssistantViewModel.loadModels", "Load models skipped: no key available", mapOf("source" to source))
                return@launch
            }
            _uiState.update { it.copy(modelsLoading = true, error = null, failure = null, keyInvalid = false) }
            val logData = mutableMapOf<String, Any?>("keyLength" to key.length, "source" to source)
            if (usedDraft) logData["fingerprint"] = settingsStore.fingerprint(key)
            logger.i("AssistantViewModel.loadModels", "Validating key and loading models", logData)
            try {
                val endpoint = endpointClient(_uiState.value.baseUrl)
                val models = withContext(ioDispatcher) { endpoint.fetchModels(key, sessionId) }
                if (models.isEmpty()) {
                    logger.w("AssistantViewModel.loadModels", "Provider returned an empty model list")
                    _uiState.update {
                        it.copy(modelsLoading = false, error = AssistantErrorKind.SERVER, errorDetail = null, failure = null)
                    }
                    return@launch
                }
                // A real chat call is the only honest key check (the model list is unauthenticated).
                withContext(ioDispatcher) { endpoint.validateKey(key, sessionId, models.first()) }
                logger.i("AssistantViewModel.loadModels", "Key validated with a real chat call")
                _uiState.update { state ->
                    state.copy(
                        modelsLoading = false,
                        modelOptions = models,
                        settings =
                            state.settings.copy(
                                // Never auto-select: the user picks the model.
                                model = state.settings.model.takeIf { it in models } ?: "",
                            ),
                    )
                }
                logger.i(
                    "AssistantViewModel.loadModels",
                    "Models loaded",
                    mapOf("count" to models.size, "models" to models.joinToString(",").take(400)),
                )
            } catch (http: AssistantHttpException) {
                handleHttpError("AssistantViewModel.loadModels", http, key)
            } catch (io: IOException) {
                handleIoError("AssistantViewModel.loadModels", io)
            } finally {
                // The spinner must stop on EVERY path, including a rejected key.
                _uiState.update { it.copy(modelsLoading = false) }
            }
        }
    }

    /**
     * Selects the active model in the current state only — the row itself is persisted by
     * [saveAndStart] (single-writer), so selecting alone never writes to the store.
     */
    fun selectModel(model: String) {
        updateSettings { it.copy(model = model) }
    }

    /**
     * Chooses the provider the endpoint belongs to.
     *
     * The address follows the new provider's own default unless the user has typed an address of their
     * own, which is never overwritten. The two custom providers have no default, so they start blank
     * rather than borrowing another provider's address — an unfinished setup must fail as unfinished
     * instead of calling a host the user never chose.
     */
    fun selectProvider(provider: AssistantProvider) {
        val state = _uiState.value
        val typedOwnAddress =
            state.baseUrl.isNotBlank() && state.baseUrl != state.provider?.defaultBaseUrl.orEmpty()
        val nextAddress = if (typedOwnAddress) state.baseUrl else provider.defaultBaseUrl.orEmpty()
        _uiState.update {
            it.copy(
                provider = provider,
                baseUrl = nextAddress,
                verified = false,
                keyInvalid = false,
            )
        }
        logger.i(
            "AssistantViewModel.selectProvider",
            "Provider chosen",
            mapOf(
                "provider" to provider.name,
                "addressFromDefault" to !typedOwnAddress,
                "addressStored" to nextAddress.isNotBlank(),
            ),
        )
    }

    /** Tracks the address as typed, verbatim — it is neither normalised nor persisted here. */
    fun updateBaseUrl(text: String) {
        _uiState.update { it.copy(baseUrl = text, verified = false) }
    }

    /**
     * Checks the entered provider + address + key + model against the real endpoint, using the two calls
     * the app already makes: the model list, then one minimal chat round-trip (the model list alone is
     * unauthenticated, so it can never prove the key).
     *
     * Loading is [AssistantUiState.verifying], success is [AssistantUiState.verified], and a failure is
     * the same `error`/`errorDetail`/`keyInvalid` banner every other provider failure lands in — no
     * second verification mechanism exists. The client is built for the address being checked, so this
     * verifies the host on screen and not the one the app shipped with.
     *
     * AN EMPTY MODEL LIST IS A CONNECTED ENDPOINT, not a failure: an endpoint that cannot list models
     * at all has no other way to report that it answered.
     */
    fun verifyConnection() {
        val state = _uiState.value
        val provider = state.provider
        if (provider == null) {
            logger.w("AssistantViewModel.verifyConnection", "Verification skipped: no provider chosen")
            _uiState.update { it.copy(verified = false, error = AssistantErrorKind.SERVER, errorDetail = null, failure = null) }
            return
        }
        val key = resolveKey()
        if (key.isEmpty() && provider.requiresKey) {
            logger.d(
                "AssistantViewModel.verifyConnection",
                "Verification skipped: this provider needs a key and none is available",
                mapOf("provider" to provider.name),
            )
            _uiState.update {
                it.copy(
                    verified = false,
                    error = AssistantErrorKind.KEY_REJECTED,
                    errorDetail = null,
                    failure = null,
                    keyInvalid = true,
                )
            }
            return
        }
        val address = state.baseUrl
        _uiState.update {
            it.copy(
                verifying = true,
                verified = false,
                error = null,
                errorDetail = null,
                failure = null,
                keyInvalid = false,
            )
        }
        logger.i(
            "AssistantViewModel.verifyConnection",
            "Verifying the entered endpoint",
            mapOf(
                "provider" to provider.name,
                "addressStored" to address.isNotBlank(),
                "keyLength" to key.length,
                "keyRequired" to provider.requiresKey,
            ),
        )
        scope.launch {
            try {
                val endpoint = endpointClient(address)
                val models = withContext(ioDispatcher) { endpoint.fetchModels(key, sessionId) }
                if (models.isEmpty()) {
                    // AN EMPTY LIST IS A CONNECTION, NOT A FAILURE. Some endpoints cannot list their
                    // models at all — Claude's OpenAI-compatible surface answers an empty list however
                    // healthy it is — so treating that as a server failure reported a working endpoint
                    // as broken and hid the real instruction: type the model name. There is no id to
                    // round-trip with, so verification stops here, the list stays empty, and whatever
                    // model the user typed is left exactly as typed.
                    logger.i(
                        "AssistantViewModel.verifyConnection",
                        "Endpoint answered but reported no models; treated as connected",
                        mapOf("provider" to provider.name),
                    )
                    _uiState.update { it.copy(modelOptions = emptyList(), verified = true, failure = null) }
                    return@launch
                }
                // A real chat call is the only honest check (the model list is unauthenticated).
                val model = state.settings.model.takeIf { it in models } ?: models.first()
                withContext(ioDispatcher) { endpoint.validateKey(key, sessionId, model) }
                logger.i(
                    "AssistantViewModel.verifyConnection",
                    "Entered endpoint verified with a real chat call",
                    mapOf("models" to models.size),
                )
                _uiState.update {
                    it.copy(
                        modelOptions = models,
                        verified = true,
                        settings =
                            it.settings.copy(
                                // Never auto-select: the user picks the model.
                                model = it.settings.model.takeIf { picked -> picked in models } ?: "",
                            ),
                    )
                }
            } catch (http: AssistantHttpException) {
                handleHttpError("AssistantViewModel.verifyConnection", http, key)
            } catch (io: IOException) {
                handleIoError("AssistantViewModel.verifyConnection", io)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e("AssistantViewModel.verifyConnection", "Verification failed unexpectedly", error)
                _uiState.update { it.copy(error = AssistantErrorKind.SERVER, errorDetail = null, failure = null) }
            } finally {
                // The spinner must stop on EVERY path, including a rejected key or a refused address.
                _uiState.update { it.copy(verifying = false) }
            }
        }
    }

    /**
     * Persists the endpoint, the key and the settings, and switches to the chat surface (requires a
     * picked model and a chosen provider).
     *
     * A KEY IS REQUIRED ONLY WHERE THE PROVIDER REQUIRES ONE. [AssistantProvider.requiresKey] is the
     * single authority: a local server ([AssistantProvider.OLLAMA], [AssistantProvider.LM_STUDIO], the
     * user's own [AssistantProvider.CUSTOM_LOCAL] or a self-hosted [AssistantProvider.CUSTOM_CLOUD])
     * answers with no credential at all, so a blank key there is a finished setup and not a missing one.
     * A provider that does require a key keeps the existing gate, including writing nothing at all
     * while no key is available.
     *
     * The endpoint is written by [AssistantConfigurationStore.saveConnection] and the key + settings by
     * [AssistantConfigurationStore.saveAll], always from this one call site and in that order, so a
     * provider change and its key can never be written by two different paths. The endpoint goes first
     * because it can fail on its own: if it does, no key is written for a host that was never stored.
     */
    fun saveAndStart() {
        val state = _uiState.value
        val provider = state.provider
        val key = resolveKey()
        // A provider not chosen yet says nothing about whether a key is needed, so that case is left to
        // the provider check below instead of being silently mistaken for a missing key.
        if (key.isEmpty() && provider?.requiresKey != false) return
        if (state.settings.model
                .isEmpty()
        ) {
            logger.w("AssistantViewModel.saveAndStart", "Save attempted without a selected model")
            _uiState.update { it.copy(error = AssistantErrorKind.NO_MODEL, failure = null) }
            return
        }
        if (provider == null) {
            logger.w("AssistantViewModel.saveAndStart", "Save attempted without a chosen provider")
            _uiState.update { it.copy(error = AssistantErrorKind.SERVER, errorDetail = null, failure = null) }
            return
        }
        if (state.baseUrl.isBlank()) {
            // An endpoint with no address is not an endpoint: storing one would leave a configuration
            // that can never answer. The two custom providers start blank, so this is reachable.
            logger.w("AssistantViewModel.saveAndStart", "Save attempted without an endpoint address")
            _uiState.update { it.copy(error = AssistantErrorKind.SERVER, errorDetail = null, failure = null) }
            return
        }
        if (needsKeyRemovalConfirmation()) {
            // Nothing is written here: the screen shows the confirmation and only the confirm action below
            // removes the stored key.
            logger.i(
                "AssistantViewModel.saveAndStart",
                "Endpoint change would drop the stored key; waiting for confirmation",
                mapOf("provider" to provider.name, "addressStored" to state.baseUrl.isNotBlank()),
            )
            _uiState.update { it.copy(keyRemovalPending = true) }
            return
        }
        persistEndpoint(provider, state.baseUrl.trim(), key, dropStoredKey = false)
    }

    /**
     * The user accepted the key removal, so the endpoint change proceeds and the old key is dropped.
     * This is the only path where a save removes a key.
     */
    fun confirmKeyRemoval() {
        val state = _uiState.value
        if (!state.keyRemovalPending) return
        val provider = state.provider
        val key = resolveKey()
        _uiState.update { it.copy(keyRemovalPending = false) }
        if (provider == null || (key.isEmpty() && provider.requiresKey)) {
            logger.w("AssistantViewModel.confirmKeyRemoval", "Key removal confirmed with nothing to save")
            return
        }
        persistEndpoint(provider, state.baseUrl.trim(), key, dropStoredKey = true)
    }

    /** Abandons the endpoint change: the entered provider and address go back to the stored ones. */
    fun cancelKeyRemoval() {
        _uiState.update {
            it.copy(
                provider = persistedProvider,
                baseUrl = persistedBaseUrl,
                keyRemovalPending = false,
                verified = false,
            )
        }
        logger.i("AssistantViewModel.cancelKeyRemoval", "Endpoint change abandoned; stored endpoint restored")
    }

    /**
     * True when saving what is on screen would present a key the user has not re-entered to a different
     * endpoint. A newly typed key is not the stored one, so it needs no confirmation, and a model change
     * alone never raises this because the model is not part of the endpoint.
     */
    private fun needsKeyRemovalConfirmation(): Boolean {
        val state = _uiState.value
        if (!state.hasKey || state.keyDraft.isNotBlank()) return false
        return endpointChanged(state.provider, state.baseUrl)
    }

    /** Whether this endpoint is a different one from the stored pair, compared as the user sees it. */
    private fun endpointChanged(
        provider: AssistantProvider?,
        baseUrl: String,
    ): Boolean = provider != persistedProvider || normaliseAddress(baseUrl) != normaliseAddress(persistedBaseUrl)

    /** The comparison form of an address: the client trims and drops trailing slashes, so equals compares equal. */
    private fun normaliseAddress(address: String): String = address.trim().trimEnd('/')

    /**
     * Writes the endpoint and then the key + settings.
     *
     * There is no single-transaction alternative in the store contract for the removal case: the only
     * writer that removes a key is [AssistantConfigurationStore.clearProviderConfiguration], which also
     * drops the endpoint, the model and the setup flag, so the endpoint and the settings are written
     * straight after it. Passing a null key to `saveAll` then means "keep what is stored" — which is
     * nothing — so the removed key cannot come back.
     */
    private fun persistEndpoint(
        provider: AssistantProvider,
        baseUrl: String,
        key: String,
        dropStoredKey: Boolean,
    ) {
        val settings = _uiState.value.settings
        scope.launch {
            try {
                if (dropStoredKey) settingsStore.clearProviderConfiguration()
                settingsStore.saveConnection(provider, baseUrl)
                // Key + settings persist in ONE call; a partial write here would leave a stored key with a
                // stale/empty model across the next launch.
                settingsStore.saveAll(if (dropStoredKey) null else key.takeIf { it != apiKey }, settings)
                apiKey = if (dropStoredKey) "" else key
                persistedProvider = provider
                persistedBaseUrl = baseUrl
                _uiState.update {
                    it.copy(
                        hasKey = apiKey.isNotEmpty(),
                        keyInvalid = false,
                        keyDraft = "",
                        keyRemovalPending = false,
                    )
                }
                logger.i(
                    "AssistantViewModel.saveAndStart",
                    "Assistant configured and ready",
                    mapOf(
                        "model" to settings.model,
                        "provider" to provider.name,
                        "addressStored" to baseUrl.isNotBlank(),
                        "keyDropped" to dropStoredKey,
                    ),
                )
            } catch (closed: IllegalStateException) {
                apiKey = ""
                handleDbClosed("AssistantViewModel.saveAndStart", closed, resetKey = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e("AssistantViewModel.saveAndStart", "Connection persist failed", error)
                _uiState.update { it.copy(error = AssistantErrorKind.SERVER, errorDetail = null, failure = null) }
            }
        }
    }

    /** Applies [transform] to settings immediately and persists after a short debounce. */
    fun updateSettings(transform: (AssistantSettings) -> AssistantSettings) {
        val updated = transform(_uiState.value.settings).sanitized()
        _uiState.update { it.copy(settings = updated) }
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                tryPersistSettings("AssistantViewModel.updateSettings", updated)
            }
    }

    /** Flushes any pending debounced save (called when leaving the settings screen). */
    fun flushSettings() {
        saveJob?.cancel()
        val settings = _uiState.value.settings
        scope.launch {
            tryPersistSettings("AssistantViewModel.flushSettings", settings)
        }
    }

    /** Dismisses the error banner. */
    fun dismissError() {
        _uiState.update { it.copy(error = null, errorDetail = null, failure = null) }
    }

    /** Marks the first-run notice as seen. */
    fun acknowledgeNotice() {
        updateSettings { it.copy(noticeShown = true) }
        _uiState.update { it.copy(noticeVisible = false) }
        scope.launch {
            runCatching { settingsStore.markNoticeShown() }
                .onFailure { error ->
                    logger.e("AssistantViewModel.acknowledgeNotice", "Notice acknowledgement persist failed", error)
                }
        }
    }

    /** Drops the in-memory chat history. */
    fun clearChat() {
        conversation.clear()
        _uiState.update { it.copy(messages = emptyList()) }
        logger.i("AssistantViewModel.clearChat", "Chat cleared", mapOf("session" to sessionId))
    }

    /** Sends one question through the agentic-SQL loop. */
    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _uiState.value.busy != null) return
        val state = _uiState.value
        // The chosen provider decides whether a key is needed: a local server answers with no credential,
        // so "no stored key" is only fatal where the provider demands one. No provider chosen yet is not
        // a chat surface either, so that case keeps the existing refusal.
        if (!state.hasKey && state.provider?.requiresKey != false) {
            _uiState.update { it.copy(error = AssistantErrorKind.KEY_REJECTED, failure = null) }
            return
        }
        if (state.settings.model.isEmpty()) {
            _uiState.update { it.copy(error = AssistantErrorKind.NO_MODEL, failure = null) }
            return
        }
        _uiState.update {
            it.copy(
                messages =
                    it.messages +
                        AssistantChatMessage(
                            role = AssistantChatMessage.ROLE_USER,
                            text = text,
                            id = ++messageSeq,
                        ),
                error = null,
                failure = null,
            )
        }
        scope.launch { runTurn(text) }
    }

    /**
     * Single-question orchestrator: the agentic-SQL round loop (transport -> protocol ->
     * guard -> query -> state). The complexity suppressions below are scoped to this one
     * function on purpose; extracting a turn-executor is a tracked follow-up, and a
     * file-level suppression would silently exempt every future function.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private suspend fun runTurn(question: String) {
        val settings = _uiState.value.settings
        val turnStarted = System.currentTimeMillis()
        var queries = 0
        var rowsTotal = 0
        var rounds = 0
        var guardRejections = 0
        try {
            _uiState.update { it.copy(busy = AssistantBusy.Preparing) }
            val schema = readSchemaText()
            val system = AssistantSystemPrompt.build(settings, schema, LocalDate.now().toString())
            val history = conversation.takeLast(settings.historyDepth)
            val messages = mutableListOf(ChatTurn("system", system))
            messages += history
            messages += ChatTurn("user", question)
            logger.i(
                "AssistantTurn.start",
                "Question asked",
                mapOf(
                    "questionChars" to question.length,
                    "model" to settings.model,
                    "historyTurns" to history.size,
                    "schemaChars" to schema.length,
                    "maxRows" to settings.maxRows,
                    "rounds" to settings.rounds,
                ),
            )

            var finalAnswer: String? = null
            while (rounds < settings.rounds && finalAnswer == null) {
                _uiState.update { it.copy(busy = AssistantBusy.Thinking) }
                val reply = callChat(settings.model, messages)
                rounds++
                logModelReply(round = rounds, call = "chat", reply = reply)
                if (AssistantProtocol.isEmptyReply(reply.content)) {
                    messages += ChatTurn("assistant", " ")
                    messages += ChatTurn("user", AssistantProtocol.EMPTY_REPLY_PROMPT)
                    continue
                }
                val sql = AssistantProtocol.extractSql(reply.content)
                if (sql == null && AssistantProtocol.mentionsSqlKey(reply.content)) {
                    logger.w("AssistantTurn.protocol", "Truncated or malformed SQL JSON; re-asking", mapOf("round" to rounds))
                    messages += ChatTurn("assistant", reply.content)
                    messages += ChatTurn("user", AssistantProtocol.INVALID_SQL_JSON_PROMPT)
                    continue
                }
                if (sql == null) {
                    var answer = reply.content
                    if (reply.finishReason == "length") {
                        _uiState.update { it.copy(busy = AssistantBusy.Finalizing) }
                        logger.w("AssistantTurn.truncated", "Answer hit token cap; requesting continuation", mapOf("round" to rounds))
                        messages += ChatTurn("assistant", answer)
                        messages += ChatTurn("user", AssistantProtocol.CONTINUE_PROMPT)
                        val continuation = callChat(settings.model, messages)
                        rounds++
                        logModelReply(round = rounds, call = "continuation", reply = continuation)
                        answer += continuation.content
                    }
                    finalAnswer = answer
                    break
                }
                when (val guarded = ReadOnlySqlGuard.sanitize(sql, settings.maxRows)) {
                    is GuardResult.Rejected -> {
                        guardRejections++
                        logger.w(
                            "AssistantTurn.guard",
                            "SQL rejected by read-only guard",
                            mapOf("reason" to guarded.reason, "sqlChars" to sql.length),
                        )
                        messages += ChatTurn("assistant", reply.content)
                        messages += ChatTurn("user", AssistantProtocol.guardRejectionPrompt(guarded.reason))
                    }

                    is GuardResult.Ok -> {
                        queries++
                        _uiState.update { it.copy(busy = AssistantBusy.Querying(round = queries, max = settings.rounds)) }
                        val result = runReadOnlyQuery(guarded.sql, settings.maxRows)
                        rowsTotal += result.rowCount
                        messages += ChatTurn("assistant", reply.content)
                        messages += ChatTurn("user", "TOOL_RESULT:\n" + result.text)
                    }
                }
            }

            if (finalAnswer == null) {
                logger.w("AssistantTurn.roundLimit", "Round budget exhausted; forcing final answer")
                _uiState.update { it.copy(busy = AssistantBusy.Finalizing) }
                messages += ChatTurn("user", AssistantProtocol.ROUND_LIMIT_PROMPT)
                val forced = callChat(settings.model, messages)
                logModelReply(round = rounds, call = "forcedFinal", reply = forced)
                finalAnswer =
                    if (AssistantProtocol.extractSql(forced.content) == null &&
                        !AssistantProtocol.mentionsSqlKey(forced.content)
                    ) {
                        forced.content
                    } else {
                        "(max rounds reached)"
                    }
            }
            conversation += ChatTurn("user", question)
            conversation += ChatTurn("assistant", finalAnswer)
            _uiState.update { state ->
                state.copy(
                    messages =
                        state.messages +
                            AssistantChatMessage(
                                role = AssistantChatMessage.ROLE_ASSISTANT,
                                text = finalAnswer,
                                model = settings.model,
                                queryCount = queries,
                                rowCount = rowsTotal,
                                id = ++messageSeq,
                            ),
                    busy = null,
                )
            }
            logger.i(
                "AssistantTurn.done",
                "Turn complete",
                mapOf(
                    "seconds" to ((System.currentTimeMillis() - turnStarted) / 1000),
                    "rounds" to rounds,
                    "queries" to queries,
                    "rows" to rowsTotal,
                    "guardRejections" to guardRejections,
                    "answerChars" to finalAnswer.length,
                ),
            )
        } catch (http: AssistantHttpException) {
            handleHttpError("AssistantTurn", http, apiKey)
        } catch (closed: IllegalStateException) {
            handleDbClosed("AssistantTurn.dbClosed", closed)
            _uiState.update { it.copy(hasKey = false) }
        } catch (io: IOException) {
            handleIoError("AssistantTurn", io)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Turn-level safety net: the screen must never crash; every failure ends in a
            // user-visible error kind and the throwable is logged in full.
            logger.e("AssistantTurn.error", "Turn failed unexpectedly", error)
            _uiState.update { it.copy(busy = null, error = AssistantErrorKind.SERVER, errorDetail = null, failure = null) }
        }
    }

    /**
     * Best-effort final flush of the debounced settings save. Detached from the
     * cancelled scope so a quick exit cannot lose the last edit.
     */
    fun dispose() {
        val settings = _uiState.value.settings
        scope.launch(NonCancellable + ioDispatcher) {
            tryPersistSettings("AssistantViewModel.dispose", settings)
        }
        scope.cancel()
    }

    /**
     * Runs one chat call on the IO dispatcher — the transport blocks on a socket, so it
     * must never run on the main thread (NetworkOnMainThreadException otherwise).
     *
     * It goes to the address the assistant is configured for, not to a compiled-in one: verifying an
     * endpoint and then asking the question at a different host would make the check meaningless.
     */
    private suspend fun callChat(
        model: String,
        messages: List<ChatTurn>,
    ): ChatReply =
        withContext(ioDispatcher) {
            endpointClient(_uiState.value.baseUrl).chat(apiKey, sessionId, model, messages)
        }

    /** The client for one endpoint address, so every call reaches the host the user actually entered. */
    private fun endpointClient(baseUrl: String): AssistantEndpointClient = endpointClientFactory(baseUrl)

    /** Logs one provider reply with its token/finish accounting — used by all three call sites. */
    private fun logModelReply(
        round: Int,
        call: String,
        reply: ChatReply,
    ) {
        logger.i(
            "AssistantTurn.modelReply",
            "Model reply received",
            mapOf(
                "round" to round,
                "call" to call,
                "finish" to reply.finishReason.ifEmpty { "-" },
                "promptTokens" to reply.promptTokens,
                "completionTokens" to reply.completionTokens,
                "replyChars" to reply.content.length,
            ),
        )
    }

    /**
     * Removes the stored provider key and returns to the setup surface — the feature is
     * fully off again (no key = no call path). The delete runs under [NonCancellable]
     * because the caller navigates back immediately, which cancels this scope; and on any
     * failure the state stops claiming a key instead of pretending the removal worked.
     */
    fun removeProviderKey() {
        // Cancel the pending debounced save first so the delete below is the last write to
        // land (it could also fire after the key row is deleted).
        saveJob?.cancel()
        scope.launch {
            val removed =
                try {
                    withContext(NonCancellable) {
                        // Deletes the key, the model row and the reconfigure flag in ONE
                        // transaction — no follow-up write needed.
                        settingsStore.clearProviderConfiguration()
                    }
                    true
                } catch (closed: IllegalStateException) {
                    logger.e(
                        "AssistantViewModel.removeProviderKey",
                        "Key removal attempted while DB session closed",
                        closed,
                    )
                    false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.e("AssistantViewModel.removeProviderKey", "Key removal failed", error)
                    false
                }
            if (removed) {
                apiKey = ""
                persistedProvider = null
                persistedBaseUrl = ""
                conversation.clear()
                _uiState.update {
                    it.copy(
                        hasKey = false,
                        provider = null,
                        baseUrl = "",
                        verifying = false,
                        verified = false,
                        keyRemovalPending = false,
                        keyDraft = "",
                        keyInvalid = false,
                        messages = emptyList(),
                        settings = it.settings.copy(model = ""),
                        error = null,
                        failure = null,
                    )
                }
                logger.i("AssistantViewModel.removeProviderKey", "Provider key removed; assistant disabled")
            } else {
                _uiState.update { it.copy(error = AssistantErrorKind.DB_LOCKED, failure = null) }
            }
        }
    }

    /**
     * Logs and applies the shared DB-session-closed failure state (a locked/closed session
     * blocks every assistant path; each caller only differs in whether it also clears the
     * in-memory key).
     */
    private fun handleDbClosed(
        tag: String,
        closed: IllegalStateException,
        resetKey: Boolean = true,
    ) {
        logger.e(tag, "DB session closed", closed)
        if (resetKey) apiKey = ""
        // A locked database is not a connection failure, so there is no [AssistantError] for it:
        // the coarse kind is the whole story and a finer value would misdescribe it.
        _uiState.update { it.copy(busy = null, error = AssistantErrorKind.DB_LOCKED, failure = null) }
    }

    /** Best-effort settings persist; true when the write landed. Failure is logged, never silently dropped. */
    private suspend fun tryPersistSettings(
        tag: String,
        settings: AssistantSettings,
    ): Boolean =
        runCatching { settingsStore.save(settings) }
            .onFailure { error -> logger.e(tag, "Settings save failed", error) }
            .isSuccess

    /** Replaces every occurrence of the given (non-empty) secret values in [text]. Internal for direct test pinning. */
    internal fun redactSecrets(
        text: String,
        vararg secrets: String,
    ): String {
        var redacted = text
        secrets.filter { it.isNotEmpty() }.forEach { secret -> redacted = redacted.replace(secret, "<redacted>") }
        return redacted
    }

    /**
     * Logs one provider failure. The provider body can echo the submitted key, so the
     * detail is redacted (REDACT then truncate) before it reaches the log, and the
     * throwable passed to the logger carries the redacted text too (the platform logger writes
     * `error.message`); a rejected key never logs the body at all.
     */
    private fun handleHttpError(
        tag: String,
        http: AssistantHttpException,
        submittedKey: String,
    ) {
        val rejected = http.kind == AssistantErrorKind.KEY_REJECTED
        val safeDetail =
            if (rejected) {
                null
            } else {
                redactSecrets(http.detail, submittedKey, apiKey).take(300)
            }
        val error: Throwable? = if (safeDetail == null) null else AssistantHttpException(http.kind, safeDetail)
        logger.e(
            "$tag.http",
            "Provider error",
            error,
            mapOf(
                "kind" to http.kind.name,
                // The precise classification and its status, so the local trace says which failure
                // this really was even though the banner still carries the coarse kind.
                "reason" to (http.error?.reason ?: "-"),
                "httpStatus" to (http.error?.httpStatus ?: "-"),
                "detailChars" to http.detail.length,
                "detail" to safeDetail,
            ),
        )
        _uiState.update {
            it.copy(
                busy = null,
                error = http.kind,
                // The truthful classification the client already made, carried through instead of
                // being thrown away here. Null only for an exception built before the field existed.
                failure = http.error,
                // Provider-side failures carry the provider's own words — redacted and
                // truncated exactly like the log channel, so the screen cannot echo the key.
                errorDetail = if (rejected) null else safeDetail,
                keyInvalid = rejected,
            )
        }
    }

    private fun handleIoError(
        tag: String,
        io: IOException,
    ) {
        logger.e("$tag.io", "Network failure", io)
        // An IO failure never reached an HTTP answer, so it is a transport failure by definition —
        // no status exists to record, which is the case [AssistantError.Transport] describes.
        _uiState.update {
            it.copy(busy = null, error = AssistantErrorKind.NETWORK, failure = AssistantError.Transport())
        }
    }

    /**
     * Reads the live schema for the prompt, in the shape the prompt has always had: a `-- <table>`
     * header above each `CREATE` statement, blocks blank-line separated.
     *
     * A refusal is not quietly an empty schema. `DATABASE_NOT_OPEN` is the session-closed state the
     * screen already renders, so it is raised as [IllegalStateException] and travels the existing path
     * to `DB_LOCKED`; every other refusal is logged with its reason and answered with no tables,
     * because a model told "there are no tables" asks a better question than one told nothing.
     */
    private suspend fun readSchemaText(): String {
        val result = withContext(ioDispatcher) { schemaReader.readSchema() }
        return when (result) {
            is DatabaseSchemaResult.Tables -> {
                result.tables.joinToString("\n\n") { table -> "-- ${table.name}\n${table.ddl}" }
            }

            is DatabaseSchemaResult.Refused -> {
                if (result.reason == ReadOnlyRefusal.DATABASE_NOT_OPEN) {
                    throw IllegalStateException(result.detail)
                }
                logger.w(
                    "AssistantTurn.schemaRefused",
                    "Schema read refused; the model is told the database has no tables",
                    mapOf("reason" to result.reason.name),
                )
                ""
            }
        }
    }

    /**
     * Runs one guard-approved statement through the read-only port and renders the answer for the model.
     *
     * A refusal reaches the model as an error, never as an empty table, so the model can recover from
     * it. `DATABASE_NOT_OPEN` is the one exception: that is the locked-session state the screen renders,
     * so it is raised and the callers above leave the chat surface.
     */
    private suspend fun runReadOnlyQuery(
        sql: String,
        maxRows: Int,
    ): ToolResult {
        val outcome = withContext(ioDispatcher) { query.query(sql, maxRows) }
        return when (outcome) {
            is ReadOnlyQueryOutcome.Rows -> {
                ToolResult(outcome.rows.size, renderRowsForModel(outcome.columns, outcome.rows, outcome.truncated))
            }

            is ReadOnlyQueryOutcome.Refused -> {
                if (outcome.reason == ReadOnlyRefusal.DATABASE_NOT_OPEN) {
                    throw IllegalStateException(outcome.detail)
                }
                logger.w(
                    "AssistantTurn.queryRefused",
                    "Statement refused by the read-only handle",
                    mapOf("reason" to outcome.reason.name),
                )
                ToolResult(0, "SQL_ERROR: ${outcome.reason.name}: ${outcome.detail}")
            }
        }
    }

    /**
     * Renders rows for the model: a header of column names, pipe-separated clipped cells, and a NOTE
     * whenever the row cap or the character budget cut the result short, so the model is told the
     * answer is partial instead of reading a short table as the whole one.
     */
    private fun renderRowsForModel(
        columns: List<String>,
        rows: List<List<String?>>,
        truncated: Boolean,
    ): String {
        if (columns.isEmpty()) return "SQL_ERROR: statement returned no columns"
        val lines = mutableListOf(columns.joinToString(" | "))
        rows.forEach { row -> lines += row.joinToString(" | ") { cell -> clipCell(cell ?: "NULL") } }
        var text = lines.joinToString("\n")
        if (truncated) {
            text += "\n[NOTE: result truncated at ${rows.size} rows - aggregate in SQL " +
                "(GROUP BY / SUM / COUNT) to cover the full range]"
        }
        if (text.length > AssistantDefaults.MAX_TOOL_RESULT_CHARS) {
            text = text.take(AssistantDefaults.MAX_TOOL_RESULT_CHARS) +
                "\n[NOTE: result clipped at ${AssistantDefaults.MAX_TOOL_RESULT_CHARS} chars - " +
                "aggregate in SQL (GROUP BY / SUM / COUNT) to cover the full range]"
        }
        return text
    }

    /** Clips one cell to the assistant's cell budget, marking whatever was cut. */
    private fun clipCell(value: String): String =
        if (value.length <= AssistantDefaults.CELL_CLIP) value else value.take(AssistantDefaults.CELL_CLIP) + "\u2026"

    private companion object {
        private const val SAVE_DEBOUNCE_MS = 400L
    }
}

/**
 * True once the assistant is fully configured — a stored key AND a user-picked model. The chat
 * surface is reachable only in this state, so the predicate lives in one place instead of being
 * re-derived by every gate. Deterministic despite a possibly stale model row: the assistant
 * ViewModel blanks the model during refresh while a reconfigure is pending, so this never
 * reports configured mid-reconfigure.
 *
 * It lives here rather than in the shared assistant core because its receiver [AssistantUiState]
 * is a ViewModel type a shared module cannot name.
 */
val AssistantUiState.isConfigured: Boolean
    get() = (hasKey || provider?.requiresKey == false) && settings.model.isNotEmpty()

/** One answered statement, as the turn loop needs it: rows returned, and the text the model reads. */
private data class ToolResult(
    val rowCount: Int,
    val text: String,
)

/**
 * Builds the assistant screen's ViewModel from the platform's own collaborators.
 *
 * Every one of them is supplied by the caller, for the reason
 * [io.payanam.presentation.database.passphraseChangeViewModel] is written that way: the store, the schema
 * reader and the read-only query are platform implementations of shared contracts, and a factory resolving
 * them from a `LocalDataLayer` would invent a second installation seam for this one screen.
 */
fun assistantViewModel(
    settingsStore: AssistantConfigurationStore,
    schemaReader: DatabaseSchemaReader,
    query: ReadOnlyDatabaseQuery,
    client: OpenCodeGoClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): AssistantViewModel =
    AssistantViewModel(
        settingsStore = settingsStore,
        schemaReader = schemaReader,
        query = query,
        client = client,
        scope = scope,
    )

/**
 * What the ViewModel needs from one configured endpoint: list the models it serves, prove the credential,
 * and run a turn. It is the same three operations [OpenCodeGoClient] offers, named once so a client built
 * for an arbitrary address can stand in for it.
 */
interface AssistantEndpointClient {
    /** The model ids this endpoint serves, excluding train-on-data families. */
    fun fetchModels(
        key: String,
        session: String,
    ): List<String>

    /** Proves [key] with one minimal chat round-trip on [model]. */
    fun validateKey(
        key: String,
        session: String,
        model: String,
    )

    /** Runs one chat completion turn. */
    fun chat(
        key: String,
        session: String,
        model: String,
        messages: List<ChatTurn>,
    ): ChatReply
}

/** The shipped endpoint's client, exactly as the platform supplied it. */
private class InjectedEndpointClient(
    private val client: OpenCodeGoClient,
) : AssistantEndpointClient {
    override fun fetchModels(
        key: String,
        session: String,
    ): List<String> = client.fetchModels(key, session)

    override fun validateKey(
        key: String,
        session: String,
        model: String,
    ) {
        client.validateKey(key, session, model)
    }

    override fun chat(
        key: String,
        session: String,
        model: String,
        messages: List<ChatTurn>,
    ): ChatReply = client.chat(key, session, model, messages)
}

/** A client built for one arbitrary address the user entered. */
private class AddressEndpointClient(
    private val client: OpenAiCompatibleClient,
) : AssistantEndpointClient {
    override fun fetchModels(
        key: String,
        session: String,
    ): List<String> = client.fetchModels(key, session)

    override fun validateKey(
        key: String,
        session: String,
        model: String,
    ) {
        client.validateKey(key, session, model)
    }

    override fun chat(
        key: String,
        session: String,
        model: String,
        messages: List<ChatTurn>,
    ): ChatReply = client.chat(key, session, model, messages)
}

/**
 * The client for one endpoint address.
 *
 * The address this app shipped with keeps using [injected], so the behaviour, the transport and
 * every existing test of that endpoint are untouched. Any other address gets an
 * [OpenAiCompatibleClient] of its own over the same dependency-free transport, because the shipped client
 * carries its own base URL — reusing it there would check, or ask, the wrong host while reporting success
 * for the one on screen.
 */
private fun endpointClientFor(
    injected: OpenCodeGoClient,
    baseUrl: String,
): AssistantEndpointClient =
    if (baseUrl.trim().trimEnd('/') == AssistantDefaults.BASE_URL) {
        InjectedEndpointClient(injected)
    } else {
        AddressEndpointClient(OpenAiCompatibleClient(UrlConnectionTransport(), baseUrl))
    }
