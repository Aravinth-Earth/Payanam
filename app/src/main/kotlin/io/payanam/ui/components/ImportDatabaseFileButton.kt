//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger

/** Test-only locator for the single-file import control (see maestro/e2e/SPEC.md selector policy). */
internal const val IMPORT_DATABASE_FILE_BUTTON_TAG = "import_database_file_button"

/**
 * MIME filter passed to every launch of the single-file import picker.
 *
 * The wildcard type, because Android has no dependable MIME mapping for `.db` (a specific MIME
 * would hide files on providers reporting octet-stream); the `.db` extension check in
 * resolveFromSingleFile() is the gate.
 * A `val` of [Array], never `const`: arrays have no compile-time constant form.
 */
internal val IMPORT_PICKER_MIME: Array<String> = arrayOf("*/*")

/** Which Material button the shared import control renders as. */
internal enum class ImportButtonStyle { FILLED, OUTLINED }

private val FILLED_SPINNER_SIZE = 24.dp
private val OUTLINED_SPINNER_SIZE = 16.dp
private val FILLED_SPINNER_STROKE = 4.dp
private val OUTLINED_SPINNER_STROKE = 2.dp
private val ICON_TEXT_GAP = 8.dp

/** Default leading-icon size; Settings' siblings in the same row pass 18dp instead. */
private val DEFAULT_ICON_SIZE = 24.dp

/**
 * Single-file database import control, shared by the two entry points: one definition for the tag,
 * label, in-flight guard and spinner.
 *
 * Used by the onboarding `DatabaseInitScreen` (filled, `CloudUpload`, fixed 56dp height) and by the
 * Settings Data Management row (outlined, `CloudDownload`, 18dp icon, parent-driven height).
 *
 * @param modifier caller-supplied layout modifiers — Settings passes `weight(1f)` so import and
 *   export split the row.
 * @param height fixed control height; `null` leaves the height to the parent (Settings' `weight(1f)`
 *   row).
 * @param style which Material button to render; it also selects the spinner's size, stroke and
 *   colour.
 * @param icon leading icon, shown whenever no import is in flight.
 * @param labelRes string resource for the label, resolved here so every entry point shares one.
 * @param logSource logger source tag, so a Settings pick is not attributed to the init screen.
 * @param logContext per-call-site context appended to the click message.
 * @param enabled whether the control accepts a tap. Callers fold *every* busy flag into this — a
 *   control that is showing progress must never also be tappable.
 * @param showProgress true while an import is in flight: the spinner replaces the icon but the
 *   **label stays**, so the button keeps an accessible name for TalkBack.
 * @param iconSize leading-icon size; Settings' sibling controls in the same row use 18dp.
 * @param onClick invoked after the tap has been logged.
 */
@Composable
internal fun ImportDatabaseFileButton(
    modifier: Modifier = Modifier,
    height: Dp? = 56.dp,
    style: ImportButtonStyle = ImportButtonStyle.FILLED,
    icon: ImageVector,
    labelRes: Int,
    logSource: String = "DatabaseInitScreen",
    logContext: String,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    iconSize: Dp = DEFAULT_ICON_SIZE,
    onClick: () -> Unit,
) {
    val label = stringResource(id = labelRes)
    val onClickLogged: () -> Unit = {
        // The logger call stays INSIDE the lambda: it must run on tap, not on recomposition.
        UnifiedLogger.getInstance().i(
            logSource,
            "Import database file clicked ($logContext)",
            mapOf(),
        )
        onClick()
    }
    val buttonModifier = modifier
        .fillMaxWidth()
        .then(if (height != null) Modifier.height(height) else Modifier)
        .testTag(IMPORT_DATABASE_FILE_BUTTON_TAG)

    // One body for both styles: only the Material composable wrapping it differs, so the wiring
    // (onClick, modifier, enabled, content) is written once instead of twice.
    val content: @Composable RowScope.() -> Unit = {
        ImportDatabaseFileButtonContent(
            icon = icon,
            label = label,
            showProgress = showProgress,
            style = style,
            iconSize = iconSize,
        )
    }

    when (style) {
        ImportButtonStyle.FILLED -> Button(
            onClick = onClickLogged,
            modifier = buttonModifier,
            enabled = enabled,
            content = content,
        )

        ImportButtonStyle.OUTLINED -> OutlinedButton(
            onClick = onClickLogged,
            modifier = buttonModifier,
            enabled = enabled,
            content = content,
        )
    }
}

/** Icon-or-spinner, then the label — the label is never dropped (accessibility). */
@Composable
private fun ImportDatabaseFileButtonContent(
    icon: ImageVector,
    label: String,
    showProgress: Boolean,
    style: ImportButtonStyle,
    iconSize: Dp,
) {
    if (showProgress) {
        // Plain constants plus an inline theme read — no holder object allocated per recomposition.
        CircularProgressIndicator(
            modifier = Modifier.size(
                when (style) {
                    ImportButtonStyle.FILLED -> FILLED_SPINNER_SIZE
                    ImportButtonStyle.OUTLINED -> OUTLINED_SPINNER_SIZE
                },
            ),
            strokeWidth = when (style) {
                ImportButtonStyle.FILLED -> FILLED_SPINNER_STROKE
                ImportButtonStyle.OUTLINED -> OUTLINED_SPINNER_STROKE
            },
            color = when (style) {
                ImportButtonStyle.FILLED -> MaterialTheme.colorScheme.onPrimary
                ImportButtonStyle.OUTLINED -> MaterialTheme.colorScheme.primary
            },
        )
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
    }
    Spacer(modifier = Modifier.width(ICON_TEXT_GAP))
    Text(label)
}
