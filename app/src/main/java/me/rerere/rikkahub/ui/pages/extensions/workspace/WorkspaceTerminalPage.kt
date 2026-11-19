package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.flowOf
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionManager: WorkspaceTerminalSessionManager = koinInject()
    val navController = LocalNavController.current
    val root = state.workspace?.root
    val terminalStateFlow =
        remember(root, sessionManager) {
            root?.let(sessionManager::observeWorkspace) ?: flowOf(WorkspaceTerminalTabsState())
        }
    val terminalState by terminalStateFlow.collectAsStateWithLifecycle(
        initialValue = WorkspaceTerminalTabsState(),
    )
    var pendingCloseTabId by remember(root) { mutableStateOf<Long?>(null) }
    var showBackgroundTasks by remember { mutableStateOf(false) }

    LaunchedEffect(state.workspace) {
        state.workspace?.let { sessionManager.ensureSession(it) }
    }

    RikkahubTheme(colorMode = ColorMode.DARK) {
        Scaffold { innerPadding ->
            WorkspaceTerminalContent(
                root = root,
                workspaceName = state.workspace?.name,
                state = terminalState,
                contentPadding = innerPadding,
                onCreateTab = { state.workspace?.let { sessionManager.createTab(it) } },
                onShowBackgroundTasks = { showBackgroundTasks = true },
                onSelectTab = { tabId ->
                    root?.let { sessionManager.selectTab(it, tabId) }
                },
                onCloseTab = { tabId ->
                    pendingCloseTabId = tabId
                },
                // 已退出的会话直接重开：关掉已结束的标签再建一个新会话，不用先关标签
                // （还要再过一次确认弹窗）、再新建。
                onRestartTab = { tabId ->
                    root?.let { sessionManager.closeTab(it, tabId) }
                    state.workspace?.let { sessionManager.createTab(it) }
                },
                // 未装 rootfs 时的安装入口在详情页：回详情页找安装入口。
                onGoInstall = { navController.popBackStack() },
            )
        }

        val pendingCloseTab = terminalState.tabs.firstOrNull { it.id == pendingCloseTabId }
        if (pendingCloseTab != null) {
            AlertDialog(
                onDismissRequest = { pendingCloseTabId = null },
                title = {
                    Text(
                        stringResource(
                            R.string.workspace_terminal_close_confirm_title,
                            pendingCloseTab.number,
                        ),
                    )
                },
                text = {
                    Text(stringResource(R.string.workspace_terminal_close_confirm_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            root?.let { sessionManager.closeTab(it, pendingCloseTab.id) }
                            pendingCloseTabId = null
                        },
                    ) {
                        Text(stringResource(R.string.workspace_terminal_close))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCloseTabId = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
        if (showBackgroundTasks) {
            WorkspaceBackgroundTasksSheet(
                workspaceId = id,
                onDismiss = { showBackgroundTasks = false },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WorkspaceTerminalContent(
    root: String?,
    workspaceName: String?,
    state: WorkspaceTerminalTabsState,
    contentPadding: PaddingValues,
    onCreateTab: () -> Unit,
    onShowBackgroundTasks: () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onRestartTab: (Long) -> Unit,
    onGoInstall: () -> Unit,
) {
    val selectedIndex =
        state.tabs
            .indexOfFirst { it.id == state.selectedTabId }
            .takeIf { it >= 0 }
            ?: 0
    val selectedTab = state.tabs.getOrNull(selectedIndex)

    // 查找输出：状态按页签记，换页签清空；匹配行号是回滚区的绝对行号（负数），
    // 查一次读一遍回滚记录（上限 2000 行），输入变化与上下翻时重算。
    var searchVisible by remember(state.selectedTabId) { mutableStateOf(false) }
    var searchQuery by remember(state.selectedTabId) { mutableStateOf("") }
    var searchMatches by remember(state.selectedTabId) { mutableStateOf<List<Int>>(emptyList()) }
    var searchIndex by remember(state.selectedTabId) { mutableStateOf(0) }

    fun refreshSearchMatches() {
        val tab = selectedTab ?: return
        searchMatches = findTerminalMatches(tab, searchQuery)
        searchIndex = 0
        searchMatches.firstOrNull()?.let { jumpToTerminalMatch(tab, it) }
    }

    fun stepSearchMatch(step: Int) {
        val tab = selectedTab ?: return
        // 翻页前重算一次：输出一直在涨，旧的匹配表会过期。
        searchMatches = findTerminalMatches(tab, searchQuery)
        if (searchMatches.isEmpty()) return
        searchIndex = ((searchIndex + step) % searchMatches.size + searchMatches.size) % searchMatches.size
        jumpToTerminalMatch(tab, searchMatches[searchIndex])
    }

    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                // Resize to the final IME height at animation start so the toolbar moves immediately
                // and terminal rows stay stable instead of sending a SIGWINCH for every frame.
                .windowInsetsPadding(WindowInsets.imeAnimationTarget),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton()
                // 工作区名不再单独占一格：直接写进页签标题（第一签"<工作区名> 终端"，多签加序号）。
                Box(modifier = Modifier.weight(1f)) {
                    if (selectedTab != null) {
                        SecondaryScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            modifier = Modifier.fillMaxWidth(),
                            edgePadding = 4.dp,
                            minTabWidth = 96.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            divider = {},
                            // 去掉选中页签下那条长指示线：选中态靠图标主色区分即可。
                            indicator = {},
                        ) {
                            state.tabs.forEach { tab ->
                                val tabDescription =
                                    stringResource(
                                        R.string.workspace_terminal_tab,
                                        tab.number,
                                    )
                                // 页签标题显示工作区名：第一签"<工作区名> 终端"，多签加序号"<工作区名> 终端 2"；
                                // 工作区名未加载时回落到 shell 标题或"终端 N"。
                                val tabTitle = workspaceName?.takeIf { it.isNotBlank() }?.let { name ->
                                    val terminalWord = stringResource(R.string.workspace_terminal_title)
                                    if (tab.number > 1) "$name $terminalWord ${tab.number}" else "$name $terminalWord"
                                } ?: tab.title?.takeIf { it.isNotBlank() } ?: tabDescription
                                val isSelected = selectedTab.id == tab.id
                                Tab(
                                    selected = isSelected,
                                    onClick = { onSelectTab(tab.id) },
                                    modifier =
                                        Modifier
                                            .height(52.dp)
                                            .widthIn(min = 96.dp, max = 220.dp)
                                            .semantics {
                                                contentDescription = tabTitle
                                            },
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp, end = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.ComputerTerminal01,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint =
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                        // 不使用无限循环的 basicMarquee：它不仅把 "root@localhost:/workspace"
                                        // 截成 "rkspace   root@local" 影响辨识，还会在终端打开期间持续产生 60fps UI 重绘。
                                        Text(
                                            text = tabTitle,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.MiddleEllipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        val closeDescription =
                                            stringResource(
                                                R.string.workspace_terminal_close_tab,
                                                tab.number,
                                            )
                                        IconButton(
                                            onClick = { onCloseTab(tab.id) },
                                            modifier =
                                                Modifier
                                                    .size(28.dp)
                                                    .semantics {
                                                        contentDescription = closeDescription
                                                    },
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Cancel01,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val newTabDescription = stringResource(R.string.workspace_terminal_new_tab)
                // 后台任务入口：本地与远程同一张表（仓库层按类型分流），放查找与新建标签旁边。
                val backgroundTasksDescription = stringResource(R.string.workspace_background_tasks_title)
                IconButton(
                    onClick = onShowBackgroundTasks,
                    enabled = root != null,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .semantics { contentDescription = backgroundTasksDescription },
                ) {
                    Icon(
                        imageVector = HugeIcons.Clock02,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // 查找输出开关：只在有页签时可用。
                val searchHint = stringResource(R.string.workspace_terminal_search_hint)
                IconButton(
                    onClick = { searchVisible = !searchVisible },
                    enabled = selectedTab != null,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .semantics { contentDescription = searchHint },
                ) {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (searchVisible) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(
                    onClick = onCreateTab,
                    // 未装 rootfs 时新建标签是静默失败（createTab 里准备失败就直接回 NotInstalled），
                    // 这里直接禁用，原因由下方的未安装态 + 安装入口说明。
                    enabled = root != null && !state.isCreating &&
                        state.readiness != WorkspaceTerminalReadiness.NotInstalled,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .semantics { contentDescription = newTabDescription },
                ) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 查找栏：输入即搜，上下翻在匹配间跳转（循环），关掉即清空。
            if (searchVisible && selectedTab != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { newQuery ->
                            searchQuery = newQuery
                            refreshSearchMatches()
                        },
                        placeholder = { Text(stringResource(R.string.workspace_terminal_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = if (searchMatches.isEmpty()) {
                                "0/0"
                            } else {
                                "${searchIndex + 1}/${searchMatches.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { stepSearchMatch(-1) },
                        enabled = searchMatches.isNotEmpty(),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.ArrowUp01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { stepSearchMatch(1) },
                        enabled = searchMatches.isNotEmpty(),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            searchVisible = false
                            searchQuery = ""
                            searchMatches = emptyList()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selectedTab != null) {
                WorkspaceTerminalTabContent(
                    tab = selectedTab,
                    onRestart = { onRestartTab(selectedTab.id) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text =
                                when {
                                    state.errorMessage != null -> state.errorMessage
                                    root == null || state.isCreating || state.readiness == WorkspaceTerminalReadiness.Loading -> {
                                        stringResource(R.string.workspace_terminal_loading)
                                    }

                                    state.readiness == WorkspaceTerminalReadiness.NotInstalled -> {
                                        stringResource(R.string.workspace_terminal_not_installed)
                                    }

                                    else -> stringResource(R.string.workspace_terminal_no_tabs)
                                },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                        // 未装 rootfs 时给明确的安装入口，不要只有一句话。
                        if (state.readiness == WorkspaceTerminalReadiness.NotInstalled) {
                            TextButton(onClick = onGoInstall) {
                                Text(stringResource(R.string.workspace_detail_install_rootfs))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTerminalTabContent(
    tab: WorkspaceTerminalTab,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 14.sp.roundToPx() }
    val terminalTypeface =
        remember(context) {
            ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        }
    var controlDown by remember(tab.id) { mutableStateOf(false) }
    var altDown by remember(tab.id) { mutableStateOf(false) }
    val viewClient =
        remember(tab.id) {
            WorkspaceTerminalViewClient(context)
        }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    DisposableEffect(tab.id, viewClient) {
        onDispose {
            if (tab.client.terminalView === viewClient.terminalView) {
                tab.client.terminalView = null
            }
            viewClient.terminalView = null
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    TerminalView(viewContext, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(terminalTextSizePx)
                        setTypeface(terminalTypeface)
                        setTerminalViewClient(viewClient)
                        attachSession(tab.session)
                        tab.client.terminalView = this
                        viewClient.terminalView = this
                        post {
                            viewClient.focusAndShowKeyboard()
                        }
                    }
                },
                update = { terminalView ->
                    tab.client.terminalView = terminalView
                    viewClient.terminalView = terminalView
                    if (terminalView.currentSession !== tab.session) {
                        terminalView.setTextSize(terminalTextSizePx)
                        terminalView.setTypeface(terminalTypeface)
                        terminalView.setTerminalViewClient(viewClient)
                        terminalView.attachSession(tab.session)
                        terminalView.onScreenUpdated()
                    }
                },
            )
            if (tab.finished) {
                // 已退出的会话给「重新打开」入口：之前要先关标签（还要再过一次确认弹窗）、
                // 再新建，点两轮才能重开。
                WorkspaceTerminalExitRow(onReopen = onRestart)
            }
        }
        TerminalExtraKeysBar(
            controlDown = controlDown,
            altDown = altDown,
            onControlToggle = { controlDown = !controlDown },
            onAltToggle = { altDown = !altDown },
            onSendText = { tab.session.writeText(it) },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
        )
    }
}

/**
 * 在终端回滚记录 + 当前屏里找 query（忽略大小写），返回匹配行的绝对行号（从上到下）：
 * 负数 = 回滚区，0..屏高 = 当前屏。读整段文本按行切（不拼折行，行号才对得上），
 * 回滚上限 2000 行，一次全扫在毫秒级。
 */
private fun findTerminalMatches(tab: WorkspaceTerminalTab, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    val screen = runCatching { tab.session.emulator.screen }.getOrNull() ?: return emptyList()
    val transcriptRows = screen.activeTranscriptRows
    val screenRows = screen.activeRows - transcriptRows
    val text = runCatching {
        screen.getSelectedText(0, -transcriptRows, Int.MAX_VALUE, screenRows, false)
    }.getOrNull() ?: return emptyList()
    val needle = query.lowercase()
    return text.split("\n").mapIndexedNotNull { index, line ->
        if (line.lowercase().contains(needle)) -transcriptRows + index else null
    }
}

/** 把视图滚到指定绝对行：setTopRow 与回滚行号同一坐标系（负数 = 回滚区，0 = 当前屏底）。 */
private fun jumpToTerminalMatch(tab: WorkspaceTerminalTab, row: Int) {
    val view = tab.client.terminalView ?: return
    val transcriptRows = runCatching { tab.session.emulator.screen.activeTranscriptRows }.getOrNull() ?: 0
    view.setTopRow(row.coerceIn(-transcriptRows, 0))
}
