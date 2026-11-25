//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Runs [onOpen] each time [opens] reports an open session, which is what an unlock produces. */
class UnlockRestore(
    private val opens: Flow<Boolean>,
    private val onOpen: suspend () -> Unit,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            opens.collect { open ->
                if (open) onOpen()
            }
        }
    }
}
