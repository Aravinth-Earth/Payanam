//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.PassphrasePolicy
import io.payanam.ui.viewmodel.DatabaseBootIssueType
import io.payanam.ui.viewmodel.DatabaseInitViewModel
import io.payanam.ui.viewmodel.RestoreResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DatabaseInitScreen(
    onDatabaseReady: () -> Unit,
    viewModel: DatabaseInitViewModel = hiltViewModel(),
) {
    val logger = UnifiedLogger.getInstance()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val passphraseMismatchError = androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_error_mismatch)
    val scope = rememberCoroutineScope()

    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var importPassphraseInput by rememberSaveable { mutableStateOf("") }
    var showCreatePassphraseForm by rememberSaveable { mutableStateOf(false) }
    var createPassphrase by rememberSaveable { mutableStateOf("") }
    var createPassphraseConfirm by rememberSaveable { mutableStateOf("") }
    var createPassphraseError by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePassphrase by rememberSaveable { mutableStateOf(false) }
    var showCreatePassphraseConfirm by rememberSaveable { mutableStateOf(false) }
    var showImportPassphrase by rememberSaveable { mutableStateOf(false) }
    var hasFinishedOnboarding by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.importDatabase(it, onSuccess = onDatabaseReady) }
    }
    if (uiState.showCreateNewWipeConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelCreateNewWipe() },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_confirm_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_confirm_create_new_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        logger.i("DatabaseInitScreen", "User confirmed create new with wipe")
                        viewModel.confirmCreateNew(passphrase = createPassphrase)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(androidx.compose.ui.res.stringResource(R.string.loc_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelCreateNewWipe() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }
    if (uiState.showImportWipeConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportWipe() },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_confirm_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_confirm_import_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        logger.i("DatabaseInitScreen", "User confirmed import with wipe")
                        viewModel.confirmImportAfterWipe(onSuccess = onDatabaseReady)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(androidx.compose.ui.res.stringResource(R.string.loc_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportWipe() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }
    when (uiState.restoreResult) {
        is RestoreResult.RestoredOk -> AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreResult() },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_restore_ok_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_restore_ok_body)) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRestoreResult() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.loc_ok))
                }
            },
        )

        is RestoreResult.RestoreFailed -> AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreResult() },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_restore_failed_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.db_init_wipe_restore_failed_body)) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRestoreResult() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.loc_ok))
                }
            },
        )

        null -> { /* nothing */ }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_database_title),
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))

            when {
                uiState.awaitingDimensionSetup -> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        MandatoryDimensionSetupSection(
                            isSaving = uiState.isCreating,
                            onSave = { dimensionInputs ->
                                viewModel.completeNewDatabaseDimensionSetup(
                                    dimensionInputs = dimensionInputs,
                                    onSuccess = onDatabaseReady,
                                )
                            },
                        )
                    }
                }

                uiState.awaitingImportPassphrase -> {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = R.string.db_import_passphrase_prompt_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = R.string.db_import_passphrase_prompt_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = importPassphraseInput,
                        onValueChange = { importPassphraseInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_input_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (importPassphraseInput.isNotBlank() && !uiState.isImporting) {
                                logger.i("DatabaseInitScreen", "Import passphrase submitted from IME action")
                                viewModel.resumeImportWithPassphrase(importPassphraseInput, onSuccess = {
                                    importPassphraseInput = ""
                                    showImportPassphrase = false
                                    onDatabaseReady()
                                })
                            }
                        }),
                        visualTransformation = if (showImportPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showImportPassphrase = !showImportPassphrase }) {
                                Icon(
                                    imageVector = if (showImportPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = androidx.compose.ui.res.stringResource(
                                        id = if (showImportPassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                                    ),
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !uiState.isImporting,
                    )

                    uiState.importPassphraseError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Import passphrase submitted")
                            viewModel.resumeImportWithPassphrase(importPassphraseInput, onSuccess = {
                                importPassphraseInput = ""
                                showImportPassphrase = false
                                onDatabaseReady()
                            })
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = importPassphraseInput.isNotBlank() && !uiState.isImporting,
                    ) {
                        if (uiState.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(androidx.compose.ui.res.stringResource(id = R.string.db_import_passphrase_prompt_action))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Import passphrase cancelled")
                            importPassphraseInput = ""
                            showImportPassphrase = false
                            viewModel.cancelImportPassphrase()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !uiState.isImporting,
                    ) {
                        Text(androidx.compose.ui.res.stringResource(id = R.string.settings_action_cancel))
                    }
                }

                showCreatePassphraseForm -> {
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_setup_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_no_recovery_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = createPassphrase,
                        onValueChange = {
                            createPassphrase = it
                            createPassphraseError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_input_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showCreatePassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCreatePassphrase = !showCreatePassphrase }) {
                                Icon(
                                    imageVector = if (showCreatePassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = androidx.compose.ui.res.stringResource(
                                        id = if (showCreatePassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                                    ),
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !uiState.isCreating,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = createPassphraseConfirm,
                        onValueChange = {
                            createPassphraseConfirm = it
                            createPassphraseError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_confirm_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showCreatePassphraseConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCreatePassphraseConfirm = !showCreatePassphraseConfirm }) {
                                Icon(
                                    imageVector = if (showCreatePassphraseConfirm) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = androidx.compose.ui.res.stringResource(
                                        id = if (showCreatePassphraseConfirm) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                                    ),
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !uiState.isCreating,
                    )

                    createPassphraseError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val validation = PassphrasePolicy.validate(createPassphrase)
                            if (!validation.isValid) {
                                createPassphraseError = passphraseValidationMessage(context, validation.reasonCode)
                            } else if (createPassphrase != createPassphraseConfirm) {
                                createPassphraseError = passphraseMismatchError
                            } else {
                                logger.i("DatabaseInitScreen", "Create DB passphrase submitted")
                                viewModel.createNewDatabase(passphrase = createPassphrase)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = createPassphrase.isNotBlank() && createPassphraseConfirm.isNotBlank() && !uiState.isCreating,
                    ) {
                        if (uiState.isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(androidx.compose.ui.res.stringResource(id = R.string.db_passphrase_setup_action))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Create DB passphrase form cancelled")
                            showCreatePassphraseForm = false
                            createPassphrase = ""
                            createPassphraseConfirm = ""
                            createPassphraseError = null
                            showCreatePassphrase = false
                            showCreatePassphraseConfirm = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !uiState.isCreating,
                    ) {
                        Text(androidx.compose.ui.res.stringResource(id = R.string.loc_back))
                    }
                }

                uiState.isChecking -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_checking_database_status))
                }

                uiState.bootIssue != null -> {
                    val bootIssue = uiState.bootIssue!!
                    val emphasizeUpdate = bootIssue.type == DatabaseBootIssueType.DB_TOO_NEW
                    val titleColor = if (emphasizeUpdate) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    val containerColor = if (emphasizeUpdate) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                    val onContainerColor = if (emphasizeUpdate) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_warning),
                        modifier = Modifier.size(64.dp),
                        tint = titleColor,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = bootIssueTitleRes(bootIssue.type)),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = containerColor,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = bootIssueHeadlineRes(bootIssue.type)),
                                fontWeight = FontWeight.Bold,
                                color = onContainerColor,
                            )
                            Text(
                                text = bootIssueBodyText(bootIssue, uiState.corruptionMessage),
                                color = onContainerColor,
                            )
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    id = if (emphasizeUpdate) {
                                        R.string.db_init_issue_update_app_or_choose_other_path
                                    } else {
                                        io.payanam.R.string.loc_import_or_create_new_database
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = onContainerColor,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = {
                            logger.i(
                                "DatabaseInitScreen",
                                "Recheck database status clicked",
                                mapOf("issueType" to bootIssue.type.name),
                            )
                            viewModel.retryDatabaseStatusCheck()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !uiState.isChecking && !uiState.isCreating && !uiState.isImporting,
                    ) {
                        Text(androidx.compose.ui.res.stringResource(id = R.string.db_init_action_recheck_database))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Import database clicked", mapOf())
                            importLauncher.launch(null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !uiState.isCreating && !uiState.isImporting,
                    ) {
                        if (uiState.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_import_valid_database))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Create new database clicked (boot issue path)", mapOf())
                            showCreatePassphraseForm = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !uiState.isCreating && !uiState.isImporting,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_create_new_empty_database))
                    }
                }

                uiState.databaseExists -> {
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_existing_database_found),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_size), fontWeight = FontWeight.Medium)
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        id = io.payanam.R.string.settings_database_size_value,
                                        uiState.databaseSizeKB,
                                    ),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_last_modified), fontWeight = FontWeight.Medium)
                                Text(
                                    uiState.lastModified?.takeIf { it > 0 }?.let {
                                        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                            .format(Date(it))
                                    } ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_not_available),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_tasks), fontWeight = FontWeight.Medium)
                                Text(uiState.taskCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_time_entries), fontWeight = FontWeight.Medium)
                                Text(uiState.timeEntryCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_journal_entries), fontWeight = FontWeight.Medium)
                                Text(uiState.journeyEntryCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_notes), fontWeight = FontWeight.Medium)
                                Text(uiState.noteCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_schema_version), fontWeight = FontWeight.Medium)
                                Text(uiState.databaseSchemaVersion.toString())
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Continue with existing database", mapOf("taskCount" to uiState.taskCount.toString()))
                            viewModel.continueWithExistingDatabase(onDatabaseReady)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_continue_with_existing_database))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            logger.i("DatabaseInitScreen", "Import database (existing) clicked", mapOf())
                            importLauncher.launch(null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_import_different_database))
                    }
                }

                else -> {
                    if (!hasFinishedOnboarding) {
                        AppOnboardingIntroScreen(
                            onFinished = {
                                hasFinishedOnboarding = true
                            },
                        )
                    } else {
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_welcome_to_payanam),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_database_found_choose_start),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
                                logger.i("DatabaseInitScreen", "Create new database (no existing) clicked", mapOf())
                                showCreatePassphraseForm = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = !uiState.isCreating && !uiState.isImporting,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_create_new_empty_database))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                logger.i("DatabaseInitScreen", "Import database (no existing) clicked", mapOf())
                                importLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = !uiState.isCreating && !uiState.isImporting,
                        ) {
                            if (uiState.isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_import_existing_database))
                            }
                        }
                    }
                }
            }
            if (!uiState.awaitingDimensionSetup && (hasFinishedOnboarding || uiState.databaseExists || uiState.bootIssue != null || showCreatePassphraseForm || uiState.awaitingImportPassphrase || uiState.isChecking)) {
                Spacer(modifier = Modifier.height(16.dp))
                DatabaseInitLogExportActions(
                    logger = logger,
                    context = context,
                    scope = scope,
                    debugExportMessage = debugExportMessage,
                    onDebugExportMessageChange = { debugExportMessage = it },
                    showHint = uiState.errorMessage != null,
                )
            }

            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
