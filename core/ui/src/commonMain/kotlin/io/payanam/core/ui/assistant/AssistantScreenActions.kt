//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.assistant

import io.payanam.domain.assistant.AssistantLength
import io.payanam.domain.assistant.AssistantProvider

/**
 * Everything the assistant chat screen can do, named once.
 *
 * WHY A HOLDER: the screen drives two states (loading, the chat) and the handful of controls each of
 * them shows, and each of them is a write the platform owns. Threading them as parameters would put that
 * many lambdas on the signature of a screen a third destination will later want, and every one of them
 * defaults to a no-op - which is precisely how a control ends up rendering as if it worked while
 * reaching nothing. Naming them here means a caller either wires a write or sees it unwired.
 *
 * The lifecycle members are separated from the data members on purpose: [onScreenEntered],
 * [onNavigateBack] and [onOpenSettings] fire on a screen event, while the rest are a control reporting a
 * value, so a reader can tell which callbacks exist to keep the screen in step with storage.
 *
 * There are deliberately no configuration writes here. Setup is the settings editor's job
 * ([AssistantSettingsScreenActions]), and this surface only says so and points at it.
 */
class AssistantScreenActions(
    /**
     * Re-reads the stored key, model and settings. Fired once when the screen enters composition, and
     * it has to be a callback rather than a direct read of shared state: the settings editor is a
     * separate destination with its own scope, so a key removed over there must be visible on the way
     * back, and only the platform knows what re-reading means (a store load on Android, a refresh on
     * the desktop).
     */
    val onScreenEntered: () -> Unit = {},
    val onNavigateBack: () -> Unit = {},
    /**
     * Opens the settings editor, which owns the provider, address, key and model.
     *
     * It is the ONLY route into setup now that the chat surface has no setup form of its own, and the
     * gear in the top bar is the same destination reached from a second place - not a second route.
     */
    val onOpenSettings: () -> Unit = {},
    val onSendQuestion: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onAcknowledgeNotice: () -> Unit = {},
)

/**
 * Every write the assistant settings editor can make, and the one read it needs from the platform.
 *
 * The three length presets and the six About-me fields are deliberately NOT folded into one
 * "settings changed" callback: each of them writes a different column, and a screen that reported a
 * merged value could not tell a caller which one the user touched. Every field reports its own value,
 * so a caller can persist the one that changed and leave the rest of the row alone.
 *
 * [onFlushRequested] is separate from the field callbacks because it is a STORE write, not a field
 * change: the editor holds edits in memory and the platform decides when they reach storage, which is
 * why it is requested on the way out of the screen rather than on every keystroke.
 */
class AssistantSettingsScreenActions
    @JvmOverloads
    constructor(
        val onScreenEntered: () -> Unit = {},
        val onFlushRequested: () -> Unit = {},
        val onNavigateBack: () -> Unit = {},
        /** Removes the stored key, the model and the reconfigure flag in one transaction. */
        val onRemoveKeyRequested: () -> Unit = {},
        val onLengthChange: (AssistantLength) -> Unit = {},
        val onChipsEnabledChange: (Boolean) -> Unit = {},
        val onShowMethodChange: (Boolean) -> Unit = {},
        val onMaxRowsChange: (Int) -> Unit = {},
        val onRoundsChange: (Int) -> Unit = {},
        val onHistoryDepthChange: (Int) -> Unit = {},
        val onPromptAdditionsChange: (String) -> Unit = {},
        val onAboutGoalsChange: (String) -> Unit = {},
        val onAboutWantsChange: (String) -> Unit = {},
        val onAboutNeedsChange: (String) -> Unit = {},
        val onAboutStrengthsChange: (String) -> Unit = {},
        val onAboutFocusAreasChange: (String) -> Unit = {},
        val onAboutNoteChange: (String) -> Unit = {},
        // --- the connection: where the model runs, and how to reach it -------------------------
        /** Chooses the provider; the address follows its default unless the user typed their own. */
        val onProviderSelected: (AssistantProvider) -> Unit = {},
        val onBaseUrlChange: (String) -> Unit = {},
        val onKeyChange: (String) -> Unit = {},
        val onLoadModels: () -> Unit = {},
        val onSelectModel: (String) -> Unit = {},
        /** Checks the provider, address, key and model currently on screen, not the saved ones. */
        val onVerifyConnection: () -> Unit = {},
        val onDismissError: () -> Unit = {},
        /** The only path that stores the endpoint, the key and the settings. */
        val onSaveAndStart: () -> Unit = {},
        val onKeyRemovalConfirm: () -> Unit = {},
        val onKeyRemovalCancel: () -> Unit = {},
    )
