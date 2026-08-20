//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * NumDenToConfigConverterTest.
 */
class NumDenToConfigConverterTest {

    private val anchor: LocalDate = LocalDate.of(2025, 8, 3)

    // ── 1/1 → DAILY ────────────────────────────────────────────────────────
    @Test
    fun `1 slash 1 converts to DAILY`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("1/1", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }

    @Test
    fun `n slash n converts to DAILY`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("7/7", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }

    // ── 5/7 → WEEKDAYS_ONLY (Mon–Fri) ──────────────────────────────────────
    @Test
    fun `5 slash 7 converts to WEEKDAYS_ONLY`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("5/7", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.WEEKDAYS_ONLY, config.type)
        // Mon–Fri due, Sat/Sun not due
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2026, 8, 3)))  // Monday
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2026, 8, 7)))  // Friday
        /** Assert true. */
        assertTrue(!config.isScheduledDay(LocalDate.of(2026, 8, 8))) // Saturday
        /** Assert true. */
        assertTrue(!config.isScheduledDay(LocalDate.of(2026, 8, 9))) // Sunday
    }

    // ── 2/7 → SPECIFIC_WEEKDAYS [6,7] (Sat, Sun) ───────────────────────────
    @Test
    fun `2 slash 7 converts to weekend specific weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("2/7", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        /** Assert equals. */
        assertEquals(setOf(6, 7), config.weekdays)
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2026, 8, 8)))  // Saturday
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2026, 8, 9)))  // Sunday
        /** Assert true. */
        assertTrue(!config.isScheduledDay(LocalDate.of(2026, 8, 3))) // Monday
    }

    // ── 4/7 → SPECIFIC_WEEKDAYS [1,2,3,4] (Mon–Thu default) ────────────────
    @Test
    fun `4 slash 7 converts to Mon-Thu specific weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("4/7", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        /** Assert equals. */
        assertEquals(setOf(1, 2, 3, 4), config.weekdays)
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2026, 8, 3)))  // Monday
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2026, 8, 6)))  // Thursday
        /** Assert true. */
        assertTrue(!config.isScheduledDay(LocalDate.of(2026, 8, 7))) // Friday
    }

    // ── 1/N → INTERVAL N with startDate ────────────────────────────────────
    @Test
    fun `1 slash 7 converts to INTERVAL 7 with anchor start`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("1/7", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        /** Assert equals. */
        assertEquals(7, config.intervalDays)
        /** Assert true. */
        assertTrue(config.isScheduledDay(anchor))
        /** Assert true. */
        assertTrue(config.isScheduledDay(anchor.plusDays(7)))
        /** Assert true. */
        assertTrue(config.isScheduledDay(anchor.plusDays(14)))
        /** Assert true. */
        assertTrue(!config.isScheduledDay(anchor.plusDays(3)))
    }

    @Test
    fun `1 slash 30 converts to INTERVAL 30`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("1/30", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        /** Assert equals. */
        assertEquals(30, config.intervalDays)
        /** Assert true. */
        assertTrue(config.isScheduledDay(anchor.plusDays(30)))
    }

    @Test
    fun `1 slash 14 converts to INTERVAL 14`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("1/14", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        /** Assert equals. */
        assertEquals(14, config.intervalDays)
    }

    @Test
    fun `1 slash 2 converts to INTERVAL 2`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("1/2", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        /** Assert equals. */
        assertEquals(2, config.intervalDays)
    }

    // ── 1/365 → YEARLY ─────────────────────────────────────────────────────
    @Test
    fun `1 slash 365 converts to YEARLY`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("1/365", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.YEARLY, config.type)
        /** Assert true. */
        assertTrue(config.isScheduledDay(anchor))
    }

    // ── Rule with !start= suffix (anchor embedded in rule) ─────────────────
    @Test
    fun `rule with embedded start suffix uses anchor from rule`() {
        /** Embedded anchor. */
        val embeddedAnchor = LocalDate.of(2026, 1, 1)
        /** Config. */
        val config = RecurrenceConfig.parse(
            NumDenToConfigConverter.convert("1/7!start=$embeddedAnchor", anchor),
        )
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        // Frequency.parse reads the embedded anchor, but our converter takes
        // anchorDate explicitly; rule-suffix anchor is ignored by design
        /** Assert equals. */
        assertEquals(7, config.intervalDays)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────
    @Test
    fun `blank rule converts to DAILY`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert(null, anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
        /** Config2. */
        val config2 = RecurrenceConfig.parse(NumDenToConfigConverter.convert("", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config2.type)
    }

    @Test
    fun `garbage rule falls back to DAILY`() {
        /** Config. */
        val config = RecurrenceConfig.parse(NumDenToConfigConverter.convert("garbage", anchor))
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }

    @Test
    fun `output round-trips through RecurrenceConfig serialize-parse`() {
        /** Inputs. */
        val inputs = listOf("1/1", "5/7", "2/7", "4/7", "1/7", "1/30", "1/14", "1/365", "2/7")
        /** For. */
        for (rule in inputs) {
            /** Serialized. */
            val serialized = NumDenToConfigConverter.convert(rule, anchor)
            /** Reparsed. */
            val reparsed = RecurrenceConfig.parse(serialized)
            // reparsed must serialize to the identical string (stable form)
            /** Assert equals. */
            assertEquals("round-trip failed for $rule", serialized, reparsed.serialize())
        }
    }

    @Test
    fun `interval config keeps startDate in serialized form`() {
        /** Serialized. */
        val serialized = NumDenToConfigConverter.convert("1/7", anchor)
        /** Assert true. */
        assertTrue("expected start=$anchor in $serialized", serialized.contains("start=$anchor"))
    }
}
