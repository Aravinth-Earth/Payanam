//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.assistant

/**
 * The failure vocabulary of an assistant connection, declared once for every provider.
 *
 * WHY THIS EXISTS: the classification that exists today, [AssistantErrorKind], was shaped by one
 * provider's behaviour — an authentication failure is 401 and every other non-2xx status is
 * "server". That shape cannot describe a second endpoint. A local OpenAI-compatible server answers
 * 400 for a malformed request and 404 for a model it does not serve, and a hosted provider answers
 * 429 both when a quota is spent and when a burst limit is hit. A two-case enum has to give all of
 * those the same advice, which means the user is told to check their network when the real fix was
 * to shorten the conversation or to wait a minute.
 *
 * THIS IS A CLASSIFICATION, NOT A PAYLOAD. The provider's own message stays on the failure that
 * carried it, because the app never rewrites a provider error; nothing here should be read as a
 * replacement for that text.
 *
 * NOTHING HERE RETRIES ANYTHING. [retryable] only records whether asking again could plausibly
 * succeed, so that the retry decision lives in one place instead of in each caller. [Auth],
 * [ModelNotFound], [ContextOverflow] and [InvalidRequest] must NEVER be retried: each fails
 * identically however many times it is asked, and re-sending a rejected credential can trip a
 * provider's own lockout.
 */
sealed interface AssistantError {
    /**
     * A short, stable, machine-readable token for this failure.
     *
     * It is meant to be logged, matched and grouped — never shown. User-facing wording belongs to
     * the platform's string resources, so this token is the join key between the two.
     */
    val reason: String

    /**
     * The HTTP status the endpoint answered with, when the failure had one at all.
     *
     * A refused connection or a cancelled socket has no status, so null is a real answer and not a
     * missing value. The status is recorded for diagnostics, never used to re-classify: deciding
     * which case applies is the classifier's job, and re-deriving it from a number here would give
     * the same failure two possible readings.
     */
    val httpStatus: Int?

    /** Whether asking again could plausibly succeed. See the never-retry list on this type. */
    val retryable: Boolean

    /** The endpoint rejected the credential: the fix is a new key, and nothing else makes it pass. */
    data class Auth(
        override val reason: String = AssistantError.REASON_AUTH,
        override val httpStatus: Int? = 401,
    ) : AssistantError {
        override val retryable: Boolean = false
    }

    /**
     * The request produced no HTTP answer: a DNS failure, a refused connection, a TLS failure, a
     * socket dropped mid-flight.
     */
    data class Transport(
        override val reason: String = AssistantError.REASON_TRANSPORT,
        override val httpStatus: Int? = null,
    ) : AssistantError {
        override val retryable: Boolean = true
    }

    /**
     * The endpoint accepted the request and did not answer inside the caller's deadline.
     *
     * Kept apart from [Transport] because the request may have been processed before the wait
     * expired, which matters to anything that might send it again.
     */
    data class Timeout(
        override val reason: String = AssistantError.REASON_TIMEOUT,
        override val httpStatus: Int? = null,
    ) : AssistantError {
        override val retryable: Boolean = true
    }

    /** The endpoint understood the request and refused to serve it right now. */
    data class RateLimit(
        override val reason: String = AssistantError.REASON_RATE_LIMIT,
        override val httpStatus: Int? = 429,
    ) : AssistantError {
        override val retryable: Boolean = true
    }

    /**
     * The endpoint answered and failed on its own side — a 5xx, or a reply that arrived with a
     * success status but could not be read as an answer.
     */
    data class Server(
        override val reason: String = AssistantError.REASON_SERVER,
        override val httpStatus: Int? = null,
    ) : AssistantError {
        override val retryable: Boolean = true
    }

    /**
     * The endpoint does not serve the requested model id, so the stored choice cannot be used on
     * this connection.
     */
    data class ModelNotFound(
        override val reason: String = AssistantError.REASON_MODEL_NOT_FOUND,
        override val httpStatus: Int? = null,
    ) : AssistantError {
        override val retryable: Boolean = false
    }

    /**
     * The request was well-formed but the conversation no longer fits the model's context window,
     * so shortening the history is the only fix.
     */
    data class ContextOverflow(
        override val reason: String = AssistantError.REASON_CONTEXT_OVERFLOW,
        override val httpStatus: Int? = null,
    ) : AssistantError {
        override val retryable: Boolean = false
    }

