//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import java.io.IOException
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [UrlConnectionTransport], specifically the bounded response-body reader.
 * Uses a local [ServerSocket] to serve controlled HTTP responses.
 */
class UrlConnectionTransportTest {

    private val transport = UrlConnectionTransport()

    @Test
    fun `normal response is read correctly`() {
        withServer("Hello, Payanam!") { port ->
            val result = transport.get(
                url = "http://127.0.0.1:$port/",
                headers = emptyMap(),
                timeoutMs = 5_000,
            )
            assertEquals(200, result.code)
            assertEquals("Hello, Payanam!", result.body)
        }
    }

    @Test
    fun `empty response returns empty string`() {
        withServer("") { port ->
            val result = transport.get(
                url = "http://127.0.0.1:$port/",
                headers = emptyMap(),
                timeoutMs = 5_000,
            )
            assertEquals(200, result.code)
            assertEquals("", result.body)
        }
    }

    @Test
    fun `multi-byte UTF-8 characters are decoded correctly`() {
        // Emoji (4 bytes), accented chars (2-3 bytes), scripts (3 bytes) — common in
        // chat responses. Verifies the transport preserves multi-byte sequences end-to-end.
        val full = "Café ☕ こんにちは ₹500"
        withServer(full) { port ->
            val result = transport.get(
                url = "http://127.0.0.1:$port/",
                headers = emptyMap(),
                timeoutMs = 10_000,
            )
            assertEquals(200, result.code)
            assertEquals("Multi-byte UTF-8 preserved", full, result.body)
        }
    }

    @Test
    fun `response exceeding 5 MB throws IOException`() {
        val size = 5 * 1024 * 1024 + 1024 // 5 MB + 1 KB
        val body = "X".repeat(size)
        withServer(body) { port ->
            try {
                transport.get(
                    url = "http://127.0.0.1:$port/",
                    headers = emptyMap(),
                    timeoutMs = 30_000,
                )
                fail("Expected IOException for oversized response")
            } catch (e: IOException) {
                assertTrue(
                    "Message should mention size limit",
                    e.message?.contains("limit") == true,
                )
            }
        }
    }

    @Test
    fun `HTTP error status code is returned in HttpResult`() {
        withServer("not found", code = 404) { port ->
            val result = transport.get(
                url = "http://127.0.0.1:$port/",
                headers = emptyMap(),
                timeoutMs = 5_000,
            )
            assertEquals(404, result.code)
            assertEquals("not found", result.body)
        }
    }

    @Test
    fun `response close to 5 MB limit succeeds`() {
        // 4 MB — well under the 5 MB cap, should succeed without issues.
        val body = "Y".repeat(4 * 1024 * 1024)
        withServer(body) { port ->
            val result = transport.get(
                url = "http://127.0.0.1:$port/",
                headers = emptyMap(),
                timeoutMs = 15_000,
            )
            assertEquals(200, result.code)
            assertEquals(4 * 1024 * 1024, result.body.length)
        }
    }

    /**
     * Starts a [ServerSocket] on a random port, serves a single HTTP response, and invokes
     * [block] with the port number. The server shuts down after [block] completes.
     */
    private fun withServer(
        responseBody: String,
        code: Int = 200,
        block: (Int) -> Unit,
    ) {
        val server = ServerSocket(0)
        try {
            server.soTimeout = 10_000
            Thread {
                try {
                    val client = server.accept()
                    client.use { socket ->
                        socket.getInputStream().bufferedReader().readLine()
                        val bytes = responseBody.toByteArray(Charsets.UTF_8)
                        val header = buildString {
                            append("HTTP/1.1 $code OK\r\n")
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Content-Type: text/plain; charset=utf-8\r\n")
                            append("\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    // Client may disconnect early on overflow test — that's fine.
                }
            }.start()
            block(server.localPort)
        } finally {
            server.close()
        }
    }
}
