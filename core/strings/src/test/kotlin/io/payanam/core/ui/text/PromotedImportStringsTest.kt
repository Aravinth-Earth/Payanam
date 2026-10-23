//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The copy move: the nine import strings a promoted shared ViewModel needs, promoted from
 * `app/src/main/res/values/strings.xml` so the desktop renders the same words.
 *
 * These assert the MOVE, not the wording. The wording is Android's and is asserted byte-for-byte here so
 * a future "tidy-up" of either table cannot quietly reword one platform and leave the other behind.
 */
class PromotedImportStringsTest {
    @Test
    fun everyPromotedStringIsPresentInBothLanguages() {
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val strings = UiStrings.of(language)
            listOf(
                strings.dbImportPassphraseWrong,
                strings.settingsImportErrorEmptyDb,
                strings.settingsImportErrorEncryptionConvertFailed,
                strings.settingsImportErrorFolderMissingDb,
                strings.settingsImportErrorFolderMultipleDb,
                strings.settingsImportErrorSchemaTooNew,
                strings.settingsImportErrorSchemaTooOld,
                strings.settingsImportErrorSelectMainDb,
                strings.settingsImportErrorSourceStreamOpen,
            ).forEach { copy ->
                assertThat(copy).isNotEmpty()
            }
        }
    }

    @Test
    fun promotedCopyMatchesAndroidByteForByte() {
        // Copied verbatim from app/src/main/res/values/strings.xml. If a value here drifts, one of the two
        // tables was edited without the other - which is the divergence this module exists to prevent.
        val english = UiStrings.of(UiLanguage.ENGLISH)
        assertThat(english.dbImportPassphraseWrong)
            .isEqualTo("Wrong passphrase for the imported database. Please try again.")
        assertThat(english.settingsImportErrorEmptyDb)
            .isEqualTo("Selected database file is empty.")
        assertThat(english.settingsImportErrorFolderMissingDb)
            .isEqualTo("Selected folder does not contain a database file.")
        assertThat(english.settingsImportErrorFolderMultipleDb)
            .isEqualTo("Selected folder has multiple .db files. Choose a specific backup folder.")
        assertThat(english.settingsImportErrorSelectMainDb)
            .isEqualTo("Select the main database file (.db), not -wal or -shm.")
        assertThat(english.settingsImportErrorSourceStreamOpen)
            .isEqualTo("Could not open selected backup source.")
    }

    @Test
    fun theTwoSchemaErrorsKeepBothNumbersInOrder() {
        // These two carry TWO format arguments and they mean different things: the schema the file HAS,
        // then the schema the app supports. Swapping them would tell a user their old backup is newer
        // than this app - the exact inverse of the truth - so the order is pinned, not just the presence.
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val strings = UiStrings.of(language)
            listOf(
                strings.settingsImportErrorSchemaTooNew,
                strings.settingsImportErrorSchemaTooOld,
            ).forEach { copy ->
                assertThat(copy).contains("%1\$d")
                assertThat(copy).contains("%2\$d")
                assertThat(copy.indexOf("%1\$d")).isLessThan(copy.indexOf("%2\$d"))
            }
        }
    }

    @Test
    fun theMisleadingPlaintextInstructionIsRewordedInBothLanguages() {
        // This test was a tripwire, and the key it watched for has now appeared - because it was REWORDED
        // in the same change that promoted it, which is the only condition it named.
        //
        // WHY THE REWORD WAS REQUIRED: the old Android wording told the user to "export a plaintext backup
        // from the source device, then re-import". Payanam's export IS the encrypted database file, so no
        // version of the app can produce what that sentence asks for - it sent a user who had already lost
        // access to their data down a dead end. The shared copy says what a readable import actually is.
        val accessor = UiStrings::class.java.methods.any { it.name == "getSettingsImportErrorUnreadableDb" }
        assertThat(accessor).isTrue()

        // The INSTRUCTION is what cannot be followed, so that is what is pinned - not the phrase "plaintext
        // backup", which the reworded copy mentions only to deny it. Pinning the bare phrase would have
        // forbidden the one sentence that tells the user why there is no such thing to export.
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val copy = UiStrings.of(language).settingsImportErrorUnreadableDb
            assertThat(copy).isNotEmpty()
            assertThat(copy.lowercase()).doesNotContain("export a plaintext backup")
            assertThat(copy).doesNotContain("எளிய உரை காப்புப்பிரதியை ஏற்குமதி செய்து")
        }
        // And it still names the thing the user CAN do, so the refusal is actionable rather than a dead end.
        assertThat(UiStrings.of(UiLanguage.ENGLISH).settingsImportErrorUnreadableDb).contains("passphrase")
    }

    @Test
    fun tamilIsNotAnEnglishCopy() {
        // Parity is about key SETS, not content. A Tamil table that received the English text would pass
        // every key-set check and ship an untranslated app, so one Tamil string is pinned as Tamil.
        assertThat(UiStrings.of(UiLanguage.TAMIL).settingsImportErrorEmptyDb)
            .isNotEqualTo(UiStrings.of(UiLanguage.ENGLISH).settingsImportErrorEmptyDb)
        assertThat(UiStrings.of(UiLanguage.TAMIL).settingsImportErrorEmptyDb).contains("தரவுத்தள")
    }
}
