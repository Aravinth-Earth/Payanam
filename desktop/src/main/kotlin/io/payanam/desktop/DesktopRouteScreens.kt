//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.tasks.DesktopHabitListItem
import io.payanam.shared.tasks.DesktopTaskBoardLoadState
import io.payanam.shared.tasks.DesktopTaskBoardSnapshot
import io.payanam.shared.tasks.DesktopTaskFilter
import io.payanam.shared.tasks.DesktopTaskListItem

@Composable
internal fun desktopTasksRoute(
    snapshot: DesktopTaskBoardSnapshot,
    onSnapshotChanged: (DesktopTaskBoardSnapshot) -> Unit,
) {
    desktopTaskBoardRoute(
        route = DesktopTopLevelRoute.TASKS,
        snapshot = snapshot,
        onSnapshotChanged = onSnapshotChanged,
    )
}

@Composable
internal fun desktopTimeRoute() {
    desktopRoutePlaceholder(DesktopTopLevelRoute.TIME)
}

@Composable
internal fun desktopHabitsRoute(
    snapshot: DesktopTaskBoardSnapshot,
    onSnapshotChanged: (DesktopTaskBoardSnapshot) -> Unit,
) {
    desktopTaskBoardRoute(
        route = DesktopTopLevelRoute.HABITS,
        snapshot = snapshot,
        onSnapshotChanged = onSnapshotChanged,
    )
}

@Composable
internal fun desktopLensesRoute() {
    desktopRoutePlaceholder(DesktopTopLevelRoute.LENSES)
}

@Composable
private fun desktopRoutePlaceholder(route: DesktopTopLevelRoute) {
    val content =
        checkNotNull(desktopRoutePlaceholderContent(route)) {
            "Placeholder content required for route ${route.storageKey}"
        }
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics {
                        contentDescription = "${route.displayName} placeholder route"
                        stateDescription = "${content.readiness} readiness"
                    }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = content.summary,
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            Text(
                text = "Readiness: ${content.readiness}",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
            )
            content.details.forEach { detail ->
                Text(
                    text = "• $detail",
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
            }
        }
    }
}

@Composable
private fun desktopTaskBoardRoute(
    route: DesktopTopLevelRoute,
    snapshot: DesktopTaskBoardSnapshot,
    onSnapshotChanged: (DesktopTaskBoardSnapshot) -> Unit,
) {
    val isTasksSurface = route == DesktopTopLevelRoute.TASKS
    val preferences = snapshot.preferences
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics {
                        contentDescription = "${route.displayName} board surface"
                        stateDescription =
                            snapshot.content.loadState.name
                                .lowercase()
                    }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isTasksSurface) "Desktop task board" else "Desktop habits board",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Backed by the local desktop database.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            if (isTasksSurface) {
                desktopChoiceRow(
                    label = "Task filter",
                    value = preferences.selectedTaskFilter.storageKey,
                    onAdvance = {
                        onSnapshotChanged(
                            snapshot.copy(
                                preferences = preferences.copy(selectedTaskFilter = preferences.selectedTaskFilter.nextDesktopOption()),
                            ),
                        )
                    },
                )
                desktopChoiceRow(
                    label = "Task sort",
                    value = preferences.selectedTaskSort.storageKey,
                    onAdvance = {
                        onSnapshotChanged(
                            snapshot.copy(
                                preferences = preferences.copy(selectedTaskSort = preferences.selectedTaskSort.nextDesktopOption()),
                            ),
                        )
                    },
                )
            } else {
                desktopChoiceRow(
                    label = "Habit sort",
                    value = preferences.selectedHabitSort.storageKey,
                    onAdvance = {
                        onSnapshotChanged(
                            snapshot.copy(
                                preferences = preferences.copy(selectedHabitSort = preferences.selectedHabitSort.nextDesktopOption()),
                            ),
                        )
                    },
                )
                desktopToggleRow(
                    label = "Show completed habits",
                    enabled = preferences.showCompletedHabits,
                    onToggle = {
                        onSnapshotChanged(
                            snapshot.copy(
                                preferences = preferences.copy(showCompletedHabits = !preferences.showCompletedHabits),
                            ),
                        )
                    },
                )
                desktopToggleRow(
                    label = "Show archived habits",
                    enabled = preferences.showArchivedHabits,
                    onToggle = {
                        onSnapshotChanged(
                            snapshot.copy(
                                preferences = preferences.copy(showArchivedHabits = !preferences.showArchivedHabits),
                            ),
                        )
                    },
                )
            }
            desktopTaskBoardSummary(route = route, snapshot = snapshot)
            desktopTaskBoardContent(route = route, snapshot = snapshot)
        }
    }
}

