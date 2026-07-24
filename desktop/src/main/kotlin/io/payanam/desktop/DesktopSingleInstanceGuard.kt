//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JOptionPane

internal sealed interface DesktopSingleInstanceAcquireResult {
    data class Acquired(
        val lease: DesktopSingleInstanceLease,
    ) : DesktopSingleInstanceAcquireResult

    data class AlreadyRunning(
        val details: DesktopRunningInstanceDetails,
    ) : DesktopSingleInstanceAcquireResult
}

internal data class DesktopRunningInstanceDetails(
    val processId: Long?,
    val buildName: String?,
    val versionDisplayName: String?,
    val acquiredAt: String?,
    val logFilePath: String?,
    val executablePath: String?,
)

internal data class DesktopProcessInfo(
    val executablePath: String?,
    val startedAt: String?,
)

internal class DesktopSingleInstanceLease(
    private val lockFilePath: Path,
    private val metadataFilePath: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    fun recordSessionLogPath(logFilePath: Path) {
        DesktopSingleInstanceGuard.writeMetadata(
            metadataFilePath = metadataFilePath,
            channel = channel,
            processId = ProcessHandle.current().pid(),
            logFilePath = logFilePath.toString(),
        )
    }

    override fun close() {
        DesktopSingleInstanceGuard.clearTrackedState(lockFilePath = lockFilePath, metadataFilePath = metadataFilePath)
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(lockFilePath) }
        runCatching { Files.deleteIfExists(metadataFilePath) }
    }
}

internal object DesktopSingleInstanceGuard {
    private const val LOCK_FILE_NAME = "desktop-single-instance.lock"
    private const val METADATA_FILE_NAME = "desktop-single-instance.properties"
    private const val KEY_PROCESS_ID = "processId"
    private const val KEY_BUILD_NAME = "buildName"
    private const val KEY_VERSION_DISPLAY = "versionDisplayName"
    private const val KEY_ACQUIRED_AT = "acquiredAt"
    private const val KEY_LOG_FILE_PATH = "logFilePath"
    private const val KEY_EXECUTABLE_PATH = "executablePath"
    private val trackedProcesses = ConcurrentHashMap<Path, Long>()
    private val trackedDetails = ConcurrentHashMap<Path, DesktopRunningInstanceDetails>()
    private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

    fun acquire(
        runtimeDirectory: Path = DesktopAppPaths.resolveRuntimeDirectory(),
        processId: Long = ProcessHandle.current().pid(),
    ): DesktopSingleInstanceAcquireResult {
        Files.createDirectories(runtimeDirectory)
        val lockFilePath = runtimeDirectory.resolve(LOCK_FILE_NAME)
        val metadataFilePath = runtimeDirectory.resolve(METADATA_FILE_NAME)
        val channel =
            FileChannel.open(
                lockFilePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )

        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

        if (lock == null) {
            val existingDetails = readExistingInstanceDetails(lockFilePath = lockFilePath, metadataFilePath = metadataFilePath)
            channel.close()
            return DesktopSingleInstanceAcquireResult.AlreadyRunning(details = existingDetails)
        }

        writeMetadata(
            metadataFilePath = metadataFilePath,
            channel = channel,
            processId = processId,
            logFilePath = null,
        )
        trackedProcesses[lockFilePath] = processId
        return DesktopSingleInstanceAcquireResult.Acquired(
            lease =
                DesktopSingleInstanceLease(
                    lockFilePath = lockFilePath,
                    metadataFilePath = metadataFilePath,
                    channel = channel,
                    lock = lock,
                ),
        )
    }

