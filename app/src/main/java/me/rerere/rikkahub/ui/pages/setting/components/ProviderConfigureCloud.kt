package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.data.datastore.ProviderDescriptors
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.IOException


@Composable
internal fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit,
) {
    ProviderDescriptors.get(provider.id).description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    // 统一改为"引用 / 粘贴即入库"输入：默认不在此处留存明文，
    // 引用（$$名字）由运行时解析层取真值
    SecretRefField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
        label = stringResource(R.string.setting_provider_page_api_key),
        nameHint = provider.name,
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_caching))
        Switch(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
        )
    }

    if (provider.promptCaching) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ClaudePromptCacheTtl.entries.forEachIndexed { index, ttl ->
                SegmentedButton(
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ClaudePromptCacheTtl.entries.size,
                        ),
                    label = {
                        Text(
                            when (ttl) {
                                ClaudePromptCacheTtl.FIVE_MINUTES -> {
                                    stringResource(
                                        R.string.setting_provider_page_claude_prompt_cache_ttl_5m,
                                    )
                                }

                                ClaudePromptCacheTtl.ONE_HOUR -> {
                                    stringResource(
                                        R.string.setting_provider_page_claude_prompt_cache_ttl_1h,
                                    )
                                }
                            },
                        )
                    },
                    selected = provider.promptCacheTtl == ttl,
                    onClick = { onEdit(provider.copy(promptCacheTtl = ttl)) },
                )
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
                // openInputStream/readText 仅声明 IOException（FileNotFoundException 为其子类）
                toaster.show(toastImportFailed + e.message, type = ToastType.Error)
            } catch (e: IllegalArgumentException) {
                // Json.parseToJsonElement 抛 SerializationException、jsonObject/jsonPrimitive 访问器抛
                // IllegalArgumentException，前者是后者子类，此 catch 全覆盖
                toaster.show(toastImportFailed + e.message, type = ToastType.Error)
            }
        }

    ProviderDescriptors.get(provider.id).description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (!(provider.vertexAI && provider.useServiceAccount)) {
        // 统一改为"引用 / 粘贴即入库"输入（同上）
        SecretRefField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
            label = stringResource(R.string.setting_provider_page_api_key),
            nameHint = provider.name,
        )
    }

    if (!provider.vertexAI) {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
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
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.setting_provider_page_vertex_ai))
        Switch(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
        )
    }

    if (!provider.vertexAI) {
        val interactionsLabel = stringResource(R.string.setting_provider_page_google_interactions_api)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = provider.useInteractionsApi,
                    role = Role.Switch,
                    onValueChange = { onEdit(provider.copy(useInteractionsApi = it)) },
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(interactionsLabel)
            Switch(
                checked = provider.useInteractionsApi,
                onCheckedChange = null,
            )
        }
    }

    if (provider.vertexAI) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.setting_provider_page_use_service_account))
            Switch(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
            )
        }
    }

    if (provider.vertexAI && provider.useServiceAccount) {
        OutlinedButton(
            onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setting_provider_page_import_service_account_json))
        }

        OutlinedTextField(
            value = provider.serviceAccountEmail,
            onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_service_account_email)) },
            modifier = Modifier.fillMaxWidth(),
        )

        // 统一为"引用 / 粘贴即入库"输入：私钥可整体存入凭证库，此处只留 `$$名字`
        SecretRefField(
            value = provider.privateKey,
            onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
            label = stringResource(R.string.setting_provider_page_private_key),
            nameHint = "VERTEX_SERVICE_ACCOUNT_KEY",
            maxLines = 6,
        )

        OutlinedTextField(
            value = provider.location,
            onValueChange = { onEdit(provider.copy(location = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_location)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = provider.projectId,
            onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_project_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
