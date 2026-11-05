//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.OutputStream
import java.lang.reflect.Method

/**
 * The port surfaces stay portable: no `android.*` type and no `Uri` may appear in a port signature.
 *
 * THIS IS THE TEST THAT MAKES THE RULE ENFORCED RATHER THAN INTENDED. A port that names a platform
 * type compiles on Android and only fails when a shared caller tries to use it — which is the
 * promotion this work exists to unblock. Reflecting over the declared members of the ports listed in
 * [guardedPorts] catches the slip at the moment it is written, on either platform, with no build
 * matrix.
 *
 * THE LIST IS EXPLICIT, NOT DISCOVERED. Enumerating every port in the package at runtime would need
 * classpath scanning this module has no dependency for, and a scan that silently found nothing would
 * pass vacuously — the same failure the coverage floor in this module's build file was ratcheted to
 * stop. A named list can be read, reviewed, and extended deliberately; when a new port is added it
 * belongs on it, and adding it is a one-line edit in a test rather than a silent gap.
 */
class PortSignaturePortabilityTest {
    /** Ports whose implementation is deliberately platform-shaped and are checked as a group. */
    private val guardedPorts =
        listOf(
            DatabaseUnlockSettings::class.java,
            DatabaseSnapshotExport::class.java,
            ImportedTaskReconciliation::class.java,
            DatabaseBackupCoordinator::class.java,
            DatabaseSessionManager::class.java,
        )

    @Test
    fun `no guarded port declares a member that names an android type`() {
        val offenders = mutableListOf<String>()

        for (port in guardedPorts) {
            for (member in membersOf(port)) {
                for (type in parameterAndReturnTypesOf(member)) {
                    val typeName = type.typeName
                    if (typeName.startsWith("android.") || typeName.contains("<android.")) {
                        offenders += "${port.simpleName}.${member.name} uses $typeName"
                    }
                }
            }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `no guarded port declares a member that names a Uri`() {
        val offenders = mutableListOf<String>()

        for (port in guardedPorts) {
            for (member in membersOf(port)) {
                for (type in parameterAndReturnTypesOf(member)) {
                    // Matched by simple name because `android.net.Uri` is what is forbidden and a
                    // test must not import it to check for it.
                    if (type.simpleName == "Uri") {
                        offenders += "${port.simpleName}.${member.name} uses ${type.simpleName}"
                    }
                }
            }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the snapshot export destination is ordinary java io`() {
        val export = exportMember()

        // A suspend member compiles with a trailing Continuation parameter, so this asserts the
        // DECLARED parameter by position and name rather than the whole JVM parameter list, which
        // carries the compiler's own continuation alongside it.
        val destination = export.parameterTypes.first()
        assertThat(destination).isEqualTo(OutputStream::class.java)
    }

    @Test
    fun `the export member carries a compiler continuation after its declared parameter`() {
        // The portability walk above reads parameterTypes, which for a suspend member includes the
        // compiler's own Continuation. Assert that here so a future reader does not mistake it for a
        // declared parameter and "fix" the test above into a shape that could never pass.
        val export = exportMember()

        assertThat(export.parameterTypes.last().simpleName).isEqualTo("Continuation")
    }

    @Test
    fun `every guarded port is an interface so a platform can supply its own`() {
        for (port in guardedPorts) {
            assertThat(port.isInterface).isTrue()
        }
    }

    private fun exportMember(): Method =
        DatabaseSnapshotExport::class.java.methods.first { it.name == "exportSnapshotTo" }

    private fun membersOf(port: Class<*>): List<Method> = port.methods.toList()

    private fun parameterAndReturnTypesOf(member: Method): List<Class<*>> =
        member.parameterTypes.toList() + member.returnType
}