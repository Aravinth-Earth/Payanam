//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.payanam.core.ui.assistant.ASSISTANT_SETTINGS_BUTTON
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.text.formatArgs
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The desktop assistant is CONFIGURED through the real UI, against a real OpenAI-compatible HTTP server,
 * and the configuration is still there after the composition is torn down and rebuilt.
 *
 * **The gap this closes.** Three neighbours already exist and none of them makes this claim.
 * `DesktopAssistantSharedScreenRenderTest` composes the screens over a hand-built state, so it never types
 * into a field. `DesktopAssistantDestinationWiringTest` drives the real ViewModel against the real store,
 * so it never touches a control. `DesktopAssistantJourneyTest` walks the Lenses entry and the consent gate
 * but stops there, because its fixture controller refused every open. Nothing in `:desktop` had ever typed
 * an address, loaded models, verified and saved through the real screens - and nothing at all had ever
 * reopened the app and asked whether the save survived. A save that writes nothing still hands the user
 * back their settings in memory, so only a SECOND mount can tell a working save from a silent one.
 *
 * **Why a real server on loopback rather than a scripted transport.** `OpenAiCompatibleClient` accepts
 * plaintext only for `http://127.0.0.1` / `http://localhost` (every other plaintext address is refused as
 * an invalid request), so a test-owned `HttpServer` on an ephemeral loopback port is the one endpoint the
 * shipped client will actually call without TLS. That makes "Load models" and "Verify" real: real sockets,
 * real request building, real JSON parsing, no external network, and no credential the server ever
 * inspects. The fake server
 * answers `GET /v1/models` and `POST /v1/chat/completions` and records every path and body it receives, so
 * the journey can assert that the app talked to the address the user typed and used the model the user
 * picked.
 *
 * **Why the production fixture.** The configuration is read and written by `AssistantConfigurationStoreJvm`
 * over the shell's own encrypted database, so the restart leg is only meaningful against the real
 * `PayanamDatabaseOpener.create` database and the real session controller - a hand-built fixture would prove
 * the test's own DDL instead. `installProductionFixtureLayer` is that path, and it is what leaves the
 * controller holding a passphrase the assistant collaborators need.
 *
 * **The restart leg is the load-bearing one.** The composition is replaced with an empty one and the shell
 * is mounted again over the SAME database file and the SAME session, which builds a SECOND
 * `DesktopAssistantCollaborators` (fresh ViewModel, fresh store handle). Everything the second leg asserts
 * is therefore read back from storage through the real `refresh()`, not remembered in a ViewModel:
 *  - the chat surface opens instead of the setup pointer, which needs `hasKey` AND a model row;
 *  - the settings gear exists, which is gated on `hasKey` - i.e. the key row survived;
 *  - the top bar names the picked model;
 *  - the address field holds the exact loopback address that was typed, and the model field holds the
 *    picked model, read from each field's `EditableText`.
 *
 * **One thing the journey must do that the product does not require.** The chat surface's own
 * `isConfigured` is `hasKey && model.isNotEmpty()`, which ignores `AssistantProvider.requiresKey` - so a
 * local server that needs no credential still reads as "not configured" and the chat surface will not open
 * without SOME key in the store. The journey therefore types an opaque fixture key into the key field. The
 * fake server never inspects it, and it is never a real credential; a keyless local setup is reported as a
 * product observation rather than papered over here.
 *
 * **What a frame is here.** Every step writes a PNG (`captureToImage()` + skiko encode) and a text record of
 * the strings that were on screen, at the app's OWN window size - the same instrument
 * `DesktopFirstRunJourneyTest` uses. Frames for the two dialog steps are captured from the main root, so the
 * PNG shows the screen BENEATH the dialog; the dialog's own copy is still asserted and recorded in the step
 * text, because the semantics tree carries it.
 *
 * **What it does NOT claim.** It never sends a chat question, so the agentic-SQL turn is not measured (that
 * is `AssistantViewModelTest`'s and the Android device suite's claim). It says nothing about the Tamil
 * table. It does not exercise the key-removal confirmation, and it does not measure the error path for a
 * rejected endpoint. It does not prove the desktop disposes its assistant collaborators on unmount - the
 * harness mounts a fresh holder per leg and this file does not assert the old one was released.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopAssistantConfigureJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the assistant is configured through the real UI and reloads it after a rebuild`() =
        FakeProviderServer().use { provider ->
            runAtTheAppWindowSize {
                installProductionFixtureLayer()
                mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

                // ---- LEG 1, STEP 1: the assistant opens UNCONFIGURED --------------------------------
                openAssistantFromTheEntryCard(
                    entryFrame = "00-lenses-ai-assistance-entry",
                    consentFrame = "01-consent-gate",
                )

                // The disclosure comes first and is not dismissable: the user acknowledges it before
                // anything else on the surface is reachable.
                awaitNodeWithText(strings.assistantNoticeTitle)
                frame(
                    "02-first-open-notice",
                    "the AI-usage disclosure over the unconfigured assistant",
                    copy = strings.assistantNoticeTitle,
                )
                onAllNodesWithText(strings.assistantNoticeOk).onFirst().performClick()
                awaitNodeGone(strings.assistantNoticeTitle)

                // The unconfigured surface is a POINTER, not a form: its headline and its one button.
                awaitNodeWithText(strings.assistantSetupHeadline)
                assertThat(onAllNodesWithText(strings.assistantSetupPointer).fetchSemanticsNodes()).hasSize(1)
                assertThat(onAllNodesWithText(strings.assistantInputHint).fetchSemanticsNodes()).isEmpty()
                frame(
                    "03-assistant-unconfigured",
                    "the assistant that has never been configured: setup lives in settings",
                    copy = strings.assistantSetupHeadline,
                )

                // ---- LEG 1, STEP 2: the pointer opens the settings editor ---------------------------
                onAllNodesWithText(strings.assistantSetupPointer).onFirst().performClick()
                waitForIdle()
                awaitNodeWithText(strings.assistantProviderWhere)
                frame(
                    "04-settings-entry",
                    "the settings editor: where the model runs, with the upgrade default already shown",
                    copy = strings.assistantProviderWhere,
                )

                // ---- LEG 1, STEP 3: "On this device" ------------------------------------------------
                onAllNodesWithText(strings.assistantProviderLocal).onFirst().performScrollTo().performClick()
                waitForIdle()
                // The fields below the backend choice only exist once a provider does.
                awaitNodeWithText(strings.assistantAddress)
                awaitNodeWithText(strings.assistantApiKey)
                frame(
                    "05-backend-local",
                    "On this device chosen: the provider, address and key fields appear",
                    copy = strings.assistantProviderLocal,
                )

                // ---- LEG 1, STEP 4: the endpoint of the fake server ---------------------------------
                // Replaced, not appended: "On this device" prefills Ollama's own localhost address, and the
                // journey points that at the test server instead.
                textFieldLabelled(strings.assistantAddress)
                    .performScrollTo()
                    .performTextReplacement(provider.baseUrl)
                waitForIdle()
                assertThat(fieldValue(strings.assistantAddress)).isEqualTo(provider.baseUrl)
                frame(
                    "06-address-typed",
                    "the fake loopback endpoint typed into the address field",
                    copy = strings.assistantAddress,
                )

                // Opaque fixture key: the fake server ignores it, and it is what makes the chat surface
                // open (see the class KDoc's note on the screen's own configured-ness rule).
                textFieldLabelled(strings.assistantApiKey)
                    .performScrollTo()
                    .performTextReplacement(FAKE_PROVIDER_KEY)
                waitForIdle()

                // ---- LEG 1, STEP 5: "Load models" against the real endpoint ------------------------
                onAllNodesWithText(strings.assistantKeyLoadModels).onFirst().performScrollTo().performClick()
                awaitNodeWithText(strings.assistantModelPick)
                // The model list is the fake server's, and the client sorted it: the two ids it serves.
                assertThat(provider.paths()).contains(MODEL_PATH)
                frame(
                    "07-models-loaded",
                    "the two models the local endpoint reported",
                    copy = strings.assistantModelPick,
                )

                // ---- LEG 1, STEP 6: pick one of them ------------------------------------------------
                onAllNodesWithText(strings.assistantModelPick).onFirst().performScrollTo().performClick()
                awaitNodeWithText(MODEL_A)
                awaitNodeWithText(MODEL_B)
                onAllNodesWithText(MODEL_B).onFirst().performClick()
                awaitNodeGone(MODEL_A)
                assertThat(onAllNodesWithText(MODEL_B).fetchSemanticsNodes()).hasSize(1)
                frame(
                    "08-model-picked",
                    "the picked model in the model row",
                    copy = MODEL_B,
                )

                // ---- LEG 1, STEP 7: Verify, which is a real model list plus a real chat round-trip ---
                onAllNodesWithText(strings.assistantVerify).onFirst().performScrollTo().performClick()
                val verifySuccess = strings.assistantVerifySuccess.formatArgs(FAKE_MODEL_COUNT)
                onAllNodesWithText(verifySuccess).onFirst().performScrollTo()
                awaitNodeWithText(verifySuccess)
                assertThat(provider.chatBodies().any { it.contains(MODEL_B) }).isTrue()
                frame(
                    "09-verify-success",
                    "the connected line after the endpoint answered both calls",
                    copy = verifySuccess,
                )

                // ---- LEG 1, STEP 8: "Save & start" --------------------------------------------------
                onAllNodesWithText(strings.assistantSaveStart).onFirst().performScrollTo().performClick()
                // "Remove key" is rendered only while a key is STORED, so its appearance is the state
                // proof that the save reached the key draft, not just the fields.
                awaitNodeWithText(strings.assistantRemoveKey)
                frame(
                    "10-saved",
                    "the settings editor after the save: a key is now held, so it can be removed",
                    copy = strings.assistantRemoveKey,
                )

                // ---- LEG 1, STEP 9: back on chat, the setup pointer is gone -------------------------
                onAllNodesWithContentDescription(strings.locBack).onFirst().performClick()
                waitForIdle()
                awaitNodeGone(strings.assistantSetupHeadline)
                awaitNodeWithText(strings.assistantInputHint)
                assertThat(onAllNodesWithText(strings.assistantSetupPointer).fetchSemanticsNodes()).isEmpty()
                frame(
                    "11-chat-surface-configured",
                    "the chat surface the save opened; the setup pointer is gone",
                    copy = strings.assistantInputHint,
                )

                // ---- LEG 2: DISPOSE THE COMPOSITION AND REBUILD OVER THE SAME DATABASE --------------
                // The same two-step the first-run journey uses: an empty composition ends the mount, then
                // the shell is mounted again. The data root, the database file and the session are the
                // SAME ones, so everything asserted below is read back from storage.
                setContent { }
                waitForIdle()
                mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

                openAssistantFromTheEntryCard(
                    entryFrame = "12-restart-lenses-entry",
                    consentFrame = "13-restart-consent-gate",
                )

                // Waited for FIRST, and deliberately: the top bar renders the model only once `refresh()`
                // has read the store back, so this one wait is what makes every assertion below it about
                // reloaded state rather than about the pre-refresh defaults (which would make the notice
                // assertion pass on a screen that had not loaded anything yet).
                awaitNodeWithText(MODEL_B)
                awaitNodeGone(strings.assistantSetupHeadline)

                // The disclosure was acknowledged before the save, and that acknowledgement is a stored
                // row, so a restarting app must not ask again.
                assertThat(onAllNodesWithText(strings.assistantNoticeTitle).fetchSemanticsNodes()).isEmpty()
                frame(
                    "14-restart-chat-configured",
                    "the chat surface after the rebuild, still configured with the stored model",
                    copy = MODEL_B,
                )
                // The chat surface, not a shell with a top bar: its input row is what the save made reachable.
                assertThat(onAllNodesWithText(strings.assistantInputHint).fetchSemanticsNodes()).isNotEmpty()
                assertThat(onAllNodesWithText(strings.assistantSetupPointer).fetchSemanticsNodes()).isEmpty()

                // The gear itself is gated on `hasKey`, so finding it proves the KEY row was reloaded...
                awaitNodeWithTag(ASSISTANT_SETTINGS_BUTTON)
                onNodeWithTag(ASSISTANT_SETTINGS_BUTTON).performClick()
                waitForIdle()

                // ...and these two read the stored endpoint and model out of the fields themselves.
                awaitFieldValue(strings.assistantAddress, provider.baseUrl)
                awaitFieldValue(strings.assistantModelManual, MODEL_B)
                frame(
                    "15-restart-settings",
                    "the settings editor after the rebuild: the stored address and model",
                    copy = strings.assistantAddress,
                )
            }
        }

    // ---- journey steps shared by both legs -------------------------------------------------------

    /** Lenses -> the AI Assistance entry card -> the opt-in consent gate -> the assistant route. */
    private fun DesktopComposeUiTest.openAssistantFromTheEntryCard(
        entryFrame: String,
        consentFrame: String,
    ) {
        // The Lenses tab label equals the screen title, so the tab is taken by first match rather than by
        // the harness's singular lookup - the same reason `DesktopAssistantJourneyTest` does it this way.
        onAllNodesWithText(PayanamTab.Lenses.title(strings)).onFirst().performClick()
        waitForIdle()

        awaitNodeWithText(strings.assistantTitle)
        frame(
            entryFrame,
            "the Lenses screen offering the AI Assistance entry",
            copy = strings.assistantTitle,
        )

        // Scroll first: the entry card is the LAST element of the Lenses scroll column, and on a
        // phone-height surface it sits below the fold.
        onAllNodesWithText(strings.assistantEntryCta).onFirst().performScrollTo().performClick()
        awaitNodeWithText(strings.assistantConsentTitle)
        frame(
            consentFrame,
            "the opt-in consent dialog before the assistant opens",
            copy = strings.assistantConsentTitle,
        )

        onAllNodesWithText(strings.assistantConsentProceed).onFirst().performClick()
        waitForIdle()
    }

    // ---- field helpers ---------------------------------------------------------------------------

    /**
     * The text FIELD carrying [label] as its label, in the merged tree.
     *
     * A labelled `OutlinedTextField` publishes its LABEL as `Text` and its VALUE as `EditableText` on the
     * same merged node, and `hasText` matches either - so the pair with [hasSetTextAction] picks the field
     * rather than the plain label node beside it.
     */
    private fun DesktopComposeUiTest.textFieldLabelled(label: String) =
        onAllNodes(hasText(label) and hasSetTextAction()).onFirst()

    /** The text a labelled field currently holds, read from `EditableText` - never from the label. */
    private fun DesktopComposeUiTest.fieldValue(label: String): String =
        onAllNodes(hasText(label) and hasSetTextAction())
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.EditableText)?.text }
            .firstOrNull()
            .orEmpty()

    /** Waits for a labelled field to hold [expected]; the store's row arrives a frame or two later. */
    private fun DesktopComposeUiTest.awaitFieldValue(
        label: String,
        expected: String,
    ) {
        waitUntil(timeoutMillis = FIELD_TIMEOUT_MS) { fieldValue(label) == expected }
        assertThat(fieldValue(label)).isEqualTo(expected)
    }

    private companion object {
        /** What the fake server serves, and what the journey picks. Not copy, so an assertion is about a value. */
        const val MODEL_A = "fake-model-a"
        const val MODEL_B = "fake-model-b"
        const val FAKE_MODEL_COUNT = 2

        /**
         * An opaque fixture credential.
         *
         * Deliberately NOT shaped like a live secret: it reaches the encrypted database file of this
         * fixture and nothing else, and the fake server never inspects it. A fixture value that reads like
         * a real key is a hazard in a repository people paste logs from.
         */
        const val FAKE_PROVIDER_KEY = "desktop-journey-provider-key"

        /** The model-list path the shipped client appends to the configured address. */
        const val MODEL_PATH = "/v1/models"

        /** Long enough for a real socket round-trip into the JVM's own loopback; short enough to fail. */
        const val FIELD_TIMEOUT_MS = 10_000L
    }
}

/**
 * A minimal OpenAI-compatible endpoint on a loopback port, owned by one test.
 *
 * **Why a real server.** The shipped `OpenAiCompatibleClient` accepts plaintext ONLY for a loopback host,
 * so this is the single arrangement that exercises the app's real request building, real JSON and real
 * status handling without TLS, without a key and without leaving the machine. Everything the app sends is
 * recorded, which is what lets a journey assert "it called the address I typed" and "it used the model I
 * picked" rather than inferring both from the screen.
 *
 * **Ports.** Bound with port 0, so the JVM picks a free ephemeral port and the address is read back from the
 * bound socket - there is no separate "find a port, then hope it is still free" step.
 *
 * **Status.** [responseStatus] is what every answer is sent with, so a caller can drive the failure path
 * (a 401, a 500) without changing anything else.
 *
 * The server runs on its own single-threaded dispatcher and the client blocks on it from another thread, so
 * no handler can be starved by the test thread. [close] stops it with no grace period, which is safe because
 * no request is in flight when a `use {}` block exits.
 */
private class FakeProviderServer : AutoCloseable {
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), 0)

    /** The status every response is sent with. Defaults to a healthy 200. */
    @Volatile
    var responseStatus: Int = 200

    /** The root the app appends `/models` and `/chat/completions` to, as a user would type it. */
    val baseUrl: String

    init {
        server.createContext(MODEL_PATH) { exchange -> handle(exchange) }
        server.createContext(CHAT_PATH) { exchange -> handle(exchange) }
        server.start()
        baseUrl = "http://$LOOPBACK_HOST:${server.address.port}/v1"
    }

    /** Every request path the app asked for, in arrival order. */
    fun paths(): List<String> = recorded.map { it.path }

    /** Every chat-completion body the app sent, so a case can assert WHICH model was used. */
    fun chatBodies(): List<String> =
        recorded
            .filter { it.path.endsWith(CHAT_SUFFIX) }
            .map { it.body }

    private fun handle(exchange: HttpExchange) {
        // The body is read and drained on EVERY path, including an error status: an unread request body
        // makes the connection unusable and the client sees a reset rather than the status it was sent.
        val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
        recorded +=
            RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                body = body,
            )
        val payload = if (exchange.requestURI.path.endsWith(MODELS_SUFFIX)) MODELS_JSON else CHAT_JSON
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }

    /** One request as it arrived: what the app asked for, and what it sent. */
    data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val MODEL_PATH = "/v1/models"
        const val CHAT_PATH = "/v1/chat/completions"
        const val MODELS_SUFFIX = "/models"
        const val CHAT_SUFFIX = "/chat/completions"

        /** The model catalogue the client reads `data[].id` from. */
        val MODELS_JSON = """{"data":[{"id":"fake-model-a"},{"id":"fake-model-b"}]}"""

        /**
         * A valid chat completion, because the client's `validateKey` posts one and then requires a 2xx.
         * Without a parseable `choices[0]` the verify step would report a server failure.
         */
        val CHAT_JSON =
            """
            {"id":"fake-completion","object":"chat.completion",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """.trimIndent()
    }
}
