//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Redraws the home-screen time-tracking widget.
 *
 * A port of its own rather than another member of [TimeTrackingController], because a widget is a
 * presentation surface and a tracking session is not: the widget is redrawn after changes that never
 * touch a session at all — a stop tapped on the widget itself, a reboot, a notification action — so
 * a caller that only needs to repaint it should not have to be handed a session controller.
 */
interface TimeTrackingWidgetRefresher {
    /**
     * Asks the home-screen widget to redraw itself from current state.
     */
    fun refreshWidget()
}
