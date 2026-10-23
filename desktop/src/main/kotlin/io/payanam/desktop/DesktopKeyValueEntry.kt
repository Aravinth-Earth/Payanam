//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The desktop's key/value entry, backed by a real file.
 *
 * This replaces [DesktopLocalStateMock] for the one consumer left that matters. The mock is a
 * `ConcurrentHashMap`: the passphrase verifier written through it lived in RAM only, so after a restart
 * `hasPassphraseConfigured` read false, the gate offered a fresh passphrase setup, and setting a new one
 * produced a database on disk encrypted with a key that no longer existed anywhere. The stores' own KDoc
 * predicted it ("divergence and data loss on restart"); this class is the fix.
 *
 * The three operations are deliberately the same three the mock exposed ([readEntry], [writeEntry],
 * [hasEntry]) so [DesktopSecurityStore]'s L5 fail-closed logic - which decides ABSENT vs UNAVAILABLE from
 * the results of exactly these calls - is unchanged. The store is not modified to accommodate the
 * storage; the storage matches the store.
 *
 * **Permissions are the security property, not an afterthought.** Android's verifier lives in
 * `SharedPreferences` opened `MODE_PRIVATE`, i.e. app-private storage the OS sandboxes. The direct
 * desktop analogue is a file under the user's own data root readable only by them: created `0600`, and
 * tightened on every write so a file that was copied in with wider permissions is repaired rather than
 * trusted. There is a unit test per platform for that, because a permission regression here is silent.
 */
internal class DesktopKeyValueEntry(
    /** The file this entry reads and writes. */
    private val file: Path,
) {
    /** The stored payload, or null when there is no file. */
    fun readEntry(): String? = if (Files.exists(file)) Files.readString(file) else null

    /**
     * Stores [payload], replacing any previous value.
     *
     * Written to a temp file and then moved into place, so a failure or a crash mid-write cannot leave a
     * half-written verifier: the file either holds the previous complete payload or the new complete one.
     * A truncated verifier would read as `verifier_missing` and silently re-offer setup.
     */
    fun writeEntry(payload: String) {
        file.parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(
            temp,
            payload,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        restrictToOwner(temp)
        Files.move(
            temp,
            file,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        restrictToOwner(file)
    }

    /** Removes the file, if present. */
    fun deleteEntry() {
        Files.deleteIfExists(file)
    }

    /** Whether a payload is stored. */
    fun hasEntry(): Boolean = Files.exists(file)

    /**
     * Tightens [target] to `0600`, and only the owner may read or write it.
     *
     * Applied on write rather than once at construction: a file that arrived by import or by a
     * permissions-preserving copy can be wider than it should be, and a store that trusted it would be
     * relying on the copy rather than on itself. Best-effort per attribute, because a filesystem that
     * cannot express POSIX modes (Windows, some network mounts) must not make the verifier unreadable -
     * the platform's own ACLs are the boundary there.
     */
    private fun restrictToOwner(target: Path) {
        runCatching { Files.setPosixFilePermissions(target, OWNER_ONLY) }
    }

    private companion object {
        /**
         * `rw-------` - the desktop analogue of Android's `MODE_PRIVATE`. Declared as a constant set
         * rather than a mode string so the intent is legible at the call site.
         */
        private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}
