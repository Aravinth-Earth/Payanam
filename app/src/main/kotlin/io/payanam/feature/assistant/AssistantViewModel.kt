//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress(
    "TooGenericExceptionCaught",
    "SwallowedException",
    "MagicNumber",
    "TooManyFunctions",
)

package io.payanam.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import javax.inject.Inject

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
@HiltViewModel
class AssistantViewModel
    @Inject
    constructor(
        private val settingsStore: AssistantSettingsStore,
        private val schemaProvider: DbSchemaProvider,
        private val queryTool: DbQueryTool,
        private val client: OpenCodeGoClient,
    ) : ViewModel() {
        private val logger = UnifiedLogger.getInstance()
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
            viewModelScope.launch {
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
        private fun resolveKey(): String = _uiState.value.keyDraft.trim().ifEmpty { apiKey }

        /**
         * Validates the pasted key with a real chat call, then loads the selectable models.
         * A key rejection is surfaced as [AssistantErrorKind.KEY_REJECTED].
         */
        fun loadModels() {
            viewModelScope.launch {
                val usedDraft = _uiState.value.keyDraft.trim().isNotEmpty()
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
                    val models = withContext(Dispatchers.IO) { client.fetchModels(key, sessionId) }
                    if (models.isEmpty()) {
                        logger.w("AssistantViewModel.loadModels", "Provider returned an empty model list")
                        _uiState.update {
                            it.copy(modelsLoading = false, error = AssistantErrorKind.SERVER, errorDetail = null)
                        }
                        return@launch
                    }
                    // A real chat call is the only honest key check (the model list is unauthenticated).
                    withContext(Dispatchers.IO) { client.validateKey(key, sessionId, models.first()) }
                    logger.i("AssistantViewModel.loadModels", "Key validated with a real chat call")
                    _uiState.update { state ->
                        state.copy(
                            modelsLoading = false,
                            modelOptions = models,
                            settings = state.settings.copy(
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
            if (_uiState.value.settings.model.isEmpty()) {
                logger.w("AssistantViewModel.saveAndStart", "Save attempted without a selected model")
                _uiState.update { it.copy(error = AssistantErrorKind.NO_MODEL) }
                return
            }
            viewModelScope.launch {
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
            viewModelScope.launch {
                withContext(NonCancellable) {
                    // runCatching sits INSIDE NonCancellable: a throw must never reach the
                    // default handler, and the write must outlive the navigation pop.
                    runCatching { settingsStore.beginReconfigure() }
                        .onSuccess {
                            logger.i("AssistantViewModel.changeProvider", "Reconfigure requested; chat disabled until Save & start")
                        }
                        .onFailure { error ->
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
                viewModelScope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    tryPersistSettings("AssistantViewModel.updateSettings", updated)
                }
        }

        /** Flushes any pending debounced save (called when leaving the settings screen). */
        fun flushSettings() {
            saveJob?.cancel()
            val settings = _uiState.value.settings
            viewModelScope.launch {
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
            viewModelScope.launch {
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
                    messages = it.messages + AssistantChatMessage(
                        role = AssistantChatMessage.ROLE_USER,
                        text = text,
                        id = ++messageSeq,
                    ),
                    error = null,
                )
            }
            viewModelScope.launch { runTurn(text) }
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
                val schema = schemaProvider.schemaText()
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
                            val outcome = queryTool.execute(guarded.sql, settings.maxRows)
                            rowsTotal += outcome.rowCount
                            messages += ChatTurn("assistant", reply.content)
                            messages += ChatTurn("user", "TOOL_RESULT:\n" + outcome.renderForModel())
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
                        messages = state.messages + AssistantChatMessage(
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
            } catch (error: Exception) {
                // Turn-level safety net: the screen must never crash; every failure ends in a
                // user-visible error kind and the throwable is logged in full.
                logger.e("AssistantTurn.error", "Turn failed unexpectedly", error)
                _uiState.update { it.copy(busy = null, error = AssistantErrorKind.SERVER, errorDetail = null) }
            }
        }

        /**
         * Best-effort final flush of the debounced settings save. Detached from the
         * cancelled viewModelScope so a quick exit cannot lose the last edit.
         */
        override fun onCleared() {
            super.onCleared()
            val settings = _uiState.value.settings
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                tryPersistSettings("AssistantViewModel.onCleared", settings)
            }
        }

        /**
         * Runs one chat call on the IO dispatcher — the transport blocks on a socket, so it
         * must never run on the main thread (NetworkOnMainThreadException otherwise).
         */
        private suspend fun callChat(model: String, messages: List<ChatTurn>): ChatReply =
            withContext(Dispatchers.IO) { client.chat(apiKey, sessionId, model, messages) }

        /** Logs one provider reply with its token/finish accounting — used by all three call sites. */
        private fun logModelReply(round: Int, call: String, reply: ChatReply) {
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
            viewModelScope.launch {
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
        private fun handleDbClosed(tag: String, closed: IllegalStateException, resetKey: Boolean = true) {
            logger.e(tag, "DB session closed", closed)
            if (resetKey) apiKey = ""
            _uiState.update { it.copy(busy = null, error = AssistantErrorKind.DB_LOCKED) }
        }

        /** Best-effort settings persist; true when the write landed. Failure is logged, never silently dropped. */
        private suspend fun tryPersistSettings(tag: String, settings: AssistantSettings): Boolean =
            runCatching { settingsStore.save(settings) }
                .onFailure { error -> logger.e(tag, "Settings save failed", error) }
                .isSuccess

        /** Replaces every occurrence of the given (non-empty) secret values in [text]. Internal for direct test pinning. */
        internal fun redactSecrets(text: String, vararg secrets: String): String {
            var redacted = text
            secrets.filter { it.isNotEmpty() }.forEach { secret -> redacted = redacted.replace(secret, "<redacted>") }
            return redacted
        }

        /**
         * Logs one provider failure. The provider body can echo the submitted key, so the
         * detail is redacted (REDACT then truncate) before it reaches the log, and the
         * throwable passed to the logger carries the redacted text too (UnifiedLogger writes
         * `error.message`); a rejected key never logs the body at all.
         */
        private fun handleHttpError(tag: String, http: AssistantHttpException, submittedKey: String) {
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

        private fun handleIoError(tag: String, io: IOException) {
            logger.e("$tag.io", "Network failure", io)
            _uiState.update { it.copy(busy = null, error = AssistantErrorKind.NETWORK) }
        }

        private companion object {
            private const val SAVE_DEBOUNCE_MS = 400L
        }
    }
