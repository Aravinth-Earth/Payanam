//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.test

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.payanam.common.logging.UnifiedLogger

/**
 * Runs the database module's instrumentation tests with the logger already initialized.
 *
 * The module is a library: its test APK has no Application, so nothing calls
 * [UnifiedLogger.initialize] before the tests run. Several classes read
 * `UnifiedLogger.getInstance()` in a field initializer, and a field initializer runs before any
 * `@Before`/`@BeforeClass` body, so the module's own per-test initialization was always too late
 * and the whole class failed with "UnifiedLogger not initialized" (or, for classes whose first
 * touch is a top-level `val` such as `MIGRATION_21_22`, with its wrapped
 * `ExceptionInInitializerError`/`NoClassDefFoundError`).
 *
 * The production logger still requires initialization; this runner only performs it at the one
 * point in the instrumentation lifecycle that precedes every test class, exactly as the app's
 * `Application.onCreate` does for the app build.
 */
class PayanamDatabaseTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        UnifiedLogger.initialize(targetContext, "android-test", "test")
    }
}
