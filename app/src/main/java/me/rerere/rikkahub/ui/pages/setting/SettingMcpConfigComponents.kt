
package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.InputSchema
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.SwitchSize
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import me.rerere.rikkahub.ui.components.vault.SecretRefField


@Composable
internal fun McpCommonOptionsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 启用/禁用开关
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_enable))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_enable_desc))
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.setting_mcp_page_enable))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = config.commonOptions.enable,
                    onCheckedChange = { enabled ->
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> {
                                    config.copy(
                                        commonOptions = config.commonOptions.copy(enable = enabled),
                                    )
                                }

                                is McpServerConfig.StreamableHTTPServer -> {
                                    config.copy(
                                        commonOptions = config.commonOptions.copy(enable = enabled),
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

        HorizontalDivider()

        // 名称输入框
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_name))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_name_desc))
            },
        ) {
            val nameInvalid = !isValidMcpName(config.commonOptions.name)
            OutlinedTextField(
                value = config.commonOptions.name,
                onValueChange = { name ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> {
                                config.copy(
                                    commonOptions = config.commonOptions.copy(name = name),
                                )
                            }

                            is McpServerConfig.StreamableHTTPServer -> {
                                config.copy(
                                    commonOptions = config.commonOptions.copy(name = name),
                                )
                            }
                        },
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_name)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_mcp_page_name_placeholder)) },
                isError = nameInvalid,
                supportingText =
                    if (nameInvalid) {
                        { Text(stringResource(R.string.setting_mcp_page_name_invalid)) }
                    } else {
                        null
                    },
            )
        }

        HorizontalDivider()

        // 传输类型选择
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_transport_type))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_transport_type_desc))
            },
        ) {
            val transportTypes =
                listOf(
                    "Streamable HTTP",
                    "SSE",
                )
            val currentTypeIndex =
                when (config) {
                    is McpServerConfig.StreamableHTTPServer -> 0
                    is McpServerConfig.SseTransportServer -> 1
                }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                transportTypes.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, transportTypes.size),
                        onClick = {
                            if (index != currentTypeIndex) {
                                val newConfig =
                                    when (index) {
                                        0 -> {
                                            McpServerConfig.StreamableHTTPServer(
                                                id = config.id,
                                                commonOptions = config.commonOptions,
                                                url =
                                                    when (config) {
                                                        is McpServerConfig.SseTransportServer -> config.url
                                                        is McpServerConfig.StreamableHTTPServer -> config.url
                                                    },
                                            )
                                        }

                                        1 -> {
                                            McpServerConfig.SseTransportServer(
                                                id = config.id,
                                                commonOptions = config.commonOptions,
                                                url =
                                                    when (config) {
                                                        is McpServerConfig.SseTransportServer -> config.url
                                                        is McpServerConfig.StreamableHTTPServer -> config.url
                                                    },
                                            )
                                        }

                                        else -> {
                                            config
                                        }
                                    }
                                update(newConfig)
                            }
                        },
                        selected = index == currentTypeIndex,
                    ) {
                        Text(type)
                    }
                }
            }
        }

        HorizontalDivider()

        // 服务器地址配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_server_url))
            },
            description = {
                Text(
                    when (config) {
                        is McpServerConfig.SseTransportServer -> {
                            stringResource(R.string.setting_mcp_page_sse_url_desc)
                        }

                        is McpServerConfig.StreamableHTTPServer -> {
                            stringResource(
                                R.string.setting_mcp_page_streamable_http_url_desc,
                            )
                        }
                    },
                )
            },
        ) {
            OutlinedTextField(
                value =
                    when (config) {
                        is McpServerConfig.SseTransportServer -> config.url
                        is McpServerConfig.StreamableHTTPServer -> config.url
                    },
                onValueChange = { url ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(url = url)
                            is McpServerConfig.StreamableHTTPServer -> config.copy(url = url)
                        },
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> {
                                stringResource(
                                    R.string.setting_mcp_page_sse_url_placeholder,
                                )
                            }

                            is McpServerConfig.StreamableHTTPServer -> {
                                stringResource(
                                    R.string.setting_mcp_page_streamable_http_url_placeholder,
                                )
                            }
                        },
                    )
                },
            )
        }

        HorizontalDivider()

        // 请求头配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers_desc))
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                config.commonOptions.headers.forEachIndexed { index, header ->
                    var headerName by remember(header.first) { mutableStateOf(header.first) }
                    var headerValue by remember(header.second) { mutableStateOf(header.second) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] =
                                        it.trim() to updatedHeaders[index].second
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> {
                                                config.copy(
                                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                                )
                                            }

                                            is McpServerConfig.StreamableHTTPServer -> {
                                                config.copy(
                                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                                )
                                            }
                                        },
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        stringResource(R.string.setting_mcp_page_header_name_placeholder),
                                    )
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            SecretRefField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].first to it.trim()
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> {
                                                config.copy(
                                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                                )
                                            }

                                            is McpServerConfig.StreamableHTTPServer -> {
                                                config.copy(
                                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                                )
                                            }
                                        },
                                    )
                                },
                                label = stringResource(R.string.setting_mcp_page_header_value),
                                modifier = Modifier.fillMaxWidth(),
                                nameHint = header.first.ifBlank { "MCP_HEADER_${index + 1}" },
                                singleLine = true,
                                placeholder = stringResource(R.string.setting_mcp_page_header_value_placeholder),
                            )
                        }
                        IconButton(onClick = {
                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            update(
                                when (config) {
                                    is McpServerConfig.SseTransportServer -> {
                                        config.copy(
                                            commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                        )
                                    }

                                    is McpServerConfig.StreamableHTTPServer -> {
                                        config.copy(
                                            commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                        )
                                    }
                                },
                            )
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.setting_mcp_page_delete_header),
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val updatedHeaders = config.commonOptions.headers.toMutableList()
                        updatedHeaders.add("" to "")
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> {
                                    config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                    )
                                }

                                is McpServerConfig.StreamableHTTPServer -> {
                                    config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders),
                                    )
                                }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = stringResource(R.string.setting_mcp_page_add_header),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.setting_mcp_page_add_header))
                }
            }
        }
    }
}

