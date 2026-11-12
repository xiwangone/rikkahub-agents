package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.permission.PermissionLocalNetwork
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.web.WebServerManager
import org.koin.compose.koinInject
import me.rerere.rikkahub.ui.components.setting.NetworkAccessField
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.data.vault.CredentialType

/**
 * 清空密码是否需要先确认：只有“新值为空且身份验证当前开着”时才需要——
 * 此时保存会联动关掉身份验证，不能悄悄做。
 */
internal fun passwordClearNeedsConfirm(newValue: String, jwtEnabled: Boolean): Boolean =
    newValue.isBlank() && jwtEnabled

@Composable
fun SettingWebServerPage() {
    val webServerManager: WebServerManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    @Suppress("DEPRECATION") // LocalClipboard requires a suspend-friendly callsite refactor
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    var portText by remember(settings.webServerPort) {
        mutableStateOf(settings.webServerPort.toString())
    }
    var accessPasswordText by remember(settings.webServerAccessPassword) {
        mutableStateOf(settings.webServerAccessPassword)
    }
    var pendingPasswordClear by remember { mutableStateOf(false) }

    val permissionState =
        rememberPermissionState(
            permissions =
                buildSet {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(PermissionNotification)
                    }
                    // Android 17 gates LAN sockets behind ACCESS_LOCAL_NETWORK; only
                    // request when the user has chosen non-localhost mode.
                    if (Build.VERSION.SDK_INT >= 37 && !settings.webServerLocalhostOnly) {
                        add(PermissionLocalNetwork)
                    }
                },
        )
    PermissionManager(permissionState = permissionState)

    var pendingStart by remember { mutableStateOf(false) }

    fun startWebServer() {
        val intent =
            Intent(context, WebServerService::class.java).apply {
                action = WebServerService.ACTION_START
                putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                putExtra(
                    WebServerService.EXTRA_LOCALHOST_ONLY,
                    !settings.webServerListenScope.equals("lan", ignoreCase = true) &&
                        !settings.webServerListenScope.equals("any", ignoreCase = true),
                )
            }
        context.startForegroundService(intent)
        scope.launch {
            settingsStore.update { it.copy(webServerEnabled = true) }
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (pendingStart && permissionState.allPermissionsGranted) {

            startWebServer()
        }
    }

    fun copyUrl(url: String) {
        clipboardManager.setText(AnnotatedString(url))
        toaster.show(copiedText)
    }

    val jwtNeedsPasswordText = stringResource(R.string.setting_page_web_server_jwt_needs_password)

    // 开关保持可点：没密码时点开只提示、不生效；关闭随时可关。
    fun trySetJwt(checked: Boolean) {
        if (checked && accessPasswordText.isBlank()) {
            toaster.show(jwtNeedsPasswordText)
            return
        }
        scope.launch {
            settingsStore.update { it.copy(webServerJwtEnabled = checked) }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_web_server)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.testTag("web_server_toggle"),
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    if (!serverState.isRunning) {
                        if (permissionState.allPermissionsGranted) {
                            startWebServer()
                        } else {
                            pendingStart = true
                            permissionState.requestPermissions()
                        }
                    } else {
                        val intent =
                            Intent(context, WebServerService::class.java).apply {
                                action = WebServerService.ACTION_STOP
                            }
                        context.startService(intent)
                        scope.launch {
                            settingsStore.update { it.copy(webServerEnabled = false) }
                        }
                    }
                },
                icon = {
                    if (serverState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (serverState.isRunning) HugeIcons.Stop else HugeIcons.Play,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(
                        if (serverState.isRunning) {
                            stringResource(R.string.setting_page_web_server_stop)
                        } else {
                            stringResource(R.string.setting_page_web_server_start)
                        },
                    )
                },
                containerColor =
                    if (serverState.isRunning) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {

                CardGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_web_server)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = portText,
                                onValueChange = { value ->
                                    portText = value.filter { it.isDigit() }
                                    val port = portText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        scope.launch {
                                            settingsStore.update { it.copy(webServerPort = port) }
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                                modifier = Modifier.width(100.dp),
                                enabled = !serverState.isRunning,
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
                        headlineContent = { Text(stringResource(R.string.web_server_listen_scope)) },
                        supportingContent = {
                            val currentScope =
                                settings.webServerListenScope.lowercase().takeIf { it in listOf("loopback", "lan", "any") } ?: "loopback"
                            Column {
                                Text(stringResource(R.string.web_server_listen_scope_desc))
                                listOf(
                                    "loopback" to stringResource(R.string.net_scope_loopback),
                                    "lan" to stringResource(R.string.net_scope_lan),
                                    "any" to stringResource(R.string.net_scope_any),
                                ).forEach { (value, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            modifier = Modifier.testTag("web_server_scope_$value"),
                                            selected = currentScope == value,
                                            onClick = {
                                                scope.launch {
                                                    settingsStore.update {
                                                        it.copy(
                                                            webServerListenScope = value,
                                                            webServerLocalhostOnly = value == "loopback",
                                                        )
                                                    }
                                                }
                                            },
                                            enabled = !serverState.isRunning,
                                        )
                                        Text(label, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                NetworkAccessField(
                                    value = settings.webServerAllowedNetworks,
                                    onValueChange = { value ->
                                        scope.launch { settingsStore.update { it.copy(webServerAllowedNetworks = value) } }
                                    },
                                    presets = settings.networkCidrPresets,
                                    onPresetsChange = { presets ->
                                        scope.launch { settingsStore.update { it.copy(networkCidrPresets = presets) } }
                                    },
                                    label = stringResource(R.string.net_allowed_networks),
                                    supportingText = stringResource(R.string.net_allowed_networks_desc),
                                    enabled = !serverState.isRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        onClick = { trySetJwt(!settings.webServerJwtEnabled) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable_desc)) },
                        trailingContent = {
                            Switch(
                                modifier = Modifier.testTag("web_server_jwt_switch"),
                                checked = settings.webServerJwtEnabled,
                                onCheckedChange = { checked -> trySetJwt(checked) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_password)) },
                        supportingContent = {
                            SecretRefField(
                                value = accessPasswordText,
                                onValueChange = { value ->
                                    accessPasswordText = value
                                    if (passwordClearNeedsConfirm(value, settings.webServerJwtEnabled)) {
                                        // 清空密码会联动关掉身份验证：先弹确认，不再悄悄关。
                                        pendingPasswordClear = true
                                    } else {
                                        scope.launch {
                                            settingsStore.update {
                                                it.copy(
                                                    webServerAccessPassword = value,
                                                    webServerJwtEnabled = it.webServerJwtEnabled && value.isNotBlank(),
                                                )
                                            }
                                        }
                                    }
                                },
                                description = stringResource(R.string.setting_page_web_server_password_desc),
                                nameHint = "WEB_SERVER_PASSWORD",
                                typeHint = CredentialType.BASIC_AUTH,
                                singleLine = true,
                            )
                        },
                    )
                    if (serverState.isRunning) {
                        val port = serverState.port
                        if (!serverState.localhostOnly) {
                            val lanUrl = "http://${serverState.address ?: "localhost"}:$port"
                            item(
                                onClick = { copyUrl(lanUrl) },
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.setting_page_web_server_lan_address),
                                    )
                                },
                                supportingContent = { Text(lanUrl) },
                            )

                            if (serverState.hostname != null) {
                                val mdnsUrl = "http://${serverState.hostname}:$port"
                                item(
                                    onClick = { copyUrl(mdnsUrl) },
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.setting_page_web_server_mdns_address),
                                        )
                                    },
                                    supportingContent = { Text(mdnsUrl) },
                                )
                            }
                        }

                        val localUrl = "http://localhost:$port"
                        item(
                            onClick = { copyUrl(localUrl) },
                            headlineContent = { Text(stringResource(R.string.setting_page_web_server_local_address)) },
                            supportingContent = { Text(localUrl) },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (serverState.error != null) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_web_server_error),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = serverState.error ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingPasswordClear,
        title = stringResource(R.string.setting_page_web_server_clear_password_title),
        confirmText = stringResource(R.string.setting_page_web_server_clear_password_confirm),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            pendingPasswordClear = false
            scope.launch {
                settingsStore.update { it.copy(webServerAccessPassword = "", webServerJwtEnabled = false) }
            }
        },
        onDismiss = {
            pendingPasswordClear = false
            // 取消则恢复已保存的密码，输入框回到清空前的样子。
            accessPasswordText = settings.webServerAccessPassword
        },
    ) {
        Text(stringResource(R.string.setting_page_web_server_clear_password_message))
    }
}
