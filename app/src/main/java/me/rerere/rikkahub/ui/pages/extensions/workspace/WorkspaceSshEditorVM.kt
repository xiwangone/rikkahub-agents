package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshHostKey
import me.rerere.workspace.ssh.SshHostKeyChangedException
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshUnknownHostKeyException

/**
 * 新建 / 编辑 SSH 工作区。
 *
 * 凭证栏一律是**凭证库条目引用**（`$$NAME`）：页面只搬条目名，明文在连接测试与保存的瞬间
 * 由 Repository 现取现用，不进数据库、不进日志、也不回显到界面。
 */
@Suppress("TooGenericExceptionCaught")
// 测试连接要覆盖所有失败形态（网络 / JSch / 认证 / 超时）并统一转成界面提示。
class WorkspaceSshEditorVM(
    private val id: String?,
    private val repository: WorkspaceRepository,
    private val sshHostRepository: SshHostRepository,
) : ViewModel() {
    /** 主机密钥待确认：[expected] 为 null 表示首次连接，否则是与已信任密钥不一致；[jumpHost] 表示是跳板机的密钥 */
    data class PendingHostKey(
        val actual: SshHostKey,
        val expected: SshHostKey?,
        val saveAfter: Boolean,
        val jumpHost: Boolean = false,
    )

    /** Probe failure, with the cause classified so the page can show a readable reason. */
    data class ProbeFailure(
        val kind: Kind,
        val detail: String,
    ) {
        enum class Kind {
            AUTH,
            REFUSED,
            TIMEOUT,
            UNREACHABLE,
            EXTRA_VERIFICATION,
            UNKNOWN,
        }
    }

    /** Which required inputs are still missing or invalid; empty means the form can be submitted. */
    enum class SubmitBlocker {
        NAME_BLANK,
        NAME_DUPLICATE,
        HOST_BLANK,
        USERNAME_BLANK,
        PORT_INVALID,
        CREDENTIAL_MISSING,
    }

    /** 跳板机下拉选项：只取展示用的三格，不把已保存主机的敏感值（密码/私钥）带进界面状态。 */
    data class SavedJumpHost(
        val name: String,
        val user: String,
        val host: String,
    )

    data class State(
        val draft: SshWorkspaceDraft = SshWorkspaceDraft(name = "", host = "", username = "", remoteDir = ""),
        val portInput: String = "22",
        val knownNames: Set<String> = emptySet(),
        /** 已保存的 SSH 主机（供跳板机下拉选择，按名引用；为空时只剩手动输入）。 */
        val savedHosts: List<SavedJumpHost> = emptyList(),
        val busy: Boolean = false,
        val probeResult: SshProbeResult? = null,
        val probeError: ProbeFailure? = null,
        val saveError: String? = null,
        val pendingHostKey: PendingHostKey? = null,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val isNew: Boolean = id == null

    /**
     * 本次编辑会话里已信任过的主机密钥（主机名 -> 密钥）。
     * 主机名改错一个字母又改回来时，凭它把密钥恢复回来，而不是重新弹框确认；
     * 密钥始终按主机名绑定，换到没信任过的主机仍按空处理、重新确认。
     */
    private val trustedHostKeys = mutableMapOf<String, SshHostKey>()
    private val trustedJumpHostKeys = mutableMapOf<String, SshHostKey>()

    init {
        viewModelScope.launch {
            val workspace = id?.let { repository.getById(it) }
            val settings = workspace?.sshSettings()
            val draft = SshWorkspaceDraft(
                name = workspace?.name ?: "",
                host = settings?.host ?: "",
                port = settings?.port ?: DEFAULT_PORT,
                username = settings?.username ?: "",
                authType = settings?.authType ?: SshAuthType.PASSWORD,
                passwordRef = settings?.passwordRef ?: "",
                privateKeyRef = settings?.privateKeyRef ?: "",
                passphraseRef = settings?.passphraseRef ?: "",
                remoteDir = settings?.remoteDir ?: "",
                platform = settings?.platform ?: SshPlatform.AUTO,
                hostKey = settings?.hostKey,
                jumpHost = settings?.jumpHost ?: "",
                jumpHostKey = settings?.jumpHostKey,
            )
            _state.update {
                it.copy(
                    draft = draft,
                    portInput = draft.port.toString(),
                    knownNames = repository.getAll()
                        .filter { entity -> entity.id != id }
                        .map { entity -> entity.name.trim() }
                        .toSet(),
                    savedHosts = runCatching { sshHostRepository.getAll() }.getOrDefault(emptyList())
                        .map { SavedJumpHost(name = it.name, user = it.user, host = it.host) },
                )
            }
            // 编辑已有主机时，加载出来的密钥就是已信任的，记下来供改回时恢复。
            draft.hostKey?.let { trustedHostKeys[draft.host] = it }
            draft.jumpHostKey?.let { trustedJumpHostKeys[draft.jumpHost] = it }
        }
    }

    fun setName(value: String) = updateDraft { it.copy(name = value) }

    fun setUsername(value: String) = updateDropProbe { it.copy(username = value) }

    fun setHost(value: String) = updateDropProbe {
        // 主机名改回已信任过的值时恢复密钥；换到新主机则清空，重新确认。
        it.copy(host = value, hostKey = trustedHostKeys[value])
    }

    fun setJumpHost(value: String) = updateDropProbe {
        it.copy(jumpHost = value, jumpHostKey = trustedJumpHostKeys[value])
    }

    // 远程目录不影响连接本身，改它不作废已通过的测试（之前所有字段共用丢弃探测的路径，
    // 只改个目录也要完整重测一次）。
    fun setRemoteDir(value: String) = updateDraft { it.copy(remoteDir = value) }

    fun setAuthType(value: SshAuthType) = updateDropProbe { it.copy(authType = value) }

    fun setPasswordRef(value: String) = updateDropProbe { it.copy(passwordRef = value) }

    fun setPrivateKeyRef(value: String) = updateDropProbe { it.copy(privateKeyRef = value) }

    fun setPassphraseRef(value: String) = updateDropProbe { it.copy(passphraseRef = value) }

    fun setPort(value: String) {
        val digits = value.filter(Char::isDigit).take(5)
        _state.update {
            it.copy(
                portInput = digits,
                probeResult = null,
                // Only a host change invalidates the trusted host key; changing the
                // port keeps the same host identity, so the key is preserved.
                draft = it.draft.copy(port = digits.toIntOrNull() ?: it.draft.port),
            )
        }
    }

    /** Which required inputs are still missing or invalid. */
    fun submitBlockers(): Set<SubmitBlocker> {
        val state = _state.value
        val draft = state.draft
        val blockers = mutableSetOf<SubmitBlocker>()
        if (draft.name.isBlank()) blockers += SubmitBlocker.NAME_BLANK
        // 重名只标红字段不够：保存/测试按钮要一起禁用，否则点完才报一条后端英文原文。
        if (draft.name.isNotBlank() && draft.name.trim() in state.knownNames) blockers += SubmitBlocker.NAME_DUPLICATE
        if (draft.host.isBlank()) blockers += SubmitBlocker.HOST_BLANK
        if (draft.username.isBlank()) blockers += SubmitBlocker.USERNAME_BLANK
        if (state.portInput.toIntOrNull()?.let { it in 1..65535 } != true) blockers += SubmitBlocker.PORT_INVALID
        val credentialOk = when (draft.authType) {
            SshAuthType.PASSWORD -> draft.passwordRef.isNotBlank()
            SshAuthType.PRIVATE_KEY -> draft.privateKeyRef.isNotBlank()
        }
        if (!credentialOk) blockers += SubmitBlocker.CREDENTIAL_MISSING
        return blockers
    }

    /** 表单是否可提交：地址、用户、凭证齐备，端口合法 */
    fun canSubmit(): Boolean = submitBlockers().isEmpty()

    fun testConnection() = runProbe(saveAfter = false)

    /** 先测通再保存：远程目录留空时用登录主目录兜底，避免误把整个文件系统当工作区 */
    fun save() {
        val state = _state.value
        // 已测通、或已测连失败时直接保存：失败后点保存再重测一遍只会再报一次错，
        // 改了字段会清掉 probeError，下次保存重新走测连。
        if (state.probeResult != null || state.probeError != null) {
            doSave()
        } else {
            runProbe(saveAfter = true)
        }
    }

    /** 测连失败也要能存：换网络环境后回来直接用，不必再连一次 */
    fun saveAnyway() = doSave()

    fun confirmHostKey() {
        val pending = _state.value.pendingHostKey ?: return
        _state.update {
            it.copy(
                pendingHostKey = null,
                draft = if (pending.jumpHost) {
                    it.draft.copy(jumpHostKey = pending.actual)
                } else {
                    it.draft.copy(hostKey = pending.actual)
                },
            )
        }
        // 刚确认过的密钥记下来：之后主机名改错又改回来时不用重新确认。
        val draft = _state.value.draft
        if (pending.jumpHost) {
            trustedJumpHostKeys[draft.jumpHost] = pending.actual
        } else {
            trustedHostKeys[draft.host] = pending.actual
        }
        runProbe(saveAfter = pending.saveAfter)
    }

    fun dismissHostKey() {
        _state.update { it.copy(pendingHostKey = null, busy = false) }
    }

    fun dismissProbeError() {
        _state.update { it.copy(probeError = null) }
    }

    fun acknowledgeSaved() {
        _state.update { it.copy(saved = false) }
    }

    private fun updateDraft(block: (SshWorkspaceDraft) -> SshWorkspaceDraft) {
        _state.update { it.copy(draft = block(it.draft)) }
    }

    /** Map a probe failure to a classified cause; the page shows the readable reason with the original text in parentheses. */
    private fun probeFailureOf(e: Exception): ProbeFailure {
        val detail = e.message ?: e.javaClass.simpleName
        val lower = detail.lowercase()
        val kind = when {
            "additional verification" in lower -> ProbeFailure.Kind.EXTRA_VERIFICATION
            "auth fail" in lower || "authentication failed" in lower || "auth failed" in lower -> ProbeFailure.Kind.AUTH
            "connection refused" in lower -> ProbeFailure.Kind.REFUSED
            "timed out" in lower || "timeout" in lower -> ProbeFailure.Kind.TIMEOUT
            "unknown host" in lower || "no route to host" in lower || "network is unreachable" in lower -> ProbeFailure.Kind.UNREACHABLE
            else -> ProbeFailure.Kind.UNKNOWN
        }
        return ProbeFailure(kind, detail)
    }

    /**
     * 更新草稿并作废已有的探测结果：改动影响连接或命令行为的字段时用它。
     * 例如平台影响命令包装（shell 探测走 exec，Windows 时经 pwsh），改了要重测。
     */
    fun updateDropProbe(block: (SshWorkspaceDraft) -> SshWorkspaceDraft) {
        _state.update { it.copy(draft = block(it.draft), probeResult = null, probeError = null) }
    }

    private fun runProbe(saveAfter: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, probeError = null, saveError = null) }
            val draft = _state.value.draft
            try {
                val result = repository.probeSsh(id, draft)
                _state.update {
                    it.copy(
                        busy = false,
                        probeResult = result,
                        draft = if (it.draft.remoteDir.isBlank()) it.draft.copy(remoteDir = result.homeDir) else it.draft,
                    )
                }
                if (saveAfter) doSave()
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: SshUnknownHostKeyException) {
                _state.update {
                    it.copy(busy = false, pendingHostKey = PendingHostKey(e.hostKey, null, saveAfter, e.jumpHost))
                }
            } catch (e: SshHostKeyChangedException) {
                _state.update {
                    it.copy(busy = false, pendingHostKey = PendingHostKey(e.actual, e.expected, saveAfter, e.jumpHost))
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, probeError = probeFailureOf(e)) }
            }
        }
    }

    private fun doSave() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, saveError = null) }
            try {
                repository.saveSsh(id, _state.value.draft)
                _state.update { it.copy(busy = false, saved = true) }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, saveError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    companion object {
        private const val DEFAULT_PORT = 22
    }
}
