//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DesktopDataHandoffSnapshot.

 */
data class DesktopDataHandoffSnapshot(
    val exportFilePath: String,
    val exportCompleted: Boolean,
    val importCompleted: Boolean,
)
/**
 * Provides the desktop data handoff store.
 */
class DesktopDataHandoffStore(
    private val exportDirectory: Path = DesktopAppPaths.resolveExportDirectory(),
    private val databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory(),
    private val runtimeDirectory: Path = DesktopAppPaths.resolveRuntimeDirectory(),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val exportFileNamePattern =
        DateTimeFormatter.ofPattern("'Payanam_Desktop_Handoff_'yyyyMMdd_HHmmss'.zip'")
    /**
     * Writes the export local state.
     */
    fun exportLocalState(): DesktopDataHandoffSnapshot {
        Files.createDirectories(exportDirectory)
        val exportFilePath = exportDirectory.resolve(LocalDateTime.now().format(exportFileNamePattern))
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(exportFilePath))).use { zip ->
            addDirectoryToZip(zip, databaseDirectory, "database")
            addDirectoryToZip(zip, runtimeDirectory, "runtime")
        }
        logEvent(
            "DesktopDataHandoffStore.exportLocalState",
            "Exported desktop local state bundle",
            mapOf("fileName" to exportFilePath.fileName.toString()),
        )
        return DesktopDataHandoffSnapshot(
            exportFilePath = exportFilePath.toString(),
            exportCompleted = true,
            importCompleted = false,
        )
    }
    /**
     * Loads the import latest export.
     */
    fun importLatestExport(): DesktopDataHandoffSnapshot {
        val exportFilePath =
            Files.list(exportDirectory).use { files ->
                files
                    .filter { it.fileName.toString().endsWith(".zip") }
                    .max(Comparator.comparing(Path::toString))
                    .orElse(null)
            } ?: return DesktopDataHandoffSnapshot(
                exportFilePath = exportDirectory.toString(),
                exportCompleted = false,
                importCompleted = false,
            )

        ZipInputStream(BufferedInputStream(Files.newInputStream(exportFilePath))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val destination = resolveDestination(entry.name)
                    Files.createDirectories(destination.parent)
                    Files.copy(zip, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        logEvent(
            "DesktopDataHandoffStore.importLatestExport",
            "Imported desktop local state bundle",
            mapOf("fileName" to exportFilePath.fileName.toString()),
        )
        return DesktopDataHandoffSnapshot(
            exportFilePath = exportFilePath.toString(),
            exportCompleted = true,
            importCompleted = true,
        )
    }

    private fun addDirectoryToZip(
        zip: ZipOutputStream,
        directory: Path,
        entryPrefix: String,
    ) {
        if (!Files.exists(directory)) {
            return
        }
        Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val relative = directory.relativize(file).toString().replace('\\', '/')
                zip.putNextEntry(ZipEntry("$entryPrefix/$relative"))
                Files.newInputStream(file).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    private fun resolveDestination(entryName: String): Path {
        val parts = entryName.split('/', limit = 2)
        require(parts.size == 2) { "Invalid export entry: $entryName" }
        val root =
            when (parts[0]) {
                "database" -> databaseDirectory
                "runtime" -> runtimeDirectory
                "preferences" -> DesktopAppPaths.resolvePreferencesDirectory()
                "bootstrap" -> DesktopAppPaths.resolveBootstrapDirectory()
                "security" -> DesktopAppPaths.resolveSecurityDirectory()
                else -> error("Unknown export entry root: ${parts[0]}")
            }
        return root.resolve(parts[1])
    }
}
