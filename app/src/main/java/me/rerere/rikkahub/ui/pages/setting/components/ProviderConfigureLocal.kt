package me.rerere.rikkahub.ui.pages.setting.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.ModelInstall
import me.rerere.locallm.litert.LiteRtCatalog
import me.rerere.locallm.litert.LiteRtCatalogEntry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ProviderDescriptors
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.locallm.SettingLocalLlmViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.FileOutputStream


@Composable
internal fun ColumnScope.ProviderConfigureAICore(
    provider: ProviderSetting.AICore,
    onEdit: (provider: ProviderSetting.AICore) -> Unit,
) {
    ProviderDescriptors.get(provider.id).description()

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(id = R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    // Release-stage radio. PREVIEW pulls a higher-quality but more flappy build of Gemini
    // Nano; STABLE is the default. Spec details in
    // docs/superpowers/specs/2026-05-04-aicore-provider-design.md.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.setting_provider_aicore_release_stage), modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            val stages = me.rerere.ai.provider.AICoreReleaseStage.entries
            stages.forEachIndexed { index, stage ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = stages.size),
                    label = { Text(stage.name) },
                    selected = provider.releaseStage == stage,
                    onClick = { onEdit(provider.copy(releaseStage = stage)) },
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.setting_provider_aicore_status_help),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ColumnScope.ProviderConfigureLiteRT(
    provider: ProviderSetting.LiteRtLocal,
    onEdit: (ProviderSetting.LiteRtLocal) -> Unit,
) {
    val vm =
        koinViewModel<SettingLocalLlmViewModel>(
            key = "configure-${LocalRuntime.LiteRT.displayName}",
            parameters = { parametersOf(LocalRuntime.LiteRT) },
        )
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()
    val accelerator by vm.accelerator.collectAsStateWithLifecycle()
    val forceCpu by vm.forceCpu.collectAsStateWithLifecycle()
    val maxNumTokensOverride by vm.maxNumTokensOverride.collectAsStateWithLifecycle()
    val crashRecoveryAccel by vm.crashRecoveryAccelerator.collectAsStateWithLifecycle()
    val installedModelFiles by vm.installedModelFiles.collectAsStateWithLifecycle()
    val visionUnavailableSet by vm.visionUnavailableSet.collectAsStateWithLifecycle()
    val perfTelemetry by vm.perfTelemetry.collectAsStateWithLifecycle()

    // 从本地导入 .litertlm 模型文件：验证 magic 后复制进模型目录并注册。
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val importedToastFmt = stringResource(R.string.toast_provider_imported)
    val invalidModelToast = stringResource(R.string.toast_provider_invalid_model_file)
    val importFailedToastFmt = stringResource(R.string.toast_provider_import_failed)
    val importLocalLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val fileName = queryOpenDocumentName(context, uri) ?: "imported.litertlm"
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    val baseDir = ModelInstall.localModelsDir(context)
                    val target = ModelInstall.targetFile(baseDir, LocalRuntime.LiteRT, fileName)
                    val imported =
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val buf = ByteArray(64)
                            val n = input.read(buf)
                            if (n <= 0 || !ModelInstall.isValidMagicForExtension(ext, buf.copyOf(n))) {
                                false
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output -> input.copyTo(output) }
                                true
                            }
                        } ?: false
                    Triple(imported, fileName, target)
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { (imported, fileName, target) ->
                            if (imported) {
                                vm.registerImportedModel(fileName, target.absolutePath)
                                toaster.show(
                                    importedToastFmt.format(fileName),
                                    type = ToastType.Success,
                                )
                            } else {
                                toaster.show(
                                    invalidModelToast,
                                    type = ToastType.Error,
                                )
                            }
                        },
                        onFailure = { e ->
                            toaster.show(
                                importFailedToastFmt.format(e.message.orEmpty()),
                                type = ToastType.Error,
                            )
                        },
                    )
                }
            }
        }

    ProviderDescriptors.get(provider.id).description()

    // Friendly post-crash banner. Default tone is "we handled it", not "panic".
    crashRecoveryAccel?.let { accel ->
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.dismissCrashRecovery() },
        ) {
            Text(
                text = stringResource(R.string.local_llm_crash_recovery_format, accel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(id = R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    // Installed model count — model management is on the Models tab (page 1).
    Text(
        text = stringResource(R.string.local_llm_installed_models_count, provider.models.size),
        style = MaterialTheme.typography.bodySmall,
    )

    // URL install field — paste an HF URL, hit Install.
    var manualUrl by remember { mutableStateOf("") }
    OutlinedTextField(
        value = manualUrl,
        onValueChange = { manualUrl = it },
        label = { Text(stringResource(R.string.local_llm_install_url_label)) },
        supportingText = { Text(stringResource(R.string.local_llm_install_url_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                vm.startManualDownload(manualUrl)
                manualUrl = ""
            },
            enabled = manualUrl.isNotBlank() && downloadProgress == null,
        ) {
            Text(stringResource(R.string.local_llm_install_url_action))
        }
        OutlinedButton(
            onClick = { vm.startDefaultDownload() },
            enabled = downloadProgress == null,
        ) {
            Text(stringResource(R.string.local_llm_download_default))
        }
    }
    OutlinedButton(
        onClick = { importLocalLauncher.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.setting_litert_import_local))
    }

    // Manage installed files — rename or delete each downloaded .litertlm.
    if (provider.models.isNotEmpty()) {
        Text(
            stringResource(R.string.local_llm_manage_files_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        provider.models.forEach { model ->
            InstalledModelRow(
                model = model,
                visionUnavailable = model.modelId in visionUnavailableSet,
                // After a native crash inside liblitertlm we suppress the Re-try
                // vision button: re-trying immediately is what just crashed the app.
                // The user dismisses the crash banner (above) when they want to opt
                // back in to taking that risk; the button reappears.
                allowVisionRetry = crashRecoveryAccel == null,
                perfSample = perfTelemetry[model.modelId],
                onRename = { newName -> vm.renameModel(model.modelId, newName) },
                onDelete = { vm.deleteModel(model.modelId) },
                onRetryVision = { vm.retryVisionEncoder(model.modelId) },
            )
        }
    }

    // Recommended-models curated picker. Sourced from Google AI Edge Gallery's allowlist
    // (LiteRtCatalog.ENTRIES). Per-entry Install button calls the same startManualDownload
    // path the URL-paste field uses, so the install flow is identical.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.local_llm_catalog_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.local_llm_catalog_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LiteRtCatalog.ENTRIES.forEach { entry ->
            LiteRtCatalogEntryCard(
                entry = entry,
                installed = entry.modelFile in installedModelFiles,
                downloadInProgress = downloadProgress != null,
                onInstall = { vm.startManualDownload(entry.resolveUrl()) },
            )
        }
    }

    // Accelerator row with re-detect button.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.local_llm_accelerator_label, accelerator ?: "auto"),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { vm.reDetectAccelerator() }) {
            Text(stringResource(R.string.local_llm_re_detect))
        }
    }

    // GPU acceleration toggle. The default is now device-dependent (see
    // LocalRuntimePreferences.defaultForceCpu): ON for capable devices, OFF only for the
    // Google Tensor crash class where LiteRT-LM 0.11.0's GPU/NNAPI backend SIGSEGVs during
    // inference. The toggle still lets the user override either way; the crash sweep and
    // the runtime's GPU->CPU fallback backstop a wrong default.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.local_llm_try_gpu_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.local_llm_try_gpu_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = !forceCpu,
            onCheckedChange = { wantGpu -> vm.setForceCpu(!wantGpu) },
        )
    }

    // Max-context override. Lets users push capable models (Gemma 4 E2B = 32k) past
    // Gallery's curated defaults — the model's underlying KV cache size is still the
    // hard ceiling (Qwen `ekv4096` rejects values above 4096 regardless of this).
    var maxTokensInput by remember(maxNumTokensOverride) {
        mutableStateOf(maxNumTokensOverride?.toString() ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.local_llm_max_tokens_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.local_llm_max_tokens_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxTokensInput,
                onValueChange = { newValue ->
                    // Accept digits-only input; empty string = use curated default.
                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                        maxTokensInput = newValue
                        val parsed = newValue.toIntOrNull()?.takeIf { it in 1..131072 }
                        vm.setMaxNumTokensOverride(parsed)
                    }
                },
                placeholder = {
                    Text(stringResource(R.string.local_llm_max_tokens_placeholder))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    maxTokensInput = ""
                    vm.setMaxNumTokensOverride(null)
                },
                enabled = maxNumTokensOverride != null,
            ) {
                Text(stringResource(R.string.local_llm_max_tokens_reset))
            }
        }
    }

    // Download progress indicator.
    downloadProgress?.let { progress ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (progress.totalBytes != null && progress.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(R.string.local_llm_download_progress, progress.percent),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    // Error text + optional "Delete model" action when a model file is the likely culprit.
    errorMessage?.let { msg ->
        Text(
            text = stringResource(R.string.local_llm_status_error_format, msg),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        // If there are installed models, offer to delete them so the user can clear a
        // broken file (e.g. wrong runtime version) without navigating away.
        if (provider.models.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                provider.models.forEach { model ->
                    OutlinedButton(onClick = { vm.deleteModel(model.modelId) }) {
                        Text(
                            text =
                                stringResource(R.string.local_llm_delete_model) +
                                    if (provider.models.size > 1) " ${model.modelId}" else "",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiteRtCatalogEntryCard(
    entry: LiteRtCatalogEntry,
    installed: Boolean,
    downloadInProgress: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (entry.recommended) {
                    Text(
                        text = stringResource(R.string.local_llm_catalog_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier =
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (entry.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.tags.forEach { tag ->
                        val labelRes =
                            when (tag) {
                                "multimodal" -> R.string.local_llm_catalog_tag_multimodal
                                "thinking" -> R.string.local_llm_catalog_tag_thinking
                                "speculative-decoding" -> R.string.local_llm_catalog_tag_speculative
                                else -> null
                            }
                        val label = labelRes?.let { stringResource(it) } ?: tag
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors =
                                SuggestionChipDefaults.suggestionChipColors(
                                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        )
                    }
                }
            }

            Text(
                text =
                    String.format(
                        java.util.Locale.US,
                        stringResource(R.string.local_llm_catalog_size_format),
                        entry.sizeBytes / 1_000_000_000.0,
                        entry.minDeviceMemoryGb,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (installed) {
                    Text(
                        text = stringResource(R.string.local_llm_catalog_installed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !downloadInProgress,
                    ) {
                        Text(stringResource(R.string.local_llm_catalog_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledModelRow(
    model: Model,
    visionUnavailable: Boolean,
    allowVisionRetry: Boolean,
    perfSample: me.rerere.locallm.LocalRuntimePreferences.PerfSample?,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRetryVision: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember(model.id) { mutableStateOf(model.displayName) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (renaming) {
        OutlinedTextField(
            value = renameText,
            onValueChange = { renameText = it },
            label = { Text(stringResource(R.string.local_llm_rename_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = { renaming = false }) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    onRename(renameText)
                    renaming = false
                },
                enabled = renameText.isNotBlank() && renameText != model.displayName,
            ) {
                Text(stringResource(R.string.local_llm_rename_save))
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    model.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                )
                if (visionUnavailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.local_llm_vision_unavailable_caption),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (allowVisionRetry) {
                            TextButton(
                                onClick = onRetryVision,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    stringResource(R.string.local_llm_vision_retry),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                perfSample?.let { sample ->
                    Text(
                        text =
                            stringResource(
                                R.string.local_llm_perf_telemetry_format,
                                sample.prefillTps,
                                sample.decodeTps,
                            ) +
                                if (sample.specDecodingEngaged) {
                                    " · " +
                                        stringResource(R.string.local_llm_spec_decoding_engaged_short)
                                } else {
                                    ""
                                },
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
            IconButton(onClick = { renaming = true }) {
                Icon(HugeIcons.Edit01, stringResource(R.string.local_llm_rename))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(HugeIcons.Delete01, stringResource(R.string.local_llm_delete))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.local_llm_delete_confirm_title)) },
            text = { Text(stringResource(R.string.local_llm_delete_confirm_message, model.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.local_llm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 从 OpenDocument 返回的 content uri 中解析文件名（DISPLAY_NAME），解析失败返回 null。 */
private fun queryOpenDocumentName(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
