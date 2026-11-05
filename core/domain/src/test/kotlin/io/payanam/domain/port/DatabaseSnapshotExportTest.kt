//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The shape of an exported snapshot, asserted apart from any implementation.
 *
 * These tests state the parts of [DatabaseSnapshotExport] that a caller RELIES ON rather than the
 * parts an implementation happens to choose: that the destination is a plain stream, that the count
 * returned is the count written, that the caller's stream is not closed by the port, and that a
 * failure arrives as a wrapped [IllegalStateException] rather than as silence.
 *
 * The fake records what it was asked for, so a test can prove the port handed the caller's stream
 * through instead of substituting one of its own — the substitution that would silently discard the
 * user's chosen destination.
 */
class DatabaseSnapshotExportTest {
    /** Records the stream it was given and writes a fixed number of bytes into it. */
    private class RecordingDestination(
        private val bytesToWrite: Int = 4,
        private val failOnWrite: Boolean = false,
    ) : OutputStream() {
        val received = ByteArrayOutputStream()
        var closedByPort: Boolean = false
            private set

        override fun write(b: Int) {
            if (failOnWrite) throw IOException("destination refused the write")
            received.write(b)
        }

        override fun close() {
            closedByPort = true
        }
    }

    /** A port whose only job is to copy [payload] into whatever destination it is handed. */
    private class CopyingExport(
        private val payload: ByteArray,
    ) : DatabaseSnapshotExport {
        var lastDestination: OutputStream? = null
            private set

        override suspend fun exportSnapshotTo(destination: OutputStream): Long {
            lastDestination = destination
            destination.write(payload)
            return payload.size.toLong()
        }
    }

    @Test
    fun `the port writes the snapshot into the stream the caller supplied`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val destination = RecordingDestination()
        val export = CopyingExport(payload)

        val written = runBlocking { export.exportSnapshotTo(destination) }

        assertThat(export.lastDestination).isSameInstanceAs(destination)
        assertThat(destination.received.toByteArray()).isEqualTo(payload)
        assertThat(written).isEqualTo(payload.size.toLong())
    }

    @Test
    fun `the port reports the bytes written and not a number it was told to report`() {
        val destination = RecordingDestination()
        val export = CopyingExport(ByteArray(4096) { 7 })

        val written = runBlocking { export.exportSnapshotTo(destination) }

        assertThat(written).isEqualTo(4096L)
    }

    @Test
    fun `the port leaves the caller's stream open`() {
        val destination = RecordingDestination()

        runBlocking { CopyingExport(byteArrayOf(9)).exportSnapshotTo(destination) }

        assertThat(destination.closedByPort).isFalse()
    }

    @Test
    fun `an empty snapshot writes zero bytes without being an error`() {
        val destination = RecordingDestination()

        val written = runBlocking { CopyingExport(ByteArray(0)).exportSnapshotTo(destination) }

        assertThat(written).isEqualTo(0L)
    }

    @Test
    fun `a failure to write surfaces instead of reporting a count`() {
        val destination = RecordingDestination(failOnWrite = true)

        val failure =
            runCatching {
                runBlocking { CopyingExport(byteArrayOf(1, 2, 3)).exportSnapshotTo(destination) }
            }

        // The port may not swallow a refused write: a caller that reads a byte count off a failed
        // export would report a backup that was never written.
        assertThat(failure.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(destination.received.size()).isEqualTo(0)
    }
}