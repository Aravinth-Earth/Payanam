//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.assistant

/**
 * Where the model actually runs, which is how the product groups its providers.
 *
 * [LOCAL] is a server on the user's own machine or network; [CLOUD] is a hosted service reached
 * over the internet with a key the user brings. The group is a placement fact, not a protocol
 * difference — both speak OpenAI-compatible `/chat/completions`.
 */
enum class AssistantBackend {
    LOCAL,
    CLOUD,
}

/**
 * One provider the user can connect, belonging to one [backend] group.
 *
 * The list is fixed and named rather than free-form: the user picks a provider by name, and "set up
 * your own" is the last entry of each group ([CUSTOM_LOCAL], [CUSTOM_CLOUD]) for anything not
 * listed. [defaultBaseUrl] is null exactly where no address can be honest — inventing one for a
 * custom entry would silently send a request to a host the user never chose.
 *
 * [requiresKey] marks the providers that cannot answer without a credential at all. It is false both
 * where a key is meaningless ([OLLAMA], [LM_STUDIO], [CUSTOM_LOCAL]) and where the host itself
 * decides ([CUSTOM_CLOUD]).
 */
enum class AssistantProvider(
    val backend: AssistantBackend,
    val defaultBaseUrl: String?,
    val requiresKey: Boolean,
) {
    /** A model server the user runs on this machine through Ollama. */
    OLLAMA(AssistantBackend.LOCAL, "http://localhost:11434/v1", requiresKey = false),

    /** A model server the user runs on this machine through LM Studio. */
    LM_STUDIO(AssistantBackend.LOCAL, "http://localhost:1234/v1", requiresKey = false),

    /** Any other server on the user's own machine or network; the address is theirs to enter. */
    CUSTOM_LOCAL(AssistantBackend.LOCAL, defaultBaseUrl = null, requiresKey = false),

    /** The hosted OpenCode Zen Go gateway. */
    OPENCODE(AssistantBackend.CLOUD, AssistantDefaults.BASE_URL, requiresKey = true),

    /** The hosted OpenRouter gateway. */
    OPENROUTER(AssistantBackend.CLOUD, "https://openrouter.ai/api/v1", requiresKey = true),

    /** OpenAI's own API. */
    OPENAI(AssistantBackend.CLOUD, "https://api.openai.com/v1", requiresKey = true),

    /** Anthropic's OpenAI-compatible endpoint. */
    CLAUDE(AssistantBackend.CLOUD, "https://api.anthropic.com/v1", requiresKey = true),

    /** Any other hosted OpenAI-compatible endpoint; a self-hosted one may not ask for a key. */
    CUSTOM_CLOUD(AssistantBackend.CLOUD, defaultBaseUrl = null, requiresKey = false),
    ;

    companion object {
        /** The providers of one [backend], in declaration order — one call renders one group. */
        fun forBackend(backend: AssistantBackend): List<AssistantProvider> = entries.filter { it.backend == backend }
    }
}

/**
 * One assistant endpoint, with the model chosen for it.
 *
 * THE KEY IS NOT A FIELD HERE, ON PURPOSE. A credential is bound to the connection it was entered
 * for and is stored separately, not carried around inside the connection value. If the key lived
 * on this type, every operation that takes a connection would also take a key, and any code path
 * that copies a connection to a new endpoint would quietly carry the old credential with it — a
 * key entered for one host would then be presented to a different one. Keeping the two apart
 * makes that class of mistake impossible to express instead of merely discouraged.
 *
 * [provider] is null until the user has chosen one, and [baseUrl] starts from that provider's
 * [AssistantProvider.defaultBaseUrl] so an untouched entry is already usable where a default exists.
 * There is no display name and no user-facing text here: labels are string resources in the UI
 * layer, pinned to each value by name.
 */
data class AssistantConnection(
    val provider: AssistantProvider? = null,
    /**
     * The endpoint root the client appends its own paths to, with no trailing slash.
     *
     * A blank value means "not entered yet" — the two custom providers have no default and start
     * blank rather than borrowing another provider's address, so an unfinished setup fails as
     * unfinished instead of calling a host the user never chose.
     */
    val baseUrl: String = provider?.defaultBaseUrl.orEmpty(),
    val model: String = "",
) {
    /**
     * True when this connection can actually be used: a provider is chosen, its address is filled
     * in, and — only where [AssistantProvider.requiresKey] says one is needed — the caller reports
     * that a key exists for it.
     *
     * Key PRESENCE is the parameter, never the key: the credential lives elsewhere, and taking it
     * here is the coupling this type exists to prevent.
     */
    fun isConfigured(hasKey: Boolean): Boolean {
        val chosen = provider ?: return false
        if (baseUrl.isBlank()) return false
        return !chosen.requiresKey || hasKey
    }
}
