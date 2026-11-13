//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The external-deletion notice is the only user-visible evidence that the database was wiped, and the
 * reset that follows it destroys the predicate that raises it. These tests pin the flag's round trip.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalDeletionNoticeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("payanam_startup_notice_test", Context.MODE_PRIVATE)

    @Test
    fun `a fresh install has no pending notice`() {
        assertThat(isExternalDeletionNoticePending(prefs)).isFalse()
    }

    @Test
    fun `a marked notice survives a new handle on the same preferences`() {
        markExternalDeletionNoticePending(prefs)

        val reread = context.getSharedPreferences("payanam_startup_notice_test", Context.MODE_PRIVATE)

        assertThat(isExternalDeletionNoticePending(reread)).isTrue()
    }

    @Test
    fun `acknowledging clears the notice for the next start`() {
        markExternalDeletionNoticePending(prefs)
        clearExternalDeletionNotice(prefs)

        assertThat(isExternalDeletionNoticePending(prefs)).isFalse()
    }
}
