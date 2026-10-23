//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

import androidx.compose.runtime.Composable
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType

/*
 * Onboarding-side helpers for DatabaseInitScreen: boot-issue copy and passphrase
 * validation messages. The single-file import control that used to live here (with the folder-picker
 * sibling that was deleted with the import-picker unification) is now
 * `io.payanam.core.ui.components.ImportDatabaseFileButton`, shared with Settings.
 *
 * The log-export actions that used to live here are now a slot on DatabaseInitScreen itself,
 * following the MandatoryDimensionSetupSection precedent: the exporter writes to Android's own
 * storage and reads Android's R class, so the shared screen takes a composable slot instead.
 */

internal fun bootIssueTitleRes(
    type: DatabaseBootIssueType,
    strings: UiStrings,
): String =
    when (type) {
        DatabaseBootIssueType.DB_TOO_NEW -> strings.dbInitIssueTitleUpdateAppRequired

        DatabaseBootIssueType.DB_TOO_OLD -> strings.dbInitIssueTitleDbTooOld

        DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> strings.dbInitIssueTitleIncompleteDbFiles

        DatabaseBootIssueType.SCHEMA_INVALID -> strings.dbInitIssueTitleDbSchemaNotUsable

        DatabaseBootIssueType.OPEN_FAILED -> strings.dbInitIssueTitleDbOpenFailed

        DatabaseBootIssueType.REPAIRABLE_GENERIC,
        DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
        -> strings.locDatabaseNeedsRepair
    }

internal fun bootIssueHeadlineRes(
    type: DatabaseBootIssueType,
    strings: UiStrings,
): String =
    when (type) {
        DatabaseBootIssueType.DB_TOO_NEW -> strings.dbInitIssueHeadlineNewerDbDetected

        DatabaseBootIssueType.DB_TOO_OLD -> strings.dbInitIssueHeadlineUnsupportedOldDb

        DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> strings.dbInitIssueHeadlineMissingPrimaryDb

        DatabaseBootIssueType.SCHEMA_INVALID -> strings.dbInitIssueHeadlineSchemaOrTableProblem

        DatabaseBootIssueType.OPEN_FAILED -> strings.dbInitIssueHeadlineCannotOpenDatabase

        DatabaseBootIssueType.REPAIRABLE_GENERIC,
        DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
        -> strings.locDatabaseCorruptionDetected
    }

@Composable
internal fun bootIssueBodyText(
    bootIssue: DatabaseBootIssue,
    fallbackCorruptionMessage: String?,
    strings: UiStrings,
): String {
    val detail = bootIssue.detailMessage ?: fallbackCorruptionMessage
    return when (bootIssue.type) {
        DatabaseBootIssueType.DB_TOO_NEW -> {
            detail
                ?: strings.dbInitIssueBodyUpdateAppRequiredDefault
        }

        DatabaseBootIssueType.DB_TOO_OLD -> {
            detail
                ?: strings.dbInitIssueBodyDbTooOldDefault
        }

        DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> {
            detail
                ?: strings.dbInitIssueBodyIncompleteDbFilesDefault
        }

        DatabaseBootIssueType.SCHEMA_INVALID -> {
            detail
                ?: strings.dbInitIssueBodySchemaInvalidDefault
        }

        DatabaseBootIssueType.OPEN_FAILED -> {
            detail
                ?: strings.dbInitIssueBodyOpenFailedDefault
        }

        DatabaseBootIssueType.REPAIRABLE_GENERIC,
        DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
        -> {
            detail
                ?: strings.locDatabaseSchemaErrors
        }
    }
}

internal fun passphraseValidationMessage(
    strings: UiStrings,
    reasonCode: String?,
): String =
    when (reasonCode) {
        "min_length" -> strings.dbPassphraseErrorMinLength
        "missing_uppercase" -> strings.dbPassphraseErrorUppercase
        "missing_lowercase" -> strings.dbPassphraseErrorLowercase
        "missing_digit" -> strings.dbPassphraseErrorDigit
        "missing_symbol" -> strings.dbPassphraseErrorSymbol
        else -> strings.dbPassphraseErrorGeneric
    }
