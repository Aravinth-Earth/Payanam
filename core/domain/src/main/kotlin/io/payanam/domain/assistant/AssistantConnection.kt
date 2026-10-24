//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.assistant

/**
 * The provider family an [AssistantConnection] points at.
 *
 * WHY THE KIND IS STORED AND NOT DERIVED FROM THE URL: "which provider is this?" cannot be
 * answered from a host string. A server the user runs on their own machine and a hosted service
 * both speak `/v1/chat/completions`, and a custom endpoint is free to imitate either. The kind is
 * a fact the user chose, so it is recorded as a value rather than guessed by matching text, which
 * is also why [LOCAL_SERVER] and [CUSTOM] are real kinds and not URL conventions.
 *
 * [defaultBaseUrl] is the endpoint that is genuinely known for a kind, and null where no default
 * can be honest: a self-hosted or custom endpoint has an address only the user knows, so inventing
 * one would silently send a request to the wrong host.
 */
enum class AssistantConnectionKind(
    val defaultBaseUrl: String?,
) {
    /** The hosted OpenCode Zen Go gateway — the only endpoint with a known address. */
    OPEN_CODE_ZEN(AssistantDefaults.BASE_URL),

    /** An OpenAI-compatible server the user runs themselves, on loopback or the local network. */
    LOCAL_SERVER(null),

    /** Any other OpenAI-compatible endpoint, known only to the user who configured it. */
    CUSTOM(null),
}

/**
 * One named assistant endpoint, with the model chosen for it.
 *
 * THE KEY IS NOT A FIELD HERE, ON PURPOSE. A credential is bound to the connection it was entered
 * for and is stored separately, not carried around inside the connection value. If the key lived
 * on this type, every operation that takes a connection would also take a key, and any code path
 * that copies a connection to a new endpoint would quietly carry the old credential with it — a
 * key entered for one host would then be presented to a different one. Keeping the two apart
 * makes that class of mistake impossible to express instead of merely discouraged.
 *
 * [displayName] is the user's own label and is never app copy: a blank value means the UI shows a
 * localized name for [kind], because user-facing text is resolved on the platform's side and in
 * its own language resources, not baked into a shared domain constant.
 */
data class AssistantConnection(
    val kind: AssistantConnectionKind,
    val displayName: String = "",
    /**
     * The endpoint root the client appends its own paths to, with no trailing slash.
     *
     * A blank value means "not entered yet" — a kind without a
     * [AssistantConnectionKind.defaultBaseUrl] starts blank rather than borrowing another
     * provider's address, so an unfinished setup fails as unfinished instead of calling a host the
     * user never chose.
     */
    val baseUrl: String = kind.defaultBaseUrl.orEmpty(),
    val model: String = "",
)
