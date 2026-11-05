//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.DayPlanRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties

/**
 * Tests for [DayPlanRepositoryJvm].
 *
 * The behaviours worth this many tests are the ones the port can get wrong silently: the mode deciding
 * WHICH allocations are returned, template-derived allocations being virtual rather than written, an
 * inactive template resolving to nothing, past days being refused, and `clearDayPlan` deleting and resetting
 * the mode inside one transaction that nests with the dirty-mark it triggers.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class DayPlanRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "day-plan-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val today: LocalDate = LocalDate.now()
    private val todayKey: String = today.toString()

    /** A future day that is a working day, for AUTO-mode resolution. */
    private val weekdayKey: String = nextDayMatching { it.dayOfWeek.value in 1..5 }

    /** A future day on a weekend, for AUTO-mode resolution. */
    private val weekendKey: String = nextDayMatching { it.dayOfWeek.value in 6..7 }

    private fun nextDayMatching(predicate: (LocalDate) -> Boolean): String {
        var candidate = today.plusDays(1)
        while (!predicate(candidate)) candidate = candidate.plusDays(1)
        return candidate.toString()
    }

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("dayplan.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE day_plan_allocations (
                    id TEXT PRIMARY KEY NOT NULL,
                    day_key TEXT NOT NULL,
                    dimension_id TEXT NOT NULL,
                    planned_minutes INTEGER NOT NULL,
                    source TEXT NOT NULL DEFAULT 'manual',
                    template_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_policies (
                    day_key TEXT PRIMARY KEY NOT NULL,
                    mode TEXT NOT NULL DEFAULT 'auto',
                    template_id TEXT,
                    is_starred INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_templates (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_template_allocations (
                    id TEXT PRIMARY KEY NOT NULL,
                    template_id TEXT NOT NULL,
                    dimension_id TEXT NOT NULL,
                    planned_minutes INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_type_template_preferences (
                    day_type TEXT PRIMARY KEY NOT NULL,
                    template_id TEXT,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE daily_insights (
                    id TEXT PRIMARY KEY NOT NULL,
                    day_key TEXT NOT NULL,
                    module TEXT NOT NULL,
                    dimension_id TEXT,
                    planned_minutes INTEGER,
                    actual_minutes INTEGER,
                    focused_minutes INTEGER,
                    completed_count INTEGER,
                    total_count INTEGER,
                    summary_json TEXT,
                    generated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun repository() = DayPlanRepositoryJvm(session, pollIntervalMs = 10L)

    private fun dirtyDays(): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT day_key FROM daily_insights WHERE module = 'lens_dirty_day'").use { resultSet ->
                buildSet {
                    while (resultSet.next()) add(resultSet.getString(1))
                }
            }
        }

    private fun rawAllocationMinutes(
        dayKey: String,
        dimensionId: String,
    ): Int? =
        connection
            .prepareStatement(
                "SELECT planned_minutes FROM day_plan_allocations WHERE day_key = ? AND dimension_id = ?",
            ).use { statement ->
                statement.setString(1, dayKey)
                statement.setString(2, dimensionId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else null
                }
            }

    private fun rawAllocationCount(dayKey: String): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM day_plan_allocations WHERE day_key = ?").use { statement ->
            statement.setString(1, dayKey)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun rawMode(dayKey: String): String? =
        connection.prepareStatement("SELECT mode FROM day_plan_policies WHERE day_key = ?").use { statement ->
            statement.setString(1, dayKey)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    // ---- allocations ----

    @Test
    fun `setAllocation forces custom mode and marks the day dirty`(): Unit =
        runBlocking {
            repository().setAllocation(todayKey, "dim_work_livelihood", 120)

            assertThat(rawAllocationMinutes(todayKey, "dim_work_livelihood")).isEqualTo(120)
            assertThat(rawMode(todayKey)).isEqualTo(DayPlanRepository.MODE_CUSTOM)
            assertThat(dirtyDays()).contains(todayKey)
        }

    @Test
    fun `a negative allocation is clamped rather than rejected`(): Unit =
        runBlocking {
            repository().setAllocation(todayKey, "dim_work_livelihood", -30)

            assertThat(rawAllocationMinutes(todayKey, "dim_work_livelihood")).isEqualTo(0)
        }

    @Test
    fun `setAllocation on a past day is refused`(): Unit =
        runBlocking {
            val failure =
                runCatching { repository().setAllocation(today.minusDays(1).toString(), "dim_work_livelihood", 60) }

            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(rawAllocationCount(today.minusDays(1).toString())).isEqualTo(0)
        }

    @Test
    fun `setAllocations replaces the day - an omitted dimension disappears`(): Unit =
        runBlocking {
            repository().setAllocations(todayKey, mapOf("dim_work_livelihood" to 60, "dim_physical_health" to 30))
            assertThat(rawAllocationCount(todayKey)).isEqualTo(2)

            repository().setAllocations(todayKey, mapOf("dim_work_livelihood" to 90))

            assertThat(rawAllocationCount(todayKey)).isEqualTo(1)
            assertThat(rawAllocationMinutes(todayKey, "dim_physical_health")).isNull()
            assertThat(rawAllocationMinutes(todayKey, "dim_work_livelihood")).isEqualTo(90)
        }

    @Test
    fun `clearDayPlan removes the allocations and resets the mode to auto`(): Unit =
        runBlocking {
            repository().setAllocation(todayKey, "dim_work_livelihood", 60)

            repository().clearDayPlan(todayKey)

            assertThat(rawAllocationCount(todayKey)).isEqualTo(0)
            assertThat(rawMode(todayKey)).isEqualTo(DayPlanRepository.MODE_AUTO)
        }

    // ---- policy ----

    @Test
    fun `a day with no stored policy reads as auto and not starred`(): Unit =
        runBlocking {
            val policy = repository().getDayPolicy(todayKey)

            assertThat(policy.mode).isEqualTo(DayPlanRepository.MODE_AUTO)
            assertThat(policy.templateId).isNull()
            assertThat(policy.isStarred).isFalse()
        }

    @Test
    fun `setDayMode rejects an unknown mode`(): Unit =
        runBlocking {
            val failure = runCatching { repository().setDayMode(todayKey, "whenever") }

            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }

    @Test
    fun `setDayMode keeps the stored starred flag and clears a template id outside template mode`(): Unit =
        runBlocking {
            repository().setDayStarred(todayKey, isStarred = true)
            repository().setDayMode(todayKey, DayPlanRepository.MODE_TEMPLATE, "template-x")

            assertThat(repository().getDayPolicy(todayKey).templateId).isEqualTo("template-x")
            assertThat(repository().getDayPolicy(todayKey).isStarred).isTrue()

            repository().setDayMode(todayKey, DayPlanRepository.MODE_CUSTOM, "template-x")

            assertThat(repository().getDayPolicy(todayKey).templateId).isNull()
            assertThat(repository().getDayPolicy(todayKey).isStarred).isTrue()
        }

    @Test
    fun `setDayStarred keeps the mode`(): Unit =
        runBlocking {
            repository().setAllocation(todayKey, "dim_work_livelihood", 60)

            repository().setDayStarred(todayKey, isStarred = true)

            assertThat(rawMode(todayKey)).isEqualTo(DayPlanRepository.MODE_CUSTOM)
            assertThat(repository().getDayPolicy(todayKey).isStarred).isTrue()
        }

    // ---- day-type preferences ----

    @Test
    fun `a day type preference round-trips and rejects unknown types`(): Unit =
        runBlocking {
            repository().setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKEND, "template-w")

            assertThat(repository().getDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKEND).templateId)
                .isEqualTo("template-w")
            assertThat(
                runCatching { repository().getDayTypeTemplatePreference("holiday") }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
        }

    // ---- templates ----

    @Test
    fun `createTemplate trims the name, drops zero minutes and returns an id`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "  Deep work  ",
                    description = "   ",
                    allocations = mapOf("dim_work_livelihood" to 120, "dim_physical_health" to 0),
                )

            val template = repository().getTemplateById(id)

            assertThat(template?.name).isEqualTo("Deep work")
            assertThat(template?.description).isNull()
            assertThat(template?.allocations?.map { it.dimensionId }).containsExactly("dim_work_livelihood")
            assertThat(template?.isActive).isTrue()
        }

    @Test
    fun `the active template limit is enforced`(): Unit =
        runBlocking {
            repeat(DayPlanRepository.MAX_TEMPLATE_COUNT) { index ->
                repository().createTemplate(name = "Template $index", description = null, allocations = emptyMap())
            }

            val failure =
                runCatching {
                    repository().createTemplate(name = "One too many", description = null, allocations = emptyMap())
                }

            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(repository().observeActiveTemplates().first()).hasSize(DayPlanRepository.MAX_TEMPLATE_COUNT)
        }

    @Test
    fun `updateTemplate replaces the allocation rows`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Focus",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 120, "dim_learning_growth" to 60),
                )

            repository().updateTemplate(
                id = id,
                name = "Focus v2",
                description = "  revised  ",
                allocations = mapOf("dim_work_livelihood" to 180),
            )

            val template = repository().getTemplateById(id)

            assertThat(template?.name).isEqualTo("Focus v2")
            assertThat(template?.description).isEqualTo("revised")
            assertThat(template?.allocations?.map { it.dimensionId }).containsExactly("dim_work_livelihood")
            assertThat(template?.allocations?.single()?.plannedMinutes).isEqualTo(180)
        }

    @Test
    fun `updateTemplate on a missing id does nothing`(): Unit =
        runBlocking {
            repository().updateTemplate(id = "absent", name = "x", description = null, allocations = emptyMap())

            assertThat(repository().getTemplateById("absent")).isNull()
        }

    @Test
    fun `deleteTemplate soft-deletes - it leaves the all-templates view but not the active one`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Retire me",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 60),
                )

            repository().deleteTemplate(id)

            assertThat(repository().observeActiveTemplates().first()).isEmpty()
            val all = repository().observeAllTemplates().first()
            assertThat(all.map { it.id }).containsExactly(id)
            // The definition survives: a past day planned from it still resolves its allocations.
            assertThat(all.single().allocations).hasSize(1)
        }

    // ---- effective allocations ----

    @Test
    fun `custom mode returns the explicit rows`(): Unit =
        runBlocking {
            repository().setAllocation(todayKey, "dim_work_livelihood", 90)

            val effective = repository().getEffectiveAllocationsForDay(todayKey)

            assertThat(effective.map { it.dimensionId }).containsExactly("dim_work_livelihood")
            assertThat(effective.single().source).isEqualTo("manual")
        }

    @Test
    fun `template mode derives VIRTUAL allocations and persists none of them`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Template mode",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 120, "dim_physical_health" to 45),
                )

            repository().setDayMode(todayKey, DayPlanRepository.MODE_TEMPLATE, id)
            val effective = repository().getEffectiveAllocationsForDay(todayKey)

            assertThat(effective).hasSize(2)
            assertThat(effective.map { it.source })
                .containsExactly(DayPlanRepository.SOURCE_TEMPLATE, DayPlanRepository.SOURCE_TEMPLATE)
            assertThat(effective.map { it.id }.all { it.startsWith("virtual_") }).isTrue()
            // Nothing derived is written: the day still has no explicit rows.
            assertThat(rawAllocationCount(todayKey)).isEqualTo(0)
        }

    @Test
    fun `template mode falls back to the explicit rows when the template has no allocations`(): Unit =
        runBlocking {
            val empty = repository().createTemplate(name = "Empty", description = null, allocations = emptyMap())
            repository().setAllocation(todayKey, "dim_work_livelihood", 30)

            repository().setDayMode(todayKey, DayPlanRepository.MODE_TEMPLATE, empty)

            val effective = repository().getEffectiveAllocationsForDay(todayKey)

            assertThat(effective.map { it.dimensionId }).containsExactly("dim_work_livelihood")
            assertThat(effective.single().source).isEqualTo("manual")
        }

    @Test
    fun `auto mode resolves the weekday preference with the auto source`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Weekday",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 300),
                )
            repository().setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY, id)

            val effective = repository().getEffectiveAllocationsForDay(weekdayKey)

            assertThat(effective.single().source).isEqualTo(DayPlanRepository.SOURCE_TEMPLATE_AUTO)
            assertThat(effective.single().plannedMinutes).isEqualTo(300)
        }

    @Test
    fun `auto mode resolves the weekend preference on a weekend day`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Weekend",
                    description = null,
                    allocations = mapOf("dim_recreation_leisure" to 120),
                )
            repository().setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKEND, id)

            val effective = repository().getEffectiveAllocationsForDay(weekendKey)

            assertThat(effective.single().dimensionId).isEqualTo("dim_recreation_leisure")
        }

    @Test
    fun `a starred day prefers the starred template over the day-type one`(): Unit =
        runBlocking {
            val weekdayTemplate =
                repository().createTemplate(
                    name = "Weekday",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 300),
                )
            val starredTemplate =
                repository().createTemplate(
                    name = "Starred",
                    description = null,
                    allocations = mapOf("dim_mental_health" to 60),
                )
            repository().setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY, weekdayTemplate)
            repository().setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_STARRED, starredTemplate)
            repository().setDayStarred(weekdayKey, isStarred = true)

            val effective = repository().getEffectiveAllocationsForDay(weekdayKey)

            assertThat(effective.single().dimensionId).isEqualTo("dim_mental_health")
        }

    @Test
    fun `an inactive template resolves to null and derives nothing`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Inactive",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 120),
                )
            repository().setDayMode(todayKey, DayPlanRepository.MODE_TEMPLATE, id)
            repository().deleteTemplate(id)

            assertThat(repository().resolveTemplateForDay(todayKey)).isNull()
            assertThat(repository().getEffectiveAllocationsForDay(todayKey)).isEmpty()
        }

    @Test
    fun `applyTemplateToDay copies the allocations and sets template mode`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Apply me",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 240, "dim_learning_growth" to 60),
                )

            repository().applyTemplateToDay(todayKey, id)

            assertThat(rawMode(todayKey)).isEqualTo(DayPlanRepository.MODE_TEMPLATE)
            assertThat(rawAllocationCount(todayKey)).isEqualTo(2)
            assertThat(rawAllocationMinutes(todayKey, "dim_work_livelihood")).isEqualTo(240)
        }

    @Test
    fun `applyTemplateToDay on an empty template sets the mode and stops`(): Unit =
        runBlocking {
            val empty = repository().createTemplate(name = "Nothing", description = null, allocations = emptyMap())
            repository().setAllocation(todayKey, "dim_work_livelihood", 30)

            repository().applyTemplateToDay(todayKey, empty)

            assertThat(rawMode(todayKey)).isEqualTo(DayPlanRepository.MODE_TEMPLATE)
            // An empty template does not clear the day: the Android path returns before the delete.
            assertThat(rawAllocationCount(todayKey)).isEqualTo(1)
        }

    @Test
    fun `resolveTemplateForDay returns the named template in template mode`(): Unit =
        runBlocking {
            val id =
                repository().createTemplate(
                    name = "Named",
                    description = null,
                    allocations = mapOf("dim_work_livelihood" to 60),
                )
            repository().setDayMode(todayKey, DayPlanRepository.MODE_TEMPLATE, id)

            assertThat(repository().resolveTemplateForDay(todayKey)?.id).isEqualTo(id)
        }

    @Test
    fun `allocations for a day are ordered by dimension`(): Unit =
        runBlocking {
            repository().setAllocations(
                todayKey,
                mapOf("dim_work_livelihood" to 60, "dim_family_relationships" to 30, "dim_money_finance" to 15),
            )

            val ordered = repository().getAllocationsForDay(todayKey).map { it.dimensionId }

            assertThat(ordered).isInOrder()
        }
}
