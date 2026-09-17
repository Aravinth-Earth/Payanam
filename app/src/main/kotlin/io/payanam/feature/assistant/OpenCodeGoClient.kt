//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

import io.payanam.common.logging.UnifiedLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One model reply plus provider accounting. */
data class ChatReply(
    val content: String,
    val finishReason: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

/** Provider/transport failure with the UI-facing classification. */
class AssistantHttpException(
    val kind: AssistantErrorKind,
    val detail: String,
) : Exception(detail)

/** One outgoing chat message. */
data class ChatTurn(val role: String, val content: String)

/**
 * OpenCode Go chat client (BYOK). Mirrors the verified desktop POC behaviour:
 * - `GET /models` lists model ids; `POST /chat/completions` runs one turn.
 * - Every call carries `Authorization`, a distinct User-Agent and — mandatory for this
 *   provider — an `x-opencode-session` header (missing it returns 400 MissingSessionID).
 * - 401 is our key rejection; every other provider failure (403 included) keeps the provider's
 *   own message verbatim — the app never rewrites a provider error.
 *
 * The API key is only ever used inside headers — never logged, never stored here.
 */
class OpenCodeGoClient(private val transport: HttpTransport) {
    // Lazy and null-tolerant: JVM unit tests construct this client without the app's logger
    // having been initialized, while production always initializes it in Application.onCreate().
    private val logger: UnifiedLogger? by lazy {
        if (UnifiedLogger.isInitialized()) UnifiedLogger.getInstance() else null
    }
    /**
     * Fetches the model ids the key may use, excluding train-on-data families.
     * Order: alphabetical — there is no default model; the user picks one.
     */
    fun fetchModels(key: String, session: String): List<String> {
        val result = transport.get(BASE_URL + "/models", headers(key, session), READ_TIMEOUT_MS)
        ensureSuccess(result.code, result.body)
        val root = runCatching { json.parseToJsonElement(result.body).jsonObject }
            .onFailure { error -> logParseFailure("OpenCodeGoClient.fetchModels", error) }
            .getOrNull()
            ?: throw AssistantHttpException(AssistantErrorKind.SERVER, "models: unparseable response")
        val ids = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { element ->
                ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            }
            .filter { id -> AssistantDefaults.BLOCKED_MODEL_PREFIXES.none { id.startsWith(it) } }
            .distinct()
        return ids.sorted()
    }

    /**
     * Validates [key] with a real chat call on [model]. The models endpoint is NOT
     * authenticated (it returns the catalog for any key, valid or not), so the model list can
     * never prove a key works; a minimal chat round-trip can. 401/403 surface as KEY_REJECTED
     * via [ensureSuccess].
     */
    fun validateKey(key: String, session: String, model: String) {
        val payload =
            buildJsonObject {
                put("model", model)
                put("max_tokens", VALIDATION_MAX_TOKENS)
                put("stream", false)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", VALIDATION_PROMPT)
                        },
                    )
                }
            }.toString()
        val result = transport.postJson(BASE_URL + "/chat/completions", headers(key, session), payload, READ_TIMEOUT_MS)
        ensureSuccess(result.code, result.body)
    }

    /** Runs one chat completion turn. */
    fun chat(
        key: String,
        session: String,
        model: String,
        messages: List<ChatTurn>,
        maxTokens: Int = AssistantDefaults.MAX_TOKENS,
    ): ChatReply {
        val payload =
            buildJsonObject {
                put("model", model)
                put("temperature", AssistantDefaults.TEMPERATURE)
                put("max_tokens", maxTokens)
                put("stream", false)
                putJsonArray("messages") {
                    messages.forEach { turn ->
                        add(
                            buildJsonObject {
                                put("role", turn.role)
                                put("content", turn.content)
                            },
                        )
                    }
                }
            }.toString()
        val result = transport.postJson(BASE_URL + "/chat/completions", headers(key, session), payload, READ_TIMEOUT_MS)
        ensureSuccess(result.code, result.body)
        val root = runCatching { json.parseToJsonElement(result.body).jsonObject }
            .onFailure { error -> logParseFailure("OpenCodeGoClient.chat", error) }
            .getOrNull()
            ?: throw AssistantHttpException(AssistantErrorKind.SERVER, "chat: unparseable response")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AssistantHttpException(AssistantErrorKind.SERVER, "chat: empty choices")
        val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull ?: ""
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull ?: ""
        val usage = root["usage"] as? JsonObject
        return ChatReply(
            content = content,
            finishReason = finish,
            promptTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            completionTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    private fun headers(key: String, session: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $key",
        "Content-Type" to "application/json",
        "User-Agent" to AssistantDefaults.USER_AGENT,
        "x-opencode-session" to session,
    )

    /**
     * Reports an unparseable provider response. The app logger is absent in JVM unit tests, and a
     * parse failure must never vanish silently — it falls back to stderr there.
     */
    private fun logParseFailure(tag: String, error: Throwable) {
        val active = logger
        if (active != null) {
            active.e(tag, "Unparseable provider response", error)
        } else {
            System.err.println("$tag: unparseable provider response (${error.javaClass.simpleName})")
        }
    }

    private fun ensureSuccess(code: Int, body: String) {
        if (code in 200..299) return
        // Only an authentication failure gets our own wording; every other provider failure is
        // surfaced with the provider's own message, exactly as it came back.
        val kind = if (code == 401) AssistantErrorKind.KEY_REJECTED else AssistantErrorKind.SERVER
        throw AssistantHttpException(kind, "http $code: ${body.take(600)}")
    }

    private companion object {
        private const val BASE_URL = AssistantDefaults.BASE_URL
        private const val READ_TIMEOUT_MS = 180_000
        private const val VALIDATION_MAX_TOKENS = 1
        private const val VALIDATION_PROMPT = "ping"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
