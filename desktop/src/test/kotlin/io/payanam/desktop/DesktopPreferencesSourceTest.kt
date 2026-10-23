//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.resolveEffectiveLanguageTag
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FocusModePreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The desktop's settings source, driven against a REAL encrypted database.
 *
 * Every assertion here is about the shared rows, not about a desktop payload: the source holds no payload of
 * its own, so what it writes is what Android would read. The fixture is a generated one (the same pattern
 * `DesktopSharedStateExecutionTest` uses) - a real export is the user's personal data and is never used.
 *
 * The session is constructed directly rather than through `PayanamDatabaseOpener`: the driver cannot write
 * `PRAGMA user_version`, so a generated fixture is always version 0 and the opener correctly refuses it.
 */
class DesktopPreferencesSourceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "desktop-preferences-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var source: DesktopPreferencesSource
    private val log = mutableListOf<String>()

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE app_settings (
                    `key` TEXT NOT NULL,
                    value TEXT,
                    updatedAt TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        // The holder is process-wide, so a leaked install would let a later class pass on this one's state.
        LocalDataLayer.resetForTests()
        log.clear()
        source = DesktopPreferencesSource(sessionLogger = capturingLogger(), systemLanguageTag = "en")
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        runCatching { session.close() }
    }

    @Test
    fun `reload publishes the state the rows describe`(): Unit =
        runBlocking {
            installLayer()
            writeRow(AppPreferenceKeys.KEY_THEME_MODE, ThemeModeOption.DARK.key)
            writeRow(AppPreferenceKeys.KEY_APP_LANGUAGE, AppLanguageOption.TAMIL.key)

            source.reload()

            assertThat(source.preferences.value.themeMode).isEqualTo(ThemeModeOption.DARK)
            assertThat(source.preferences.value.appLanguage).isEqualTo(AppLanguageOption.TAMIL)
        }

    @Test
    fun `a stored Tamil row is the effective language tag, and SYSTEM resolves through the shared rule`(): Unit =
        runBlocking {
            installLayer()

            // Row to TAG: `effectiveLanguageTag` is what the desktop shell derives its language from, so a
            // state that carried the option but dropped the tag would render the machine locale again.
            writeRow(AppPreferenceKeys.KEY_APP_LANGUAGE, AppLanguageOption.TAMIL.key)
            source.reload()
            assertThat(source.preferences.value.appLanguage).isEqualTo(AppLanguageOption.TAMIL)
            assertThat(source.preferences.value.effectiveLanguageTag).isEqualTo(AppLanguageOption.TAMIL.key)

            // SYSTEM is the stored choice, not the answer: the SHARED rule hands it to the platform's own
            // tag, and this source was built with an English one.
            writeRow(AppPreferenceKeys.KEY_APP_LANGUAGE, AppLanguageOption.SYSTEM.key)
            source.reload()
            assertThat(source.preferences.value.appLanguage).isEqualTo(AppLanguageOption.SYSTEM)
            assertThat(source.preferences.value.effectiveLanguageTag)
                .isEqualTo(resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, systemLanguageTag = "en"))
            assertThat(source.preferences.value.effectiveLanguageTag).isEqualTo("en")

            // The SAME row on a machine reported as Tamil resolves Tamil - SYSTEM defers rather than
            // collapsing to a constant, which is the part an English-only assertion could not see.
            val tamilPlatform =
                DesktopPreferencesSource(
                    sessionLogger = capturingLogger(),
                    systemLanguageTag = "ta",
                )
            tamilPlatform.reload()
            assertThat(tamilPlatform.preferences.value.appLanguage).isEqualTo(AppLanguageOption.SYSTEM)
            assertThat(tamilPlatform.preferences.value.effectiveLanguageTag).isEqualTo(AppLanguageOption.TAMIL.key)
        }

    @Test
    fun `a write lands in the encrypted database and reads back equal`(): Unit =
        runBlocking {
            installLayer()
            source.reload()

            source.setSetting(AppPreferenceKeys.KEY_THEME_MODE, ThemeModeOption.DARK.key)

            // Read back over a separate statement path: the row is really in the file, not only in the state.
            assertThat(rowsInDatabase()[AppPreferenceKeys.KEY_THEME_MODE]).isEqualTo("dark")
            assertThat(source.preferences.value.themeMode).isEqualTo(ThemeModeOption.DARK)
            assertThat(source.writeFailure.value).isNull()
        }

    @Test
    fun `scoring_model round-trips as its own row`(): Unit =
        runBlocking {
            installLayer()
            source.reload()

            source.setSetting(DESKTOP_SCORING_MODEL_KEY, "strict")

            assertThat(rowsInDatabase()[DESKTOP_SCORING_MODEL_KEY]).isEqualTo("strict")
            // And it stayed out of the shared keys: a desktop-only control must not rewrite Android's rows.
            assertThat(rowsInDatabase().keys).containsExactly(DESKTOP_SCORING_MODEL_KEY)
        }

    @Test
    fun `a focus preset writes the rows Android writes, and the completion row among them`(): Unit =
        runBlocking {
            installLayer()
            source.reload()

            source.setFocusModePreset(FocusModePreset.SIMPLE_TASKS)

            val rows = rowsInDatabase()
            // The three facts the desktop and Android must agree on, each read back out of the FILE.
            assertThat(rows[AppPreferenceKeys.KEY_ACTIVE_PRESET]).isEqualTo("simple_tasks")
            assertThat(rows[AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED]).isEqualTo("true")
            // The preset's own tab set, written per tab - not just the preset id. Android's
            // setActivePreset writes all seven, and a preset row with no tab rows is a rail that
            // still shows whatever it showed before.
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}tasks"]).isEqualTo("true")
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}lenses"]).isEqualTo("true")
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}settings"]).isEqualTo("true")
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}time"]).isEqualTo("false")
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}journal"]).isEqualTo("false")
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}notes"]).isEqualTo("false")
            assertThat(rows["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}habits"]).isEqualTo("false")
            assertThat(source.writeFailure.value).isNull()
        }

    @Test
    fun `the preset row set is exactly Android's - one preset, seven tabs, one completion`(): Unit =
        runBlocking {
            installLayer()
            source.reload()

            source.setFocusModePreset(FocusModePreset.SIMPLE_JOURNAL)

            // Exactly nine rows. A tenth would mean a desktop-invented key is riding along in the same
            // write, which is the failure this whole migration is about.
            assertThat(rowsInDatabase().keys).containsExactly(
                AppPreferenceKeys.KEY_ACTIVE_PRESET,
                AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED,
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}tasks",
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}habits",
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}time",
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}journal",
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}notes",
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}lenses",
                "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}settings",
            )
        }

    @Test
    fun `the preset write publishes the shared state, so the gate and the settings screen agree`(): Unit =
        runBlocking {
            installLayer()
            source.reload()

            source.setFocusModePreset(FocusModePreset.SIMPLE_TASKS)

            val state = source.preferences.value
            assertThat(state.focusModeOnboardingCompleted).isTrue()
            assertThat(state.activePreset).isEqualTo(FocusModePreset.SIMPLE_TASKS)
            // The state the nav rail derives from is the SAME state, not a second answer: this is the
            // value the startup gate reads, so a write that stopped here would re-ask for the preset.
            assertThat(state.isRouteVisible(DesktopTopLevelRoute.TASKS)).isTrue()
            assertThat(state.isRouteVisible(DesktopTopLevelRoute.TIME)).isFalse()
            assertThat(state.isRouteVisible(DesktopTopLevelRoute.SETTINGS)).isTrue()
        }

    @Test
    fun `the completion row survives a reload, which is the whole point of it`(): Unit =
        runBlocking {
            installLayer()
            source.reload()
            source.setFocusModePreset(FocusModePreset.FULL_SUITE)

            // A fresh source over the same file is what a restart looks like. Before this row existed the
            // answer came from an in-memory Properties payload, so this assertion could not have existed.
            val restarted =
                DesktopPreferencesSource(
                    sessionLogger = capturingLogger(),
                    systemLanguageTag = "en",
                )
            restarted.reload()

            assertThat(restarted.preferences.value.focusModeOnboardingCompleted).isTrue()
        }

    @Test
    fun `without a session the source reports the absent-row defaults and says so`() {
        // Nothing installed: this is the pre-unlock state, where the layer throws on any access.
        source.reportNoSession()

        assertThat(source.preferences.value.themeMode).isEqualTo(ThemeModeOption.SYSTEM)
        assertThat(source.preferences.value.appLanguage).isEqualTo(AppLanguageOption.SYSTEM)
        assertThat(log.any { it.contains("reporting defaults") }).isTrue()
    }

    @Test
    fun `a failed write keeps the previous state and surfaces one line`(): Unit =
        runBlocking {
            installLayer()
            source.reload()
            // A closed session is a write that cannot land, without inventing a repository that throws.
            session.close()

            source.setSetting(AppPreferenceKeys.KEY_THEME_MODE, ThemeModeOption.DARK.key)

            assertThat(source.preferences.value.themeMode).isEqualTo(ThemeModeOption.SYSTEM)
            assertThat(source.writeFailure.value).isNotNull()
            assertThat(source.writeFailure.value).contains(AppPreferenceKeys.KEY_THEME_MODE)
            assertThat(
                log.any { it.contains("Setting write failed") && it.contains(AppPreferenceKeys.KEY_THEME_MODE) },
            ).isTrue()
            // The value never reaches the log, on the failure path either.
            assertThat(log.none { it.contains(ThemeModeOption.DARK.key) }).isTrue()
        }

    @Test
    fun `a write requested while a swap is in flight is discarded, not applied`(): Unit =
        runBlocking {
            installLayer()
            source.reload()
            val swapInside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val swap =
                async(Dispatchers.Default) {
                    source.runDatabaseSwap {
                        swapInside.complete(Unit)
                        release.await()
                    }
                }
            swapInside.await()
            // Requested while the swap holds the write mutex: the write was made against the database that is
            // being replaced, so it must not land on the new one.
            val write = async(Dispatchers.Default) { source.setSetting(AppPreferenceKeys.KEY_THEME_MODE, "dark") }
            delay(200)
            release.complete(Unit)
            swap.await()
            write.await()

            assertThat(rowsInDatabase()).doesNotContainKey(AppPreferenceKeys.KEY_THEME_MODE)
            assertThat(source.preferences.value.themeMode).isEqualTo(ThemeModeOption.SYSTEM)
            assertThat(log.any { it.contains("cancelled=layer re-install") }).isTrue()
        }

    private fun installLayer() {
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))
    }

    private fun writeRow(
        key: String,
        value: String,
    ) {
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES ('$key', '$value', '2026-09-23T00:00:00')",
            )
        }
    }

    private fun rowsInDatabase(): Map<String, String?> =
        session.requireConnection().createStatement().use { statement ->
            statement.executeQuery("SELECT `key`, value FROM app_settings").use { resultSet ->
                buildMap {
                    while (resultSet.next()) put(resultSet.getString(1), resultSet.getString(2))
                }
            }
        }

    /** Captures what the source logs, so the "never silent" clauses are asserted rather than assumed. */
    private fun capturingLogger(): UnifiedLogger =
        object : UnifiedLogger {
            override fun d(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun i(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                log += "INFO $source $message $data"
            }

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                log += "WARN $source $message $data"
            }

            override fun e(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit

            override fun eSync(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit
        }

    private companion object {
        /** The desktop-only scoring row: see the plan's Amendment (Android has no named-model key). */
        const val DESKTOP_SCORING_MODEL_KEY = "scoring_model"
    }
}
