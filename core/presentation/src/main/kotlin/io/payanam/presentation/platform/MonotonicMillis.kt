//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.platform

/**
 * A monotonic millisecond reading, for measuring how long a piece of work took.
 *
 * WHY NOT `System.currentTimeMillis()`: a wall-clock reading can jump backwards (NTP, a user changing the
 * clock, a device waking from suspend), which turns a measured duration negative and prints a nonsense figure
 * in the log it was collected for. `System.nanoTime()` has an arbitrary origin and is only meaningful as a
 * difference, which is exactly how every caller uses it.
 *
 * The reading replaces `android.os.SystemClock.elapsedRealtime()`, which a platform-neutral module cannot
 * reach. The two answer the same question (a monotonic count of elapsed milliseconds) and neither is
 * comparable across processes, so nothing changes for a caller that only subtracts two readings.
 *
 * ONE shared helper rather than a private copy per file: two copies of a timing primitive are two things that
 * can drift, and the modules that measure with it are not otherwise related.
 */
fun elapsedMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
