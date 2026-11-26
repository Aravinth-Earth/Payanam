//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockRestoreTest {
    @Test
    fun `runs once per unlock and never while the session is closed`() = runTest {
        var runs = 0

        UnlockRestore(flowOf(false, true, false, true)) { runs += 1 }.start(this)
        advanceUntilIdle()

        assertEquals(2, runs)
    }

    @Test
    fun `does nothing when the session never opens`() = runTest {
        var runs = 0

        UnlockRestore(flowOf(false, false)) { runs += 1 }.start(this)
        advanceUntilIdle()

        assertEquals(0, runs)
    }
}
