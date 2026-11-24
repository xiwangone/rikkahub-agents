package me.rerere.rikkahub.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.QuarantinedField
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.CrashHandler
import org.koin.android.ext.android.inject
import java.io.File
import kotlin.uuid.Uuid

class SafeModeActivity : ComponentActivity() {
    private val settingsStore by inject<SettingsStore>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = CrashHandler.getStackTrace(this)
        CrashHandler.clearCrashed(this)
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
                val largeFields by settingsStore.largeStoredFieldsFlow()
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val quarantinedFields by settingsStore.quarantinedFields
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val quarantineSnapshot by settingsStore.quarantineSnapshot
                    .collectAsStateWithLifecycle(initialValue = null)
                var showAssistantPicker by remember { mutableStateOf(false) }
                var fieldToClear by remember { mutableStateOf<SettingsStore.LargeStoredField?>(null) }
                var fieldToClearQuarantined by remember { mutableStateOf<QuarantinedField?>(null) }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.safe_mode_title)) })
                    },
                ) { innerPadding ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.safe_mode_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text =
                                stringResource(
                                    R.string.safe_mode_current_assistant,
                                    settings.getCurrentAssistant().name.ifEmpty {
                                        stringResource(
                                            R.string.safe_mode_default_assistant,
                                        )
                                    },
                                ),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        Button(
                            onClick = {
                                startActivity(Intent(this@SafeModeActivity, RouteActivity::class.java))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.safe_mode_enter_app))
                        }

                        OutlinedButton(
                            onClick = { showAssistantPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.safe_mode_switch_assistant))
                        }

                        if (largeFields.isNotEmpty()) {
                            OversizedFieldsSection(
                                largeFields = largeFields,
                                onClearRequest = { fieldToClear = it },
                            )
                        }

                        if (quarantinedFields.isNotEmpty()) {
                            QuarantinedFieldsSection(
                                quarantinedFields = quarantinedFields,
                                snapshotFile = quarantineSnapshot,
                                onClearRequest = { fieldToClearQuarantined = it },
                                onExportSnapshot = { file -> shareSnapshot(context, file) },
                            )
                        }

                        if (stackTrace != null) {
                            CrashReportSection(stackTrace = stackTrace)
                        }
                    }
                }

                if (showAssistantPicker) {
                    AssistantPickerSheet(
                        settings = settings,
                        onAssistantSelected = { assistantId ->
                            scope.launch { settingsStore.updateAssistant(assistantId) }
                            context.writeStringPreference("lastConversationId", null)
                            showAssistantPicker = false
                        },
                        onDismiss = { showAssistantPicker = false },
                    )
                }

                fieldToClear?.let { field ->
                    ClearFieldConfirmDialog(
                        fieldLabel = storedFieldLabel(field.keyName),
                        textRes = R.string.safe_mode_clear_text,
                        onConfirm = { scope.launch { settingsStore.resetStoredField(field.keyName) } },
                        onDismiss = { fieldToClear = null },
                    )
                }

                fieldToClearQuarantined?.let { field ->
                    ClearFieldConfirmDialog(
                        fieldLabel = storedFieldLabel(field.keyName),
                        textRes = R.string.safe_mode_quarantined_clear_text,
                        onConfirm = {
                            scope.launch {
                                settingsStore.resetStoredField(field.keyName)
                                settingsStore.clearQuarantinedField(field.keyName)
                            }
                        },
                        onDismiss = { fieldToClearQuarantined = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CrashReportSection(stackTrace: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.safe_mode_crash_report),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", stackTrace))
            },
        ) {
            Text(stringResource(R.string.safe_mode_copy))
        }
    }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        Text(
            text = stackTrace,
            modifier =
                Modifier
                    .padding(12.dp)
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun OversizedFieldsSection(
    largeFields: List<SettingsStore.LargeStoredField>,
    onClearRequest: (SettingsStore.LargeStoredField) -> Unit,
) {
    CardGroup(
        modifier = Modifier.fillMaxWidth(),
        title = { Text(stringResource(R.string.safe_mode_oversized_data)) },
    ) {
        largeFields.forEach { field ->
            item(
                onClick = { onClearRequest(field) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.safe_mode_oversized_size,
                            field.charCount / 1_000_000.0,
                        ),
                    )
                },
                trailingContent = {
                    Text(
                        stringResource(R.string.safe_mode_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            ) {
                Text(storedFieldLabel(field.keyName))
            }
        }
    }
}

@Composable
private fun QuarantinedFieldsSection(
    quarantinedFields: List<QuarantinedField>,
    snapshotFile: File?,
    onClearRequest: (QuarantinedField) -> Unit,
    onExportSnapshot: (File) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.safe_mode_quarantined_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.safe_mode_quarantined_title)) },
        ) {
            quarantinedFields.forEach { field ->
                item(
                    onClick = { onClearRequest(field) },
                    supportingContent = {
                        Text(stringResource(R.string.safe_mode_quarantined_default))
                    },
                    trailingContent = {
                        Text(
                            stringResource(R.string.safe_mode_clear),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                ) {
                    Text(storedFieldLabel(field.keyName))
                }
            }
            if (snapshotFile != null) {
                item(
                    onClick = { onExportSnapshot(snapshotFile) },
                    supportingContent = {
                        Text(snapshotFile.name)
                    },
                ) {
                    Text(stringResource(R.string.safe_mode_quarantined_export))
                }
            }
        }
    }
}

private fun shareSnapshot(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
private fun ClearFieldConfirmDialog(
    fieldLabel: String,
    textRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.safe_mode_clear_title, fieldLabel))
        },
        text = { Text(stringResource(textRes)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(
                    stringResource(R.string.safe_mode_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun storedFieldLabel(keyName: String): String =
    when (keyName) {
        SettingsStore.LOREBOOKS.name -> stringResource(R.string.prompt_page_lorebook_tab)
        SettingsStore.ASSISTANTS.name -> stringResource(R.string.safe_mode_assistants)
        else -> keyName
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    onAssistantSelected: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val filteredAssistants =
        remember(settings.assistants, selectedTagIds) {
            if (selectedTagIds.isEmpty()) {
                settings.assistants
            } else {
                settings.assistants.filter { it.tags.any { id -> id in selectedTagIds } }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.safe_mode_assistants),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(settings.assistantTags, key = { it.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds =
                                    if (tag.id in selectedTagIds) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == settings.assistantId
                    Card(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onAssistantSelected(assistant.id)
                            }
                        },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (checked) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                contentColor =
                                    if (checked) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            ),
                    ) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.safe_mode_default_assistant) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
