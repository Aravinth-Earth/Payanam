//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The shared Material 3 [AlertDialog], with a width that is the SAME on Android and the desktop.
 *
 * WHY THIS EXISTS - a measured cross-platform divergence. Material 3's `AlertDialog` defaults to
 * `DialogProperties(usePlatformDefaultWidth = true)`, and the two platforms answer "what is the platform
 * default width" differently: on Android the dialog window is `match_parent`, so at the app's 411 dp window
 * the surface is about **379 dp**; on the desktop the window wraps its content, so the same dialog is about
 * **320 dp**. Measured 2026-10-16 at a 411x891 dp window: the desktop's dialog content came out 272 dp
 * against Android's 331 dp, so every dialog on the desktop was ~60 dp narrower and its content had less
 * room. The parity probe (`DesktopLayoutProbeTest` + `build-tools/scripts/parity-screen-diff.py`) reports it
 * as `dL = 0.11` on the Notes editor and the recurrence picker.
 *
 * THE RULE. Take the width away from the platform and give it an explicit one: `usePlatformDefaultWidth =
 * false`, then `widthIn(max = [PAYANAM_DIALOG_MAX_WIDTH])` (so a wide desktop window does not stretch a
 * phone-shaped dialog) and `fillMaxWidth()` (so the dialog fills the width the dialog's OWN margins leave,
 * which at a 411 dp window is the same ~379 dp Android's platform default already produced - measured
 * 2026-10-16: the desktop's Notes-editor content moved from `L=0.207` to `L=0.097`, matching Android's
 * `L=0.097` exactly).
 *
 * Use this instead of `androidx.compose.material3.AlertDialog` for every dialog the shared screens show.
 * The `properties` parameter is honoured except for `usePlatformDefaultWidth`, which is always false here.
 */
@Composable
fun PayanamAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier =
            modifier
                .widthIn(max = PAYANAM_DIALOG_MAX_WIDTH)
                .fillMaxWidth(PAYANAM_DIALOG_WIDTH_FRACTION),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties =
            DialogProperties(
                dismissOnBackPress = properties.dismissOnBackPress,
                dismissOnClickOutside = properties.dismissOnClickOutside,
                usePlatformDefaultWidth = false,
            ),
    )
}

/** The widest a shared dialog grows, so a desktop window does not stretch a phone-shaped dialog. */
val PAYANAM_DIALOG_MAX_WIDTH: Dp = 560.dp

/**
 * The fraction of the available dialog width a shared dialog fills.
 *
 * `1f` - the dialog fills what the dialog's OWN margins leave, which at a 411 dp window is the same
 * ~379 dp Android's platform default already produced, so the desktop is brought TO Android rather than
 * Android being changed.
 */
const val PAYANAM_DIALOG_WIDTH_FRACTION: Float = 1f
