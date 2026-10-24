//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Each provider failure reaches the user as ITS OWN sentence, rendered by the real settings screen.
 *
 * **The gap this closes.** `AssistantError` declares eight connection failures and
 * `AssistantSettingsScreen.connectionFailureText` gives every one of them a distinct string, while the coarse
 * `AssistantErrorKind` the screens also carry can only distinguish "key rejected", "network", "server",
 * "no model" and "locked". Nothing on the desktop had ever put a real status through the real client into
 * that mapping and then read what the screen actually drew: `DesktopAssistantConfigureJourneyTest` walks the
 * happy path and says so in its own KDoc ("does not measure the error path for a rejected endpoint"), and
 * `DesktopAssistantSharedScreenRenderTest` composes the screens over a hand-built state, so it can never
 * prove the state a real endpoint produces. A regression that collapsed all eight back into one generic
 * message would leave every one of those files green.
 *
 * **The claim, stated so that it cannot pass on a generic message.** Every case asserts
 *   1. the exact shared-table sentence for that failure is on screen (`strings.assistantError*`), and
 *   2. the SEVEN other failure sentences are NOT on screen, so a screen that showed a catch-all line
 *      instead of the specific one cannot satisfy the case, and
 *   3. the sentence and its `Dismiss` action are ONE merged semantics node, which is the screen's
 *      deliberate accessibility shape: a failure announced together with the way to clear it, not two
 *      unrelated nodes a screen reader reads as sentence-then-orphan-button.
 *
 * **Why a real HTTP server, and why the statuses are the ones they are.** `OpenAiCompatibleClient` accepts
 * plaintext only for a loopback host, so a test-owned `HttpServer` on an ephemeral loopback port is the one
 * endpoint the shipped client will really call without TLS. The server answers EVERY path with one status,
 * which is the whole knob: the settings screen's Verify runs `fetchModels` first, so the classified failure
 * comes out of the real request/status/classification path rather than a fixture's opinion of it.
 *  - The first case points the address at a port nobody listens on (bound, read, closed), so the failure is
 *    the client's own transport classification of a refused connection - no server at all.
 *  - The timeout case uses a 408 rather than a slow server: the shipped read deadline is 180 s, and a case
 *    that waits for a real socket timeout would cost three minutes to prove what the classifier already
 *    decides from the status. The socket-timeout arm is therefore NOT covered here - see *what it does not
 *    claim* below.
 *  - No key is typed. "On this device" selects `OLLAMA`, whose `requiresKey` is false, and the fake server
 *    never inspects the credential anyway - so nothing here depends on a secret existing.
 *
 * **The fake server is a local equivalent, deliberately.** The one in
 * `DesktopAssistantConfigureJourneyTest` is `private` to that file, so it cannot be reused across files; this
 * one is the same arrangement (real `HttpServer`, bound at port 0, loopback, body drained on every path)
 * reduced to the single knob these cases need - a fixed status. It is not a second idea, and it must not
 * grow into one.
 *
 * **What it does NOT claim.**
 *  - It says nothing about the Tamil table: every assertion reads the ENGLISH table through `strings`.
 *  - It measures the SETTINGS editor's failure line only. The chat surface's banner
 *    (`AssistantScreen.kt`) is a different rendering of the same failure, covered by neither this file nor
 *    any other desktop case.
 *  - The socket-timeout arm (`SocketTimeoutException`) is unmeasured; only the endpoint-answered 408 is.
 *  - Frames are written for three cases only (first, one middle, last). Eight near-identical PNGs of one red
 *    line is noise, not evidence.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopAssistantFailureMessagesTest : DesktopAppJourneyHarness() {
    /**
     * A refused connection - the endpoint that never answered at all.
     *
     * The address is a loopback port this test bound and then released, so the client's own transport layer
     * sees a real `ConnectException` and classifies it as `AssistantError.Transport`.
     */
    @Test
    fun `a refused connection shows the transport sentence`() =
        assertFailureSentence(
            expected = strings.assistantErrorTransport,
            address = closedLoopbackBaseUrl(),
            frameName = "01-transport-failure",
        )

    /**
     * 401 - the credential was refused.
     *
     * This case also proves the dismiss control is live: the failure row merges the sentence and the button
     * into one announced node, and clicking that button clears the line through the ViewModel, so the
     * accessibility claim is about a real control and not about a text pair that merely sits beside each
     * other.
     */
    @Test
    fun `a 401 shows the auth sentence and its dismiss action clears it`() =
        withFakeProvider(HTTP_UNAUTHORIZED) { address ->
            assertFailureSentence(
                expected = strings.assistantErrorAuth,
                address = address,
                dismissClearsTheLine = true,
            )
        }

    /** 429 - a burst limit or a spent quota, which is not a broken provider. */
    @Test
    fun `a 429 shows the rate-limit sentence`() =
        withFakeProvider(HTTP_TOO_MANY_REQUESTS) { address ->
            assertFailureSentence(expected = strings.assistantErrorRateLimit, address = address)
        }

    /** 404 - the endpoint does not serve the model, so the fix is a different name. */
    @Test
    fun `a 404 shows the model-missing sentence`() =
        withFakeProvider(HTTP_NOT_FOUND) { address ->
            assertFailureSentence(expected = strings.assistantErrorModelMissing, address = address)
        }

    /** 413 - the conversation no longer fits, so the fix is a narrower question. */
    @Test
    fun `a 413 shows the context-overflow sentence`() =
        withFakeProvider(HTTP_PAYLOAD_TOO_LARGE) { address ->
            assertFailureSentence(
                expected = strings.assistantErrorContext,
                address = address,
                frameName = "02-context-overflow-failure",
            )
        }

    /** 400 - the endpoint rejected the request as malformed before any model ran. */
    @Test
    fun `a 400 shows the rejected-request sentence`() =
        withFakeProvider(HTTP_BAD_REQUEST) { address ->
            assertFailureSentence(expected = strings.assistantErrorInvalid, address = address)
        }

    /** 408 - the endpoint answered, but as a timeout, which is its own advice. */
    @Test
    fun `a 408 shows the timeout sentence`() =
        withFakeProvider(HTTP_REQUEST_TIMEOUT) { address ->
            assertFailureSentence(expected = strings.assistantErrorTimeout, address = address)
        }

    /** 503 - the endpoint failed on its own side, so waiting is the right advice. */
    @Test
    fun `a 503 shows the provider-problem sentence`() =
        withFakeProvider(HTTP_UNAVAILABLE) { address ->
            assertFailureSentence(
                expected = strings.assistantErrorServer,
                address = address,
                frameName = "03-server-failure",
            )
        }

    // ---- the shared arrangement -------------------------------------------------------------------

    /**
     * Drives the REAL settings screen from a cold shell to a Verify against [address], then asserts the
     * failure line.
     *
     * The walk is the user's own: Lenses -> the AI Assistance entry -> the opt-in consent gate -> the chat
     * surface's setup pointer -> the settings editor -> "On this device" -> the address -> Verify. It is
     * deliberately the same steps `DesktopAssistantConfigureJourneyTest` walks, minus the key and the model,
     * because the claim here is about what the failure line says and not about configuring a connection.
     *
     * @param expected the exact shared-table sentence this failure must produce.
     * @param address the endpoint root to type into the address field.
     * @param frameName when non-null, a PNG + text record of this step is written under the journey run
     *   directory. Only three cases pass one; see the class KDoc.
     * @param dismissClearsTheLine when true, the case goes on to click Dismiss and waits for the line to go.
     */
    private fun assertFailureSentence(
        expected: String,
        address: String,
        frameName: String? = null,
        dismissClearsTheLine: Boolean = false,
    ) = runAtTheAppWindowSize {
        installProductionFixtureLayer()
        mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

        openSettingsAndVerify(address)

        // The sentence this failure OWNS, waited for rather than asserted straight away: the request goes
        // out on the ViewModel's own dispatcher and returns through the store's state flow.
        awaitNodeWithText(expected)
        onAllNodesWithText(expected).assertCountEquals(1)

        // The other seven, absent. This is what makes the case about the SPECIFIC sentence: a screen that
        // collapsed everything into one catch-all line would fail whichever sentence it chose, because at
        // most one of them can be the expected one.
        otherFailureSentences(expected).forEach { other ->
            onAllNodesWithText(other).assertCountEquals(0)
        }

        // The sentence and its dismiss action are TWO nodes, and that is correct: a button has to stay its
        // own focusable target or a screen reader cannot reach it. What matters is that the sentence is
        // announced once and the action is reachable, so both are asserted separately.
        onAllNodesWithText(expected).assertCountEquals(1)
        assertThat(onAllNodesWithText(strings.assistantErrorDismiss).fetchSemanticsNodes()).isNotEmpty()

        if (frameName != null) {
            frame(
                frameName,
                "the settings editor after Verify failed: $expected",
                copy = expected,
            )
        }

        if (dismissClearsTheLine) {
            // Clicked in the UNMERGED tree on purpose: the action belongs to the trailing `TextButton`, and
            // the merged row's own centre is over the sentence, not over the button - a click there would
            // land on text that does nothing. The unmerged `Text`'s centre is inside the button's hit area.
            // The BUTTON node carries the click action; in the unmerged tree this text has none, which is
            // why clicking it there did nothing. The button is its own node, so the merged tree finds it.
            // Invoke the button's own action instead of tapping coordinates: the error row merges its
            // children, so a coordinate click lands on the sentence above the button and does nothing.
            onAllNodes(hasText(strings.assistantErrorDismiss) and hasClickAction())
                .onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
            // Dismissing clears the ERROR LINE, not every occurrence of the same words: a rejected key
            // also shows the auth sentence as the key field support text, which stays until the key is replaced.
            awaitNodeGone(strings.assistantErrorDismiss)
        }
    }

    /** Walks the user's own path from the shell to a Verify press on the settings editor. */
    private fun DesktopComposeUiTest.openSettingsAndVerify(address: String) {
        // The Lenses tab label equals the screen title, so the tab is taken by first match rather than by a
        // singular lookup - the same reason the configure journey does it this way.
        onAllNodesWithText(PayanamTab.Lenses.title(strings)).onFirst().performClick()
        waitForIdle()
        awaitNodeWithText(strings.assistantTitle)

        // The entry card is the LAST element of the Lenses scroll column, so it needs scrolling into view.
        onAllNodesWithText(strings.assistantEntryCta).onFirst().performScrollTo().performClick()
        awaitNodeWithText(strings.assistantConsentTitle)
        onAllNodesWithText(strings.assistantConsentProceed).onFirst().performClick()
        waitForIdle()

        // The AI-usage disclosure comes first on a fresh install and is not dismissable.
        awaitNodeWithText(strings.assistantNoticeTitle)
        onAllNodesWithText(strings.assistantNoticeOk).onFirst().performClick()
        awaitNodeGone(strings.assistantNoticeTitle)

        // The unconfigured chat surface is a pointer to settings, which is where the connection lives.
        awaitNodeWithText(strings.assistantSetupHeadline)
        onAllNodesWithText(strings.assistantSetupPointer).onFirst().performClick()
        waitForIdle()
        awaitNodeWithText(strings.assistantProviderWhere)

        // "On this device": the fields below the backend choice only render once a provider exists, and
        // OLLAMA needs no key, so no credential is typed anywhere in this file.
        onAllNodesWithText(strings.assistantProviderLocal).onFirst().performScrollTo().performClick()
        waitForIdle()
        // The address field only exists once a provider does; waiting for it is what makes the replacement
        // below an interaction with a real field rather than with a node that is not there yet.
        awaitNodeWithText(strings.assistantAddress)

        // REPLACED, not appended: the backend choice prefilled Ollama's own localhost address.
        textFieldLabelled(strings.assistantAddress)
            .performScrollTo()
            .performTextReplacement(address)
        waitForIdle()
        // The endpoint the failure is about is the one on screen - asserted, so a field that silently kept
        // its prefilled default cannot produce this case's verdict about the wrong host.
        assertThat(fieldValue(strings.assistantAddress)).isEqualTo(address)

        onAllNodesWithText(strings.assistantVerify).onFirst().performScrollTo().performClick()
    }

    /** The seven failure sentences that are NOT [expected], so each case rules out a generic message. */
    private fun otherFailureSentences(expected: String): List<String> =
        listOf(
            strings.assistantErrorAuth,
            strings.assistantErrorTransport,
            strings.assistantErrorTimeout,
            strings.assistantErrorRateLimit,
            strings.assistantErrorServer,
            strings.assistantErrorModelMissing,
            strings.assistantErrorContext,
            strings.assistantErrorInvalid,
        ).filterNot { it == expected }

    /**
     * The text FIELD carrying [label] as its label, in the merged tree.
     *
     * A labelled `OutlinedTextField` publishes its LABEL as `Text` and its VALUE as `EditableText` on the
     * same merged node, and `hasText` matches either - so the pair with [hasSetTextAction] picks the field
     * rather than the plain label node beside it. Same helper as the configure journey's, duplicated because
     * that file keeps it private.
     */
    private fun DesktopComposeUiTest.textFieldLabelled(label: String) = onAllNodes(hasText(label) and hasSetTextAction()).onFirst()

    /** The text a labelled field currently holds, read from `EditableText` - never from the label. */
    private fun DesktopComposeUiTest.fieldValue(label: String): String =
        onAllNodes(hasText(label) and hasSetTextAction())
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.EditableText)?.text }
            .firstOrNull()
            .orEmpty()

    private companion object {
        /** 401 Unauthorized: the credential was refused. */
        const val HTTP_UNAUTHORIZED = 401

        /** 400 Bad Request: rejected as malformed before any model ran. */
        const val HTTP_BAD_REQUEST = 400

        /** 404 Not Found: the endpoint does not serve the requested model. */
        const val HTTP_NOT_FOUND = 404

        /** 408 Request Timeout: answered, but as a timeout. */
        const val HTTP_REQUEST_TIMEOUT = 408

        /** 413 Payload Too Large: the conversation no longer fits the context window. */
        const val HTTP_PAYLOAD_TOO_LARGE = 413

        /** 429 Too Many Requests: a burst limit or a spent quota. */
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** 503 Service Unavailable: the endpoint's own side failed. */
        const val HTTP_UNAVAILABLE = 503
    }
}

