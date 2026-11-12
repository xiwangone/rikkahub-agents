package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import java.util.concurrent.atomic.AtomicLong
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 *
 * 会话内部仍以工作区 root 为键（root 是文件区与终端会话的稳定标识）；入口收实体，
 * 因为创建分支要看能力位（SSH 走 SshTerminalTransport，本机走 proot 进程）。
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(workspace: WorkspaceEntity) {
        launchCreateTab(workspace = workspace, onlyIfEmpty = true)
    }

    internal fun createTab(workspace: WorkspaceEntity) {
        launchCreateTab(workspace = workspace, onlyIfEmpty = false)
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // Wait for rootfs preparation to leave its IO section before callers delete or replace the
        // same files. CancellationException is deliberately rethrown by createTab().
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    private fun launchCreateTab(workspace: WorkspaceEntity, onlyIfEmpty: Boolean) {
        val root = workspace.root
        if (root in creationJobs) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(workspace = workspace, onlyIfEmpty = onlyIfEmpty)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(workspace: WorkspaceEntity, onlyIfEmpty: Boolean) = withContext(Dispatchers.Main.immediate) {
        val root = workspace.root
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        // 远程工作区没有本机 rootfs 可准备，跳过；本机已就绪的也不重复准备
        val prepared = if (!workspace.capabilities().rootfs ||
            initialState.readiness == WorkspaceTerminalReadiness.Ready
        ) {
            true
        } else {
            try {
                withContext(Dispatchers.IO) {
                    if (!workspaceRootfsReady(appContext, root)) {
                        false
                    } else {
                        prepareWorkspaceTerminalSession(appContext, root)
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.e(TAG, "Failed to prepare terminal for workspace $root", error)
                false
            }
        }

        if (!prepared) {
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext
        }

        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(
            context = appContext,
            onTitleUpdated = { title -> updateTitle(root, tabId, title) },
            onFinished = { markFinished(root = root, tabId = tabId) },
        )
        val session = runCatching {
            if (workspace.isSsh()) {
                val settings = workspace.sshSettings()
                    ?: error("SSH workspace settings missing: ${workspace.id}")
                createWorkspaceTerminalSession(
                    transport = SshTerminalTransport(
                        address = "${settings.username}@${settings.host}:${settings.port}",
                    ) { columns, rows ->
                        workspaceRepository.openSshShell(workspace, columns, rows)
                    },
                    root = root,
                    client = client,
                )
            } else {
                createWorkspaceTerminalSession(
                    context = appContext,
                    root = root,
                    client = client,
                    shellCompatibilityMode = workspace.shellCompatibilityMode,
                )
            }
        }.onFailure { error ->
            AppLog.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (session == null) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }

        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    private fun updateTitle(root: String, tabId: Long, title: String?) {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(title = normalizedTitle) else tab
                },
            ))
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        // 类名 24 字符, 超过 23 字符会被 logcat 截断, Terminal 缩写为 Term。
        const val TAG = "WorkspaceTermManager"
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val title: String? = null,
    val finished: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
