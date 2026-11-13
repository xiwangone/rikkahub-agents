package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Global
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.BrowserActivity
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.TunnelStartConfig
import me.rerere.rikkahub.data.tunnel.TunnelState
import me.rerere.rikkahub.data.tunnel.resolveTunnelAuthKey
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.vault.SecretRefField
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * 偏好设置 → 网络 → 隧道（Material 3 Expressive 卡片组）。
 *
 * 能力边界：隧道只在 App 进程内做 per-app 代理（覆盖 SSH 与 OkHttp HTTP/HTTPS），不注册 VpnService，
 * 因此系统路由与 DNS 不受影响，其他 App 也不经过它。
 */
@Composable
fun TunnelSettingSection(vm: SettingVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val network = settings.networkSetting
    val context = LocalContext.current
    // 文案在组合期取好：点击回调里调 stringResource 不合法（非 @Composable），
    // 而在组合期通过 LocalContext 取字符串会被 lint 的 LocalContextGetResourceValueCall 判红。
    val copiedLabel = stringResource(R.string.copied)
    val savedLabel = stringResource(R.string.file_editor_saved)
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val vaultRepository: CredentialVaultRepository = koinInject()
    // 隧道认证密钥解析也要过会话授权门（LOCAL_USE 自 df5ad9c71 起必须授权）：
    // 按名过滤，未授权的凭证名解析失败并在状态行显示原因。
    val credentialResolver = remember(vaultRepository, context) {
        CredentialResolver(vaultRepository) { name ->
            me.rerere.rikkahub.data.vault.VaultSessionManager(context.applicationContext)
                .hasActiveAuthorization(name)
        }
    }

    var controlUrl by remember(network.tailscaleControlUrl) {
        mutableStateOf(network.tailscaleControlUrl)
    }
    var authKey by remember(network.tailscaleAuthKey) {
        mutableStateOf(network.tailscaleAuthKey)
    }
    var hostname by remember(network.effectiveTunnelHostname) {
        mutableStateOf(network.effectiveTunnelHostname)
    }
    var acceptRoutes by remember(network.tailscaleAcceptRoutes) {
        mutableStateOf(network.tailscaleAcceptRoutes)
    }
    var exitNode by remember(network.tailscaleExitNode) {
        mutableStateOf(network.tailscaleExitNode)
    }
    var advertiseRoutes by remember(network.tailscaleAdvertiseRoutes) {
        mutableStateOf(network.tailscaleAdvertiseRoutes)
    }
    var serve by remember(network.tailscaleServe) {
        mutableStateOf(network.tailscaleServe)
    }
    var busy by remember { mutableStateOf(false) }
    // 当前正在弹窗编辑的字段（null = 未打开）。
    var editingField by remember { mutableStateOf<TunnelField?>(null) }
    var showExitPicker by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDevicesDialog by remember { mutableStateOf(false) }
    // 连接配置与路由收进弹层（二级页只留开关+状态，功能变多也不会塞不下）。
    var showDetail by remember { mutableStateOf(false) }
    var nodeStatus by remember { mutableStateOf<TunnelStatus?>(null) }

    // 状态只有一个来源：[TsnetBridge.state]（由启动/停止与守护循环写回）。
    // 开关只反映"此刻是否开着"，因此进程重启后自然显示为关，不会出现"开关开着却没连"。
    val tunnelState by TsnetBridge.state.collectAsStateWithLifecycle()
    val status = when (val s = tunnelState) {
        is TunnelState.Connected -> stringResource(R.string.tunnel_status_connected, s.ip)
        is TunnelState.Connecting -> stringResource(R.string.tunnel_status_connecting)
        is TunnelState.Offline -> stringResource(R.string.tunnel_status_offline)
        is TunnelState.Failed -> stringResource(R.string.tunnel_status_failed, s.reason)
        is TunnelState.Off -> stringResource(R.string.tunnel_status_disconnected)
    }
    // Failed 也计入开态：失败后守护仍在按退避重试，显示成关会让用户误以为已停，
    // 且没有 UI 路径调 stop()；计入开态后用户拨到关即可真正停掉重连。
    val switchOn = tunnelState is TunnelState.Connected ||
        tunnelState is TunnelState.Connecting ||
        tunnelState is TunnelState.Offline ||
        tunnelState is TunnelState.Failed

    // 节点与 peer 列表只在已连上时拉取（设置页打开时一次，连上/断开时再一次）。
    LaunchedEffect(tunnelState) {
        nodeStatus = if (tunnelState is TunnelState.Connected) {
            parseTunnelStatus(TsnetBridge.statusJson())
        } else {
            null
        }
    }

    // 设备弹窗打开期间每 5 秒刷新一次节点状态，圆点随连接状态实时变化；
    // 关掉弹窗即停，不在后台空转。
    LaunchedEffect(showDevicesDialog) {
        if (!showDevicesDialog) return@LaunchedEffect
        while (true) {
            if (tunnelState is TunnelState.Connected) {
                nodeStatus = parseTunnelStatus(TsnetBridge.statusJson())
            }
            delay(5000)
        }
    }

    /** 解析密钥后启动隧道；建连结果由 [TsnetBridge.state] 广播，这里只负责把失败原因也写回。 */
    suspend fun startTunnel() {
        val resolved = resolveTunnelAuthKey(credentialResolver, authKey)
            .getOrElse {
                TsnetBridge.publish(TunnelState.Failed(it.message ?: "auth key resolve failed"))
                return
            }
        TsnetBridge.start(
            context = context,
            config = TunnelStartConfig(
                controlURL = controlUrl.trim(),
                authKey = resolved,
                hostname = hostname.trim().ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME },
                acceptRoutes = acceptRoutes,
                exitNode = exitNode,
                advertiseRoutes = advertiseRoutes,
                ephemeral = network.tailscaleEphemeral,
                serve = serve,
            ),
        )
    }

    fun persistConfig() {
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(
                    tailscaleControlUrl = controlUrl.trim(),
                    tailscaleAuthKey = authKey.trim(),
                    tailscaleHostname = hostname.trim().ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME },
                    tailscaleAcceptRoutes = acceptRoutes,
                    tailscaleExitNode = exitNode,
                    tailscaleAdvertiseRoutes = advertiseRoutes.trim(),
                    tailscaleServe = serve.trim(),
                ),
            )
        }
    }

    fun save() {
        val newHostname = hostname.trim().ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME }
        val hostnameChanged = newHostname != network.effectiveTunnelHostname
        persistConfig()
        // 保存成功的反馈：之前 savedHint 只写不读，页面上没有任何“已保存”提示；
        // 改成 Toast，存没存上立刻知道。
        toaster.show(savedLabel, type = ToastType.Success)
        // 隧道已在运行时用新配置重连，避免"保存了但没生效"。
        if (TsnetBridge.isRunning || TsnetBridge.desiredRunning) {
            busy = true
            scope.launch {
                if (hostnameChanged) {
                    // 改名：仅靠 EditPrefs 推主机名，已注册节点控制面不一定采纳
                    //（tsnet.Server.Hostname 只在首次注册时生效，见 golib 注释）；
                    // 退出登录再重建，才能保证控制台显示新名。先确认密钥能解析，
                    // 解析失败就不退出、只做普通重连，避免把一条能用的隧道断掉。
                    val keyOk = resolveTunnelAuthKey(credentialResolver, authKey).isSuccess
                    if (keyOk) {
                        TsnetBridge.logout()
                    } else {
                        TsnetBridge.stop()
                    }
                } else {
                    TsnetBridge.stop()
                }
                startTunnel()
                busy = false
            }
        }
    }

    fun cancel() {
        controlUrl = network.tailscaleControlUrl
        authKey = network.tailscaleAuthKey
        hostname = network.effectiveTunnelHostname
        acceptRoutes = network.tailscaleAcceptRoutes
        exitNode = network.tailscaleExitNode
        advertiseRoutes = network.tailscaleAdvertiseRoutes
        serve = network.tailscaleServe
    }

    val badgeContainerColor = when (tunnelState) {
        is TunnelState.Connected -> MaterialTheme.colorScheme.primaryContainer
        is TunnelState.Connecting, is TunnelState.Offline -> MaterialTheme.colorScheme.tertiaryContainer
        is TunnelState.Failed -> MaterialTheme.colorScheme.errorContainer
        is TunnelState.Off -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val badgeContentColor = when (tunnelState) {
        is TunnelState.Connected -> MaterialTheme.colorScheme.onPrimaryContainer
        is TunnelState.Connecting, is TunnelState.Offline -> MaterialTheme.colorScheme.onTertiaryContainer
        is TunnelState.Failed -> MaterialTheme.colorScheme.onErrorContainer
        is TunnelState.Off -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.tunnel_enable_title)) },
    ) {
        item(
            leadingContent = {
                CardGroupIcon(
                    icon = HugeIcons.Global,
                    containerColor = if (switchOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (switchOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tunnel_enable_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = badgeContainerColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = tunnelState is TunnelState.Connected) {
                                (tunnelState as? TunnelState.Connected)?.ip?.let { ip ->
                                    clipboard.setText(AnnotatedString(ip))
                                }
                            },
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.tunnel_enable_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                Switch(
                    checked = switchOn,
                    enabled = !busy,
                    onCheckedChange = { enabled ->
                        busy = true
                        // 落盘总开关：跟随 App 生命周期 —— 下次启动读到关就自动接回，关就断开且不再接。
                        vm.updateSettings { current ->
                            current.copy(
                                networkSetting = current.networkSetting.copy(tailscaleEnabled = enabled),
                            )
                        }
                        // 留痕：与 TunnelAutoStart 的 restore 日志成对，定位「写没写入 / 读不读得到」。
                        AppLog.i("TunnelSetting", "persist tailscaleEnabled=$enabled")
                        persistConfig()
                        scope.launch {
                            if (enabled) {
                                startTunnel()
                            } else {
                                TsnetBridge.stop()
                            }
                            busy = false
                        }
                    },
                )
            },
        )

        // 连接配置与路由收进「隧道设置」弹层：二级页只留开关 + 状态，
        // 后续再加 Taildrop / Serve / 质量面板也不会把这一页堆满。
        item(
            headlineContent = {
                TunnelRow(
                    title = stringResource(R.string.tunnel_settings_entry),
                    value = hostname.ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME },
                    onClick = { showDetail = true },
                )
            },
        )

        // 设备：在线 / 总数。未连上时 nodeStatus 为 null，peers 为空，直接算会出 0/0，
        // 看着像尾网没设备；实际是还没连上，显示“未连接”。
        item(
            headlineContent = {
                TunnelRow(
                    title = stringResource(R.string.tunnel_devices),
                    value = run {
                        val status = nodeStatus
                        if (status == null) {
                            stringResource(R.string.tunnel_status_disconnected)
                        } else {
                            val peers = status.peers
                            "${peers.count { it.online }}/${peers.size}"
                        }
                    },
                    onClick = { showDevicesDialog = true },
                )
            },
        )

        // 退出登录：服务端失效 + 停止（危险操作）
        item(
            headlineContent = {
                Text(
                    text = stringResource(R.string.tunnel_logout),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogoutConfirm = true }
                        .padding(vertical = 12.dp),
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.tunnel_logout_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }

    if (showDetail) {
        ModalBottomSheet(onDismissRequest = { showDetail = false }) {
            // 弹层内容约 10 行，小屏/大字体下底部够不着；Column 不会替内容滚，之前两个
            // AlertDialog 修过同类问题，这里漏了，一并加上。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.tunnel_settings_entry),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                // 本机的 MagicDNS 名：其他设备用它访问本机，换 IP 也不会变
                nodeStatus?.dnsName?.takeIf { it.isNotBlank() }?.let { dns ->
                    Text(
                        text = dns.trimEnd('.'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                // 控制面侧的本机名（控制台显示的那个）：改名后拿它核对控制台到底变没变。
                // 只读展示，改名不在这里做——控制面不认本机推的主机名（见 save() 的重注册）。
                nodeStatus?.hostName?.takeIf { it.isNotBlank() }?.let { consoleName ->
                    Text(
                        text = stringResource(R.string.tunnel_console_hostname, consoleName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                TunnelRow(
                    title = stringResource(R.string.tunnel_control_url_label),
                    value = controlUrl.ifBlank { NetworkSetting.DEFAULT_TUNNEL_CONTROL_URL },
                ) { editingField = TunnelField.ControlUrl }
                HorizontalDivider()
                TunnelRow(
                    title = stringResource(R.string.tunnel_hostname_label),
                    value = hostname.ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME },
                ) { editingField = TunnelField.Hostname }
                HorizontalDivider()
                TunnelRow(
                    title = stringResource(R.string.tunnel_auth_key_label),
                    value = if (authKey.isBlank()) "—" else "••••••••••••",
                ) { editingField = TunnelField.AuthKey }
                HorizontalDivider()
                TunnelRow(
                    title = stringResource(R.string.tunnel_advertise_routes_label),
                    value = advertiseRoutes.trim()
                        .ifBlank { stringResource(R.string.tunnel_advertise_routes_none) },
                ) { editingField = TunnelField.AdvertiseRoutes }
                HorizontalDivider()
                TunnelRow(
                    title = stringResource(R.string.tunnel_serve_label),
                    value = serve.trim().ifBlank { stringResource(R.string.tunnel_serve_none) },
                ) { editingField = TunnelField.Serve }
                // 对外端口已开且已连上时给一条可点的访问链接（MagicDNS 名优先，IP 更稳定就用 IP），
                // 免得用户自己拿尾网 IP 去拼。默认走 App 内置浏览器（BrowserActivity），不走系统浏览器：
                // 内置浏览器走 App 的网络栈，尾网地址能直接打开；系统浏览器不在 tailnet 上会打不开。
                serveUrl(serve, nodeStatus?.dnsName, nodeStatus?.selfIps.orEmpty())?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { context.startActivity(BrowserActivity.intent(context, url)) }
                            .padding(vertical = 8.dp),
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tunnel_accept_routes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.tunnel_accept_routes_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = nodeStatus?.acceptRoutes ?: acceptRoutes,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            // 先乐观更新再调底层：开关立刻响应；失败时把开关、落盘值、
                            // 节点状态三处都回滚到旧值并报错。之前只在 onSuccess 里更新
                            // nodeStatus，失败时开关看着像“拨了又弹回去”，且落盘值已是新值、
                            // 底层却没生效，界面与实际分叉。
                            val old = nodeStatus?.acceptRoutes ?: acceptRoutes
                            acceptRoutes = on
                            nodeStatus = nodeStatus?.copy(acceptRoutes = on)
                            vm.updateSettings { current ->
                                current.copy(
                                    networkSetting = current.networkSetting.copy(tailscaleAcceptRoutes = on),
                                )
                            }
                            if (nodeStatus?.running == true) {
                                scope.launch {
                                    TsnetBridge.setAcceptRoutes(on).onFailure { e ->
                                        acceptRoutes = old
                                        nodeStatus = nodeStatus?.copy(acceptRoutes = old)
                                        vm.updateSettings { current ->
                                            current.copy(
                                                networkSetting = current.networkSetting.copy(tailscaleAcceptRoutes = old),
                                            )
                                        }
                                        toaster.show(
                                            e.message ?: "set accept routes failed",
                                            type = ToastType.Error,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tunnel_ephemeral),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.tunnel_ephemeral_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = network.tailscaleEphemeral,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            vm.updateSettings { current ->
                                current.copy(
                                    networkSetting = current.networkSetting.copy(tailscaleEphemeral = on),
                                )
                            }
                        },
                    )
                }
                HorizontalDivider()
                TunnelRow(
                    title = stringResource(R.string.tunnel_exit_node),
                    value = exitNodeLabel(nodeStatus, exitNode, stringResource(R.string.tunnel_exit_node_none)),
                    onClick = { showExitPicker = true },
                )
            }
        }
    }

    if (showExitPicker) {
        val exitPeers = nodeStatus?.peers.orEmpty().filter { it.canBeExitNode }
        AlertDialog(
            onDismissRequest = { showExitPicker = false },
            title = { Text(stringResource(R.string.tunnel_exit_node)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        showExitPicker = false
                        scope.launch {
                            // 只在底层成功后才改本地状态与落盘值；失败时报错、不改。
                            // 之前 exitNode 与落盘是无条件改的，底层没生效、设置里却写着已选。
                            TsnetBridge.setExitNode("")
                                .onSuccess {
                                    nodeStatus = nodeStatus?.copy(exitNodeIp = "")
                                    exitNode = ""
                                    vm.updateSettings { current ->
                                        current.copy(networkSetting = current.networkSetting.copy(tailscaleExitNode = ""))
                                    }
                                }
                                .onFailure { e ->
                                    toaster.show(
                                        e.message ?: "set exit node failed",
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    }) { Text(stringResource(R.string.tunnel_exit_node_none)) }
                    exitPeers.forEach { peer ->
                        TextButton(
                            onClick = {
                                showExitPicker = false
                                val ip = peer.ips.firstOrNull().orEmpty()
                                scope.launch {
                                    TsnetBridge.setExitNode(ip)
                                        .onSuccess {
                                            nodeStatus = nodeStatus?.copy(exitNodeIp = ip)
                                            exitNode = ip
                                            vm.updateSettings { current ->
                                                current.copy(networkSetting = current.networkSetting.copy(tailscaleExitNode = ip))
                                            }
                                        }
                                        .onFailure { e ->
                                            toaster.show(
                                                e.message ?: "set exit node failed",
                                                type = ToastType.Error,
                                            )
                                        }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // 名字 + IP 两行：名字重复时靠 IP 区分，离线节点名字也可能重复。
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(peer.hostName.ifBlank { peer.ips.firstOrNull().orEmpty() })
                                val ip = peer.ips.firstOrNull().orEmpty()
                                if (ip.isNotBlank()) {
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExitPicker = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showDevicesDialog) {
        val devicePeers = nodeStatus?.peers.orEmpty()
        AlertDialog(
            onDismissRequest = { showDevicesDialog = false },
            title = { Text(stringResource(R.string.tunnel_devices)) },
            text = {
                // 设备多时可上下滑，AlertDialog 本身不会替内容滚，行数一多底部会被裁掉。
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (nodeStatus == null) {
                        // 隧道没连上：peers 为空是“没连上”，不是“尾网没设备”，别显示空列表。
                        Text(
                            text = stringResource(R.string.tunnel_status_disconnected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (devicePeers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tunnel_devices_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        devicePeers.forEach { peer ->
                            val name = peer.hostName.ifBlank { peer.ips.firstOrNull().orEmpty() }
                            val ip = peer.ips.firstOrNull().orEmpty()
                            // 圆点颜色 = 实时路径：绿直连 / 黄中继 / 灰空闲 / 红离线。
                            // 离线节点没有实时连接，不挂中继——relay 是控制面给的归属 DERP，
                            // 空闲时也会填着，挂出来等于把“能上次走哪”说成“现在正走哪”。
                            val dotColor: Color
                            val path: String
                            when {
                                !peer.online -> {
                                    dotColor = MaterialTheme.colorScheme.error
                                    path = ""
                                }
                                !peer.active -> {
                                    dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    path = stringResource(R.string.tunnel_path_idle)
                                }
                                peer.curAddr.isNotBlank() -> {
                                    dotColor = Color(0xFF43A047)
                                    path = stringResource(R.string.tunnel_path_direct)
                                }
                                peer.relay.isNotBlank() -> {
                                    dotColor = Color(0xFFFFB300)
                                    path = stringResource(R.string.tunnel_path_relay, peer.relay)
                                }
                                else -> {
                                    dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    path = ""
                                }
                            }
                            // 点一行复制该设备的 IP（没有 IP 时复制 MagicDNS 名），
                            // 免得对着弹窗一个字符一个字符手打；状态徽章点一下能复制本机 IP，
                            // 设备行之前是纯文本、复制不了。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val toCopy = ip.ifBlank { peer.dnsName.trimEnd('.') }
                                        if (toCopy.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(toCopy))
                                            toaster.show(
                                                copiedLabel,
                                                type = ToastType.Success,
                                            )
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(color = dotColor)) { append("● ") }
                                        withStyle(
                                            SpanStyle(
                                                color = if (peer.online) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            ),
                                        ) { append("$name  $ip") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (path.isNotBlank()) {
                                    Text(
                                        text = path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // MagicDNS 名（控制面后缀）：换 IP/重连不变
                                if (peer.dnsName.isNotBlank()) {
                                    Text(
                                        text = peer.dnsName.trimEnd('.'),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevicesDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.tunnel_logout)) },
            text = { Text(stringResource(R.string.tunnel_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    scope.launch {
                        // 先把开关落成关，否则重启后 TunnelAutoStart 按开关自动接回，等于登出没发生。
                        vm.updateSettings { current ->
                            current.copy(
                                networkSetting = current.networkSetting.copy(tailscaleEnabled = false),
                            )
                        }
                        TsnetBridge.logout()
                    }
                }) {
                    Text(
                        text = stringResource(R.string.tunnel_logout),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    editingField?.let { field ->
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = {
                Text(
                    stringResource(
                        when (field) {
                            TunnelField.ControlUrl -> R.string.tunnel_control_url_label
                            TunnelField.AuthKey -> R.string.tunnel_auth_key_label
                            TunnelField.Hostname -> R.string.tunnel_hostname_label
                            TunnelField.AdvertiseRoutes -> R.string.tunnel_advertise_routes_label
                            TunnelField.Serve -> R.string.tunnel_serve_label
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (field) {
                        TunnelField.ControlUrl -> OutlinedTextField(
                            value = controlUrl,
                            onValueChange = { controlUrl = it },
                            placeholder = { Text(NetworkSetting.DEFAULT_TUNNEL_CONTROL_URL) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TunnelField.Hostname -> OutlinedTextField(
                            value = hostname,
                            onValueChange = { hostname = it },
                            placeholder = { Text("yao") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TunnelField.AdvertiseRoutes -> OutlinedTextField(
                            value = advertiseRoutes,
                            onValueChange = { advertiseRoutes = it },
                            placeholder = { Text("192.168.1.0/24") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TunnelField.Serve -> OutlinedTextField(
                            value = serve,
                            onValueChange = { serve = it },
                            placeholder = { Text("8080:8080") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TunnelField.AuthKey -> SecretRefField(
                            value = authKey,
                            onValueChange = { authKey = it },
                            label = stringResource(R.string.tunnel_auth_key_label),
                            placeholder = stringResource(R.string.tunnel_auth_key_placeholder),
                            nameHint = "TAILSCALE_AUTH_KEY",
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingField = null
                    save()
                }) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingField = null
                    cancel()
                }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

/** 设置页里的一行“标题 — 当前值 ›”，点开弹窗编辑。 */
@Composable
private fun TunnelRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 出口节点当前值：空 = 无；否则优先显示 peer 名，退化到 IP。 */
private fun exitNodeLabel(status: TunnelStatus?, fallbackIp: String, noneLabel: String): String {
    // 已连上时以 native 的 prefs 为准；未连上时回退到设置值（重启后仍能看到上次选的出口节点）。
    val raw = status?.exitNodeIp?.trim().orEmpty().ifBlank { fallbackIp }.trim()
    val ip = raw.takeIf { it.isNotBlank() && it != "<nil>" && it != "invalid IP" } ?: return noneLabel
    val peer = status?.peers?.firstOrNull { p -> p.ips.any { it == ip } }
    return peer?.hostName?.takeIf { it.isNotBlank() } ?: ip
}

/**
 * 对外端口（Serve）的访问链接：解析 "tailnetPort:localPort"，主机用 MagicDNS 名、没有就用尾网 IP。
 * 与 [TsnetBridge.applyServe] 同口径：规格不是两段正端口时底层根本没暴露，返回 null 不显示链接，
 * 免得给一条打不开的地址；没连上（拿不到主机地址）时也返回 null。
 */
private fun serveUrl(serve: String, dnsName: String?, selfIps: List<String>): String? {
    val parts = serve.trim().split(":")
    if (parts.size != 2) return null
    val tailnetPort = parts[0].trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    if (parts[1].trim().toIntOrNull()?.takeIf { it > 0 } == null) return null
    val host = dnsName?.trimEnd('.')?.takeIf { it.isNotBlank() }
        ?: selfIps.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: return null
    return "http://$host:$tailnetPort"
}

/** 可在隧道设置页弹窗编辑的字段。 */
private enum class TunnelField { ControlUrl, AuthKey, Hostname, AdvertiseRoutes, Serve }

/** 一个 tailnet 对端。 */
private data class TunnelPeer(
    val hostName: String,
    val dnsName: String,
    val ips: List<String>,
    val online: Boolean,
    val canBeExitNode: Boolean,
    /** 直连地址；为空表示走中继。 */
    val curAddr: String = "",
    /** 中继所在的 DERP 区域（如 hkg / sz）。 */
    val relay: String = "",
    /** 最近 2 分钟内有流量；在线但无流量即为空闲（未建立实时路径）。缺省 true，兼容旧版 Go 库。 */
    val active: Boolean = true,
)

/** tsnet 状态快照（来自 [TsnetBridge.statusJson]）。 */
private data class TunnelStatus(
    val running: Boolean = false,
    val selfIps: List<String> = emptyList(),
    val dnsName: String = "",
    /** 控制面侧的终端名称（控制台可改；本机只读展示）。 */
    val hostName: String = "",
    val peers: List<TunnelPeer> = emptyList(),
    val acceptRoutes: Boolean = false,
    val exitNodeIp: String = "",
)

/** 解析 Go 侧 StatusJSON；字段缺失一律取默认值，不抛异常。 */
private fun parseTunnelStatus(raw: String?): TunnelStatus? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val o = org.json.JSONObject(raw)
        val peers = mutableListOf<TunnelPeer>()
        o.optJSONArray("peers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val ips = mutableListOf<String>()
                p.optJSONArray("ips")?.let { a -> for (j in 0 until a.length()) ips += a.optString(j) }
                peers += TunnelPeer(
                    hostName = p.optString("hostName"),
                    dnsName = p.optString("dnsName"),
                    ips = ips,
                    online = p.optBoolean("online"),
                    canBeExitNode = p.optBoolean("exitNodeOption"),
                    curAddr = p.optString("curAddr"),
                    relay = p.optString("relay"),
                    active = p.optBoolean("active", true),
                )
            }
        }
        val selfIps = mutableListOf<String>()
        o.optJSONArray("tailscaleIPs")?.let { a ->
            for (j in 0 until a.length()) selfIps += a.optString(j)
        }
        TunnelStatus(
            running = o.optBoolean("running"),
            selfIps = selfIps,
            dnsName = o.optString("dnsName"),
            hostName = o.optString("hostName"),
            peers = peers,
            acceptRoutes = o.optBoolean("acceptRoutes"),
            exitNodeIp = o.optString("exitNodeIP"),
        )
    }.getOrNull()
}
