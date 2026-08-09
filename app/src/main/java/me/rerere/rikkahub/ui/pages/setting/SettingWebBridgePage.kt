package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Web 桥三级页（反向隧道到 ECS）。
 *
 * 全局配置：ECS 主机/用户名/SSH 端口/远程隧道端口。
 * 供 Reasonix 等 provider 选择「使用全局 Web 桥配置」复用（后续接入其他后端）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWebBridgePage() {
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var webBridgeEcsHost by remember(settings.webBridgeEcsHost) {
        mutableStateOf(settings.webBridgeEcsHost)
    }
    var webBridgeEcsUser by remember(settings.webBridgeEcsUser) {
        mutableStateOf(settings.webBridgeEcsUser)
    }
    var webBridgeSshPort by remember(settings.webBridgeEcsPort) {
        mutableStateOf(settings.webBridgeEcsPort.toString())
    }
    var webBridgeRemotePort by remember(settings.webBridgeRemotePort) {
        mutableStateOf(settings.webBridgeRemotePort.toString())
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_web_bridge_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_web_bridge_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_desc)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_desc_detail)) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_ecs_host)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeEcsHost,
                                onValueChange = {
                                    webBridgeEcsHost = it
                                    scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsHost = it.trim()) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(160.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_ecs_user)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeEcsUser,
                                onValueChange = {
                                    webBridgeEcsUser = it
                                    scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsUser = it.trim()) } }
                                },
                                singleLine = true,
                                modifier = Modifier.width(120.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_ssh_port)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeSshPort,
                                onValueChange = {
                                    webBridgeSshPort = it
                                    it.toIntOrNull()?.let { port ->
                                        scope.launch { settingsStore.update { s -> s.copy(webBridgeEcsPort = port) } }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_web_bridge_remote_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_web_bridge_remote_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = webBridgeRemotePort,
                                onValueChange = {
                                    webBridgeRemotePort = it
                                    it.toIntOrNull()?.let { port ->
                                        scope.launch { settingsStore.update { s -> s.copy(webBridgeRemotePort = port) } }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp),
                                shape = CircleShape,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                    ),
                            )
                        },
                    )
                }
            }
        }
    }
}