    /**
     * The endpoint rejected the request as malformed before any model ran: a bad field, an
     * unsupported parameter, an empty message list.
     */
    data class InvalidRequest(
        override val reason: String = AssistantError.REASON_INVALID_REQUEST,
        override val httpStatus: Int? = null,
    ) : AssistantError {
        override val retryable: Boolean = false
    }

    companion object {
        /** [Auth] token. */
        const val REASON_AUTH = "auth-rejected"

        /** [Transport] token. */
        const val REASON_TRANSPORT = "transport-failed"

        /** [Timeout] token. */
        const val REASON_TIMEOUT = "timeout"

        /** [RateLimit] token. */
        const val REASON_RATE_LIMIT = "rate-limited"

        /** [Server] token. */
        const val REASON_SERVER = "server-error"

        /** [ModelNotFound] token, for a model the endpoint does not serve. */
        const val REASON_MODEL_NOT_FOUND = "model-not-found"

        /** [ModelNotFound] token, for the different case of no model being chosen at all. */
        const val REASON_NO_MODEL_CONFIGURED = "no-model-configured"

        /** [ContextOverflow] token. */
        const val REASON_CONTEXT_OVERFLOW = "context-overflow"

        /** [InvalidRequest] token. */
        const val REASON_INVALID_REQUEST = "invalid-request"
    }
}

/**
 * Classifies one existing [AssistantErrorKind] into [AssistantError], so the new vocabulary has a
 * single source instead of a second classification grown beside the old one.
 *
 * The mapping is faithful to what the existing type can actually distinguish, and stays inside it
 * on purpose:
 * - [AssistantErrorKind.KEY_REJECTED] is [AssistantError.Auth].
 * - [AssistantErrorKind.NETWORK] is [AssistantError.Transport] — that kind is produced from a
 *   thrown transport failure, so it never carried a status.
 * - [AssistantErrorKind.SERVER] stays [AssistantError.Server] whatever [httpStatus] says. Today
 *   every status other than 401 collapses into that kind, so a 429 or a 404 arrives
 *   indistinguishable from a 500; re-splitting it by status here would be a second classification
 *   invented at the mapping step, which is exactly what this function is meant to prevent. A
 *   connection that knows its own status should classify directly, before it loses it.
 * - [AssistantErrorKind.NO_MODEL] becomes [AssistantError.ModelNotFound] with
 *   [AssistantError.REASON_NO_MODEL_CONFIGURED] as its reason: it is about the model, but it means
 *   "the user has not chosen one", not "the endpoint rejected the chosen one", and the reason token
 *   is what keeps those two apart.
 *
 * @param httpStatus the status the caller still holds, recorded but never used to re-classify.
 * @return the matching case, or null for [AssistantErrorKind.DB_LOCKED] — that kind is a local
 *   storage failure, not a connection failure, so this hierarchy has no case for it and folding it
 *   into a connection case would report a locked database as a broken endpoint.
 */
fun AssistantErrorKind.toAssistantError(httpStatus: Int? = null): AssistantError? =
    when (this) {
        AssistantErrorKind.KEY_REJECTED -> {
            AssistantError.Auth(httpStatus = httpStatus ?: 401)
        }

        AssistantErrorKind.NETWORK -> {
            AssistantError.Transport(httpStatus = httpStatus)
        }

        AssistantErrorKind.SERVER -> {
            AssistantError.Server(httpStatus = httpStatus)
        }

        AssistantErrorKind.NO_MODEL -> {
            AssistantError.ModelNotFound(
                reason = AssistantError.REASON_NO_MODEL_CONFIGURED,
                httpStatus = httpStatus,
            )
        }

        AssistantErrorKind.DB_LOCKED -> {
            null
        }
    }

/**
 * Classifies the failure the client actually throws.
 *
 * The status is left out because [AssistantHttpException] does not carry one as a value: the
 * client folds it into the free-text detail, and re-reading a message to recover a number would be
 * the guessing this hierarchy exists to end. A caller that still holds the status passes it to
 * [toAssistantError] on the kind instead.
 */
fun AssistantHttpException.toAssistantError(): AssistantError? = kind.toAssistantError()
