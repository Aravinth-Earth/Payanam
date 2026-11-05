//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.tasks

import io.payanam.core.ui.components.HabitActivityDetailSection
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.text.LocalUiStrings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.presentation.tasks.ScoreDetailType
import io.payanam.presentation.tasks.ScoreDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreDetailScreen(
    type: ScoreDetailType,
    key: String,
    onNavigateBack: () -> Unit,
    viewModel: ScoreDetailViewModel,
) {
    val strings = LocalUiStrings.current
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(type, key) {
        viewModel.currentType = type
        viewModel.currentKey = key
        viewModel.load(type, key)
    }
    val title =
        if (type == ScoreDetailType.DAY) {
            strings.locLensScoreDetailDay.formatArgs(key)
        } else {
            strings.locLensScoreDetailDimension.formatArgs(key)
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.locBack,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // Side margins: content must not touch screen edges.
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            when {
                uiState.isLoading && uiState.rows.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                else -> {
                    HabitActivityDetailSection(
                        windowSizeDays = uiState.windowSizeDays,
                        windowEnd = uiState.windowEnd,
                        rows = uiState.rows,
                        occurrences = emptyMap(),
                        isLoading = uiState.isLoading,
                        showChartView = uiState.showChartView,
                        onWindowSizeChange = viewModel::setWindowSize,
                        onWindowBack = { viewModel.shiftWindow(-1 * uiState.windowSizeDays) },
                        onWindowForward = { viewModel.shiftWindow(uiState.windowSizeDays) },
                        onWindowToday = viewModel::goToday,
                        onChartViewChange = viewModel::setChartView,
                    )
                }
            }
        }
    }
}
