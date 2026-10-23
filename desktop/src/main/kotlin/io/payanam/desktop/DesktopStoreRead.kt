//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

/**
 * Reads one persisted store at startup, warning instead of degrading in silence.
 *
 * Every store read in `rememberDesktopMutableState` used to fall back through a bare
 * `runCatching { ... }.getOrElse { ... }`. The fallback value is indistinguishable from a genuinely
 * empty store, so an unreadable database presented as "nothing saved yet" - the user saw a fresh
 * app rather than a problem, and nothing was written anywhere to say otherwise.
 *
 * The warning names the store and the failure's class. The exception's *message* is deliberately not
 * logged: store exceptions are thrown from JDBC and parse paths, so their text routinely carries
 * absolute paths (`/home/<user>/...`, `C:\Users\...`). The class alone separates a disk fault from
 * bad data, which is the distinction the reader needs.
 *
 * The catch is deliberately broad, mirroring `runResetStep`: the store reads raise unchecked
 * `IllegalStateException` as well as checked JDBC exceptions (`SQLException` from
 * `DriverManager.getConnection`) and `IOException` from property/path handling. All of them must
 * degrade to the fallback rather than crash the startup path, and there is no narrower common type.
 *
 * @param storeName short identifier of the store being read, e.g. `settings`.
 * @param read the read to attempt.
 * @param fallback the value to continue with when the read fails.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> readStoreOrFallback(
    storeName: String,
    logger: UnifiedLogger,
    read: () -> T,
    fallback: () -> T,
): T =
    try {
        read()
    } catch (failure: Exception) {
        logger.w(
            source = "DesktopStartupRead",
            message = "Desktop store unreadable; continuing with an empty snapshot",
            data =
                mapOf(
                    "store" to storeName,
                    "failure" to failure.javaClass.simpleName,
                ),
        )
        fallback()
    }
