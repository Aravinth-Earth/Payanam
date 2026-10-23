//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.ReadOnlyDatabaseQueryJvm
import io.payanam.domain.assistant.OpenCodeGoClient
import io.payanam.domain.assistant.UrlConnectionTransport
import io.payanam.presentation.assistant.AssistantUiState
import io.payanam.presentation.assistant.AssistantViewModel
import io.payanam.presentation.assistant.assistantViewModel
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The assistant destinations' collaborators, and the ONE shared ViewModel both of them read.
 *
 * ## WHY THE VIEW MODEL IS HELD HERE AND NOT PER DESTINATION
 *
 * Android's two assistant destinations share a retention KEY on purpose (`retainAssistantViewModel` in
 * `:app`), and the reason is the configuration, not tidiness: a key per screen would give each its own
 * store handle, and a model chosen in the settings editor would not be the model the chat surface then
 * loads. Every other desktop destination builds its own ViewModel with `desktopRetained`, which is the
 * WRONG shape for these two - composition scope is per destination here, so a ViewModel built inside
 * `assistantSettings` would not be the one `assistant` re-enters, and a conversation would be empty on
 * the way back from the settings editor. So this holder is the desktop's answer to a shared retention
 * key: one instance, built the first time either route asks for it, released when the shell does.
 *
 * ## WHERE EACH COLLABORATOR COMES FROM
 *
 * | collaborator | source |
 * |---|---|
 * | `settingsStore` | `DesktopDatabaseSessionController.assistantConfigurationStore()` - the SHELL's own open session, so the provider key, the model and the settings rows are the ones every other desktop screen's `app_settings` reads |
 * | `schemaReader`, `query` | ONE [ReadOnlyDatabaseQueryJvm], because one verified read-only handle answers both and two classes could answer them differently on one platform - the same reasoning `AssistantModule` gives on Android |
 * | `client` | the shared [OpenCodeGoClient] over the shared [UrlConnectionTransport], a parameter so a test can drive a turn without a network |
 *
 * ## WHY THE READ-ONLY HANDLE IS RELEASED HERE AND NOT LEFT OPEN
 *
 * [ReadOnlyDatabaseQueryJvm] opens and CACHES its own verified read-only session, and `AutoCloseable` is
 * its only release. A holder that did not close it would leave one SQLCipher connection open per shell
 * for the life of the process, holding a handle on the same file the shell's own session has open.
 * [dispose] is therefore the counterpart of Android's `onRelease = { it.dispose() }`, and it releases
 * BOTH: the ViewModel (which flushes a pending debounced settings save) and the read-only handle.
 *
 * ## WHY THE STORE IS THE SHELL'S SESSION AND NOT A SECOND CONNECTION
 *
 * A second writable connection to a file the shell already has open is a second writer contending with
 * every repository poll, and its writes would land outside the session the import path has to fold
 * before it can replace the file. The shell's session is the one writer; this holder only asks for the
 * store the shell's own session can hand out.
 *
 * @param sessionController the shell's own session controller, which is the only owner of the open session
 * @param databaseFile the same file the shell opened, which the read-only ports open their OWN verified
 * read-only handle against. A path rather than a resolved one for the reason
 * [DesktopSettingsCollaborators] takes one: a render probe must never resolve the host's real
 * `~/.local/share`.
 * @param client the provider client, injectable so a test can serve a turn without a network
 */
