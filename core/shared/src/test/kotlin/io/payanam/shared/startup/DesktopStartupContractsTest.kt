//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test

class DesktopStartupContractsTest {
    @Test
    fun `startup snapshot flags missing desktop database lifecycle attention`() {
        val snapshot =
            DesktopStartupContracts.snapshot(
                launchRoute = DesktopTopLevelRoute.SETTINGS,
                paths =
                    DesktopStartupPaths(
                        appDataRoot = "C:/Users/test/AppData/Local/Payanam",
                        bootstrapFilePath = "C:/Users/test/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
                        securityFilePath = "C:/Users/test/AppData/Local/Payanam/security/desktop-security.properties",
                        databaseFilePath = "C:/Users/test/AppData/Local/Payanam/database/payanam-desktop.db",
                    ),
                state =
                    DesktopStartupState(
                        passphraseConfigured = false,
                        sessionOpen = false,
                    ),
            )
        assertThat(snapshot.schemaVersion).isEqualTo(DesktopStartupContracts.SCHEMA_VERSION)
        assertThat(snapshot.requiresAttention()).isTrue()
        assertThat(snapshot.readyChecks()).isEqualTo(3)
        assertThat(snapshot.checks.last().status).isEqualTo(DesktopStartupCheckStatus.AttentionRequired)
    }

