//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

/** One rendered block of a markdown-lite assistant reply. */
sealed interface MdBlock {
    /** `#`/`##`/`###` heading line. */
    data class Heading(val text: String) : MdBlock

    /** `- ` / `* ` bullet line. */
    data class Bullet(val text: String) : MdBlock

    /** `1. ` numbered line. */
    data class Numbered(val text: String) : MdBlock

    /** A row of a markdown table (rendered monospace). */
    data class TableRow(val text: String) : MdBlock

    /** Plain paragraph line. */
    data class Plain(val text: String) : MdBlock

    /** Empty separator line. */
    data object Blank : MdBlock
}

/** An inline text run; [bold] marks `**...**` spans. */
data class TextSegment(val text: String, val bold: Boolean)

/**
 * Renders the model's markdown-lite output in Compose-friendly blocks.
 *
 * Supported subset (matched to what the POC answers actually produce): headings,
 * `-`/`*` bullets, `1.` numbered lines, markdown table rows, and inline `**bold**`.
 * Everything else falls through as plain text — nothing throws, nothing is dropped.
 */
object AssistantText {
    private val heading = Regex("^#{1,3}\\s+(.*)$")
    private val bullet = Regex("^[-*•]\\s+(.*)$")
    private val numbered = Regex("^\\d+[.)]\\s+(.*)$")
    private val tableSeparator = Regex("^\\|?[\\s:\\-|]+\\|?$")

    /** Splits [markdown] into display blocks (pure; safe on malformed input). */
    fun parse(markdown: String): List<MdBlock> {
        if (markdown.isEmpty()) return emptyList()
        val blocks = mutableListOf<MdBlock>()
        markdown.split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> blocks += MdBlock.Blank
                heading.matches(trimmed) -> blocks += MdBlock.Heading(heading.find(trimmed)?.groupValues?.get(1) ?: "")
                tableSeparator.matches(trimmed) && trimmed.contains('-') -> Unit // separator row: not rendered
                trimmed.contains('|') -> blocks += MdBlock.TableRow(trimmed)
                bullet.matches(trimmed) -> blocks += MdBlock.Bullet(bullet.find(trimmed)?.groupValues?.get(1) ?: "")
                numbered.matches(trimmed) -> blocks += MdBlock.Numbered(trimmed)
                else -> blocks += MdBlock.Plain(line)
            }
        }
        return blocks
    }

    /**
     * Splits [text] into inline segments on `**bold**` markers. Unpaired markers are
     * kept as literal text.
     */
    fun segments(text: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var index = 0
        while (index < text.length) {
            val open = text.indexOf("**", index)
            if (open < 0) {
                segments += TextSegment(text.substring(index), bold = false)
                break
            }
            val close = text.indexOf("**", open + 2)
            if (close < 0) {
                segments += TextSegment(text.substring(index), bold = false)
                break
            }
            if (open > index) {
                segments += TextSegment(text.substring(index, open), bold = false)
            }
            val boldText = text.substring(open + 2, close)
            if (boldText.isNotEmpty()) {
                segments += TextSegment(boldText, bold = true)
            }
            index = close + 2
        }
        return segments.ifEmpty { listOf(TextSegment(text, bold = false)) }
    }
}
