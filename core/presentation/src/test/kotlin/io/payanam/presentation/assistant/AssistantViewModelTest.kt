//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.assistant

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.assistant.AssistantChatMessage
import io.payanam.domain.assistant.AssistantConnection
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantError
import io.payanam.domain.assistant.AssistantErrorKind
import io.payanam.domain.assistant.AssistantProvider
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.domain.assistant.ChatReply
import io.payanam.domain.assistant.ChatTurn
import io.payanam.domain.assistant.HttpResult
import io.payanam.domain.assistant.HttpTransport
import io.payanam.domain.assistant.OpenAiCompatibleClient
import io.payanam.domain.assistant.OpenCodeGoClient
import io.payanam.domain.port.AssistantConfigurationStore
import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.DatabaseSchemaResult
import io.payanam.domain.port.DatabaseTableSchema
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import io.payanam.domain.port.ReadOnlyQueryOutcome
import io.payanam.domain.port.ReadOnlyRefusal
import io.payanam.domain.port.StoredAssistantConfig
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Pins the shared assistant ViewModel's half of the "Change provider or key" contract, and the two port
 * seams the promotion added, on the module's own fakes and plain JVM.
 *
 * No Robolectric, no Mockito, no Android Context. The provider client is the REAL
 * [io.payanam.domain.assistant.OpenCodeGoClient] over a scripted transport rather than a mock, because the
 * cases that matter here are about what the model is TOLD - a refused read, a truncated result, a clipped
 * cell - and a mocked client is handed the answer, so it can never fail for the reason production does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // ------------------------------------------------------------------ fakes

    private class FakeStore : AssistantConfigurationStore {
        var config = StoredAssistantConfig("", AssistantSettings(), false)
        val saved = mutableListOf<AssistantSettings>()
        val savedAllKeys = mutableListOf<String?>()

        /** Every connection write, as the pair the ViewModel asked for — so "what was written" is assertable. */
        val savedConnections = mutableListOf<Pair<AssistantProvider, String>>()
        var reconfigured = false
        var noticeAcknowledged = false
        var cleared = false

        /** Counted separately from [cleared] so "the key was dropped" is distinct from "a connection was written". */
        var clearCount = 0
        var loadFailure: Throwable? = null
        var saveFailure: Throwable? = null
        var saveAllFailure: Throwable? = null
        var clearFailure: Throwable? = null
        var beginReconfigureFailure: Throwable? = null
        var markNoticeFailure: Throwable? = null

        override suspend fun loadConfig(): StoredAssistantConfig {
            loadFailure?.let { throw it }
            return config
        }

        override suspend fun beginReconfigure() {
            beginReconfigureFailure?.let { throw it }
            reconfigured = true
        }

        override suspend fun save(settings: AssistantSettings) {
            saveFailure?.let { throw it }
            saved += settings
        }

        override suspend fun saveAll(
            apiKey: String?,
            settings: AssistantSettings,
        ) {
            saveAllFailure?.let { throw it }
            savedAllKeys += apiKey
            saved += settings
        }

        override suspend fun saveConnection(
            provider: AssistantProvider,
            baseUrl: String,
        ) {
            savedConnections += provider to baseUrl
        }

        override suspend fun markNoticeShown() {
            markNoticeFailure?.let { throw it }
            noticeAcknowledged = true
        }

        override suspend fun clearProviderConfiguration() {
            clearFailure?.let { throw it }
            cleared = true
            clearCount++
        }

        override fun fingerprint(value: String): String = if (value.isEmpty()) "-" else "fp"
    }

    private class FakeSchema(
        var result: DatabaseSchemaResult,
    ) : DatabaseSchemaReader {
        override fun readSchema(): DatabaseSchemaResult = result
    }

    private class ExplodingSchema : DatabaseSchemaReader {
        override fun readSchema(): DatabaseSchemaResult = throw IllegalArgumentException("boom")
    }

    private class FakeQuery(
        var outcome: ReadOnlyQueryOutcome,
    ) : ReadOnlyDatabaseQuery {
        val asked = mutableListOf<Pair<String, Int>>()

        override fun query(
            sql: String,
            maxRows: Int,
        ): ReadOnlyQueryOutcome {
            asked += sql to maxRows
            return outcome
        }
    }

    /** Scripts the provider: a queue of chat bodies, plus the models list and any forced status code. */
    private class ScriptedTransport : HttpTransport {
        val chatBodies = ArrayDeque<String>()
        val chatFinishes = ArrayDeque<String>() // popped in step with chatBodies; default "stop"
        var modelsBody = """{"data":[{"id":"m2"},{"id":"m1"}]}"""
        var postStatus = 200
        var modelsStatus = 200
        val postBodies = mutableListOf<String>()
        val postKeys = mutableListOf<String>()

        /** The address each POST went to, so "the host on screen was called" is assertable. */
        val postUrls = mutableListOf<String>()

        /** Chat POSTs only, so a count is not inflated by key validation. */
        var chatPosts = 0

        /** The finish reason of each served chat reply, in order. */
        val servedFinishes = mutableListOf<String>()

        /** The content of each served chat reply, in order. */
        val servedContents = mutableListOf<String>()

        /** When set, every chat POST parks on this before answering - the in-flight case. */
        var blockOnEveryChat: java.util.concurrent.CountDownLatch? = null

        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ) = HttpResult(modelsStatus, modelsBody)

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpResult {
            postBodies += body
            postKeys += headers["Authorization"].orEmpty()
            postUrls += url
            if (postStatus != 200) return HttpResult(postStatus, """{"error":"nope"}""")
            if (url.endsWith("/models")) return HttpResult(modelsStatus, modelsBody)
            val content = chatBodies.removeFirstOrNull() ?: "done"
            val finish = chatFinishes.removeFirstOrNull() ?: "stop"
            chatPosts++
            servedFinishes += finish
            servedContents += content
            blockOnEveryChat?.await()
            return HttpResult(200, chatBody(content, finish))
        }

        /**
         * One provider chat response, built to the shape [OpenCodeGoClient.chat] parses.
         *
         * Assembled with a brace count a reader can check rather than by concatenating raw strings: an
         * unbalanced root here does not fail this fake, it fails every turn-loop case with a parse error
         * that looks like a ViewModel bug.
         */
        private fun chatBody(
            content: String,
            finish: String,
        ): String =
            "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "},\"finish_reason\":\"$finish\"}]," +
                "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5}}"

        private fun quote(value: String): String {
            val out = StringBuilder("\"")
            value.forEach { ch ->
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    else -> out.append(ch)
                }
            }
            return out.append("\"").toString()
        }

        /**
         * The request body with the client's JSON string escapes turned back into real characters.
         *
         * Written as a scan rather than a chain of `replace` calls because the escapes are themselves
         * two-character sequences, and a chain that unescapes the quote before the backslash rewrites a
         * different sequence than the one it means to.
         */
        private fun decoded(index: Int): String {
            val raw = postBodies[index]
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        'n' -> {
                            out.append('\n')
                            i += 2
                        }

                        't' -> {
                            out.append('\t')
                            i += 2
                        }

                        '"' -> {
                            out.append('"')
                            i += 2
                        }

                        '\\' -> {
                            out.append('\\')
                            i += 2
                        }

                        else -> {
                            out.append(ch)
                            i++
                        }
                    }
                } else {
                    out.append(ch)
                    i++
                }
            }
            return out.toString()
        }

        /** The text the model was shown for the last tool result - the payload the screen built, verbatim. */
        fun lastToolResult(): String = decoded(postBodies.lastIndex).substringAfter("TOOL_RESULT:")

        /** The system prompt of the first call, which is where the schema text goes. */
        fun lastSystemPrompt(): String = decoded(0).substringAfter("\"content\":\"").substringBefore("\",\"role\"")
    }

    /**
     * The client for one arbitrary address, driven by the scripted transport instead of a socket.
     *
     * Production builds exactly this [OpenAiCompatibleClient] for a non-shipped address, over the
     * dependency-free transport; only the transport differs here, so the request shape and the URLs
     * under test are the production ones.
     */
    private class ScriptedAddressClient(
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

    private lateinit var log: RecordingLogger
    private lateinit var store: FakeStore
    private lateinit var schema: FakeSchema
    private lateinit var query: FakeQuery
    private lateinit var transport: ScriptedTransport
    private lateinit var client: OpenCodeGoClient

    /** Records what the ViewModel logs, so a failure caught by its own net is still diagnosable. */
    private class RecordingLogger : io.payanam.shared.logging.UnifiedLogger {
        val lines = mutableListOf<String>()

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            lines += "I $source $message"
        }

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            lines += "D $source $message"
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            lines += "W $source $message"
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            lines += "E $source $message :: ${error?.javaClass?.simpleName}: ${error?.message}"
        }

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            lines += "E $source $message :: ${error?.javaClass?.simpleName}: ${error?.message}"
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        log = RecordingLogger()
        AppLogger.install(log)
        store = FakeStore()
        schema =
            FakeSchema(
                DatabaseSchemaResult.Tables(listOf(DatabaseTableSchema("tasks", "CREATE TABLE tasks (id INTEGER)"))),
            )
        query = FakeQuery(ReadOnlyQueryOutcome.Rows(listOf("id"), listOf(listOf("1")), false))
        transport = ScriptedTransport()
        client = OpenCodeGoClient(transport)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppLogger.resetForTests()
    }

    private fun newViewModel() = AssistantViewModel(store, schema, query, client, CoroutineScope(dispatcher), dispatcher)

    /**
     * The same ViewModel with the endpoint-client factory supplied.
     *
     * Needed only by the cases about an address that is not the shipped one: production builds that
     * client over a real transport, so a test cannot reach it without this seam.
     */
    private fun newViewModel(endpointClientFactory: (String) -> AssistantEndpointClient) =
        AssistantViewModel(
            store,
            schema,
            query,
            client,
            CoroutineScope(dispatcher),
            dispatcher,
            endpointClientFactory,
        )

    private fun rows(
        columns: List<String>,
        rows: List<List<String?>> = emptyList(),
        truncated: Boolean = false,
    ) = ReadOnlyQueryOutcome.Rows(columns, rows, truncated)

    private fun configured(
        model: String = "m1",
        key: String = "stored-key",
    ) {
        store.config = StoredAssistantConfig(key, AssistantSettings(model = model), false)
    }

    private fun sqlReply(sql: String) = transport.chatBodies.addLast("""{"sql":"$sql"}""")

    @Test
    fun `the chat surface is reachable only with a key AND a picked model`() {
        assertThat(AssistantUiState(hasKey = false, settings = AssistantSettings(model = "m1")).isConfigured).isFalse()
        assertThat(AssistantUiState(hasKey = true, settings = AssistantSettings(model = "")).isConfigured).isFalse()
        assertThat(AssistantUiState(hasKey = true, settings = AssistantSettings(model = "m1")).isConfigured).isTrue()
    }

    // ------------------------------------------------------------------ refresh

    @Test
    fun `refresh suppresses the model while reconfigure is pending, even with a stale model row`() =
        runTest(dispatcher) {
            store.config = StoredAssistantConfig("stored-key", AssistantSettings(model = "m1"), true)
            val viewModel = newViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.isConfigured).isFalse()
            assertThat(state.hasKey).isTrue()
            assertThat(state.settings.model).isEmpty()
        }

    @Test
    fun `refresh keeps the model when no setup is pending`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.isConfigured).isTrue()
            assertThat(viewModel.uiState.value.settings.model).isEqualTo("m1")
        }

    @Test
    fun `a closed session during refresh reports DB_LOCKED and drops the key`() =
        runTest(dispatcher) {
            store.loadFailure = IllegalStateException("session closed")
            val viewModel = newViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.error).isEqualTo(AssistantErrorKind.DB_LOCKED)
            assertThat(state.hasKey).isFalse()
            assertThat(state.loading).isFalse()
        }

    // ------------------------------------------------------------------ model loading

    @Test
    fun `loadModels validates with a real chat call and keeps a still-offered model`() =
        runTest(dispatcher) {
            configured(model = "m1")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.loadModels()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.modelOptions).containsExactly("m1", "m2").inOrder()
            // Validation is a POST, which the models GET does not stand in for.
            assertThat(transport.postBodies).isNotEmpty()
            assertThat(state.settings.model).isEqualTo("m1")
            assertThat(state.modelsLoading).isFalse()
        }

    @Test
    fun `a stored model the provider no longer lists is dropped rather than kept`() =
        runTest(dispatcher) {
            configured(model = "gone")
            transport.modelsBody = """{"data":[{"id":"m1"}]}"""
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.loadModels()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.settings.model).isEmpty()
        }

    @Test
    fun `a rejected key is reported without echoing the provider body`() =
        runTest(dispatcher) {
            transport.postStatus = 401
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-secret")
            viewModel.loadModels()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.error).isEqualTo(AssistantErrorKind.KEY_REJECTED)
            assertThat(state.keyInvalid).isTrue()
            assertThat(state.errorDetail).isNull()
            assertThat(state.modelsLoading).isFalse()
        }

    @Test
    fun `a provider failure keeps its own words but never the submitted key`() =
        runTest(dispatcher) {
            transport.postStatus = 403
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-secret")
            viewModel.loadModels()
            advanceUntilIdle()
            val detail = viewModel.uiState.value.errorDetail
            assertThat(detail).isNotNull()
            assertThat(detail).contains("nope")
            assertThat(detail).doesNotContain("sk-secret")
        }

    @Test
    fun `a 429 is carried through as a rate limit, not just a server error`() =
        runTest(dispatcher) {
            // The model list succeeds; the chat round-trip is what the endpoint rate-limits.
            transport.postStatus = 429
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-secret")
            viewModel.loadModels()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            // The fine classification survives into the state, status and all.
            assertThat(state.failure).isInstanceOf(AssistantError.RateLimit::class.java)
            assertThat(state.failure?.reason).isEqualTo(AssistantError.REASON_RATE_LIMIT)
            assertThat(state.failure?.httpStatus).isEqualTo(429)
            // The coarse kind is untouched: every non-401 status still reads as SERVER for the banner.
            assertThat(state.error).isEqualTo(AssistantErrorKind.SERVER)
        }

    @Test
    fun `a 404 is carried through as a missing model, not just a server error`() =
        runTest(dispatcher) {
            transport.postStatus = 404
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-secret")
            viewModel.loadModels()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.failure).isInstanceOf(AssistantError.ModelNotFound::class.java)
            assertThat(state.failure?.reason).isEqualTo(AssistantError.REASON_MODEL_NOT_FOUND)
            assertThat(state.failure?.httpStatus).isEqualTo(404)
            assertThat(state.error).isEqualTo(AssistantErrorKind.SERVER)
        }

    @Test
    fun `an empty model list is a server error, not a silent empty picker`() =
        runTest(dispatcher) {
            configured(model = "")
            transport.modelsBody = """{"data":[]}"""
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.loadModels()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.SERVER)
            assertThat(viewModel.uiState.value.modelsLoading).isFalse()
        }

    @Test
    fun `loadModels with no key available at all does nothing`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.loadModels()
            advanceUntilIdle()
            assertThat(transport.postBodies).isEmpty()
            assertThat(viewModel.uiState.value.modelsLoading).isFalse()
        }

    @Test
    fun `a provider that needs no key can list its models`() =
        runTest(dispatcher) {
            val address = "http://localhost:11434/v1"
            store.config =
                StoredAssistantConfig(
                    apiKey = "",
                    settings = AssistantSettings(model = "m1"),
                    setupRequired = false,
                    connection =
                        AssistantConnection(
                            provider = AssistantProvider.OLLAMA,
                            baseUrl = address,
                            model = "m1",
                        ),
                )
            val viewModel = newViewModel { url -> ScriptedAddressClient(OpenAiCompatibleClient(transport, url)) }
            advanceUntilIdle()
            viewModel.loadModels()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            // The list is fetched, and the key check behind it is the real chat call, not a skip.
            assertThat(state.modelOptions).containsExactly("m1", "m2").inOrder()
            assertThat(state.error).isNull()
            assertThat(state.modelsLoading).isFalse()
            assertThat(transport.postUrls).contains("$address/chat/completions")
            // No credential is presented, because this provider needs none.
            assertThat(transport.postKeys.single()).isEqualTo("Bearer ")
        }

    // ------------------------------------------------------------------ save, reconfigure, key removal

    @Test
    fun `saveAndStart without a picked model is refused and writes nothing`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-new")
            viewModel.saveAndStart()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.NO_MODEL)
            assertThat(store.savedAllKeys).isEmpty()
        }

    @Test
    fun `saveAndStart writes the key and the settings in one call`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-new")
            viewModel.selectModel("m2")
            viewModel.saveAndStart()
            advanceUntilIdle()
            assertThat(store.savedAllKeys).containsExactly("sk-new")
            assertThat(store.saved.last().model).isEqualTo("m2")
            val state = viewModel.uiState.value
            assertThat(state.hasKey).isTrue()
            assertThat(state.keyDraft).isEmpty()
        }

    @Test
    fun `saveAndStart with the unchanged stored key writes no key`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.selectModel("m1")
            viewModel.saveAndStart()
            advanceUntilIdle()
            assertThat(store.savedAllKeys).containsExactly(null)
        }

    @Test
    fun `a closed session while saving reports the locked state`() =
        runTest(dispatcher) {
            store.saveAllFailure = IllegalStateException("session closed")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-new")
            viewModel.selectModel("m2")
            viewModel.saveAndStart()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.DB_LOCKED)
        }

    @Test
    fun `changeProvider flags a pending setup and blanks the model without removing the key`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.changeProvider()
            advanceUntilIdle()
            assertThat(store.reconfigured).isTrue()
            val state = viewModel.uiState.value
            assertThat(state.settings.model).isEmpty()
            assertThat(state.modelOptions).isEmpty()
            assertThat(state.hasKey).isTrue()
        }

    @Test
    fun `a failed reconfigure persist leaves the flag unpersisted and the model still cleared`() =
        runTest(dispatcher) {
            store.beginReconfigureFailure = IllegalStateException("session closed")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.changeProvider()
            advanceUntilIdle()
            assertThat(store.reconfigured).isFalse()
            assertThat(viewModel.uiState.value.settings.model).isEmpty()
        }

    @Test
    fun `removeProviderKey clears the stored configuration and the chat history`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            transport.chatBodies.addLast("four tasks are open")
            viewModel.send("how many tasks?")
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.messages).isNotEmpty()
            viewModel.removeProviderKey()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(store.cleared).isTrue()
            assertThat(state.hasKey).isFalse()
            assertThat(state.messages).isEmpty()
            assertThat(state.settings.model).isEmpty()
        }

    @Test
    fun `a failed key removal stops the screen claiming the key is gone`() =
        runTest(dispatcher) {
            configured()
            store.clearFailure = IllegalStateException("session closed")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.removeProviderKey()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.DB_LOCKED)
            assertThat(viewModel.uiState.value.hasKey).isTrue()
        }

    // ------------------------------------------------------------------ settings persistence

    @Test
    fun `a settings edit is applied immediately and persisted after the debounce`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(maxRows = 250) }
            assertThat(viewModel.uiState.value.settings.maxRows).isEqualTo(250)
            advanceUntilIdle()
            assertThat(store.saved.map { it.maxRows }).containsExactly(250)
        }

    @Test
    fun `only the last of several rapid edits is persisted`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(maxRows = 250) }
            viewModel.updateSettings { it.copy(maxRows = 400) }
            viewModel.updateSettings { it.copy(maxRows = 550) }
            advanceUntilIdle()
            assertThat(store.saved.map { it.maxRows }).containsExactly(550)
        }

    @Test
    fun `an out-of-range edit is sanitized before it reaches the state`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(maxRows = 100_000) }
            assertThat(viewModel.uiState.value.settings.maxRows).isEqualTo(AssistantDefaults.HARD_MAX_ROWS)
        }

    @Test
    fun `a failed neutral save leaves the edited state in place and raises no error`() =
        runTest(dispatcher) {
            store.saveFailure = IllegalStateException("no database")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(maxRows = 250) }
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.settings.maxRows).isEqualTo(250)
            assertThat(viewModel.uiState.value.error).isNull()
        }

    @Test
    fun `acknowledgeNotice hides the notice and records it`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.acknowledgeNotice()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.noticeVisible).isFalse()
            assertThat(store.noticeAcknowledged).isTrue()
        }

    @Test
    fun `a failed notice acknowledgement still hides the notice`() =
        runTest(dispatcher) {
            store.markNoticeFailure = IllegalStateException("no database")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.acknowledgeNotice()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.noticeVisible).isFalse()
        }

    @Test
    fun `dispose flushes the pending settings`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(maxRows = 330) }
            viewModel.dispose()
            advanceUntilIdle()
            assertThat(store.saved.map { it.maxRows }).contains(330)
        }

    @Test
    fun `dismissError clears the kind, the provider detail and the fine classification`() =
        runTest(dispatcher) {
            transport.postStatus = 403
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.updateKeyDraft("sk-secret")
            viewModel.loadModels()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isNotNull()
            assertThat(viewModel.uiState.value.failure).isNotNull()
            viewModel.dismissError()
            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(viewModel.uiState.value.errorDetail).isNull()
            // The fine field goes with the coarse one: a banner that is gone cannot leave a
            // precise reason behind for the next screen state to inherit.
            assertThat(viewModel.uiState.value.failure).isNull()
        }

    // ------------------------------------------------------------------ the turn loop over the ports

    @Test
    fun `a question with no key is refused before any provider call`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.send("hello")
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.KEY_REJECTED)
            assertThat(transport.postBodies).isEmpty()
        }

    @Test
    fun `a question with no picked model is refused before any provider call`() =
        runTest(dispatcher) {
            configured(model = "")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.send("hello")
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.NO_MODEL)
            assertThat(transport.postBodies).isEmpty()
        }

    @Test
    fun `a direct answer is rendered with a receipt and no query`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            transport.chatBodies.addLast("four tasks are open")
            viewModel.send("how many tasks?")
            advanceUntilIdle()
            val reply =
                viewModel.uiState.value.messages
                    .last { it.role == AssistantChatMessage.ROLE_ASSISTANT }
            assertThat(reply.text).isEqualTo("four tasks are open")
            assertThat(reply.model).isEqualTo("m1")
            assertThat(reply.queryCount).isEqualTo(0)
            assertThat(viewModel.uiState.value.busy).isNull()
            assertThat(transport.postKeys).contains("Bearer stored-key")
        }

    @Test
    fun `the schema read from the port reaches the system prompt`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            transport.chatBodies.addLast("an answer")
            viewModel.send("what tables are there?")
            advanceUntilIdle()
            val system = transport.lastSystemPrompt()
            assertThat(system).contains("CREATE TABLE tasks (id INTEGER)")
            assertThat(system).contains("-- tasks")
        }

    @Test
    fun `a model-authored query runs through the port and its rows reach the model`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            query.outcome = rows(listOf("id", "title"), listOf(listOf("1", "ship it"), listOf("2", "review")))
            sqlReply("SELECT id, title FROM tasks")
            transport.chatBodies.addLast("two tasks are open")
            viewModel.send("list the tasks")
            advanceUntilIdle()
            assertThat(query.asked.single().first).contains("SELECT")
            assertThat(query.asked.single().second).isEqualTo(AssistantSettings().maxRows)
            val toolResult = transport.lastToolResult()
            assertThat(toolResult).contains("id | title")
            assertThat(toolResult).contains("ship it")
            val reply =
                viewModel.uiState.value.messages
                    .last { it.role == AssistantChatMessage.ROLE_ASSISTANT }
            assertThat(reply.queryCount).isEqualTo(1)
            assertThat(reply.rowCount).isEqualTo(2)
        }

    @Test
    fun `a refused query is told to the model as an error, never as an empty table`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            query.outcome = ReadOnlyQueryOutcome.Refused(ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED, "handle is writable")
            sqlReply("SELECT id FROM tasks")
            transport.chatBodies.addLast("the database would not answer that")
            viewModel.send("list the tasks")
            advanceUntilIdle()
            val toolResult = transport.lastToolResult()
            assertThat(toolResult).contains("SQL_ERROR")
            assertThat(toolResult).contains("READ_ONLY_NOT_ENFORCED")
            assertThat(toolResult).doesNotContain("no columns")
        }

    @Test
    fun `a closed session surfaces as DB_LOCKED and leaves the chat surface`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            query.outcome = ReadOnlyQueryOutcome.Refused(ReadOnlyRefusal.DATABASE_NOT_OPEN, "no session")
            sqlReply("SELECT id FROM tasks")
            viewModel.send("list the tasks")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.error).isEqualTo(AssistantErrorKind.DB_LOCKED)
            assertThat(state.hasKey).isFalse()
            assertThat(state.busy).isNull()
        }

    @Test
    fun `a refused schema read with the database open tells the model there are no tables`() =
        runTest(dispatcher) {
            configured()
            schema.result = DatabaseSchemaResult.Refused(ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED, "writable")
            val viewModel = newViewModel()
            advanceUntilIdle()
            transport.chatBodies.addLast("an answer")
            viewModel.send("what is in here?")
            advanceUntilIdle()
            assertThat(transport.lastSystemPrompt()).doesNotContain("CREATE TABLE tasks")
            assertThat(viewModel.uiState.value.error).isNull()
        }

    @Test
    fun `a closed session on the schema read reports DB_LOCKED`() =
        runTest(dispatcher) {
            configured()
            schema.result = DatabaseSchemaResult.Refused(ReadOnlyRefusal.DATABASE_NOT_OPEN, "no session")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.send("what is in here?")
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.error).isEqualTo(AssistantErrorKind.DB_LOCKED)
        }

    @Test
    fun `a truncated result tells the model the answer is partial`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            query.outcome = rows(listOf("id"), listOf(listOf("1")), truncated = true)
            sqlReply("SELECT id FROM tasks")
            transport.chatBodies.addLast("at least one")
            viewModel.send("list the tasks")
            advanceUntilIdle()
            assertThat(transport.lastToolResult()).contains("result truncated at 1 rows")
        }

    @Test
    fun `a long cell is clipped with a marker rather than sent whole`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            val huge = "x".repeat(AssistantDefaults.CELL_CLIP + 300)
            query.outcome = rows(listOf("title"), listOf(listOf(huge)))
            sqlReply("SELECT title FROM tasks")
            transport.chatBodies.addLast("one very long title")
            viewModel.send("what are the titles?")
            advanceUntilIdle()
            val toolResult = transport.lastToolResult()
            assertThat(toolResult).doesNotContain(huge)
            assertThat(toolResult).contains("\u2026")
        }

    @Test
    fun `a statement that produced no columns is reported as an error`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            query.outcome = rows(emptyList())
            sqlReply("SELECT id FROM tasks")
            transport.chatBodies.addLast("nothing came back")
            viewModel.send("list the tasks")
            advanceUntilIdle()
            assertThat(transport.lastToolResult()).contains("no columns")
        }

    @Test
    fun `a second question while busy is refused`() =
        runBlocking {
            configured()
            val latch = java.util.concurrent.CountDownLatch(1)
            transport.blockOnEveryChat = latch
            // A single-threaded test dispatcher cannot hold a turn in flight, because the whole turn drains
            // in one go; this case needs a real thread to park on, so the ViewModel's IO work goes to one.
            val viewModel =
                AssistantViewModel(store, schema, query, client, CoroutineScope(Dispatchers.Default), Dispatchers.IO)
            viewModel.uiState.first { !it.loading }
            sqlReply("SELECT id FROM tasks")
            transport.chatBodies.addLast("first answer")
            viewModel.send("one")
            assertThat(viewModel.uiState.first { it.busy != null }).isNotNull()
            viewModel.send("two")
            latch.countDown()
            viewModel.uiState.first { it.busy == null && it.messages.isNotEmpty() }
            // The first turn costs two posts: the model's SQL round, then the answer round. The refused
            // second question costs none, which is what this asserts.
            assertThat(transport.chatPosts).isEqualTo(2)
            assertThat(
                viewModel.uiState.value.messages
                    .count { it.role == AssistantChatMessage.ROLE_USER },
            ).isEqualTo(1)
        }

    @Test
    fun `a blank question is refused`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.send("   ")
            advanceUntilIdle()
            assertThat(transport.postBodies).isEmpty()
            assertThat(viewModel.uiState.value.messages).isEmpty()
        }

    @Test
    fun `a network failure is reported as NETWORK and never crashes the screen`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            val failing =
                AssistantViewModel(store, schema, query, OpenCodeGoClient(UnreachableTransport()), CoroutineScope(dispatcher), dispatcher)
            advanceUntilIdle()
            failing.send("hello")
            advanceUntilIdle()
            assertThat(failing.uiState.value.error).isEqualTo(AssistantErrorKind.NETWORK)
            // The transport failure is recorded finely too: an IO failure never had a status, so the
            // case is Transport and not something invented from a message string.
            assertThat(failing.uiState.value.failure).isInstanceOf(AssistantError.Transport::class.java)
            assertThat(
                failing.uiState.value.failure
                    ?.httpStatus,
            ).isNull()
            assertThat(failing.uiState.value.busy).isNull()
        }

    @Test
    fun `an unexpected turn failure still ends in a user-visible error`() =
        runTest(dispatcher) {
            configured()
            val viewModel = AssistantViewModel(store, ExplodingSchema(), query, client, CoroutineScope(dispatcher), dispatcher)
            advanceUntilIdle()
            viewModel.send("hello")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.error).isEqualTo(AssistantErrorKind.SERVER)
            assertThat(state.busy).isNull()
        }

    @Test
    fun `clearChat drops the history and the rendered messages`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            transport.chatBodies.addLast("an answer")
            viewModel.send("hello")
            advanceUntilIdle()
            viewModel.clearChat()
            assertThat(viewModel.uiState.value.messages).isEmpty()
        }

    @Test
    fun `an answer cut off by the token cap is continued`() =
        runTest(dispatcher) {
            configured()
            val viewModel = newViewModel()
            advanceUntilIdle()
            transport.chatBodies.addLast("the first half ")
            transport.chatFinishes.addLast("length")
            transport.chatBodies.addLast("and the second half")
            viewModel.send("explain")
            advanceUntilIdle()
            val reply =
                viewModel.uiState.value.messages
                    .last { it.role == AssistantChatMessage.ROLE_ASSISTANT }
            assertThat(reply.text).isEqualTo("the first half and the second half")
            assertThat(transport.servedFinishes).containsExactly("length", "stop").inOrder()
        }

    // ---------------------------------------------------------------- endpoint configuration

    /** The endpoint every existing install has: the gateway, with a stored key. */
    private fun storedOpenCode(
        key: String = "stored-key",
        model: String = "m1",
    ) {
        store.config =
            StoredAssistantConfig(
                apiKey = key,
                settings = AssistantSettings(model = model),
                setupRequired = false,
                connection =
                    AssistantConnection(
                        provider = AssistantProvider.OPENCODE,
                        baseUrl = AssistantDefaults.BASE_URL,
                        model = model,
                    ),
            )
    }

    @Test
    fun `refresh loads the stored provider and address`() =
        runTest(dispatcher) {
            store.config =
                StoredAssistantConfig(
                    apiKey = "stored-key",
                    settings = AssistantSettings(model = "m1"),
                    setupRequired = false,
                    connection =
                        AssistantConnection(
                            provider = AssistantProvider.OPENROUTER,
                            baseUrl = "https://openrouter.ai/api/v1",
                            model = "m1",
                        ),
                )
            val viewModel = newViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.provider).isEqualTo(AssistantProvider.OPENROUTER)
            assertThat(state.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
        }

    @Test
    fun `a stored connection with no provider reads as unconfigured`() =
        runTest(dispatcher) {
            // What the store returns when the provider row named nothing it recognised: no provider,
            // no address, no key. Nothing here may be treated as a usable endpoint.
            store.config =
                StoredAssistantConfig(
                    apiKey = "",
                    settings = AssistantSettings(model = "m1"),
                    setupRequired = true,
                    connection = AssistantConnection(provider = null, baseUrl = "", model = "m1"),
                )
            val viewModel = newViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.provider).isNull()
            assertThat(state.baseUrl).isEmpty()
            assertThat(state.isConfigured).isFalse()
        }

    @Test
    fun `choosing a provider fills its default address and a custom one starts blank`() =
        runTest(dispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.selectProvider(AssistantProvider.OPENROUTER)
            assertThat(viewModel.uiState.value.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
            viewModel.selectProvider(AssistantProvider.CUSTOM_LOCAL)
            assertThat(viewModel.uiState.value.baseUrl).isEmpty()
        }

    @Test
    fun `an edited address is kept verbatim and clears the previous verification`() =
        runTest(dispatcher) {
            storedOpenCode()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.verifyConnection()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.verified).isTrue()
            viewModel.updateBaseUrl("  http://192.168.1.20:8080/v1/  ")
            assertThat(viewModel.uiState.value.baseUrl).isEqualTo("  http://192.168.1.20:8080/v1/  ")
            assertThat(viewModel.uiState.value.verified).isFalse()
        }

    @Test
    fun `a failed verification is reported and leaves the endpoint unverified`() =
        runTest(dispatcher) {
            storedOpenCode()
            // The model list is the first call verification makes; a rejected key stops it there.
            transport.modelsStatus = 401
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.verifyConnection()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.verified).isFalse()
            assertThat(state.error).isEqualTo(AssistantErrorKind.KEY_REJECTED)
        }

    @Test
    fun `an endpoint that lists no models is verified, with the list left empty`() =
        runTest(dispatcher) {
            // The Claude case: the endpoint answers, but cannot list its models at all. That is a
            // connection, not a failure — the screen asks for a model name instead of reporting an error.
            storedOpenCode()
            transport.modelsBody = """{"data":[]}"""
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.verifyConnection()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.verified).isTrue()
            assertThat(state.modelOptions).isEmpty()
            assertThat(state.error).isNull()
            assertThat(state.failure).isNull()
            assertThat(state.verifying).isFalse()
        }

    @Test
    fun `changing the provider with a stored key asks before removing it`() =
        runTest(dispatcher) {
            storedOpenCode()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.selectProvider(AssistantProvider.OPENROUTER)
            viewModel.saveAndStart()
            advanceUntilIdle()
            // Nothing is written and nothing is removed until the user says so.
            assertThat(viewModel.uiState.value.keyRemovalPending).isTrue()
            assertThat(store.savedConnections).isEmpty()
            assertThat(store.clearCount).isEqualTo(0)
        }

    @Test
    fun `confirming the removal drops the key and stores the new endpoint`() =
        runTest(dispatcher) {
            storedOpenCode()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.selectProvider(AssistantProvider.OPENROUTER)
            viewModel.saveAndStart()
            advanceUntilIdle()
            viewModel.confirmKeyRemoval()
            advanceUntilIdle()
            assertThat(store.clearCount).isEqualTo(1)
            assertThat(store.savedConnections)
                .containsExactly(AssistantProvider.OPENROUTER to "https://openrouter.ai/api/v1")
            assertThat(viewModel.uiState.value.hasKey).isFalse()
        }

    @Test
    fun `cancelling the removal stores nothing and restores the stored endpoint`() =
        runTest(dispatcher) {
            storedOpenCode()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.selectProvider(AssistantProvider.OPENROUTER)
            viewModel.saveAndStart()
            advanceUntilIdle()
            viewModel.cancelKeyRemoval()
            val state = viewModel.uiState.value
            assertThat(state.provider).isEqualTo(AssistantProvider.OPENCODE)
            assertThat(state.baseUrl).isEqualTo(AssistantDefaults.BASE_URL)
            assertThat(state.keyRemovalPending).isFalse()
            assertThat(store.savedConnections).isEmpty()
            assertThat(store.clearCount).isEqualTo(0)
        }

    @Test
    fun `changing only the model never asks to remove the key`() =
        runTest(dispatcher) {
            storedOpenCode()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.selectModel("m2")
            viewModel.saveAndStart()
            advanceUntilIdle()
            // The key belongs to an address, not to a model, so this is not an endpoint change.
            assertThat(viewModel.uiState.value.keyRemovalPending).isFalse()
            assertThat(store.clearCount).isEqualTo(0)
            assertThat(store.savedConnections).hasSize(1)
        }

    @Test
    fun `a provider that needs no key saves and chats without one`() =
        runTest(dispatcher) {
            val address = "http://localhost:11434/v1"
            store.config =
                StoredAssistantConfig(
                    apiKey = "",
                    settings = AssistantSettings(model = "m1"),
                    setupRequired = false,
                    connection =
                        AssistantConnection(
                            provider = AssistantProvider.OLLAMA,
                            baseUrl = address,
                            model = "m1",
                        ),
                )
            // The address is not the shipped one, so the ViewModel asks the factory for its client; this
            // factory answers with one over the scripted transport, so the turn is answered here instead
            // of reaching a socket.
            val viewModel = newViewModel { url -> ScriptedAddressClient(OpenAiCompatibleClient(transport, url)) }
            advanceUntilIdle()
            // A local server answers with no credential at all, so this is a finished setup.
            assertThat(viewModel.uiState.value.isConfigured).isTrue()

            viewModel.saveAndStart()
            advanceUntilIdle()
            assertThat(store.savedConnections)
                .containsExactly(AssistantProvider.OLLAMA to address)

            transport.chatBodies.addLast("three tasks are open")
            viewModel.send("hello")
            advanceUntilIdle()

            // A custom address is served by the ViewModel's own client, not the injected one, so the
            // question reaches the address on screen and its answer lands in the chat: the key gate is
            // passed and the turn completed, with nothing sent as a credential.
            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(transport.postUrls).containsExactly("$address/chat/completions")
            assertThat(transport.chatPosts).isEqualTo(1)
            assertThat(transport.postKeys.single()).isEqualTo("Bearer ")
            val reply =
                viewModel.uiState.value.messages
                    .last { it.role == AssistantChatMessage.ROLE_ASSISTANT }
            assertThat(reply.text).isEqualTo("three tasks are open")
        }

    private class UnreachableTransport : HttpTransport {
        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpResult = throw IOException("no route to host")

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpResult = throw IOException("no route to host")
    }
}
