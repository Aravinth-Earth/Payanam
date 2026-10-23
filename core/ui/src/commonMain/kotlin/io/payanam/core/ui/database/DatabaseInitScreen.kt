//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.IMPORT_PICKER_MIME
import io.payanam.core.ui.components.ImportDatabaseFileButton
import io.payanam.core.ui.onboarding.OnboardingIntroScreen
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.formatArgs
import io.payanam.presentation.database.DatabaseInitViewModel
import io.payanam.presentation.database.RestoreResult
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The database initialisation / import gate, rendered identically by every platform.
 *
 * Moved here from `:app` with its layout unchanged: same column, same spacing, same field order. Only the
 * things that are genuinely platform- or app-specific were lifted out, so the Android app and the desktop
 * cannot render two different screens:
 *
 *  - The copy comes from [LocalUiStrings], the ambient table the navigation graph installs, exactly as it
 *    arrived from the platform's resources before. Which language is active is OS interaction (per-app
 *    language on Android, `Locale.getDefault()` on the desktop) and this module holds none of it.
 *  - The ViewModel arrives as a parameter rather than being built here. The platform owns retention
 *    (`retainSharedViewModel` on Android, the desktop's own holder) and construction from the Hilt graph;
 *    the shared screen owns none of it.
 *  - [onImportFileSelected] replaces the SAF launcher. The picker is Android's own `OpenDocument` contract
 *    and the `Uri` it returns is an Android type, so the shared screen asks the platform to pick and hands
 *    back the MIME filter to use. The desktop passes a no-op.
 *  - [debugLogExport] replaces the log-export actions, following the [MandatoryDimensionSetupSection]
 *    precedent: the exporter writes to Android's own storage and reads Android's Rclass, so the shared
 *    screen takes a composable slot instead of a parallel exporter.
 *
 * The passphrase validation is inlined here rather than imported from `:core:database` because that module
 * is an Android library and this one is KMP - the validation itself is pure Kotlin with no platform
 * meaning, so it lives beside the screen that uses it.
 */
@Composable
fun DatabaseInitScreen(
    onDatabaseReady: () -> Unit,
    viewModel: DatabaseInitViewModel,
    onImportFileSelected: (Array<String>) -> Unit,
    language: UiLanguage,
    debugLogExport: (@Composable () -> Unit)? = null,
    /**
     * True when the host already showed the shared intro in this run - the desktop renders it from its own
     * startup surface first, so the graph must not show it a second time.
     */
    onboardingIntroAlreadyCompleted: Boolean = false,
) {
    val strings = LocalUiStrings.current
    val logger = AppLogger.current
    val passphraseMismatchError = strings.dbPassphraseErrorMismatch
    val uiState by viewModel.uiState.collectAsState()

    var importPassphraseInput by rememberSaveable { mutableStateOf("") }
    var showCreatePassphraseForm by rememberSaveable { mutableStateOf(false) }
    var createPassphrase by rememberSaveable { mutableStateOf("") }
    var createPassphraseConfirm by rememberSaveable { mutableStateOf("") }
    var createPassphraseError by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePassphrase by rememberSaveable { mutableStateOf(false) }
    var showCreatePassphraseConfirm by rememberSaveable { mutableStateOf(false) }
    var showImportPassphrase by rememberSaveable { mutableStateOf(false) }
    // Seeded from the host, not hardcoded false: the desktop renders this same intro from its own startup
    // surface first and passes true, and that seed is what stops the second copy here. Android only has
    // this one, so it keeps the false default.
    var hasFinishedOnboarding by rememberSaveable { mutableStateOf(onboardingIntroAlreadyCompleted) }
    if (uiState.showCreateNewWipeConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelCreateNewWipe() },
            title = { Text(strings.dbInitWipeConfirmTitle) },
            text = { Text(strings.dbInitWipeConfirmCreateNewBody) },
            confirmButton = {
                Button(
                    onClick = {
                        logger.i("DatabaseInitScreen", "User confirmed create new with wipe")
                        viewModel.confirmCreateNew(passphrase = createPassphrase)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(strings.locContinue) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelCreateNewWipe() }) {
                    Text(strings.settingsActionCancel)
                }
            },
        )
    }
    if (uiState.showImportWipeConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelImportWipe() },
            title = { Text(strings.dbInitWipeConfirmTitle) },
            text = { Text(strings.dbInitWipeConfirmImportBody) },
            confirmButton = {
                Button(
                    onClick = {
                        logger.i("DatabaseInitScreen", "User confirmed import with wipe")
                        viewModel.confirmImportAfterWipe(onSuccess = onDatabaseReady)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(strings.locContinue) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImportWipe() }) {
                    Text(strings.settingsActionCancel)
                }
            },
        )
    }
    when (uiState.restoreResult) {
        is RestoreResult.RestoredOk -> AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreResult() },
            title = { Text(strings.dbInitWipeRestoreOkTitle) },
            text = { Text(strings.dbInitWipeRestoreOkBody) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRestoreResult() }) {
                    Text(strings.locOk)
                }
            },
        )

        is RestoreResult.RestoreFailed -> AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreResult() },
            title = { Text(strings.dbInitWipeRestoreFailedTitle) },
            text = { Text(strings.dbInitWipeRestoreFailedBody) },
            confirmButton = {
                Button(onClick = { viewModel.dismissRestoreResult() }) {
                    Text(strings.locOk)
                }
            },
        )

        null -> { /* nothing */ }
    }
    Surface(
        modifier = Modifier
            .fillMaxSize(),
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
                contentDescription = strings.settingsDatabaseTitle,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Fold the passphrase-pause state in too: the import is staged there, so a tap must not
            // start a second one over the only pre-import restore copy. Defined once for all three
            // onboarding import call sites below (Settings' copy also folds `isExporting`, a flag this
            // screen does not have).
            val importInFlight = uiState.isImporting || uiState.awaitingImportPassphrase

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
                            // The section is shared, so the debug-log exporter comes in as a slot: it
                            // writes to Android's own storage and reads Android's Rclass, and the desktop
                            // passes nothing rather than a parallel exporter.
                            debugLogExport = debugLogExport,
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
                        text = strings.dbImportPassphrasePromptTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = strings.dbImportPassphrasePromptDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = importPassphraseInput,
                        onValueChange = { importPassphraseInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.dbPassphraseInputLabel) },
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
                                    contentDescription = if (showImportPassphrase) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
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
                            Text(strings.dbImportPassphrasePromptAction)
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
                        Text(strings.settingsActionCancel)
                    }
                }

                showCreatePassphraseForm -> {
                    Text(
                        text = strings.dbPassphraseSetupTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = strings.dbPassphraseNoRecoveryWarning,
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
                        label = { Text(strings.dbPassphraseInputLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showCreatePassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCreatePassphrase = !showCreatePassphrase }) {
                                Icon(
                                    imageVector = if (showCreatePassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showCreatePassphrase) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
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
                        label = { Text(strings.dbPassphraseConfirmLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showCreatePassphraseConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showCreatePassphraseConfirm = !showCreatePassphraseConfirm }) {
                                Icon(
                                    imageVector = if (showCreatePassphraseConfirm) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showCreatePassphraseConfirm) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
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
                            val validation = validatePassphrase(createPassphrase)
                            if (!validation.isValid) {
                                createPassphraseError = passphraseValidationMessage(strings, validation.reasonCode)
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
                            Text(strings.dbPassphraseSetupAction)
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
                        Text(strings.locBack)
                    }
                }

                uiState.isChecking -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(strings.locCheckingDatabaseStatus)
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
                        contentDescription = strings.locWarning,
                        modifier = Modifier.size(64.dp),
                        tint = titleColor,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = bootIssueTitleRes(bootIssue.type, strings),
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
                                text = bootIssueHeadlineRes(bootIssue.type, strings),
                                fontWeight = FontWeight.Bold,
                                color = onContainerColor,
                            )
                            Text(
                                text = bootIssueBodyText(bootIssue, uiState.corruptionMessage, strings),
                                color = onContainerColor,
                            )
                            Text(
                                text = if (emphasizeUpdate) {
                                    strings.dbInitIssueUpdateAppOrChooseOtherPath
                                } else {
                                    strings.locImportOrCreateNewDatabase
                                },
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
                        Text(strings.dbInitActionRecheckDatabase)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ImportDatabaseFileButton(
                        icon = Icons.Default.CloudUpload,
                        label = strings.locImportDatabaseFile,
                        logContext = "boot issue path",
                        enabled = !uiState.isCreating && !importInFlight,
                        showProgress = importInFlight,
                        onClick = { onImportFileSelected(IMPORT_PICKER_MIME) },
                    )
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
                        Text(strings.locCreateNewEmptyDatabase)
                    }
                }

                uiState.databaseExists -> {
                    Text(
                        text = strings.locExistingDatabaseFound,
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
                                Text(strings.locSize, fontWeight = FontWeight.Medium)
                                Text(
                                    strings.settingsDatabaseSizeValue.formatArgs(uiState.databaseSizeKB),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(strings.locLastModified, fontWeight = FontWeight.Medium)
                                Text(
                                    uiState.lastModified?.takeIf { it > 0 }?.let {
                                        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                            .format(Date(it))
                                    } ?: strings.locNotAvailable,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(strings.locTasks, fontWeight = FontWeight.Medium)
                                Text(uiState.taskCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(strings.locTimeEntries, fontWeight = FontWeight.Medium)
                                Text(uiState.timeEntryCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(strings.locJournalEntries, fontWeight = FontWeight.Medium)
                                Text(uiState.journeyEntryCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(strings.locNotes, fontWeight = FontWeight.Medium)
                                Text(uiState.noteCount.toString())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(strings.locSchemaVersion, fontWeight = FontWeight.Medium)
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
                        // Guards the worst outcome of this branch: a tap mid-import runs
                        // continueWithExistingDatabase() -> onDatabaseReady -> MainActivity.restartProcess(),
                        // killing the process before the import's own failure/restore path can run.
                        enabled = !uiState.isCreating && !uiState.isImporting,
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.locContinueWithExistingDatabase)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ImportDatabaseFileButton(
                        icon = Icons.Default.CloudUpload,
                        label = strings.locImportDatabaseFile,
                        logContext = "existing",
                        enabled = !uiState.isCreating && !importInFlight,
                        showProgress = importInFlight,
                        onClick = { onImportFileSelected(IMPORT_PICKER_MIME) },
                    )
                }

                else -> {
                    if (!hasFinishedOnboarding) {
                        OnboardingIntroScreen(
                            language = language,
                            onFinished = {
                                hasFinishedOnboarding = true
                            },
                        )
                    } else {
                        Text(
                            text = strings.locWelcomeToPayanam,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.locNoDatabaseFoundChooseStart,
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
                            Text(strings.locCreateNewEmptyDatabase)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        ImportDatabaseFileButton(
                            icon = Icons.Default.CloudUpload,
                            label = strings.locImportDatabaseFile,
                            logContext = "no existing",
                            enabled = !uiState.isCreating && !importInFlight,
                            showProgress = importInFlight,
                            onClick = { onImportFileSelected(IMPORT_PICKER_MIME) },
                        )
                    }
                }
            }
            if (!uiState.awaitingDimensionSetup && (hasFinishedOnboarding || uiState.databaseExists || uiState.bootIssue != null || showCreatePassphraseForm || uiState.awaitingImportPassphrase || uiState.isChecking)) {
                Spacer(modifier = Modifier.height(16.dp))
                debugLogExport?.invoke()
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

/** The shortest passphrase [validatePassphrase] will accept, in characters. */
private const val PASSPHRASE_MIN_LENGTH = 12

/**
 * Validates a candidate database passphrase: requires at least
 * [PASSPHRASE_MIN_LENGTH] characters and at least one uppercase, lowercase, digit, and symbol,
 * returning the first failing rule as [PassphraseValidation.reasonCode].
 *
 * Inlined here rather than imported from `:core:database` because that module
 * is an Android library and this one is KMP - the validation itself is pure
 * Kotlin with no platform meaning.
 */
private fun validatePassphrase(passphrase: String): PassphraseValidation {
    if (passphrase.length < PASSPHRASE_MIN_LENGTH) {
        return PassphraseValidation(
            isValid = false,
            reasonCode = "min_length",
        )
    }
    if (!passphrase.any { it.isUpperCase() }) {
        return PassphraseValidation(
            isValid = false,
            reasonCode = "missing_uppercase",
        )
    }
    if (!passphrase.any { it.isLowerCase() }) {
        return PassphraseValidation(
            isValid = false,
            reasonCode = "missing_lowercase",
        )
    }
    if (!passphrase.any { it.isDigit() }) {
        return PassphraseValidation(
            isValid = false,
            reasonCode = "missing_digit",
        )
    }
    if (!passphrase.any { !it.isLetterOrDigit() }) {
        return PassphraseValidation(
            isValid = false,
            reasonCode = "missing_symbol",
        )
    }
    return PassphraseValidation(isValid = true, reasonCode = null)
}

/**
 * Result of [validatePassphrase]: whether the candidate passphrase is
 * acceptable, and when rejected the [reasonCode] naming the first failed rule
 * (e.g. `min_length`, `missing_symbol`).
 */
private data class PassphraseValidation(
    val isValid: Boolean,
    val reasonCode: String?,
)
