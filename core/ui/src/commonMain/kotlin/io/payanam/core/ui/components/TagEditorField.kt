//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

@Composable
fun TagEditorField(
    rawValue: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    val selectedTags = parseTagsInput(rawValue)
    val matchingTagSuggestions =
        suggestions
            .filter { suggestion ->
                suggestion.contains(rawValue.trim(), ignoreCase = true) && suggestion !in selectedTags
            }.take(6)
    OutlinedTextField(
        value = rawValue,
        onValueChange = onValueChange,
        label = { Text(strings.locTagsOptional) },
        placeholder = { Text(strings.locTagsHint) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (matchingTagSuggestions.isNotEmpty()) {
        LazyRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(matchingTagSuggestions) { suggestion ->
                FilterChip(
                    selected = false,
                    onClick = {
                        val newTags = (selectedTags + suggestion).distinct()
                        onValueChange(newTags.joinToString(", "))
                        logger.d("TagEditorField", "Tag suggestion applied", mapOf("tag" to suggestion))
                    },
                    label = { Text(suggestion) },
                )
            }
        }
    }
}

/**
 * Splits a comma-separated tag string into trimmed, distinct tags.
 */
fun parseTagsInput(rawTags: String): List<String> =
    rawTags
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