internal class DesktopAssistantCollaborators(
    private val sessionController: DesktopDatabaseSessionController,
    private val databaseFile: Path,
    private val client: OpenCodeGoClient = OpenCodeGoClient(UrlConnectionTransport()),
    // Injectable for the same reason the shared ViewModel takes an injected scope: a test drives this on one
    // thread so a state write and the coroutine that reads it cannot race, while production keeps a pool.
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = AppLogger.current

    /**
     * ONE verified read-only handle answering BOTH read-only ports.
     *
     * A single instance is what makes the two ports the same answer rather than two sessions racing for
     * the same file. It opens nothing until a read asks, so holding it costs no connection while the
     * user is on the other routes.
     */
    private val readOnlyReads =
        ReadOnlyDatabaseQueryJvm(
            databaseFile = { databaseFile },
            // A COPY per call, and the controller hands one out only while a session is open - so a
            // closed session refuses the read (`DATABASE_NOT_OPEN`) rather than opening a keyless one.
            passphrase = { sessionController.passphraseForTransfer() },
        )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The one shared ViewModel, once a session exists to build it over.
     *
     * A field rather than a `by lazy` because the store CANNOT exist before a session does, and this
     * holder is built in `DesktopApp.kt` beside the other collaborators - which is before any session. A
     * `lazy` would either throw at first access or capture a null store for the process, and both are the
     * "renders a store that does not exist" shape this change exists to remove.
     */
    private var viewModel: AssistantViewModel? = null

    /**
     * The state the shared screen renders, ALWAYS present whether or not a ViewModel has been built.
     *
     * **Why this exists rather than the ViewModel's own `uiState`.** A destination that wrote
     * `viewModelOrNull()?.uiState?.collectAsState()?.value` would call a composable CONDITIONALLY, and
     * Compose requires the same composables in the same order on every recomposition: the frame before a
     * session opens and the frame after would disagree about how many to call, which is a crash at the
     * exact moment the app becomes usable. Publishing one flow from the holder makes the call
     * unconditional, and the value it publishes is the shared ViewModel's own state once one exists.
     *
     * Before that, the value is [AssistantUiState]'s defaults - `loading = true` - which is precisely the
     * state `AssistantViewModel` itself publishes between its construction and its first `refresh()`. The
     * screen has a branch for it and renders a spinner, so the pre-session frame is a correct screen
     * rather than a blank one.
     */
    private val publishedState = MutableStateFlow(AssistantUiState())

    /** The shared state both assistant destinations render. Never null; see [publishedState]. */
    val uiState: StateFlow<AssistantUiState> = publishedState

    /**
     * Starts mirroring [AssistantViewModel.uiState] into [publishedState] for as long as this holder lives.
     *
     * One collector on the holder's own scope rather than one per destination: the two routes read the same
     * flow, so a second subscription would buy nothing and would need its own cancellation.
     */
    private fun publish(viewModel: AssistantViewModel) {
        scope.launch {
            viewModel.uiState.collect { state -> publishedState.value = state }
        }
    }

    /**
     * The shared ViewModel both assistant destinations read, or null while no session is open.
     *
     * Null is a reported state rather than a throw, because pre-unlock no session exists on this platform
     * and that is the NORMAL half of every launch. Callers reach for it from the screens' ACTION callbacks
     * - which is why [uiState] exists at all - and a null there is a control that reports an action nobody
     * can carry out, which the graph's own gates make unreachable for a user.
     *
     * The build is idempotent and never replaced: the second route to compose gets the SAME instance,
     * which is the property a per-route ViewModel would break.
     */
    fun viewModelOrNull(): AssistantViewModel? {
        viewModel?.let { return it }
        val store =
            sessionController.assistantConfigurationStore()
                ?: run {
                    logger.d(
                        "DesktopAssistantCollaborators.viewModelOrNull",
                        "No open session, so the assistant has no configuration store yet",
                    )
                    return null
                }
        return assistantViewModel(
            settingsStore = store,
            schemaReader = readOnlyReads,
            query = readOnlyReads,
            client = client,
            scope = scope,
        ).also { built ->
            viewModel = built
            publish(built)
            logger.i(
                "DesktopAssistantCollaborators.viewModelOrNull",
                "Built the shared assistant ViewModel over the open desktop session",
            )
        }
    }

    /**
     * Releases the ViewModel and the read-only handle - the desktop's counterpart of Android's
     * `onRelease = { it.dispose() }`.
     *
     * Safe with nothing built: a shell that never opened either assistant route disposes nothing, and the
     * read-only handle was never opened either. [publishedState] returns to its defaults, so a screen still
     * composing after a dispose renders the same pre-session state rather than the last state read.
     */
    fun dispose() {
        scope.cancel()
        viewModel?.dispose()
        viewModel = null
        publishedState.value = AssistantUiState()
        readOnlyReads.close()
    }
}
