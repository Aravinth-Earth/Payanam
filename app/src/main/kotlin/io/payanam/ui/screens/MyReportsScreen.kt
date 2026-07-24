//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feedback.FeedbackIssue
import io.payanam.ui.viewmodel.FeedbackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReportsScreen(
    onNavigateBack: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val logger = remember { UnifiedLogger.getInstance() }
    val expandedStateByIssue = remember { mutableStateMapOf<Int, Boolean>() }

    val now = System.currentTimeMillis()
    val canRefresh = now >= uiState.nextRefreshAllowedMs
    val remainingMs = (uiState.nextRefreshAllowedMs - now).coerceAtLeast(0L)
    val remainingHrs = (remainingMs / (60 * 60 * 1000)).toInt()
    val remainingMin = ((remainingMs % (60 * 60 * 1000)) / (60 * 1000)).toInt()

    LaunchedEffect(Unit) {
        logger.i("MyReportsScreen", "Screen opened, attempting initial load")
        viewModel.loadIssues()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_reports_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            logger.i("MyReportsScreen", "Manual refresh triggered")
                            viewModel.loadIssues()
                        },
                        enabled = canRefresh && !uiState.isLoadingIssues,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.my_reports_refresh_button))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (!canRefresh) {
                Text(
                    text = stringResource(R.string.my_reports_refresh_throttled, "${remainingHrs}h ${remainingMin}m"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            when {
                uiState.isLoadingIssues -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.issuesError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.my_reports_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                uiState.issues.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.my_reports_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.issues) { issue ->
                            IssueCard(
                                issue = issue,
                                expanded = expandedStateByIssue[issue.number] == true,
                                onToggleExpanded = {
                                    expandedStateByIssue[issue.number] = !(expandedStateByIssue[issue.number] == true)
                                },
                                onOpenLink = {
                                    logger.i("MyReportsScreen", "Opening issue link", mapOf("number" to issue.number))
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issue.htmlUrl)))
                                    }.onFailure {
                                        logger.w(
                                            "MyReportsScreen",
                                            "Could not open issue URL",
                                            mapOf("number" to issue.number),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueCard(
    issue: FeedbackIssue,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenLink: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "#${issue.number}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IssueStateChip(state = issue.state)
            }
            Text(text = issue.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = issue.createdAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                IssueBodySummary(issue = issue)
                TextButton(onClick = onOpenLink, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.my_reports_open_link_button))
                }
            } else {
                Text(
                    text = stringResource(R.string.my_reports_tap_expand_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IssueBodySummary(issue: FeedbackIssue) {
    val description = issueSection(issue.body, "Description")
    val steps = issueSection(issue.body, "Steps to Reproduce")
    val metadata = issueSection(issue.body, "Included Metadata")

    if (!description.isNullOrBlank()) {
        Text(text = description, style = MaterialTheme.typography.bodySmall)
    }
    if (!steps.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.my_reports_steps_prefix, steps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!metadata.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.my_reports_metadata_prefix, metadata),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun issueSection(body: String?, title: String): String? {
    if (body.isNullOrBlank()) return null
    val header = "## $title"
    val start = body.indexOf(header)
    if (start < 0) return null
    val contentStart = start + header.length
    val nextHeader = body.indexOf("## ", contentStart).takeIf { it >= 0 } ?: body.length
    val raw = body.substring(contentStart, nextHeader)
        .replace("---", "")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("*Submitted via ") }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return raw.ifBlank { null }
}

@Composable
private fun IssueStateChip(state: String) {
    val isOpen = state == "open"
    SuggestionChip(
        onClick = {},
        label = {
            Text(
                text = if (isOpen) {
                    stringResource(R.string.my_reports_issue_state_open)
                } else {
                    stringResource(R.string.my_reports_issue_state_closed)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (isOpen) {
                Color(0xFF2DA44E).copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            labelColor = if (isOpen) {
                Color(0xFF2DA44E)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    )
}
