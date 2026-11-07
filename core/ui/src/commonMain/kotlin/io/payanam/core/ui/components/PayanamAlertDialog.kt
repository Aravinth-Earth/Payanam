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
 * The shared Material 3 [AlertDialog], with a width that is the SAME on Android and the desktop: the
 * platform default width diverges, so it is taken from the platform and set explicitly. Use this instead of
 * `material3.AlertDialog` for every dialog the shared screens show. [dismissOnClickOutside] is a second veto:
 * a dialog holding typed text passes `false` so an outside tap cannot discard the draft.
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
    dismissOnClickOutside: Boolean = true,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier =
            modifier
                .widthIn(max = PAYANAM_DIALOG_MAX_WIDTH)
                .fillMaxWidth(PAYANAM_DIALOG_WIDTH_FRACTION)
                // A dialog draws in its own window, so the app root's publication of test tags is not
                // an ancestor of this subtree and the tags inside a dialog stay invisible without this.
                .exposeTestTagsAsResourceIds(),
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
                // Either the dedicated parameter or a caller-supplied `properties` may veto it.
                dismissOnClickOutside = dismissOnClickOutside && properties.dismissOnClickOutside,
                usePlatformDefaultWidth = false,
            ),
    )
}

/** The widest a shared dialog grows, so a desktop window does not stretch a phone-shaped dialog. */
val PAYANAM_DIALOG_MAX_WIDTH: Dp = 560.dp

/** The fraction of the available dialog width a shared dialog fills; `1f` matches Android's own margins. */
const val PAYANAM_DIALOG_WIDTH_FRACTION: Float = 1f
