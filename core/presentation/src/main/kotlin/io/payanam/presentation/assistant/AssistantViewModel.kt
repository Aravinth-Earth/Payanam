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
import io.payanam.domain.assistant.AssistantErrorKind
import io.payanam.domain.assistant.AssistantHttpException
import io.payanam.domain.assistant.AssistantProtocol
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.domain.assistant.AssistantSystemPrompt
import io.payanam.domain.assistant.ChatReply
import io.payanam.domain.assistant.ChatTurn
import io.payanam.domain.assistant.GuardResult
import io.payanam.domain.assistant.OpenCodeGoClient
import io.payanam.domain.assistant.ReadOnlySqlGuard
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
    val keyDraft: String = "",
    val keyInvalid: Boolean = false,
    val modelsLoading: Boolean = false,
    val modelOptions: List<String> = emptyList(),
    val settings: AssistantSettings = AssistantSettings(),
    val messages: List<AssistantChatMessage> = emptyList(),
    val busy: AssistantBusy? = null,
    val error: AssistantErrorKind? = null,
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
) {
    private val logger = AppLogger.current
    private val sessionId = UUID.randomUUID().toString()
    private var apiKey = ""
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

    /** Loads the stored key + settings + reconfigure flag (DB must be open). */
    fun refresh() {
        scope.launch {
            try {
                val config = settingsStore.loadConfig()
                // No key ⇒ no model; a pending reconfigure ⇒ no model either: the stored
                // model must never surface before the user completes setup again
                // ("Save & start" / key removal are the only paths that clear the flag).
                val settings =
                    if (config.apiKey.isEmpty() || config.setupRequired) {
                        config.settings.copy(model = "")
                    } else {
                        config.settings
                    }
                apiKey = config.apiKey
                _uiState.update {
                    it.copy(
                        loading = false,
                        hasKey = config.apiKey.isNotEmpty(),
                        settings = settings,
                        noticeVisible = !settings.noticeShown,
                    )
                }
                logger.d(
                    "AssistantViewModel.refresh",
                    "Assistant state loaded",
                    mapOf(
                        "hasKey" to config.apiKey.isNotEmpty(),
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
                _uiState.update { it.copy(loading = false, hasKey = false) }
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
            if (key.isEmpty()) {
                logger.d("AssistantViewModel.loadModels", "Load models skipped: no key available", mapOf("source" to source))
                return@launch
            }
            _uiState.update { it.copy(modelsLoading = true, error = null, keyInvalid = false) }
            val logData = mutableMapOf<String, Any?>("keyLength" to key.length, "source" to source)
            if (usedDraft) logData["fingerprint"] = settingsStore.fingerprint(key)
            logger.i("AssistantViewModel.loadModels", "Validating key and loading models", logData)
            try {
                val models = withContext(ioDispatcher) { client.fetchModels(key, sessionId) }
                if (models.isEmpty()) {
                    logger.w("AssistantViewModel.loadModels", "Provider returned an empty model list")
                    _uiState.update {
                        it.copy(modelsLoading = false, error = AssistantErrorKind.SERVER, errorDetail = null)
                    }
                    return@launch
                }
                // A real chat call is the only honest key check (the model list is unauthenticated).
                withContext(ioDispatcher) { client.validateKey(key, sessionId, models.first()) }
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

    /** Persists key + settings and switches to the chat surface (requires a picked model). */
    fun saveAndStart() {
        val key = resolveKey()
        if (key.isEmpty()) return
        if (_uiState.value.settings.model
                .isEmpty()
        ) {
            logger.w("AssistantViewModel.saveAndStart", "Save attempted without a selected model")
            _uiState.update { it.copy(error = AssistantErrorKind.NO_MODEL) }
            return
        }
        scope.launch {
            try {
                val settings = _uiState.value.settings
                // Key + settings persist in ONE transaction; a partial write here would leave a
                // stored key with a stale/empty model across the next launch.
                settingsStore.saveAll(if (key != apiKey) key else null, settings)
                apiKey = key
                _uiState.update {
                    it.copy(
                        hasKey = true,
                        keyInvalid = false,
                        keyDraft = "",
                    )
                }
                logger.i(
                    "AssistantViewModel.saveAndStart",
                    "Assistant configured and ready",
                    mapOf("model" to settings.model),
                )
            } catch (closed: IllegalStateException) {
                apiKey = ""
                handleDbClosed("AssistantViewModel.saveAndStart", closed, resetKey = false)
            }
        }
    }

    /**
     * Reconfigures the provider: flags a pending setup (persisted so the assistant
     * destination re-reads it on entry and resolves to the setup surface) and clears the
     * in-memory model selection. The stored key is kept — "Remove key" is the destructive
     * path — and neutral writers never touch the model row.
     */
    fun changeProvider() {
        // Cancel any in-flight debounced neutral save so the reconfigure write below is the
        // last persistence to land; neutral saves cannot touch the model row anyway.
        saveJob?.cancel()
        _uiState.update {
            it.copy(
                modelOptions = emptyList(),
                settings = it.settings.copy(model = ""),
                error = null,
            )
        }
        scope.launch {
            withContext(NonCancellable) {
                // runCatching sits INSIDE NonCancellable: a throw must never reach the
                // default handler, and the write must outlive the navigation pop.
                runCatching { settingsStore.beginReconfigure() }
                    .onSuccess {
                        logger.i("AssistantViewModel.changeProvider", "Reconfigure requested; chat disabled until Save & start")
                    }.onFailure { error ->
                        logger.e("AssistantViewModel.changeProvider", "Reconfigure persist failed", error)
                    }
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
        _uiState.update { it.copy(error = null, errorDetail = null) }
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
        if (!state.hasKey) {
            _uiState.update { it.copy(error = AssistantErrorKind.KEY_REJECTED) }
            return
        }
        if (state.settings.model.isEmpty()) {
            _uiState.update { it.copy(error = AssistantErrorKind.NO_MODEL) }
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
            _uiState.update { it.copy(busy = null, error = AssistantErrorKind.SERVER, errorDetail = null) }
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
     */
    private suspend fun callChat(
        model: String,
        messages: List<ChatTurn>,
    ): ChatReply = withContext(ioDispatcher) { client.chat(apiKey, sessionId, model, messages) }

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
                conversation.clear()
                _uiState.update {
                    it.copy(
                        hasKey = false,
                        keyDraft = "",
                        keyInvalid = false,
                        messages = emptyList(),
                        settings = it.settings.copy(model = ""),
                        error = null,
                    )
                }
                logger.i("AssistantViewModel.removeProviderKey", "Provider key removed; assistant disabled")
            } else {
                _uiState.update { it.copy(error = AssistantErrorKind.DB_LOCKED) }
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
        _uiState.update { it.copy(busy = null, error = AssistantErrorKind.DB_LOCKED) }
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
            mapOf("kind" to http.kind.name, "detailChars" to http.detail.length, "detail" to safeDetail),
        )
        _uiState.update {
            it.copy(
                busy = null,
                error = http.kind,
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
        _uiState.update { it.copy(busy = null, error = AssistantErrorKind.NETWORK) }
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
    get() = hasKey && settings.model.isNotEmpty()

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
