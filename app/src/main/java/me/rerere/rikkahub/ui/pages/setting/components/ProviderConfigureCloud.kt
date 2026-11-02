package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ProviderDescriptors
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.IOException

@Composable
internal fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit,
) {
    ProviderDescriptors.get(provider.id).description()

    CardGroup {
        switchItem(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
        )
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_name)) },
        ) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        }
        formItem {
            SecretRefField(
                value = provider.apiKey,
                onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
                label = stringResource(R.string.setting_provider_page_api_key),
                nameHint = provider.name,
            )
        }
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        ) {
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
                trailingIcon =
                    resetBaseUrlButton(provider) {
                        onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
                    },
            )
        }
    }

    CardGroup {
        switchItem(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_caching)) },
        )
        if (provider.promptCaching) {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl)) },
            ) {
                ConnectedChoiceButtons(
                    options = ClaudePromptCacheTtl.entries,
                    selected = provider.promptCacheTtl,
                    onSelect = { onEdit(provider.copy(promptCacheTtl = it)) },
                ) { ttl, selected ->
                    ConnectedChoiceLabel(
                        text =
                            when (ttl) {
                                ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                                ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                            },
                        selected = selected,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val toastServiceImported = stringResource(R.string.provider_import_service_account_success)
    val toastImportFailed = stringResource(R.string.provider_import_gcloud_failed)
    val serviceAccountJsonLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            try {
                val content =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText()
                        ?: return@rememberLauncherForActivityResult
                val json = Json.parseToJsonElement(content).jsonObject
                onEdit(
                    provider.copy(
                        projectId =
                            json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty {
                                null
                            } ?: provider.projectId,
                        serviceAccountEmail =
                            json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                                ?: provider.serviceAccountEmail,
                        privateKey =
                            json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                    ),
                )
                toaster.show(toastServiceImported, type = ToastType.Success)
            } catch (e: IOException) {
                toaster.show(toastImportFailed + e.message, type = ToastType.Error)
            } catch (e: IllegalArgumentException) {
                toaster.show(toastImportFailed + e.message, type = ToastType.Error)
            }
        }

    val useServiceAccount = provider.vertexAI && provider.useServiceAccount

    ProviderDescriptors.get(provider.id).description()

    CardGroup {
        switchItem(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
        )
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_name)) },
        ) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        if (!useServiceAccount) {
            formItem {
                SecretRefField(
                    value = provider.apiKey,
                    onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
                    label = stringResource(R.string.setting_provider_page_api_key),
                    nameHint = provider.name,
                )
            }
        }
        if (!provider.vertexAI) {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            ) {
                OutlinedTextField(
                    value = provider.baseUrl,
                    onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    isError =
                        provider.baseUrl.isNotBlank() && (
                            !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                        ),
                    supportingText =
                        if (!provider.baseUrl.endsWith("/v1beta")) {
                            { Text(stringResource(R.string.setting_provider_page_base_url_v1beta_hint)) }
                        } else {
                            null
                        },
                    trailingIcon =
                        resetBaseUrlButton(provider) {
                            onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
                        },
                )
            }
        }
    }

    CardGroup {
        switchItem(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_vertex_ai)) },
        )
        if (provider.vertexAI) {
            switchItem(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_use_service_account)) },
            )
        } else {
            switchItem(
                checked = provider.useInteractionsApi,
                onCheckedChange = { onEdit(provider.copy(useInteractionsApi = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_google_interactions_api)) },
            )
        }
    }

    if (useServiceAccount) {
        CardGroup {
            item(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_import_service_account_json)) },
                trailingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
            )
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_service_account_email)) },
            ) {
                OutlinedTextField(
                    value = provider.serviceAccountEmail,
                    onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem {
                SecretRefField(
                    value = provider.privateKey,
                    onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
                    label = stringResource(R.string.setting_provider_page_private_key),
                    nameHint = "VERTEX_SERVICE_ACCOUNT_KEY",
                    maxLines = 6,
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_location)) },
            ) {
                OutlinedTextField(
                    value = provider.location,
                    onValueChange = { onEdit(provider.copy(location = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_project_id)) },
            ) {
                OutlinedTextField(
                    value = provider.projectId,
                    onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
