//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.widget.TimeTrackingWidgetProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Redraws the home-screen time-tracking widget through Android's widget provider.
 */
@Singleton
class AndroidTimeTrackingWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) : TimeTrackingWidgetRefresher {
    /**
     * Asks the home-screen widget to redraw itself from current state.
     */
    override fun refreshWidget() {
        TimeTrackingWidgetProvider.requestUpdate(context)
    }
}
