//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.assistant

import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
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
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * One OpenAI-compatible chat endpoint, driven entirely by the connection the caller supplies.
 *
 * WHY THIS IS SEPARATE FROM [OpenCodeGoClient]: the address, the model and the key belong to the
 * connection the user configured, not to a compiled-in constant. A class that reads its own base
 * URL can only ever describe one provider, and a second provider then means a second copy of the
 * request building, the status handling and the reply parsing — three places for the same bug.
 *
 * THE SHAPE OF ONE CALL IS FIXED: exactly one HTTP request, exactly one outcome. There is no
 * retry, no backoff, no fallback model and no second attempt. Every failure leaves this class as
 * an [AssistantHttpException] whose [AssistantHttpException.error] is the truthful
 * [AssistantError] case, with the numeric status carried as a value — never folded into a string
 * for a later reader to parse back out.
 *
 * WHAT STILL PROPAGATES UNCHANGED: an [java.io.IOException] that is not a socket or DNS failure
 * (a TLS failure, a cancelled read, a body over the size cap) is not caught here. The transport
 * seam documents [java.io.IOException] as its failure channel, and callers already turn that into
 * their own network state, so re-wrapping it would give one failure two spellings.
 *
 * The API key is only ever used inside headers — never logged, never stored here.
 */
class OpenAiCompatibleClient(
    private val transport: HttpTransport,
    private val baseUrl: String,
) {
    // Read through the shared accessor rather than the platform singleton: `AppLogger.current`
    // starts as a no-op, so this client needs no logger to exist (JVM unit tests construct it
    // without one) and production still writes to the app's sink via the install-once holder.
    private val logger: UnifiedLogger get() = AppLogger.current

    /**
     * Fetches the model ids the key may use, excluding train-on-data families.
     * Order: alphabetical — there is no default model; the user picks one.
     */
    fun fetchModels(
        key: String,
        session: String,
    ): List<String> {
        val result = call { transport.get(endpoint() + MODELS_PATH, headers(key, session), READ_TIMEOUT_MS) }
        requireSuccess(result.code, result.body)
        val root = parseObject("OpenAiCompatibleClient.fetchModels", result.body, "models: unparseable response")
        val ids =
            (root["data"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                }.filter { id -> AssistantDefaults.BLOCKED_MODEL_PREFIXES.none { id.startsWith(it) } }
                .distinct()
        return ids.sorted()
    }

    /**
     * Validates [key] with a minimal chat round-trip on [model].
     *
     * The models endpoint is NOT authenticated (it returns the catalog for any key, valid or not),
     * so listing models can never prove a key works.
     *
     * @throws AssistantHttpException classified by [requireSuccess]; [AssistantError.Auth] is
     *   recorded for 401 and 403. Every other status keeps the endpoint's own message verbatim.
     */
    fun validateKey(
        key: String,
        session: String,
        model: String,
    ) {
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
        val result =
            call { transport.postJson(endpoint() + CHAT_PATH, headers(key, session), payload, READ_TIMEOUT_MS) }
        requireSuccess(result.code, result.body)
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
        val result =
            call { transport.postJson(endpoint() + CHAT_PATH, headers(key, session), payload, READ_TIMEOUT_MS) }
        requireSuccess(result.code, result.body)
        val root = parseObject("OpenAiCompatibleClient.chat", result.body, "chat: unparseable response")
        val choice =
            (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw failureOf(AssistantError.Server(), "chat: empty choices")
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

    private fun headers(
        key: String,
        session: String,
    ): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json",
            "User-Agent" to AssistantDefaults.USER_AGENT,
            SESSION_HEADER to session,
        )

    /**
     * The endpoint root this call appends its paths to, with the trailing slashes removed.
     *
     * `https://` is required because the key travels in a header and a plaintext hop would expose
     * it. The two loopback spellings are the exception: a server on the user's own machine has no
     * certificate and no network in between, so demanding TLS there would forbid the case this
     * client exists for. Anything else is refused as [AssistantError.InvalidRequest] rather than
     * attempted — a rejected endpoint is a setup mistake, not a provider outage.
     */
    private fun endpoint(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        val scheme = if (schemeEnd > 0) trimmed.substring(0, schemeEnd).lowercase() else ""
        val authority = if (schemeEnd > 0) trimmed.substring(schemeEnd + 3).substringBefore('/') else ""
        // Drop any userinfo before reading the host, so "http://user@evil.test" is not loopback.
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        val secure = scheme == "https" && host.isNotEmpty()
        val loopback = scheme == "http" && (host == LOOPBACK_HOST || host == LOOPBACK_ADDRESS)
        if (!secure && !loopback) {
            throw failureOf(AssistantError.InvalidRequest(), "invalid-request: base-url")
        }
        return trimmed
    }

    /**
     * Runs one transport call, classifying the two failure shapes the transport reports by
     * throwing. See the class comment for the failures deliberately left alone.
     */
    private fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (timeout: SocketTimeoutException) {
            throw failureOf(AssistantError.Timeout(), timeout.message ?: AssistantError.REASON_TIMEOUT, timeout)
        } catch (socket: SocketException) {
            throw failureOf(AssistantError.Transport(), socket.message ?: AssistantError.REASON_TRANSPORT, socket)
        } catch (unknownHost: UnknownHostException) {
            throw failureOf(
                AssistantError.Transport(),
                unknownHost.message ?: AssistantError.REASON_TRANSPORT,
                unknownHost,
            )
        }

    /**
     * Maps the one HTTP status this call actually got onto the shared failure vocabulary.
     *
     * The status is a value on the way in and a value on the way out, and the returned case records
     * it in [AssistantError.httpStatus], so nothing downstream has to read a message to find out
     * what happened. Statuses with no dedicated case are still answered truthfully rather than
     * lumped in with the 5xx family: a 4xx outside the list means this request was refused, and
     * everything else means the endpoint answered something unexpected.
     */
    private fun classify(code: Int): AssistantError =
        when (code) {
            401, 403 -> AssistantError.Auth(httpStatus = code)
            402, 429 -> AssistantError.RateLimit(httpStatus = code)
            404 -> AssistantError.ModelNotFound(httpStatus = code)
            413 -> AssistantError.ContextOverflow(httpStatus = code)
            400, 422 -> AssistantError.InvalidRequest(httpStatus = code)
            408 -> AssistantError.Timeout(httpStatus = code)
            in 500..599 -> AssistantError.Server(httpStatus = code)
            in 400..499 -> AssistantError.InvalidRequest(httpStatus = code)
            else -> AssistantError.Server(httpStatus = code)
        }

    /**
     * Fails on any non-2xx status with the classified case.
     *
     * The response body is kept as it came back, truncated but never rewritten: the app does not
     * edit a provider's own error into its own words.
     */
    private fun requireSuccess(
        code: Int,
        body: String,
    ) {
        if (code in 200..299) return
        val error = classify(code)
        throw failureOf(error, "http $code: ${body.take(DETAIL_CLIP)}")
    }

    /**
     * Builds the failure, keeping the origin cause when there is one.
     *
     * The cause is carried rather than dropped: the transport exception's own message is not
     * enough to diagnose a TLS or DNS failure after the fact, and a swallowed throwable is a
     * stack trace nobody can recover later.
     */
    private fun failureOf(
        error: AssistantError,
        detail: String,
        cause: Throwable? = null,
    ): AssistantHttpException =
        AssistantHttpException(
            kind = error.toLegacyKind(),
            detail = detail,
            error = error,
            cause = cause,
        )

    /**
     * Reports an unparseable provider response.
     *
     * No stderr fallback: the caller cannot miss this failure because every call site turns it
     * into an [AssistantHttpException] it must handle, so the fact reaches the user either way.
     */
    private fun parseObject(
        tag: String,
        body: String,
        failure: String,
    ): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }
            .onFailure { error -> logger.e(tag, "Unparseable provider response", error) }
            .getOrNull()
            ?: throw failureOf(AssistantError.Server(), failure)

    companion object {
        /**
         * The per-session header every request carries.
         *
         * OpenCode Zen Go mandates it — without it the gateway answers 400 MissingSessionID — so
         * removing it would break the endpoint already in use. An OpenAI-compatible server ignores
         * a header it does not define, so sending it to one costs nothing.
         */
        const val SESSION_HEADER = "x-opencode-session"

        private const val MODELS_PATH = "/models"
        private const val CHAT_PATH = "/chat/completions"
        private const val READ_TIMEOUT_MS = 180_000
        private const val VALIDATION_MAX_TOKENS = 1
        private const val VALIDATION_PROMPT = "ping"

        /** How much of a provider's error body is kept in the failure detail. */
        private const val DETAIL_CLIP = 600

        private const val LOOPBACK_HOST = "localhost"
        private const val LOOPBACK_ADDRESS = "127.0.0.1"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Projects one [AssistantError] onto the older [AssistantErrorKind] vocabulary.
 *
 * This exists only for the call sites that still read the enum — [AssistantHttpException.error] is
 * the truthful classification and any new code must read that instead.
 *
 * ONE DELIBERATE LOSS: [AssistantError.Auth] covers 401 and 403, but the old enum reserved
 * [AssistantErrorKind.KEY_REJECTED] for a credential the user can replace. A 403 from the gateway
 * today is a RegionError, not a bad key, and reporting it as a rejection makes the screen hide the
 * provider's own words and tell the user to replace a key that works, so 403 keeps its previous
 * reading here while `error` still records [AssistantError.Auth]. This branch goes away with the
 * enum itself.
 */
private fun AssistantError.toLegacyKind(): AssistantErrorKind =
    when (this) {
        is AssistantError.Auth -> if (httpStatus == 403) AssistantErrorKind.SERVER else AssistantErrorKind.KEY_REJECTED

        is AssistantError.Transport, is AssistantError.Timeout -> AssistantErrorKind.NETWORK

        is AssistantError.Server,
        is AssistantError.RateLimit,
        is AssistantError.ModelNotFound,
        is AssistantError.ContextOverflow,
        is AssistantError.InvalidRequest,
        -> AssistantErrorKind.SERVER
    }
