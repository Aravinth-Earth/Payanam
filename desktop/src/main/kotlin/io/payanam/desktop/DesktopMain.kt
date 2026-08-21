//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import kotlin.math.max
/**
 * Performs the main.
 */
fun main() {
    val singleInstanceResult = DesktopSingleInstanceGuard.acquire()
    if (singleInstanceResult is DesktopSingleInstanceAcquireResult.AlreadyRunning) {
        DesktopSingleInstanceGuard.showAlreadyRunningDialog(details = singleInstanceResult.details)
        return
    }

    val singleInstanceLease =
        (singleInstanceResult as DesktopSingleInstanceAcquireResult.Acquired).lease
    val sessionLogger = DesktopSessionLogger.initialize()
    singleInstanceLease.recordSessionLogPath(sessionLogger.getLogPath())
    sessionLogger.i(
        source = "DesktopMain.main",
        message = "Desktop application starting",
        data =
            mapOf(
                "processId" to ProcessHandle.current().pid(),
                "platformBuildNumber" to DesktopBuildInfo.PLATFORM_BUILD_NUMBER,
                "overallBuildNumber" to DesktopBuildInfo.OVERALL_BUILD_NUMBER,
                "buildName" to DesktopBuildInfo.BUILD_NAME,
            ),
    )
    try {
        application {
            val initialWindowSize = rememberAdaptiveDesktopWindowSize()
            val desktopWindowState = rememberWindowState(size = initialWindowSize)
            remember(initialWindowSize) {
                sessionLogger.i(
                    source = "DesktopMain.windowSize",
                    message = "Resolved adaptive initial window size",
                    data =
                        mapOf(
                            "widthDp" to initialWindowSize.width.value,
                            "heightDp" to initialWindowSize.height.value,
                        ),
                )
            }
            Window(
                onCloseRequest = {
                    sessionLogger.i("DesktopMain.onCloseRequest", "Window close requested")
                    exitApplication()
                },
                title = "Payanam Desktop ${DesktopBuildInfo.VERSION_DISPLAY_NAME}",
                state = desktopWindowState,
            ) {
                desktopApp()
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        sessionLogger.e("DesktopMain.main", "Desktop application terminated with error", error)
        throw error
    } finally {
        sessionLogger.close()
        singleInstanceLease.close()
    }
}

@Composable
private fun rememberAdaptiveDesktopWindowSize(): DpSize {
    val density = LocalDensity.current
    return remember(density) {
        val graphicsConfiguration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = graphicsConfiguration.bounds
        val screenWidthPx = max(bounds.width * WINDOW_WIDTH_RATIO, MIN_WINDOW_WIDTH_PX.toDouble())
        val screenHeightPx = max(bounds.height * WINDOW_HEIGHT_RATIO, MIN_WINDOW_HEIGHT_PX.toDouble())
        with(density) {
            DpSize(
                width = screenWidthPx.toFloat().toDp(),
                height = screenHeightPx.toFloat().toDp(),
            )
        }
    }
}

private const val WINDOW_WIDTH_RATIO = 0.80
private const val WINDOW_HEIGHT_RATIO = 0.75
private const val MIN_WINDOW_WIDTH_PX = 1280
private const val MIN_WINDOW_HEIGHT_PX = 720
