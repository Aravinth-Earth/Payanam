//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the auto-backup cadence against the rows an EXISTING install already has on disk.
 *
 * This enum moved from `io.payanam.core.ui.preferences` to here to sit below `:core:ui` in the module
 * graph, so the thing most at risk is not its behaviour - there is none - but its PERSISTED shape: the
 * [BackupInterval.key] values are literal strings in the `app_settings` table, and both the Android
 * reader and the desktop reader resolve them through [BackupInterval.fromKey]. A renamed key does not
 * throw; it silently falls back to [BackupInterval.SIXTY_MIN] on every device and quietly changes every
 * user's backup cadence. The order is pinned for the same reason: `entries` is what each settings
 * dropdown iterates, so reordering it would move a user's selection in the list without changing it.
 *
 * These are the exact strings and the exact order the enum had before the move, written out rather than
 * derived, because a derived expectation would move along with the code it is supposed to police.
 */
class BackupIntervalTest {
    @Test
    fun `persisted keys and order are unchanged by the move to the shared module`() {
        assertThat(BackupInterval.entries.map { it.key })
            .containsExactly("15m", "30m", "60m", "2h", "6h", "12h", "24h")
            .inOrder()
        assertThat(BackupInterval.entries.map { it.name })
            .containsExactly(
                "FIFTEEN_MIN",
                "THIRTY_MIN",
                "SIXTY_MIN",
                "TWO_HOURS",
                "SIX_HOURS",
                "TWELVE_HOURS",
                "DAILY",
            ).inOrder()
    }

    @Test
    fun `scheduling minutes track the key each option persists`() {
        assertThat(BackupInterval.entries.map { it.minutes })
            .containsExactly(15L, 30L, 60L, 120L, 360L, 720L, 1440L).inOrder()
    }

    @Test
    fun `fromKey resolves every persisted key back to its own option`() {
        BackupInterval.entries.forEach { interval ->
            assertThat(BackupInterval.fromKey(interval.key)).isEqualTo(interval)
        }
    }

    @Test
    fun `fromKey reports an unknown cadence as null so the caller picks its own fallback`() {
        // The fallback is the CALLER's choice, not a silent default: SettingsViewModel and both platform
        // readers each substitute SIXTY_MIN themselves, so an unknown key must stay distinguishable.
        assertThat(BackupInterval.fromKey(null)).isNull()
        assertThat(BackupInterval.fromKey("")).isNull()
        assertThat(BackupInterval.fromKey("7d")).isNull()
        assertThat(BackupInterval.fromKey("60M")).isNull()
    }

    @Test
    fun `the hourly option the callers default to is the one they name`() {
        // Three separate call sites fall back to SIXTY_MIN by name, so which entry that is - and that it
        // is persisted as 60m - is behaviour, not an implementation detail.
        assertThat(BackupInterval.SIXTY_MIN.key).isEqualTo("60m")
        assertThat(BackupInterval.SIXTY_MIN.minutes).isEqualTo(60L)
        assertThat(BackupInterval.fromKey("60m")).isEqualTo(BackupInterval.SIXTY_MIN)
    }
}
