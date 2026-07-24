//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.BODY_MAX_CHARS
import io.payanam.ui.viewmodel.FeedbackUiState
import io.payanam.ui.viewmodel.FeedbackViewModel
import io.payanam.ui.viewmodel.ReportType
import io.payanam.ui.viewmodel.SUBMISSION_MAX_PER_HOUR
import io.payanam.ui.viewmodel.SubmitResult
import io.payanam.ui.viewmodel.TITLE_MAX_CHARS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMyReports: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val logger = remember { UnifiedLogger.getInstance() }

    if (uiState.submitResult is SubmitResult.Success) {
        SubmitSuccessDialog(
            onDismiss = {
                logger.i("FeedbackScreen", "Submit success dismissed")
                viewModel.onSubmitResultDismissed()
                onNavigateToMyReports()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        logger.i("FeedbackScreen", "Navigating to my reports")
                        onNavigateToMyReports()
                    }) {
                        Text(stringResource(R.string.my_reports_screen_title))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                ReportTypeSelector(
                    selected = uiState.reportType,
                    onSelect = viewModel::onReportTypeChange,
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text(stringResource(R.string.loc_title) + " (${uiState.title.length}/$TITLE_MAX_CHARS)") },
                    placeholder = { Text(stringResource(R.string.feedback_field_title_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = { Text(stringResource(R.string.feedback_field_description_label)) },
                    placeholder = { Text(stringResource(R.string.feedback_field_description_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            if (uiState.reportType == ReportType.BUG) {
                item {
                    OutlinedTextField(
                        value = uiState.steps,
                        onValueChange = viewModel::onStepsChange,
                        label = { Text(stringResource(R.string.feedback_field_steps_label)) },
                        placeholder = { Text(stringResource(R.string.feedback_field_steps_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }

            item {
                MetaInclusionSection(uiState = uiState, viewModel = viewModel)
            }

            item {
                Text(
                    text = stringResource(R.string.feedback_privacy_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                val errorResult = uiState.submitResult as? SubmitResult.Error
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            logger.i("FeedbackScreen", "Submit tapped", mapOf("type" to uiState.reportType))
                            viewModel.submitFeedback()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSubmitting && uiState.submissionsRemainingToday > 0,
                    ) {
                        Text(
                            if (uiState.isSubmitting) {
                                stringResource(R.string.feedback_submitting)
                            } else {
                                stringResource(R.string.feedback_submit_button)
                            },
                        )
                    }
                    Text(
                        text = stringResource(R.string.feedback_submissions_remaining, uiState.submissionsRemainingToday, SUBMISSION_MAX_PER_HOUR),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.submissionsRemainingToday <= 1) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (errorResult != null) {
                        Text(
                            text = when (errorResult.message) {
                                "EMPTY_FIELDS" -> stringResource(R.string.feedback_error_empty_fields)
                                "SUBMISSION_LIMIT_REACHED" -> stringResource(R.string.feedback_error_limit_reached)
                                else -> stringResource(R.string.feedback_error_network)
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                ContactSection(context = context)
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ReportTypeSelector(selected: ReportType, onSelect: (ReportType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = {
                    Text(
                        when (type) {
                            ReportType.BUG -> stringResource(R.string.feedback_type_bug)
                            ReportType.FEATURE -> stringResource(R.string.feedback_type_feature)
                            ReportType.FEEDBACK -> stringResource(R.string.feedback_type_general)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun MetaInclusionSection(uiState: FeedbackUiState, viewModel: FeedbackViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    Column {
        Text(
            text = stringResource(R.string.feedback_meta_section_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetaCheckboxRow(
            checked = uiState.includeDeviceModel,
            label = stringResource(R.string.feedback_meta_device_model, "${Build.MANUFACTURER} ${Build.MODEL}"),
            onCheckedChange = viewModel::onIncludeDeviceModelChange,
        )
        MetaCheckboxRow(
            checked = uiState.includeOsVersion,
            label = stringResource(R.string.feedback_meta_os_version, Build.VERSION.RELEASE),
            onCheckedChange = viewModel::onIncludeOsVersionChange,
        )
        MetaCheckboxRow(
            checked = uiState.includeLocale,
            label = stringResource(R.string.feedback_meta_locale, configuration.locales[0].language),
            onCheckedChange = viewModel::onIncludeLocaleChange,
        )
        MetaCheckboxRow(
            checked = uiState.includeBuild,
            label = stringResource(R.string.feedback_meta_build, io.payanam.BuildConfig.VERSION_CODE),
            onCheckedChange = viewModel::onIncludeBuildChange,
        )
    }
}

@Composable
private fun MetaCheckboxRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ContactSection(context: Context) {
    var signalCopied by remember { mutableStateOf(false) }
    var contactStatusResId by remember { mutableStateOf<Int?>(null) }
    val devEmail = stringResource(R.string.feedback_contact_email)
    val devSignal = stringResource(R.string.feedback_contact_signal)
    val logger = remember { UnifiedLogger.getInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.feedback_contact_section_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.feedback_contact_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.feedback_contact_email_value, devEmail.ifBlank { "-" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.feedback_contact_signal_value, devSignal.ifBlank { "-" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (devEmail.isBlank()) {
                    logger.w("FeedbackScreen.ContactSection", "Email action unavailable; address missing")
                    contactStatusResId = R.string.feedback_contact_not_configured
                    return@OutlinedButton
                }
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$devEmail?subject=Payanam+Feedback"))
                runCatching {
                    context.startActivity(Intent.createChooser(intent, null))
                }.onSuccess {
                    contactStatusResId = null
                }.onFailure { error ->
                    if (error is ActivityNotFoundException) {
                        logger.w("FeedbackScreen.ContactSection", "No email app available")
                        contactStatusResId = R.string.feedback_contact_email_unavailable
                    } else {
                        logger.e("FeedbackScreen.ContactSection", "Email action failed", error)
                        contactStatusResId = R.string.feedback_contact_email_open_failed
                    }
                }
            }) {
                Text(stringResource(R.string.feedback_contact_email_button))
            }
            OutlinedButton(onClick = {
                if (devSignal.isBlank()) {
                    logger.w("FeedbackScreen.ContactSection", "Signal action unavailable; username missing")
                    contactStatusResId = R.string.feedback_contact_not_configured
                    return@OutlinedButton
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) {
                    logger.w("FeedbackScreen.ContactSection", "Clipboard unavailable")
                    contactStatusResId = R.string.feedback_contact_copy_failed
                    return@OutlinedButton
                }
                runCatching {
                    clipboard.setPrimaryClip(ClipData.newPlainText("signal_username", devSignal))
                }.onSuccess {
                    signalCopied = true
                    contactStatusResId = null
                }.onFailure { error ->
                    logger.e("FeedbackScreen.ContactSection", "Signal copy failed", error)
                    contactStatusResId = R.string.feedback_contact_copy_failed
                }
            }) {
                Text(
                    if (signalCopied) {
                        stringResource(R.string.feedback_contact_signal_copied)
                    } else {
                        stringResource(R.string.feedback_contact_signal_button)
                    },
                )
            }
        }
        if (contactStatusResId != null) {
            Text(
                text = stringResource(contactStatusResId!!),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SubmitSuccessDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feedback_success_title)) },
        text = { Text(stringResource(R.string.feedback_success_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.loc_done))
            }
        },
    )
}
