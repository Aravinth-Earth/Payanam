//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two behaviours only [OpenAiCompatibleClient] owns: the base-URL rule and the status table.
 *
 * Both are exercised through a real call rather than the private helpers, so the assertions see
 * what a caller sees: the URL that would have been dialled, and the failure that came back.
 */
class OpenAiCompatibleClientTest {
    private class FakeTransport(
        private val getHandler: ((String, Map<String, String>) -> HttpResult)? = null,
        private val postHandler: ((String, Map<String, String>, String) -> HttpResult)? = null,
    ) : HttpTransport {
        var calls = 0
        var lastUrl: String = ""
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String = ""

        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpResult {
            calls++
            lastUrl = url
            lastHeaders = headers
            return getHandler?.invoke(url, headers) ?: error("no get handler")
        }

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Int,
        ): HttpResult {
            calls++
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return postHandler?.invoke(url, headers, body) ?: error("no post handler")
        }
    }

    // ------------------------------------------------------------------ base-url rule

    @Test
    fun `an https base url is accepted`() {
        assertAccepted("https://example.test/v1", "https://example.test/v1/models")
    }

    @Test
    fun `a trailing slash is stripped so the path is not doubled`() {
        assertAccepted("https://example.test/v1/", "https://example.test/v1/models")
    }

    @Test
    fun `two trailing slashes are stripped and do not move the host`() {
        // "/v1//" must not become "//v1//models" (a protocol-relative path) or a different host.
        assertAccepted("https://example.test/v1//", "https://example.test/v1/models")
    }

    @Test
    fun `a plaintext localhost base url is accepted as the loopback exception`() {
        assertAccepted("http://localhost:11434/v1", "http://localhost:11434/v1/models")
    }

    @Test
    fun `a plaintext 127_0_0_1 base url is accepted as the loopback exception`() {
        assertAccepted("http://127.0.0.1:1234/v1", "http://127.0.0.1:1234/v1/models")
    }

    @Test
    fun `plaintext to a non-loopback host is refused without a request`() {
        // The key travels in a header, so a plaintext hop anywhere but the user's own machine
        // would expose it. Refusing is a setup mistake, not an outage: nothing is dialled.
        assertRefusedWithoutRequest("http://example.test/v1")
    }

    @Test
    fun `userinfo cannot make a remote host look like loopback`() {
        assertRefusedWithoutRequest("http://user@evil.test/v1")
    }

    @Test
    fun `a base url with no scheme is refused without a request`() {
        assertRefusedWithoutRequest("example.test/v1")
    }

    @Test
    fun `an empty base url is refused without a request`() {
        assertRefusedWithoutRequest("")
    }

    @Test
    fun `a blank base url is refused without a request`() {
        assertRefusedWithoutRequest("   ")
    }

    // ------------------------------------------------------------------ status table

    @Test
    fun `401 classifies as Auth and carries the status`() {
        assertClassified(401, AssistantError.Auth(httpStatus = 401))
    }

    @Test
    fun `403 classifies as Auth and carries the status`() {
        assertClassified(403, AssistantError.Auth(httpStatus = 403))
    }

    @Test
    fun `402 classifies as RateLimit and carries the status`() {
        assertClassified(402, AssistantError.RateLimit(httpStatus = 402))
    }

    @Test
    fun `429 classifies as RateLimit and carries the status`() {
        assertClassified(429, AssistantError.RateLimit(httpStatus = 429))
    }

    @Test
    fun `404 classifies as ModelNotFound and carries the status`() {
        assertClassified(404, AssistantError.ModelNotFound(httpStatus = 404))
    }

    @Test
    fun `413 classifies as ContextOverflow and carries the status`() {
        assertClassified(413, AssistantError.ContextOverflow(httpStatus = 413))
    }

    @Test
    fun `400 classifies as InvalidRequest and carries the status`() {
        assertClassified(400, AssistantError.InvalidRequest(httpStatus = 400))
    }

    @Test
    fun `422 classifies as InvalidRequest and carries the status`() {
        assertClassified(422, AssistantError.InvalidRequest(httpStatus = 422))
    }

    @Test
    fun `408 classifies as Timeout and carries the status`() {
        assertClassified(408, AssistantError.Timeout(httpStatus = 408))
    }

    @Test
    fun `500 classifies as Server and carries the status`() {
        assertClassified(500, AssistantError.Server(httpStatus = 500))
    }

    @Test
    fun `503 classifies as Server and carries the status`() {
        assertClassified(503, AssistantError.Server(httpStatus = 503))
    }

    @Test
    fun `an unlisted 4xx classifies as InvalidRequest and carries the status`() {
        // A 418 is the endpoint refusing this request; it is not a server fault.
        assertClassified(418, AssistantError.InvalidRequest(httpStatus = 418))
    }

    // ------------------------------------------------------------------ provider's own words

    @Test
    fun `the provider's own error body is kept in the detail`() {
        val body = "{\"error\":{\"message\":\"no such model: muse-spark-1\"}}"
        val failure = chatFailure(404, body)
        assertTrue("detail was <${failure.detail}>", failure.detail.contains(body))
    }

    @Test
    fun `an over-long error body is truncated rather than rewritten`() {
        val head = "HEAD-MARKER "
        val body = head + "x".repeat(900) + " TAIL-MARKER"
        val failure = chatFailure(400, body)
        assertTrue("the start of the body must survive", failure.detail.contains(head))
        assertFalse("the detail must be clipped, not rewritten", failure.detail.contains("TAIL-MARKER"))
        assertEquals(AssistantError.InvalidRequest(httpStatus = 400), failure.error)
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a models fetch against [baseUrl] and asserts the exact URL the transport was given. */
    private fun assertAccepted(
        baseUrl: String,
        expectedUrl: String,
    ) {
        val transport = FakeTransport(getHandler = { _, _ -> HttpResult(200, "{\"data\":[]}") })
        client(transport, baseUrl).fetchModels("k", "s")
        assertEquals(expectedUrl, transport.lastUrl)
    }

    /** Asserts [baseUrl] is rejected as a setup mistake before any request is attempted. */
    private fun assertRefusedWithoutRequest(baseUrl: String) {
        val transport = FakeTransport(getHandler = { _, _ -> HttpResult(200, "{\"data\":[]}") })
        val thrown = runCatching { client(transport, baseUrl).fetchModels("k", "s") }.exceptionOrNull()
        assertTrue("expected AssistantHttpException for <$baseUrl>, got $thrown", thrown is AssistantHttpException)
        assertEquals(AssistantError.InvalidRequest(), (thrown as AssistantHttpException).error)
        assertEquals("a refused address must not reach the network", 0, transport.calls)
    }

    /** Runs a chat call that answers [code] and asserts the case it is classified into. */
    private fun assertClassified(
        code: Int,
        expected: AssistantError,
    ) {
        val failure = chatFailure(code)
        assertEquals(expected, failure.error)
        assertEquals(code, failure.httpStatus ?: NOT_A_STATUS)
    }

    private fun chatFailure(
        code: Int,
        body: String = "provider says no",
    ): AssistantHttpException {
        val transport = FakeTransport(postHandler = { _, _, _ -> HttpResult(code, body) })
        val thrown =
            runCatching {
                client(transport).chat("k", "s", "m1", listOf(ChatTurn("user", "hi")))
            }.exceptionOrNull()
        assertTrue("expected AssistantHttpException for $code, got $thrown", thrown is AssistantHttpException)
        return thrown as AssistantHttpException
    }

    private fun client(
        transport: HttpTransport,
        baseUrl: String = SECURE_BASE,
    ): OpenAiCompatibleClient = OpenAiCompatibleClient(transport, baseUrl)

    private companion object {
        private const val SECURE_BASE = "https://example.test/v1"

        /** Only reached if the failure carried no status at all, which every case here must. */
        private const val NOT_A_STATUS = -1
    }
}
