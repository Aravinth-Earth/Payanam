//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import javax.inject.Inject

/**
 * Retains the shared ViewModels across Android's configuration changes.
 *
 * The ViewModels in `:core:presentation` are plain classes on purpose — `:core:ui` renders the same screens on
 * the desktop, where `androidx.lifecycle.ViewModel` does not exist — and their own KDoc says the platform
 * decides how they are retained. This is Android's answer, and it is deliberately the only Android-specific
 * part of a shared screen: the screen itself asks for nothing but its ViewModel.
 *
 * One holder per ViewModel-store owner, keyed by route, so a configuration change does not rebuild the state
 * and a screen that leaves the back stack does not keep it. [onCleared] releases each shared ViewModel through
 * the release action its caller registered.
 */
@HiltViewModel
internal class SharedViewModelHolder @Inject constructor() : ViewModel() {
    private val retained = mutableMapOf<String, Any>()
    private val releases = mutableListOf<() -> Unit>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(
        key: String,
        create: () -> T,
        onRelease: (T) -> Unit = {},
    ): T {
        retained[key]?.let { existing -> return existing as T }
        UnifiedLogger.getInstance().d(
            source = "SharedViewModelHolder",
            message = "Created shared view model",
            data = mapOf("key" to key),
        )
        return create().also { created ->
            retained[key] = created
            releases += { onRelease(created) }
        }
    }

    override fun onCleared() {
        releases.forEach { release -> release() }
        releases.clear()
        retained.clear()
    }
}

/**
 * The one-line entry point a destination uses to get a shared ViewModel that survives a configuration change.
 *
 * [key] identifies the ViewModel within its store owner, so two screens that want different instances must
 * pass different keys.
 */
@Composable
internal fun <T : Any> retainSharedViewModel(
    key: String,
    onRelease: (T) -> Unit = {},
    create: () -> T,
): T {
    val holder: SharedViewModelHolder = hiltViewModel()
    return holder.get(key = key, create = create, onRelease = onRelease)
}
