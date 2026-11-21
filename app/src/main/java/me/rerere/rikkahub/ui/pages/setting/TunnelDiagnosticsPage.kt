package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.tunnel.DiagItemId
import me.rerere.rikkahub.data.tunnel.DiagItemState
import me.rerere.rikkahub.data.tunnel.DiagStatus
import me.rerere.rikkahub.data.tunnel.DiagStrings
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.TunnelDiagnosticsRunner
import me.rerere.rikkahub.data.tunnel.buildCopyText
import me.rerere.rikkahub.data.tunnel.diagStrings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

class TunnelDiagnosticsViewModel(
    private val settingsStore: SettingsStore,
    private val appContext: Context,
) : ViewModel() {
    private val runner = TunnelDiagnosticsRunner(TsnetBridge, settingsStore, appContext, appContext.diagStrings())

    // 1.9 尾网 TCP 暂时隐藏。
    private val visibleItems = DiagItemId.entries.filter { it != DiagItemId.TAILNET_TCP }

    private val _items = MutableStateFlow(visibleItems.map { DiagItemState(it, DiagStatus.PENDING) })
    val items: StateFlow<List<DiagItemState>> = _items.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _startedAtMs = MutableStateFlow(0L)
    val startedAtMs: StateFlow<Long> = _startedAtMs.asStateFlow()

    private val _finishedAtMs = MutableStateFlow(0L)
    val finishedAtMs: StateFlow<Long> = _finishedAtMs.asStateFlow()

    fun start() {
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            _startedAtMs.value = System.currentTimeMillis()
            _finishedAtMs.value = 0L
            _items.value = visibleItems.map { DiagItemState(it, DiagStatus.PENDING) }
            runner.runAll { state ->
                _items.value = _items.value.map { if (it.id == state.id) state else it }
            }
            _finishedAtMs.value = System.currentTimeMillis()
            _running.value = false
        }
    }

    fun copyText(titles: Map<DiagItemId, String>, strings: DiagStrings): String =
        buildCopyText(strings, _items.value, titles, _startedAtMs.value, _finishedAtMs.value)

    /**
     * 一键恢复：只停隧道（stop() 会把 desiredRunning 置 false，守护循环不再重连）
     * 并把开关落盘为关；保留全部设置，不调 logout()，不清空自定义控制面 URL。
     */
    suspend fun resetTunnel() {
        TsnetBridge.stop()
        settingsStore.update { current ->
            current.copy(networkSetting = current.networkSetting.copy(tailscaleEnabled = false))
        }
    }
}

private fun itemTitleRes(id: DiagItemId): Int = when (id) {
    DiagItemId.MODULE -> R.string.tunnel_diag_item_module
    DiagItemId.DEVICE_NETWORK -> R.string.tunnel_diag_item_device_network
    DiagItemId.SYSTEM_DNS -> R.string.tunnel_diag_item_system_dns
    DiagItemId.DIRECT -> R.string.tunnel_diag_item_direct
    DiagItemId.CONTROL_PLANE -> R.string.tunnel_diag_item_control_plane
    DiagItemId.MAGIC_DNS -> R.string.tunnel_diag_item_magic_dns
    DiagItemId.MAGIC_DNS_RESOLVE -> R.string.tunnel_diag_item_magic_dns_resolve
    DiagItemId.PEERS -> R.string.tunnel_diag_item_peers
    DiagItemId.ROUTES -> R.string.tunnel_diag_item_routes
    DiagItemId.TAILNET_TCP -> R.string.tunnel_diag_item_tailnet_tcp
    DiagItemId.TAILNET_PING -> R.string.tunnel_diag_item_tailnet_ping
}

@Composable
fun TunnelDiagnosticsPage(vm: TunnelDiagnosticsViewModel = koinViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val startedAtMs by vm.startedAtMs.collectAsStateWithLifecycle()
    val finishedAtMs by vm.finishedAtMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val titles = DiagItemId.entries.associateWith { id -> stringResource(itemTitleRes(id)) }
    val copiedMsg = stringResource(R.string.tunnel_diag_copied)
    val resetDoneMsg = stringResource(R.string.tunnel_diag_reset_done)
    val diagStrings = remember(context) { context.diagStrings() }

    // 进页自动跑一次；重测走按钮。
    LaunchedEffect(Unit) { vm.start() }

    val summary = stringResource(
        R.string.tunnel_diag_summary,
        items.count { it.status == DiagStatus.PASS },
        items.count { it.status == DiagStatus.FAIL },
        items.count { it.status == DiagStatus.SKIP },
    )
    val elapsedSec = ((finishedAtMs - startedAtMs).coerceAtLeast(0L) + 500L) / 1000L

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tunnel_diag_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (finishedAtMs > 0L) {
                item {
                    Text(
                        text = "$summary${stringResource(R.string.tunnel_diag_elapsed, elapsedSec)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                DiagItemRow(
                    state = item,
                    title = titles[item.id] ?: item.id.name,
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { vm.start() },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.tunnel_diag_rerun))
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(vm.copyText(titles, diagStrings)))
                            toaster.show(
                                copiedMsg,
                                type = ToastType.Success,
                            )
                        },
                        enabled = finishedAtMs > 0L,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.tunnel_diag_copy))
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.tunnel_diag_reset))
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.tunnel_diag_reset_title)) },
            text = { Text(stringResource(R.string.tunnel_diag_reset_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        scope.launch {
                            vm.resetTunnel()
                            toaster.show(
                                resetDoneMsg,
                                type = ToastType.Success,
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.tunnel_diag_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.tunnel_diag_reset_cancel))
                }
            },
        )
    }
}

@Composable
private fun DiagItemRow(state: DiagItemState, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.status) {
            DiagStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            else -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (state.status) {
                            DiagStatus.PASS -> MaterialTheme.colorScheme.primary
                            DiagStatus.FAIL -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${state.id.displayNo} $title",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        when (state.status) {
                            DiagStatus.PASS -> R.string.tunnel_diag_status_pass
                            DiagStatus.FAIL -> R.string.tunnel_diag_status_fail
                            DiagStatus.SKIP -> R.string.tunnel_diag_status_skip
                            DiagStatus.RUNNING -> R.string.tunnel_diag_status_running
                            DiagStatus.PENDING -> R.string.tunnel_diag_status_pending
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.status) {
                        DiagStatus.PASS -> MaterialTheme.colorScheme.primary
                        DiagStatus.FAIL -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.detail.isNotBlank()) {
                Text(
                    text = state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
