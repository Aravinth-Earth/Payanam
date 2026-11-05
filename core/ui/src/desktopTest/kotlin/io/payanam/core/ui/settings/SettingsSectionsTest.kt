//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.LaunchDestination
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.tasks.TaskFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The shared appearance section, rendered for real on the JVM target.
 *
 * What this move can silently break is the LABEL: every option's caption used to be an `R.string` id
 * resolved in the app module, and the shared table that replaced it is a `when` over keys - so a branch
 * pointing at the wrong copy would render a plausible, wrong row. These cases therefore assert the COPY
 * each option draws, and they assert the writes as CALLS: binding them is the caller's job (`:app` binds
 * them to the ViewModel, the desktop to its own writer), so the shared contract is that the section
 * reports which row changed and leaves storage alone.
 */
class SettingsAppearanceSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each option draws the copy its key names`() {
        render {
            SettingsAppearanceSection(
                prefsState = loaded(),
                onAppLanguageChange = {},
                onThemeModeChange = {},
                onFontFamilyChange = {},
                onTimeFormatChange = {},
                fontFamilyExpanded = false,
                onFontFamilyExpandedChange = {},
            )
        }

        composeRule.onAllNodesWithText(strings.settingsOptionThemeSystem).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionThemeLight).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionThemeDark).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionTimeFormat24h).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionTimeFormat12h).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsLanguageOptionTamil).assertCountEquals(1)
    }

    @Test
    fun `tapping a theme option reports that option and no other preference`() {
        val changes = mutableListOf<String>()

        render {
            SettingsAppearanceSection(
                prefsState = loaded(),
                onAppLanguageChange = { changes.add("language") },
                onThemeModeChange = { changes.add("theme") },
                onFontFamilyChange = { changes.add("font") },
                onTimeFormatChange = { changes.add("format") },
                fontFamilyExpanded = false,
                onFontFamilyExpandedChange = {},
            )
        }
        composeRule.onNodeWithText(strings.settingsOptionThemeDark).performClick()

        // One row, one write: collapsing these four into a single callback would have made this
        // assertion impossible to write, which is the reason they are separate parameters.
        assertEquals(listOf("theme"), changes)
    }

    @Test
    fun `the collapsed font field shows the selected family as copy`() {
        var selected = ThemeModeOption.SYSTEM
        val changes = mutableListOf<String>()

        render {
            SettingsAppearanceSection(
                prefsState = loaded(themeMode = selected),
                onAppLanguageChange = {},
                onThemeModeChange = {},
                onFontFamilyChange = { changes.add("font") },
                onTimeFormatChange = {},
                fontFamilyExpanded = false,
                onFontFamilyExpandedChange = {},
            )
        }
        composeRule.onAllNodesWithText(strings.settingsOptionFontSansSerif).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionFontSerif).assertCountEquals(0)
        assertEquals(emptyList<String>(), changes)
        selected = ThemeModeOption.DARK
    }

    @Test
    fun `a loading state draws no rows`() {
        render {
            SettingsAppearanceSection(
                prefsState = AppPreferencesState(isLoading = true),
                onAppLanguageChange = {},
                onThemeModeChange = {},
                onFontFamilyChange = {},
                onTimeFormatChange = {},
                fontFamilyExpanded = false,
                onFontFamilyExpandedChange = {},
            )
        }

        composeRule.onAllNodesWithText(strings.settingsLoadingPreferences).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionThemeDark).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsOptionTimeFormat12h).assertCountEquals(0)
    }

    @Test
    fun `tamil labels are the tamil copy, not the english ones`() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalUiStrings provides UiStrings.of(UiLanguage.TAMIL),
            ) {
                SettingsAppearanceSection(
                    prefsState = loaded(),
                    onAppLanguageChange = {},
                    onThemeModeChange = {},
                    onFontFamilyChange = {},
                    onTimeFormatChange = {},
                    fontFamilyExpanded = false,
                    onFontFamilyExpandedChange = {},
                )
            }
        }

        composeRule.onAllNodesWithText(UiStrings.of(UiLanguage.TAMIL).settingsOptionThemeDark).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionThemeDark).assertCountEquals(0)
    }

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings, content = content)
        }
    }

    private fun loaded(
        themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
        appLanguage: AppLanguageOption = AppLanguageOption.SYSTEM,
        fontFamily: FontFamilyOption = FontFamilyOption.SANS_SERIF,
        timeFormat: TimeFormatOption = TimeFormatOption.TWENTY_FOUR,
    ) = AppPreferencesState(
        isLoading = false,
        themeMode = themeMode,
        appLanguage = appLanguage,
        fontFamily = fontFamily,
        timeFormat = timeFormat,
    )
}

/**
 * The shared default-landing section, rendered for real on the JVM target.
 *
 * The rule that can silently break here is the ROUTE FILTER: a hidden tab must leave the row, and the
 * count assertions below pin it in both directions. The writes are asserted as CALLS and as DISTINCT
 * CALLBACKS, because "choose Tasks records a filter, choose anything else clears it" is three different
 * storage writes wearing one row's clothing - a single callback would push that distinction onto every
 * caller that binds it.
 */
class SettingsDefaultLandingSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a hidden tab leaves the row`() {
        render(loaded(launchRoute = "habits", tabVisibility = mapOf("habits" to false))) {
            SettingsDefaultLandingSection(
                prefsState = loaded(launchRoute = "habits", tabVisibility = mapOf("habits" to false)),
                onLaunchDestinationRouteChange = {},
                onLaunchDestinationTimeChange = {},
                onLaunchDestinationTasksChange = {},
            )
        }