    @Test
    fun `startup snapshot becomes fully ready when desktop database lifecycle is supplied`() {
        val snapshot =
            DesktopStartupContracts.snapshot(
                launchRoute = DesktopTopLevelRoute.TIME,
                paths =
                    DesktopStartupPaths(
                        appDataRoot = "appData",
                        bootstrapFilePath = "bootstrap.properties",
                        securityFilePath = "security.properties",
                        databaseFilePath = "database.db",
                    ),
                state =
                    DesktopStartupState(
                        passphraseConfigured = true,
                        sessionOpen = true,
                        desktopDatabaseReady = true,
                    ),
            )
        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.TIME)
        assertThat(snapshot.requiresAttention()).isFalse()
        assertThat(snapshot.readyChecks()).isEqualTo(6)
    }

    private fun snapshotWith(
        passphraseConfigured: Boolean = true,
        sessionOpen: Boolean = true,
        desktopDatabaseReady: Boolean = true,
    ) = DesktopStartupContracts.snapshot(
        launchRoute = DesktopTopLevelRoute.SETTINGS,
        paths =
            DesktopStartupPaths(
                appDataRoot = "appData",
                bootstrapFilePath = "bootstrap.properties",
                securityFilePath = "security.properties",
                databaseFilePath = "database.db",
            ),
        state =
            DesktopStartupState(
                passphraseConfigured = passphraseConfigured,
                sessionOpen = sessionOpen,
                desktopDatabaseReady = desktopDatabaseReady,
            ),
    )

    @Test
    fun `each unconfigured fact raises exactly its own gate and leaves the rest ready`() {
        // One fact false at a time. The gates are per-fact: an app with a configured passphrase but no open
        // session must raise ONLY the session gate - if the passphrase gate also fired, the user would be sent to
        // set up a passphrase that already exists.
        val noPassphrase = snapshotWith(passphraseConfigured = false)
        assertThat(noPassphrase.readyChecks()).isEqualTo(5)
        assertThat(noPassphrase.checks.first { it.id == "desktop_passphrase" }.status)
            .isEqualTo(DesktopStartupCheckStatus.AttentionRequired)
        assertThat(noPassphrase.checks.first { it.id == "desktop_session" }.status)
            .isEqualTo(DesktopStartupCheckStatus.Ready)
        assertThat(noPassphrase.checks.first { it.id == "desktop_database" }.status)
            .isEqualTo(DesktopStartupCheckStatus.Ready)

        val noSession = snapshotWith(sessionOpen = false)
        assertThat(noSession.readyChecks()).isEqualTo(5)
        assertThat(noSession.checks.first { it.id == "desktop_session" }.status)
            .isEqualTo(DesktopStartupCheckStatus.AttentionRequired)
        assertThat(noSession.checks.first { it.id == "desktop_passphrase" }.status)
            .isEqualTo(DesktopStartupCheckStatus.Ready)
        assertThat(noSession.checks.first { it.id == "desktop_database" }.status)
            .isEqualTo(DesktopStartupCheckStatus.Ready)

        val noDatabase = snapshotWith(desktopDatabaseReady = false)
        assertThat(noDatabase.readyChecks()).isEqualTo(5)
        assertThat(noDatabase.checks.first { it.id == "desktop_database" }.status)
            .isEqualTo(DesktopStartupCheckStatus.AttentionRequired)
        assertThat(noDatabase.checks.first { it.id == "desktop_passphrase" }.status)
            .isEqualTo(DesktopStartupCheckStatus.Ready)
        assertThat(noDatabase.checks.first { it.id == "desktop_session" }.status)
            .isEqualTo(DesktopStartupCheckStatus.Ready)
    }

    @Test
    fun `the ready gates name the paths the shell is anchored to`() {
        // The summaries are the user-facing answer to "where does my data live", so the paths must be the real
        // ones: distinctive values prove the wiring - the settings and database summaries interpolate the
        // database path, the app-data summary the root - and the id order is the snapshot's serializable shape.
        val snapshot =
            DesktopStartupContracts.snapshot(
                launchRoute = DesktopTopLevelRoute.TASKS,
                paths =
                    DesktopStartupPaths(
                        appDataRoot = "root-dir",
                        bootstrapFilePath = "bootstrap-file",
                        securityFilePath = "security-file",
                        databaseFilePath = "database-file",
                    ),
                state =
                    DesktopStartupState(
                        passphraseConfigured = true,
                        sessionOpen = true,
                        desktopDatabaseReady = true,
                    ),
            )

        assertThat(snapshot.checks.map { it.id })
            .containsExactly(
                "desktop_settings",
                "desktop_app_data",
                "desktop_bootstrap",
                "desktop_passphrase",
                "desktop_session",
                "desktop_database",
            ).inOrder()

        val byId = snapshot.checks.associateBy { it.id }
        assertThat(byId.getValue("desktop_settings").title).isEqualTo("Desktop settings")
        assertThat(byId.getValue("desktop_settings").summary)
            .isEqualTo("Desktop settings are stored in the local database at database-file.")
        assertThat(byId.getValue("desktop_app_data").title).isEqualTo("App data root")
        assertThat(byId.getValue("desktop_app_data").summary)
            .isEqualTo("Desktop state is anchored at root-dir.")
        assertThat(byId.getValue("desktop_bootstrap").title).isEqualTo("Desktop bootstrap state")
        assertThat(byId.getValue("desktop_bootstrap").summary)
            .isEqualTo("Startup handoff state is tracked inside the local desktop database.")
        assertThat(byId.getValue("desktop_passphrase").title).isEqualTo("Desktop passphrase")
        assertThat(byId.getValue("desktop_passphrase").summary)
            .isEqualTo("Desktop passphrase verifier is persisted inside the local desktop database.")
        assertThat(byId.getValue("desktop_session").title).isEqualTo("Desktop session")
        assertThat(byId.getValue("desktop_session").summary)
            .isEqualTo("Desktop startup session is unlocked for the current app run.")
        assertThat(byId.getValue("desktop_database").title).isEqualTo("Desktop database lifecycle")
        assertThat(byId.getValue("desktop_database").summary)
            .isEqualTo("Desktop database lifecycle is ready and anchored at database-file.")
    }

    @Test
    fun `the attention gates say which setup step is still missing`() {
        // All three facts unconfigured: each attention summary is the exact sentence the user is shown, so the
        // copy is pinned - a gate that fired with the wrong summary would send the user to fix the wrong thing.
        val snapshot =
            DesktopStartupContracts.snapshot(
                launchRoute = DesktopTopLevelRoute.SETTINGS,
                paths =
                    DesktopStartupPaths(
                        appDataRoot = "root-dir",
                        bootstrapFilePath = "bootstrap-file",
                        securityFilePath = "security-file",
                        databaseFilePath = "database-file",
                    ),
                state =
                    DesktopStartupState(
                        passphraseConfigured = false,
                        sessionOpen = false,
                        desktopDatabaseReady = false,
                    ),
            )

        assertThat(snapshot.readyChecks()).isEqualTo(3)
        assertThat(snapshot.requiresAttention()).isTrue()

        val byId = snapshot.checks.associateBy { it.id }
        listOf("desktop_passphrase", "desktop_session", "desktop_database").forEach { id ->
            assertThat(byId.getValue(id).status).isEqualTo(DesktopStartupCheckStatus.AttentionRequired)
        }
        listOf("desktop_settings", "desktop_app_data", "desktop_bootstrap").forEach { id ->
            assertThat(byId.getValue(id).status).isEqualTo(DesktopStartupCheckStatus.Ready)
        }
        assertThat(byId.getValue("desktop_passphrase").summary)
            .isEqualTo("Desktop passphrase setup is still required before the shell can protect local data.")
        assertThat(byId.getValue("desktop_session").summary)
            .isEqualTo("Desktop startup requires passphrase unlock before the shell can continue.")
        assertThat(byId.getValue("desktop_database").summary)
            .isEqualTo(
                "Desktop database is present but startup still needs the first local initialization handoff.",
            )
    }
}