/**
 * Binds an ephemeral loopback port, reads it, and releases it - an address that will refuse the connection.
 *
 * Bound to `127.0.0.1` explicitly rather than to the wildcard address, so the port is one nothing else on
 * this machine is listening on (a wildcard bind would leave a listener on another interface sharing it).
 * The socket is closed before the address is returned, which is what makes the connection refused rather
 * than accepted.
 */
private fun closedLoopbackBaseUrl(): String {
    val loopback = InetAddress.getByName(LOOPBACK_HOST)
    val port = ServerSocket(0, 1, loopback).use { it.localPort }
    return "http://$LOOPBACK_HOST:$port/v1"
}

/** Runs [block] with the address of a fake provider that answers every request with [status]. */
private fun withFakeProvider(
    status: Int,
    block: (String) -> Unit,
) = StatusOnlyProvider(status).use { server -> block(server.baseUrl) }

/**
 * A minimal OpenAI-compatible endpoint on a loopback port whose ONLY behaviour is a fixed status.
 *
 * **Why a real server.** The shipped `OpenAiCompatibleClient` accepts plaintext only for a loopback host, so
 * this is the single arrangement that puts a real status through the app's real request building, real
 * socket, and real classifier without TLS, without a key and without leaving the machine. A scripted
 * transport would let the test decide the classification, which is the thing under measurement.
 *
 * **Ports.** Bound with port 0, so the JVM picks a free ephemeral port and the address is read back from the
 * bound socket - no "find a port, then hope it is still free" step.
 *
 * **The body is drained on every path, including an error status:** an unread request body makes the
 * connection unusable and the client then sees a reset instead of the status it was sent.
 *
 * This is the same arrangement as `DesktopAssistantConfigureJourneyTest`'s helper (which is private to that
 * file), reduced to the one knob these cases need. It must not grow a second purpose.
 */
private class StatusOnlyProvider(
    private val status: Int,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), 0)

    /** The root the app appends `/models` and `/chat/completions` to, as a user would type it. */
    val baseUrl: String

    init {
        server.createContext(MODELS_PATH) { exchange -> answer(exchange) }
        server.createContext(CHAT_PATH) { exchange -> answer(exchange) }
        server.start()
        baseUrl = "http://$LOOPBACK_HOST:${server.address.port}/v1"
    }

    private fun answer(exchange: HttpExchange) {
        exchange.requestBody.use { it.readBytes() }
        val bytes = ERROR_BODY.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        // No grace period: no request is in flight when the `use {}` block exits.
        server.stop(0)
    }

    private companion object {
        const val MODELS_PATH = "/v1/models"
        const val CHAT_PATH = "/v1/chat/completions"

        /** A canonical OpenAI-style error body. Never parsed on an error status; kept shaped, not plain. */
        val ERROR_BODY = """{"error":{"message":"fixture provider failure","type":"invalid_request_error"}}"""
    }
}

/** The loopback spelling the shipped client accepts for plaintext, used by both helpers above. */
private const val LOOPBACK_HOST = "127.0.0.1"
