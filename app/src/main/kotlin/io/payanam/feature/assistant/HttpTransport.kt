//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP result: status code + body text. */
data class HttpResult(val code: Int, val body: String)

/**
 * Transport seam for the OpenCode Go client so unit tests can run without a socket.
 *
 * Implementations throw [java.io.IOException] on network failures; HTTP error codes are
 * returned normally in [HttpResult.code] with the error body preserved.
 */
interface HttpTransport {
    /** Performs a GET request with the given headers. */
    fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult

    /** Performs a POST request with a JSON body and the given headers. */
    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult
}

/**
 * Production transport built on [HttpURLConnection] — the same dependency-free stack the
 * app already uses in `UpdateChecker`. No third-party HTTP client is introduced.
 */
class UrlConnectionTransport : HttpTransport {
    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult =
        request(method = "GET", url = url, headers = headers, body = null, readTimeoutMs = timeoutMs)

    override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult =
        request(method = "POST", url = url, headers = headers, body = body, readTimeoutMs = timeoutMs)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        readTimeoutMs: Int,
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            // Never follow redirects: a 3xx must not re-send the Authorization header
            // (and the request body) to another host.
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
    }
}
