//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.assistant

/** One model reply plus provider accounting. */
data class ChatReply(
    val content: String,
    val finishReason: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

/**
 * Provider/transport failure with the UI-facing classification.
 *
 * @property kind the older, coarser vocabulary. Kept because existing call sites read it, and it
 *   is derived from [error] rather than classified a second time.
 * @property detail the endpoint's own words, carried verbatim (truncated, never rewritten), or a
 *   stable diagnostic token for a failure that never reached the endpoint.
 * @property error the truthful [AssistantError] case for this failure, including the numeric
 *   status in [AssistantError.httpStatus]. Null only for an exception built by older code that
 *   still passes just a kind and a detail; new code reads this property, never [detail], to decide
 *   what happened.
 */
class AssistantHttpException(
    val kind: AssistantErrorKind,
    val detail: String,
    val error: AssistantError? = null,
    cause: Throwable? = null,
) : Exception(detail, cause) {
    /**
     * The HTTP status the endpoint answered with, as a value.
     *
     * It is read off [error] and never parsed back out of [detail]: a status recovered from a
     * message string is a guess wearing a number's clothes.
     */
    val httpStatus: Int? get() = error?.httpStatus
}

/** One outgoing chat message. */
data class ChatTurn(
    val role: String,
    val content: String,
)

/**
 * The OpenCode Zen Go endpoint — the assistant's original, and for now only, provider.
 *
 * THIS IS NOW A FACADE, and deliberately a thin one: every call goes straight to
 * [OpenAiCompatibleClient] with [AssistantDefaults.BASE_URL]. It exists so that Hilt, the desktop
 * graph, the ViewModel and every test keep the type and the signatures they already use, and so
 * that the endpoint-specific facts (the address, the session header) have exactly one home.
 *
 * The behaviour of this endpoint is unchanged by the move: same paths, same payloads, same
 * headers, same 180 s read timeout, same one-request-one-outcome rule, same verbatim provider
 * error text. What did change is that a failure now arrives classified by its real status instead
 * of collapsing every non-401 answer into "server": a 429 is a rate limit, a 404 is a model this
 * endpoint does not serve, a 413 is context overflow and a 400/422 is a malformed request. The
 * old `kind` view is projected back from that classification so nothing that reads it yet sees a
 * different answer.
 *
 * The API key is only ever used inside headers — never logged, never stored here.
 */
class OpenCodeGoClient(
    transport: HttpTransport,
) {
    private val delegate = OpenAiCompatibleClient(transport, AssistantDefaults.BASE_URL)

    /**
     * Fetches the model ids the key may use, excluding train-on-data families.
     * Order: alphabetical — there is no default model; the user picks one.
     */
    fun fetchModels(
        key: String,
        session: String,
    ): List<String> = delegate.fetchModels(key, session)

    /**
     * Validates [key] with a minimal chat round-trip on [model].
     *
     * The models endpoint is NOT authenticated (it returns the catalog for any key, valid or not),
     * so listing models can never prove a key works.
     *
     * @throws AssistantHttpException with [AssistantErrorKind.KEY_REJECTED] on HTTP 401. A 403
     *   keeps the endpoint's own message and reads as [AssistantErrorKind.SERVER], because this
     *   gateway answers 403 for a region restriction rather than for a bad key; the exception's
     *   [AssistantHttpException.error] records the 403 as [AssistantError.Auth].
     */
    fun validateKey(
        key: String,
        session: String,
        model: String,
    ) {
        delegate.validateKey(key, session, model)
    }

    /** Runs one chat completion turn. */
    fun chat(
        key: String,
        session: String,
        model: String,
        messages: List<ChatTurn>,
        maxTokens: Int = AssistantDefaults.MAX_TOKENS,
    ): ChatReply = delegate.chat(key, session, model, messages, maxTokens)
}
