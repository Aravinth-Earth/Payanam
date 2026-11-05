//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavioural contract of the shared log sanitizer.
 *
 * These cases moved with the class from `core/common`; the numeric-aggregate case was renamed
 * because its keys are content-class, not "sensitive tokens".
 */
class LogSanitizerTest {
    @Test
    fun sanitizeData_redactsTaskHabitAndNoteTextFields() {
        val input =
            mapOf(
                "taskTitle" to "Pay the electricity bill",
                "habitName" to "Morning walk",
                "noteText" to "Felt tired today",
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["taskTitle"]).isEqualTo("<redacted>")
        assertThat(output["habitName"]).isEqualTo("<redacted>")
        assertThat(output["noteText"]).isEqualTo("<redacted>")
    }

    @Test
    fun sanitizeData_keepsNumericAggregatesFromContentKeys() {
        val input =
            mapOf(
                "taskCount" to 4,
                "noteCount" to 2,
                "responseLength" to 120,
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["taskCount"]).isEqualTo(4)
        assertThat(output["noteCount"]).isEqualTo(2)
        assertThat(output["responseLength"]).isEqualTo(120)
    }

    @Test
    fun sanitizeData_redactsNumericAndBooleanSecretsRegardlessOfType() {
        // Regression: the type gate used to let a numeric OTP/PIN through in the clear even though
        // its key was already a sensitive token.
        val input =
            mapOf(
                "otp" to 123456,
                "token" to 98765,
                "passphrase" to "hunter2",
                "credentialEnabled" to true,
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["otp"]).isEqualTo("<redacted>")
        assertThat(output["token"]).isEqualTo("<redacted>")
        assertThat(output["passphrase"]).isEqualTo("<redacted>")
        assertThat(output["credentialEnabled"]).isEqualTo("<redacted>")
    }

    @Test
    fun sanitizeData_masksNonUuidIdentifiers() {
        val input =
            mapOf(
                "taskId" to "12345",
                "journalId" to "wrong-id-value",
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["taskId"]).isEqualTo("<non-uuid>")
        assertThat(output["journalId"]).isEqualTo("<non-uuid>")
    }

