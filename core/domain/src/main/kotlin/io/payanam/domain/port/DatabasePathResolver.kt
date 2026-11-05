//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Where the encrypted database lives.
 *
 * This is the ONE place a shared ViewModel learns the database's location: the V5 database-lifecycle
 * screens need it, and the shared state layer cannot see either platform's own path code, so the
 * question is stated here as a port. The contract is SUPPORTED on both platforms — Android and the
 * desktop each supply an implementation — and each implementation single-sources the file name from
 * its own platform authority rather than restating it, so the directory and the file a caller receives
 * are the ones that platform actually opens. Both paths are absolute, and callers never join path
 * fragments themselves: they ask for the directory or for the whole file.
 */
interface DatabasePathResolver {
    /**
     * The absolute directory that contains the database file.
     */
    fun databaseDirectory(): String

    /**
     * The absolute path of the database file itself — directory and single-sourced file name already
     * joined by the platform, never by the caller.
     */
    fun databaseFilePath(): String
}