    fun showAlreadyRunningDialog(details: DesktopRunningInstanceDetails) {
        val processText = details.processId?.toString() ?: "unknown"
        val startedAtText = details.acquiredAt ?: "unknown"
        val buildText = details.versionDisplayName ?: details.buildName ?: "unknown"
        val executableText = details.executablePath ?: "unknown"
        val logFileText = details.logFilePath ?: "not available yet"
        JOptionPane.showMessageDialog(
            null,
            "Only one Payanam Desktop instance is allowed.\n\n" +
                "An existing instance is already running.\n" +
                "Process ID: $processText\n" +
                "Build: $buildText\n" +
                "Started at: $startedAtText\n" +
                "Executable: $executableText\n" +
                "Log file: $logFileText\n\n" +
                "Close the running Payanam Desktop window before launching again.",
            "Payanam Desktop already running",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    internal fun writeMetadata(
        metadataFilePath: Path,
        channel: FileChannel,
        processId: Long,
        logFilePath: String?,
    ) {
        val existingProperties = readMetadataProperties(metadataFilePath)
        val properties =
            Properties().apply {
                setProperty(KEY_PROCESS_ID, processId.toString())
                setProperty(KEY_BUILD_NAME, DesktopBuildInfo.BUILD_NAME)
                setProperty(KEY_VERSION_DISPLAY, DesktopBuildInfo.VERSION_DISPLAY_NAME)
                setProperty(KEY_ACQUIRED_AT, timestampFormatter.format(Instant.now().atZone(ZoneId.systemDefault())))
                setProperty(KEY_EXECUTABLE_PATH, currentExecutablePath())
                setProperty(
                    KEY_LOG_FILE_PATH,
                    logFilePath ?: existingProperties.getProperty(KEY_LOG_FILE_PATH, ""),
                )
            }

        val bytes =
            buildString {
                append("$KEY_PROCESS_ID=${properties.getProperty(KEY_PROCESS_ID)}\n")
                append("$KEY_BUILD_NAME=${properties.getProperty(KEY_BUILD_NAME)}\n")
                append("$KEY_VERSION_DISPLAY=${properties.getProperty(KEY_VERSION_DISPLAY)}\n")
                append("$KEY_ACQUIRED_AT=${properties.getProperty(KEY_ACQUIRED_AT)}\n")
                append("$KEY_EXECUTABLE_PATH=${properties.getProperty(KEY_EXECUTABLE_PATH)}\n")
                append("$KEY_LOG_FILE_PATH=${properties.getProperty(KEY_LOG_FILE_PATH)}\n")
            }.toByteArray(StandardCharsets.UTF_8)

        channel.truncate(0)
        channel.position(0)
        channel.write(ByteBuffer.wrap(processId.toString().toByteArray(StandardCharsets.UTF_8)))
        channel.force(true)
        Files.write(
            metadataFilePath,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        trackedDetails[metadataFilePath] =
            DesktopRunningInstanceDetails(
                processId = processId,
                buildName = properties.getProperty(KEY_BUILD_NAME),
                versionDisplayName = properties.getProperty(KEY_VERSION_DISPLAY),
                acquiredAt = properties.getProperty(KEY_ACQUIRED_AT),
                logFilePath = properties.getProperty(KEY_LOG_FILE_PATH)?.takeIf { it.isNotBlank() },
                executablePath = properties.getProperty(KEY_EXECUTABLE_PATH),
            )
    }

    internal fun clearTrackedState(
        lockFilePath: Path,
        metadataFilePath: Path,
    ) {
        trackedProcesses.remove(lockFilePath)
        trackedDetails.remove(metadataFilePath)
    }

    private fun readExistingInstanceDetails(
        lockFilePath: Path,
        metadataFilePath: Path,
    ): DesktopRunningInstanceDetails {
        trackedDetails[metadataFilePath]?.let { tracked ->
            return mergeWithProcessInfo(tracked)
        }

        val properties = readMetadataProperties(metadataFilePath)
        val trackedProcessId = trackedProcesses[lockFilePath]
        val parsedProcessId = properties.getProperty(KEY_PROCESS_ID)?.trim()?.toLongOrNull()
        val processId = trackedProcessId ?: parsedProcessId
        val details =
            DesktopRunningInstanceDetails(
                processId = processId,
                buildName = properties.getProperty(KEY_BUILD_NAME),
                versionDisplayName = properties.getProperty(KEY_VERSION_DISPLAY),
                acquiredAt = properties.getProperty(KEY_ACQUIRED_AT),
                logFilePath = properties.getProperty(KEY_LOG_FILE_PATH)?.takeIf { it.isNotBlank() },
                executablePath = properties.getProperty(KEY_EXECUTABLE_PATH),
            )
        return mergeWithProcessInfo(details)
    }

    private fun readMetadataProperties(metadataFilePath: Path): Properties {
        if (!Files.exists(metadataFilePath)) {
            return Properties()
        }

        return runCatching {
            Files.newBufferedReader(metadataFilePath, StandardCharsets.UTF_8).use { reader ->
                Properties().apply { load(reader) }
            }
        }.getOrElse { Properties() }
    }

    private fun resolveProcessInfo(processId: Long): DesktopProcessInfo? {
        val handle = ProcessHandle.of(processId).orElse(null) ?: return null
        val info = handle.info()
        return DesktopProcessInfo(
            executablePath = info.command().orElse(null),
            startedAt =
                info
                    .startInstant()
                    .map { timestampFormatter.format(it.atZone(ZoneId.systemDefault())) }
                    .orElse(null),
        )
    }

    private fun currentExecutablePath(): String =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse("unknown")

    private fun mergeWithProcessInfo(details: DesktopRunningInstanceDetails): DesktopRunningInstanceDetails {
        val processInfo = details.processId?.let { resolveProcessInfo(it) }
        return details.copy(
            acquiredAt = processInfo?.startedAt ?: details.acquiredAt,
            executablePath = processInfo?.executablePath ?: details.executablePath,
        )
    }
}