@Composable
private fun desktopTaskBoardSummary(
    route: DesktopTopLevelRoute,
    snapshot: DesktopTaskBoardSnapshot,
) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics {
                        contentDescription = "${route.displayName} board summary"
                        stateDescription =
                            if (route == DesktopTopLevelRoute.TASKS) {
                                "${snapshot.visibleTaskCount()} visible tasks"
                            } else {
                                "${snapshot.counts.totalHabitCount} visible habits"
                            }
                    }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (route == DesktopTopLevelRoute.TASKS) "Visible tasks" else "Visible habits",
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        if (route == DesktopTopLevelRoute.TASKS) {
                            snapshot.visibleTaskCount().toString()
                        } else {
                            snapshot.counts.totalHabitCount.toString()
                        },
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (route == DesktopTopLevelRoute.TASKS) {
                Text(
                    text = "Desktop tasks now load real local entity rows from the Windows app-data catalog.",
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
                snapshot.counts.activeTaskFilterCounts
                    .filterKeys { it != DesktopTaskFilter.NOT_ACTIVE }
                    .forEach { (filter, count) ->
                        Text(
                            text = "${filter.storageKey}: $count",
                            style = MaterialTheme.typography.body2,
                            color = desktopMutedTextColor(),
                        )
                    }
            } else {
                Text(
                    text =
                        "Desktop habits now load recurring entries from the same local database " +
                            "with desktop-specific visibility toggles.",
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
                Text(
                    text = "Completed today: ${snapshot.counts.completedHabitCountToday}",
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
            }
        }
    }
}

@Composable
private fun desktopTaskBoardContent(
    route: DesktopTopLevelRoute,
    snapshot: DesktopTaskBoardSnapshot,
) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics {
                        contentDescription = "${route.displayName} board content"
                        stateDescription =
                            snapshot.content.loadState.name
                                .lowercase()
                    }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (snapshot.content.loadState) {
                DesktopTaskBoardLoadState.LOADING -> {
                    Text(
                        text = "Loading desktop board data...",
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                DesktopTaskBoardLoadState.ERROR -> {
                    Text(
                        text = "Desktop board data could not be loaded.",
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = snapshot.content.errorMessage ?: "Unknown desktop board error.",
                        style = MaterialTheme.typography.body2,
                        color = desktopMutedTextColor(),
                    )
                }

                DesktopTaskBoardLoadState.EMPTY -> {
                    Text(
                        text = if (route == DesktopTopLevelRoute.TASKS) "No desktop tasks found yet." else "No desktop habits found yet.",
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "The desktop catalog is ready, but it does not contain any matching entries for this board state.",
                        style = MaterialTheme.typography.body2,
                        color = desktopMutedTextColor(),
                    )
                }

                DesktopTaskBoardLoadState.READY -> {
                    if (route == DesktopTopLevelRoute.TASKS) {
                        desktopTaskList(items = snapshot.content.visibleTasks)
                    } else {
                        desktopHabitList(items = snapshot.content.visibleHabits)
                    }
                }
            }
        }
    }
}

@Composable
private fun desktopTaskList(items: List<DesktopTaskListItem>) {
    items.forEach { item ->
        desktopBoardItemCard(
            title = item.title,
            leadingLabel = item.status,
            middleLabel = item.dueLabel,
            trailingLabel = item.scoreLabel,
            supportingLabel = item.dimensionLabel,
        )
    }
}

@Composable
private fun desktopHabitList(items: List<DesktopHabitListItem>) {
    items.forEach { item ->
        desktopBoardItemCard(
            title = item.title,
            leadingLabel = item.todayStatusLabel,
            middleLabel = item.dueLabel,
            trailingLabel = item.scoreLabel,
            supportingLabel = item.dimensionLabel,
        )
    }
}

@Composable
private fun desktopBoardItemCard(
    title: String,
    leadingLabel: String,
    middleLabel: String,
    trailingLabel: String,
    supportingLabel: String,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics {
                        contentDescription = "Board item $title"
                        stateDescription = "$leadingLabel, $middleLabel, $trailingLabel, $supportingLabel"
                    }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = trailingLabel,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = leadingLabel,
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
                Text(
                    text = middleLabel,
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
            }
            Text(
                text = supportingLabel,
                style = MaterialTheme.typography.caption,
                color = desktopMutedTextColor(),
            )
        }
    }
}