@Composable
internal fun McpToolsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mcpManager.getClient(config) == null) {
            item {
                Text(stringResource(R.string.setting_mcp_page_tools_unavailable_message))
            }
        }
        items(config.commonOptions.tools) { tool ->
            McpToolCard(
                tool = tool,
                onEnableChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions =
                                config.commonOptions.copy(
                                    tools =
                                        config.commonOptions.tools.map {
                                            if (tool.name == it.name) {
                                                it.copy(enable = newVal)
                                            } else {
                                                it
                                            }
                                        },
                                ),
                        ),
                    )
                },
                onNeedsApprovalChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions =
                                config.commonOptions.copy(
                                    tools =
                                        config.commonOptions.tools.map {
                                            if (tool.name == it.name) {
                                                it.copy(needsApproval = newVal)
                                            } else {
                                                it
                                            }
                                        },
                                ),
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun McpToolCard(
    tool: McpTool,
    onEnableChange: (Boolean) -> Unit,
    onNeedsApprovalChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .animateContentSize()
                    .fillMaxWidth()
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行：工具名字和3个按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 需要审批开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_mcp_page_needs_approval),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = tool.needsApproval,
                        onCheckedChange = onNeedsApprovalChange,
                        size = SwitchSize.Small,
                    )
                }
                // 启用开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_mcp_page_enable),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = tool.enable,
                        onCheckedChange = onEnableChange,
                        size = SwitchSize.Small,
                    )
                }
                // 展开/收起按钮
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 展开后显示描述和参数
            if (expanded) {
                // 描述
                if (!tool.description.isNullOrBlank()) {
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                // 参数标签
                tool.inputSchema?.let { it as? InputSchema.Obj }?.let { schema ->
                    if (schema.properties.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            schema.properties.forEach { (key, _) ->
                                Tag(
                                    type =
                                        if (schema.required?.contains(key) ==
                                            true
                                        ) {
                                            TagType.INFO
                                        } else {
                                            TagType.DEFAULT
                                        },
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