        composeRule.onAllNodesWithText(strings.locHabits).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.locTime).assertCountEquals(1)
    }

    @Test
    fun `choosing Tasks reports a task filter, choosing Time reports none`() {
        val routes = mutableListOf<String>()
        val times = mutableListOf<String>()
        val tasks = mutableListOf<TaskFilter?>()

        renderLanding(loaded(launchRoute = "journal"), routes, times, tasks)
        composeRule.onNodeWithText(strings.locTime).performClick()

        assertEquals(listOf("time"), times)
        assertEquals(emptyList<String>(), routes)

        renderLanding(loaded(launchRoute = "journal"), routes, times, tasks)
        composeRule.onNodeWithText(strings.settingsDatabaseTasks).performClick()

        // The launch is Tasks with no filter chosen yet, so the write carries the filter the section
        // currently has - NOT null, and not a bare route write.
        assertEquals(listOf(TaskFilter.TODAY), tasks)
    }

    @Test
    fun `the tasks destination adds the task-layer row, and the state row only for an active layer`() {
        // No stored filter: the section falls back to the CURRENT task filter, so "active" is selected and
        // the state row belongs on screen. The state row hangs off the layer, not off the stored value.
        render(loaded(launchRoute = "tasks", taskFilter = null)) {
            SettingsDefaultLandingSection(
                prefsState = loaded(launchRoute = "tasks", taskFilter = null),
                onLaunchDestinationRouteChange = {},
                onLaunchDestinationTimeChange = {},
                onLaunchDestinationTasksChange = {},
            )
        }

        composeRule.onAllNodesWithText(strings.settingsDefaultLandingTaskLayerLabel).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDefaultLandingTaskStateLabel).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.locToday).assertCountEquals(1)
    }

    @Test
    fun `the not-active layer hides the task-state row`() {
        // NOT_ACTIVE is the one layer with no state beneath it, so the state row must not be drawn: three
        // states under a layer that means "no filter" would each render as a lie.
        render(loaded(launchRoute = "tasks", taskFilter = TaskFilter.NOT_ACTIVE)) {
            SettingsDefaultLandingSection(
                prefsState = loaded(launchRoute = "tasks", taskFilter = TaskFilter.NOT_ACTIVE),
                onLaunchDestinationRouteChange = {},
                onLaunchDestinationTimeChange = {},
                onLaunchDestinationTasksChange = {},
            )
        }

        composeRule.onAllNodesWithText(strings.settingsDefaultLandingTaskLayerLabel).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.locNotActive).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDefaultLandingTaskStateLabel).assertCountEquals(0)
    }

    @Test
    fun `a hidden stored landing asks its owner to move it to the first visible route`() {
        val routes = mutableListOf<String>()
        val state = loaded(launchRoute = "habits", tabVisibility = mapOf("habits" to false))

        render(state) {
            SettingsDefaultLandingSection(
                prefsState = state,
                onLaunchDestinationRouteChange = { routes.add(it) },
                onLaunchDestinationTimeChange = {},
                onLaunchDestinationTasksChange = {},
            )
        }
        composeRule.waitForIdle()

        // The write is requested, not performed by the section: storage is the caller's.
        assertEquals(listOf("tasks"), routes)
    }

    @Test
    fun `a loading state draws no rows and asks for no write`() {
        val routes = mutableListOf<String>()

        render(AppPreferencesState(isLoading = true)) {
            SettingsDefaultLandingSection(
                prefsState = AppPreferencesState(isLoading = true),
                onLaunchDestinationRouteChange = { routes.add(it) },
                onLaunchDestinationTimeChange = {},
                onLaunchDestinationTasksChange = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(strings.settingsLoadingPreferences).assertCountEquals(1)
        assertNull(routes.firstOrNull())
    }

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun render(
        state: AppPreferencesState,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings, content = content)
        }
    }

    private fun renderLanding(
        state: AppPreferencesState,
        routes: MutableList<String>,
        times: MutableList<String>,
        tasks: MutableList<TaskFilter?>,
    ) {
        render(state) {
            SettingsDefaultLandingSection(
                prefsState = state,
                onLaunchDestinationRouteChange = { routes.add(it) },
                onLaunchDestinationTimeChange = { times.add("time") },
                onLaunchDestinationTasksChange = { tasks.add(it) },
            )
        }
    }

    private fun loaded(
        launchRoute: String,
        taskFilter: TaskFilter? = null,
        tabVisibility: Map<String, Boolean> = emptyMap(),
    ) = AppPreferencesState(
        isLoading = false,
        tabVisibility = tabVisibility,
        launchDestination = LaunchDestination(route = launchRoute, taskFilter = taskFilter),
    )
}