    @Test
    fun sanitizeData_keepsUuidIdentifiersEvenWhenKeyContainsSensitiveModuleTokens() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val input =
            mapOf(
                "taskId" to uuid,
                "habit_id" to uuid,
                "journalEntryId" to uuid,
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["taskId"]).isEqualTo(uuid)
        assertThat(output["habit_id"]).isEqualTo(uuid)
        assertThat(output["journalEntryId"]).isEqualTo(uuid)
    }

    @Test
    fun sanitizeData_redactsJournalTaskAndHabitTextPayloadsByKey() {
        val input =
            mapOf(
                "journalPromptText" to "How did today go?",
                "taskSummary" to "Finish private client work",
                "habitReflection" to "Skipped because I was tired",
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["journalPromptText"]).isEqualTo("<redacted>")
        assertThat(output["taskSummary"]).isEqualTo("<redacted>")
        assertThat(output["habitReflection"]).isEqualTo("<redacted>")
    }

    @Test
    fun sanitizeData_redactsSeparatorVariantsOfSensitiveTokens() {
        val input =
            mapOf(
                "api-key" to "sk-live-abcdef",
                "X-Api-Key" to "sk-live-abcdef",
                "api key" to "sk-live-abcdef",
                "apiKey" to "sk-live-abcdef",
                "pass-word" to "hunter2",
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["api-key"]).isEqualTo("<redacted>")
        assertThat(output["X-Api-Key"]).isEqualTo("<redacted>")
        assertThat(output["api key"]).isEqualTo("<redacted>")
        assertThat(output["apiKey"]).isEqualTo("<redacted>")
        assertThat(output["pass-word"]).isEqualTo("<redacted>")
    }

    @Test
    fun sanitizeData_leavesBenignKeysThatMerelyResembleTokens() {
        // Guards against over-redaction: these are counts and ordinary fields, not secrets.
        val input =
            mapOf(
                "hashtagCount" to 7,
                "keyboardHeight" to 1080,
                "key" to "dueDate",
                "mode" to "encrypted",
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["hashtagCount"]).isEqualTo(7)
        assertThat(output["keyboardHeight"]).isEqualTo(1080)
        assertThat(output["key"]).isEqualTo("dueDate")
        assertThat(output["mode"]).isEqualTo("encrypted")
    }

    @Test
    fun sanitizeData_redactsPosixAndUncPathsInValues() {
        val input =
            mapOf(
                "exportTarget" to "/home/someone/AppData/Local/Payanam/logs/session.log",
                "backupTarget" to "/run/media/someone/drive/backup.db",
                "shareTarget" to "\\\\server\\share\\backup.db",
                "driveTarget" to "C:\\Users\\someone\\Documents\\backup.db",
            )

        val output = LogSanitizer.sanitizeData(input)

        assertThat(output["exportTarget"]).isEqualTo("<path>")
        assertThat(output["backupTarget"]).isEqualTo("<path>")
        assertThat(output["shareTarget"]).isEqualTo("<path>")
        assertThat(output["driveTarget"]).isEqualTo("<path>")
    }

    @Test
    fun sanitizeData_truncatesLongNonSensitiveValues() {
        val long = "x".repeat(100)

        val output = LogSanitizer.sanitizeData(mapOf("stage" to long))

        assertThat(output["stage"]).isEqualTo("${"x".repeat(64)}...")
    }

    @Test
    fun sanitizeData_replacesAnEntirePosixPathNotJustItsPrefix() {
        // Regression: an ungrouped alternation bound the trailing quantifier to the last prefix
        // only, so this value was reduced to "<path>someone/data/export.db" — partially redacted.
        val output =
            LogSanitizer.sanitizeData(
                mapOf("exportTarget" to "/home/someone/data/export.db"),
            )

        assertThat(output["exportTarget"]).isEqualTo("<path>")
    }

    @Test
    fun sanitizeData_keepsLoggerDiagnosticsAtFullLength() {
        // A stack cut to 64 characters cannot be read, which would make the crash-imminent
        // eSync path useless. Diagnostics are still path-redacted, just not truncated.
        val stack = (0 until 20).joinToString(" > ") { index -> "Crash$index.kt:${index + 1}:frame$index" }

        val output =
            LogSanitizer.sanitizeData(
                mapOf(
                    "stack" to stack,
                    "error" to "/tmp/export.db open failed",
                ),
            )

        assertThat(output["stack"]).isEqualTo(stack)
        assertThat(output["error"]).isEqualTo("<path> open failed")
    }

    @Test
    fun sanitizeData_stillRedactsCredentialsEmbeddedInDiagnostics() {
        // Regression: the diagnostic exemption initially skipped redaction entirely, so a credential
        // carried in an exception message reached the log in the clear. Length is exempt; redaction
        // is not.
        val longStack = (0 until 20).joinToString(" > ") { index -> "Crash$index.kt:${index + 1}:frame$index" }

        val output =
            LogSanitizer.sanitizeData(
                mapOf(
                    "error" to "Auth failed for passphrase=hunter2",
                    "stack" to longStack,
                ),
            )

        assertThat(output["error"].toString()).doesNotContain("hunter2")
        assertThat(output["error"].toString()).contains("<redacted>")
        assertThat(output["stack"]).isEqualTo(longStack)
    }

    @Test
    fun sanitizeData_treatsTheCauseChainKeyErrorPayloadWritesAsADiagnostic() {
        // ErrorPayload writes the key as "causeChain"; the sanitizer lowercases before matching. If
        // either the casing handling or the ErrorPayload-sourced key set regressed, this value would
        // be treated as an ordinary long string and lose its content.
        val longCause = "IOException: /home/someone/data/payanam.db unreadable ".repeat(4)

        val output = LogSanitizer.sanitizeData(mapOf("causeChain" to longCause))

        assertThat(output["causeChain"].toString()).doesNotContain("/home/someone")
        assertThat(output["causeChain"].toString()).contains("<path>")
        assertThat(output["causeChain"].toString()).contains("unreadable")
        assertThat(output["causeChain"].toString()).doesNotContain("...")
    }

    @Test
    fun sanitizeData_returnsEmptyMapForNullOrEmptyInput() {
        assertThat(LogSanitizer.sanitizeData(null)).isEmpty()
        assertThat(LogSanitizer.sanitizeData(emptyMap())).isEmpty()
    }

    @Test
    fun sanitizeMessage_stripsPathsAndLineBreaks() {
        val input = "Import failed\nat C:\\Users\\user\\Documents\\payanam\\data\\backup.db"

        val output = LogSanitizer.sanitizeMessage(input)

        assertThat(output).doesNotContain("\n")
        assertThat(output).doesNotContain("C:\\Users")
        assertThat(output).contains("<path>")
    }

    @Test
    fun sanitizeMessage_redactsPosixPaths() {
        // The leak this milestone exists to close: desktop runs on Linux, where the old rule
        // redacted Windows paths only.
        val output = LogSanitizer.sanitizeMessage("Export written to /home/someone/data/export.db")

        assertThat(output).doesNotContain("/home/someone")
        assertThat(output).contains("<path>")
    }

    @Test
    fun sanitizeMessage_redactsInlineCredentials() {
        val output = LogSanitizer.sanitizeMessage("Configure failed for passphrase=hunter2 (retry)")

        assertThat(output).doesNotContain("hunter2")
        assertThat(output).contains("<redacted>")
    }

    @Test
    fun sanitizeMessage_redactsABearerTokenButKeepsTheScheme() {
        // Regression: the value group used to capture a single non-whitespace token, so this masked
        // only the word "Bearer" and left the token itself in the log.
        val output =
            LogSanitizer.sanitizeMessage("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature")

        assertThat(output).doesNotContain("eyJhbGciOiJIUzI1NiJ9")
        assertThat(output).contains("<redacted>")
        assertThat(output).contains("Bearer")
    }

    @Test
    fun sanitizeMessage_redactsAJsonCredentialPair() {
        val output = LogSanitizer.sanitizeMessage("""Request body {"password":"secret123"} rejected""")

        assertThat(output).doesNotContain("secret123")
        assertThat(output).contains("<redacted>")
    }

    @Test
    fun sanitizeMessage_redactsAnEmailAddress() {
        val output = LogSanitizer.sanitizeMessage("Invite sent to someone@example.com for the export")

        assertThat(output).doesNotContain("someone@example.com")
        assertThat(output).contains("<redacted>")
        // The surrounding diagnostic text survives — only the address is replaced.
        assertThat(output).contains("Invite sent to")
        assertThat(output).contains("for the export")
    }

    @Test
    fun sanitizeData_redactsAnEmailAddressValue() {
        val output =
            LogSanitizer.sanitizeData(
                mapOf(
                    "contact" to "someone@example.com",
                    "retryCount" to 3,
                ),
            )

        assertThat(output["contact"]).isEqualTo("<redacted>")
        assertThat(output["retryCount"]).isEqualTo(3)
    }

    @Test
    fun sanitizeMessage_redactsAnEmailWithATagAndSubdomain() {
        val output = LogSanitizer.sanitizeMessage("Reply-to: first.last+tag@mail.example.co.uk")

        assertThat(output).doesNotContain("first.last")
        assertThat(output).doesNotContain("example.co.uk")
        assertThat(output).contains("<redacted>")
    }

    @Test
    fun sanitizeMessage_keepsAtSignsThatAreNotEmailAddresses() {
        // Over-redaction guard: an annotation name is not an address (no local part) and a bare
        // "user@" is not an address either (no domain). Neither may be rewritten.
        val input = "Preview render @Composable skipped for user@ and @Keep"

        assertThat(LogSanitizer.sanitizeMessage(input)).isEqualTo(input)
    }

    @Test
    fun sanitizeMessage_redactsABareHexKey() {
        val key = "9f2c4a7e1b3d5f6081c2d3e4f5a6b7c8"

        val output = LogSanitizer.sanitizeMessage("Transport rejected $key in header")

        assertThat(output).doesNotContain(key)
        assertThat(output).contains("<redacted>")
        assertThat(output).contains("Transport rejected")
    }

    @Test
    fun sanitizeMessage_redactsABareProviderPrefixedKey() {
        val key = "sk-or-v1-2f8a41c9be7d0356a1c4e8b9d2f60a7c"

        val output = LogSanitizer.sanitizeMessage("Pasted $key rejected")

        assertThat(output).doesNotContain("sk-or-v1")
        assertThat(output).contains("<redacted>")
    }

    @Test
    fun sanitizeMessage_keepsHexShorterThanTheCredentialThreshold() {
        // Boundary: one character under the 32-hex-character threshold must survive, or ordinary
        // build ids and short hashes become unreadable.
        val shortHex = "9f2c4a7e1b3d5f6081c2d3e4f5a6b7c"

        val output = LogSanitizer.sanitizeMessage("Build $shortHex finished")

        assertThat(output).contains(shortHex)
        assertThat(output).doesNotContain("<redacted>")
    }

    @Test
    fun sanitizeMessage_keepsOrdinaryLongWordsFileNamesAndBuildNumbers() {
        // Over-redaction guard for the shape heuristics: a mixed-case identifier with no digit, a
        // file name (its separators split the base64 run), and a plain build number all stay.
        val input =
            "Imported AutoBackupRestoreOrchestrationHelper from payanam_auto_backup_2026_10_22.db (build 202610221)"

        assertThat(LogSanitizer.sanitizeMessage(input)).isEqualTo(input)
    }

    @Test
    fun sanitizeMessage_keepsTheKeyNameWhileRedactingAHexValuedCredential() {
        // Regression: adding the bare-token rules must not swallow the `key=` framing that makes a
        // credential log readable.
        val output = LogSanitizer.sanitizeMessage("apikey=9f2c4a7e1b3d5f6081c2d3e4f5a6b7c8 rejected")

        assertThat(output).doesNotContain("9f2c4a7e")
        assertThat(output).contains("apikey=")
        assertThat(output).contains("<redacted>")
    }

    @Test
    fun sanitizeData_summarisesCollectionsAndMapsAndKeepsUnclassifiedScalars() {
        val output =
            LogSanitizer.sanitizeData(
                mapOf(
                    "rowIds" to listOf("a", "b", "c"),
                    "filters" to mapOf("from" to "x", "to" to "y"),
                    "retryCount" to 3,
                    "enabled" to true,
                    "note" to null,
                ),
            )

        assertThat(output["rowIds"]).isEqualTo("[3 items]")
        assertThat(output["filters"]).isEqualTo("{2 entries}")
        assertThat(output["retryCount"]).isEqualTo(3)
        assertThat(output["enabled"]).isEqualTo(true)
        assertThat(output).containsKey("note")
        assertThat(output["note"]).isNull()
    }

    @Test
    fun sanitizeMessage_documentsThePathWithSpacesLimitation() {
        // Known limitation (see the LogSanitizer KDoc): free text cannot delimit a path containing
        // spaces without swallowing the words that follow it. The leading segment is still redacted,
        // so this is strictly better than no redaction — but it is pinned here so the behaviour is a
        // deliberate, visible trade rather than a silent gap.
        val output = LogSanitizer.sanitizeMessage("Export to /home/john Smith/docs/export.db failed")

        assertThat(output).doesNotContain("/home/john")
        assertThat(output).contains("<path>")
    }

    @Test
    fun sanitizeData_keepsAllowedEmptyIdentifierSentinels() {
        // An identifier key carrying a "no value" sentinel stays as-is: rewriting it to
        // <non-uuid> would turn an absent id into one that looks corrupt.
        val output = LogSanitizer.sanitizeData(mapOf("taskId" to "none", "habitId" to ""))

        assertThat(output["taskId"]).isEqualTo("none")
        assertThat(output["habitId"]).isEqualTo("")
    }

    @Test
    fun sanitizeData_keepsBooleansUnderContentKeys() {
        // Content-class keys keep non-string scalars, so an aggregate flag stays readable.
        val output = LogSanitizer.sanitizeData(mapOf("taskEnabled" to true, "noteVisible" to false))

        assertThat(output["taskEnabled"]).isEqualTo(true)
        assertThat(output["noteVisible"]).isEqualTo(false)
    }

    @Test
    fun sanitizeData_redactsAndroidAppInternalPathsButNotLookalikes() {
        val output =
            LogSanitizer.sanitizeData(
                mapOf(
                    "location" to "/data/data/io.payanam/databases/tasks.db",
                    "route" to "/dataReport/list",
                ),
            )

        assertThat(output["location"]).isEqualTo("<path>")
        // The trailing slash in the prefix matters: /dataReport is not /data/.
        assertThat(output["route"]).isEqualTo("/dataReport/list")
    }
}
