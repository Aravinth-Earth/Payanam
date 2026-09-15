//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenCodeGoClientTest {
    private class FakeTransport(
        private val getHandler: ((String, Map<String, String>) -> HttpResult)? = null,
        private val postHandler: ((String, Map<String, String>, String) -> HttpResult)? = null,
    ) : HttpTransport {
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String = ""

        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult {
            lastHeaders = headers
            return getHandler?.invoke(url, headers) ?: error("no get handler")
        }

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpResult {
            lastHeaders = headers
            lastBody = body
            return postHandler?.invoke(url, headers, body) ?: error("no post handler")
        }
    }

    @Test
    fun `key validation is a real chat call and rejects a bad key`() {
        val transport = FakeTransport(
            postHandler = { _, _, _ -> HttpResult(401, "{\"error\":\"invalid_api_key\"}") },
        )
        val error = runCatching { OpenCodeGoClient(transport).validateKey("bad-key", "s", "m1") }.exceptionOrNull()
        assertTrue(error is AssistantHttpException)
        assertEquals(AssistantErrorKind.KEY_REJECTED, (error as AssistantHttpException).kind)
        assertTrue(transport.lastHeaders["Authorization"] == "Bearer bad-key")
    }

    @Test
    fun `provider failure keeps the provider's own message`() {
        val transport = FakeTransport(
            postHandler = { _, _, _ ->
                HttpResult(403, "{\"type\":\"error\",\"error\":{\"type\":\"RegionError\",\"message\":\"hosted elsewhere\"}}")
            },
        )
        val error = runCatching { OpenCodeGoClient(transport).validateKey("good-key", "s", "m1") }.exceptionOrNull()
        assertTrue(error is AssistantHttpException)
        val http = error as AssistantHttpException
        assertEquals(AssistantErrorKind.SERVER, http.kind)
        assertTrue(http.detail.contains("RegionError"))
    }

    @Test
    fun `key validation passes on an accepted key`() {
        val transport = FakeTransport(postHandler = { _, _, _ -> HttpResult(200, "{\"choices\":[]}") })
        OpenCodeGoClient(transport).validateKey("good-key", "s", "m1")
        assertTrue(transport.lastBody.contains("\"max_tokens\":1"))
        assertTrue(transport.lastBody.contains("ping"))
    }

    @Test
    fun `fetch models keeps non-blocked ids and puts the default first`() {
        val body = "{\"data\":[" +
            "{\"id\":\"muse-spark-1\"}," +
            "{\"id\":\"kimi-k2.6\"}," +
            "{\"id\":\"deepseek-v4.1-flash\"}," +
            "{\"id\":\"glm-5.3\"}]}"
        val client = OpenCodeGoClient(FakeTransport(getHandler = { _, _ -> HttpResult(200, body) }))
        val models = client.fetchModels("key", "session")
        assertEquals(listOf("deepseek-v4.1-flash", "glm-5.3", "kimi-k2.6"), models)
    }

    @Test
    fun `every call carries auth session and user agent headers`() {
        val transport = FakeTransport(getHandler = { _, _ -> HttpResult(200, "{\"data\":[]}") })
        OpenCodeGoClient(transport).fetchModels("secret-key", "session-1")
        assertEquals("Bearer secret-key", transport.lastHeaders["Authorization"])
        assertEquals("session-1", transport.lastHeaders["x-opencode-session"])
        assertEquals(AssistantDefaults.USER_AGENT, transport.lastHeaders["User-Agent"])
    }

    @Test
    fun `chat parses content usage and finish reason`() {
        val body = "{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]," +
            "\"usage\":{\"prompt_tokens\":33,\"completion_tokens\":10}}"
        val client = OpenCodeGoClient(FakeTransport(postHandler = { _, _, _ -> HttpResult(200, body) }))
        val reply = client.chat("k", "s", "deepseek-v4.1-flash", listOf(ChatTurn("user", "hi")))
        assertEquals("hello", reply.content)
        assertEquals("stop", reply.finishReason)
        assertEquals(33, reply.promptTokens)
        assertEquals(10, reply.completionTokens)
    }

    @Test
    fun `chat request body carries model and messages`() {
        val transport = FakeTransport(
            postHandler = { _, _, _ ->
                HttpResult(200, "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}")
            },
        )
        OpenCodeGoClient(transport).chat("k", "s", "deepseek-v4.1-flash", listOf(ChatTurn("user", "hi")))
        assertTrue(transport.lastBody.contains("\"model\":\"deepseek-v4.1-flash\""))
        assertTrue(transport.lastBody.contains("\"role\":\"user\""))
        assertTrue(transport.lastBody.contains("\"stream\":false"))
    }

    @Test
    fun `only 401 is classified as a key rejection`() {
        // Authentication is the 401. Every other provider status (403 included) is the provider's
        // own failure and must keep the provider's message — never our wording.
        listOf(
            401 to AssistantErrorKind.KEY_REJECTED,
            403 to AssistantErrorKind.SERVER,
        ).forEach { (code, kind) ->
            val client = OpenCodeGoClient(
                FakeTransport(
                    getHandler = { _, _ -> HttpResult(code, "nope") },
                    postHandler = { _, _, _ -> HttpResult(code, "nope") },
                ),
            )
            try {
                client.fetchModels("k", "s")
                fail("expected AssistantHttpException for $code")
            } catch (http: AssistantHttpException) {
                assertEquals(kind, http.kind)
            }
            try {
                client.chat("k", "s", "m", listOf(ChatTurn("user", "hi")))
                fail("expected AssistantHttpException for $code")
            } catch (http: AssistantHttpException) {
                assertEquals(kind, http.kind)
            }
        }
    }

    @Test
    fun `other error codes classify as server errors`() {
        val client = OpenCodeGoClient(FakeTransport(postHandler = { _, _, _ -> HttpResult(500, "boom") }))
        try {
            client.chat("k", "s", "m", listOf(ChatTurn("user", "hi")))
            fail("expected AssistantHttpException")
        } catch (http: AssistantHttpException) {
            assertEquals(AssistantErrorKind.SERVER, http.kind)
        }
    }

    @Test
    fun `io failures propagate to the caller`() {
        val client = OpenCodeGoClient(
            FakeTransport(getHandler = { _, _ -> throw IOException("no network") }),
        )
        try {
            client.fetchModels("k", "s")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertEquals("no network", expected.message)
        }
    }
}
