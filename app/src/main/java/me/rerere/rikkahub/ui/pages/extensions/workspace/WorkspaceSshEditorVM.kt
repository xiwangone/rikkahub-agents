package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
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
        HOST_BLANK,
        USERNAME_BLANK,
        PORT_INVALID,
        CREDENTIAL_MISSING,
    }

    data class State(
        val draft: SshWorkspaceDraft = SshWorkspaceDraft(name = "", host = "", username = "", remoteDir = ""),
        val portInput: String = "22",
        val knownNames: Set<String> = emptySet(),
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
                )
            }
        }
    }

    fun setName(value: String) = updateDraft { it.copy(name = value) }

    fun setUsername(value: String) = updateDropProbe { it.copy(username = value) }

    fun setHost(value: String) = updateDropProbe { it.copy(host = value, hostKey = null) }

    fun setJumpHost(value: String) = updateDropProbe { it.copy(jumpHost = value, jumpHostKey = null) }

    fun setRemoteDir(value: String) = updateDropProbe { it.copy(remoteDir = value) }

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
        if (_state.value.probeResult != null) doSave() else runProbe(saveAfter = true)
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

    private fun updateDropProbe(block: (SshWorkspaceDraft) -> SshWorkspaceDraft) {
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
