//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the shared string tables without an allowlist.
 *
 * A duplicate is a pair of keys whose English and Tamil values both match after normalisation.
 * A collision in only one language is a warning: it is either a homonym (தொடர் = continue/streak)
 * or a context-specific label (a backup trigger vs a settings button), not a duplicated key.
 */
class StringResourceIntegrityTest {
    @Test
    fun `no locale has duplicate string names`() {
        val duplicates =
            tables().flatMap { (locale, entries) ->
                entries
                    .groupBy { it.name }
                    .filterValues { it.size > 1 }
                    .map { (name, group) -> "$locale:$name (${group.size})" }
            }
        assertThat(duplicates).isEmpty()
    }

    @Test
    fun `no key pair has the same English and Tamil value`() {
        val tables = tables()
        val english = tables.getValue("values").associate { it.name to normalize(it.value) }
        val tamil = tables.getValue("values-ta").associate { it.name to normalize(it.value) }
        assertThat(english.keys).containsExactlyElementsIn(tamil.keys)

        val keys = english.keys.sorted()
        val trueDuplicates = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        keys.forEachIndexed { index, first ->
            for (second in keys.drop(index + 1)) {
                val enSame = english.getValue(first) == english.getValue(second)
                val taSame = tamil.getValue(first) == tamil.getValue(second)
                when {
                    enSame && taSame -> trueDuplicates += "$first / $second = ${english.getValue(first)}"
                    enSame -> warnings += "same English, different Tamil: $first / $second (${english.getValue(first)})"
                    taSame -> warnings += "same Tamil, different English: $first / $second (${tamil.getValue(first)})"
                }
            }
        }
        warnings.sorted().forEach { println("String collision warning: $it") }
        assertThat(trueDuplicates).isEmpty()
    }

    @Test
    fun `format specifiers match between English and Tamil`() {
        val tables = tables()
        val english = tables.getValue("values").associate { it.name to specifiers(it.value) }
        val tamil = tables.getValue("values-ta").associate { it.name to specifiers(it.value) }
        assertThat(tamil).containsExactlyEntriesIn(english)
    }

    private fun tables(): Map<String, List<Entry>> {
        val resourceDir = resolveResourceDir()
        return Files.list(resourceDir).use { paths ->
            paths
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("values") }
                .filter { Files.isRegularFile(it.resolve("strings.xml")) }
                .sorted()
                .toList()
                .associate { dir ->
                    dir.fileName.toString() to readEntries(dir.resolve("strings.xml"))
                }
        }
    }

    private fun readEntries(file: Path): List<Entry> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { index ->
            val node = nodes.item(index)
            Entry(
                name = node.attributes.getNamedItem("name").nodeValue,
                value = node.textContent.orEmpty(),
            )
        }
    }

    private fun resolveResourceDir(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("core/strings/src/main/res")
            if (Files.isDirectory(candidate)) return candidate
            current = current.parent
        }
        error("Could not resolve core/strings/src/main/res from ${Path.of("").toAbsolutePath()}")
    }

    private fun specifiers(value: String): List<String> =
        formatSpecifier
            .findAll(value.replace("%%", ""))
            .map { it.value }
            .toList()
            .sorted()

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private val formatSpecifier = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(\.\d+)?[a-zA-Z]""")

    private data class Entry(
        val name: String,
        val value: String,
    )
}
