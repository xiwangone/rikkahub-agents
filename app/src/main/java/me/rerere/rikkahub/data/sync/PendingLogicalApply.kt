package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import java.io.File

@Serializable
private data class PendingLogicalPayload(
    val version: Int = 1,
    val entries: Map<String, String> = emptyMap(),
)

/**
 * 逻辑段挂起写回。
 *
 * 为什么不能恢复时立刻写：数据库恢复是把新库**挂起**到下次启动前安装（`PendingDatabaseRestore`），
 * 若此刻往当前库里写凭证，下次启动会被挂起的新库覆盖 —— 这正是「迁移包恢复后凭证全空」的根因。
 *
 * 因此逻辑段（凭证/提供商/SSH/定时任务）只**暂存**到这里，等 Application 安装完新库、Room 打开后，
 * 再用本机 Keystore 重新加密写回。暂存的也是 Keystore 密文，明文不落盘。
 */
object PendingLogicalApply {
    private const val TAG = "PendingLogicalApply"
    private const val ROOT = "pending-logical-apply"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 包内可应用的段名（MANIFEST 等元数据不参加暂存）。 */
    private val sectionNames: Set<String> =
        setOf(
            "${MigrationPackage.DIR}/${MigrationPackage.CREDENTIALS_NAME}",
            "${MigrationPackage.DIR}/${MigrationPackage.PROVIDERS_NAME}",
            "${MigrationPackage.DIR}/${MigrationPackage.SSH_HOSTS_NAME}",
            "${MigrationPackage.DIR}/${MigrationPackage.SCHEDULED_JOBS_NAME}",
        )

    /** 恢复阶段调用：只暂存，不写库。 */
    fun stage(context: Context, entries: Map<String, String>) {
        val payloadEntries = entries.filterKeys { it in sectionNames }
        if (payloadEntries.isEmpty()) return
        val dir = File(context.noBackupFilesDir, ROOT)
        val target = File(dir, "payload.enc")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create logical restore staging" }
        // 连续两次导入时覆盖旧暂存（新包为准），而不是抛错 —— 抛错会留下「库已挂起、逻辑段未暂存」的半状态
        if (target.exists()) {
            AppLog.w(TAG, "stage: replacing previously staged payload")
            target.delete()
        }
        val encrypted =
            ProviderCredentialCipher.encrypt(
                json.encodeToString(PendingLogicalPayload(entries = payloadEntries)),
            )
        val temp = File(dir, "payload.tmp")
        temp.writeText(encrypted)
        check(temp.renameTo(target)) { "Cannot stage logical restore" }
        AppLog.i(TAG, "stage: ${payloadEntries.size} section(s)")
    }

    /** 启动写回结果：区分「没有待写回」与「写回失败（暂存仍在，下次重试）」。 */
    enum class Outcome { NONE, APPLIED, FAILED }

    /** Application 启动时调用（Room 已打开）：重新加密写回，成功后清理暂存。 */
    suspend fun applyPending(
        context: Context,
        settingsStore: SettingsStore,
        vaultRepository: CredentialVaultRepository,
        sshHostRepository: SshHostRepository,
        scheduledJobRepository: ScheduledJobRepository,
    ): Outcome {
        val dir = File(context.noBackupFilesDir, ROOT)
        val file = File(dir, "payload.enc")
        if (!file.isFile) return Outcome.NONE
        val payload =
            runCatching { ProviderCredentialCipher.decrypt(file.readText()) }
                .getOrNull()
                ?.let { plain -> runCatching { json.decodeFromString<PendingLogicalPayload>(plain) }.getOrNull() }
        if (payload == null) {
            AppLog.w(TAG, "applyPending: cannot decrypt staged payload (Keystore lost?); discarding")
            dir.deleteRecursively()
            return Outcome.FAILED
        }
        val result =
            runCatching {
                apply(payload, settingsStore, vaultRepository, sshHostRepository, scheduledJobRepository)
            }
        return if (result.isSuccess) {
            // 只有全部写回成功才删暂存；失败时保留，下次启动再试（各段都是幂等 upsert）
            dir.deleteRecursively()
            AppLog.i(TAG, "applyPending: applied ${payload.entries.size} section(s)")
            Outcome.APPLIED
        } else {
            AppLog.w(
                TAG,
                "applyPending: failed, keeping staged payload for next launch",
                result.exceptionOrNull(),
            )
            Outcome.FAILED
        }
    }

    /**
     * 逐段写回；单段失败只记日志并让整体返回 false（暂存保留以便重试），
     * 不让一条坏数据把其余三段的有效数据一起丢掉。
     */
    private suspend fun apply(
        payload: PendingLogicalPayload,
        settingsStore: SettingsStore,
        vaultRepository: CredentialVaultRepository,
        sshHostRepository: SshHostRepository,
        scheduledJobRepository: ScheduledJobRepository,
    ): Boolean {
        var allOk = true

        // best-effort：单段失败不应中断整包恢复（其余段照常应用），所以这里**有意**宽 catch。
        // CancellationException 已由上一个 catch 重抛，不会把取消吞掉。
        @Suppress("TooGenericExceptionCaught")
        suspend fun section(name: String, block: suspend (String) -> Unit) {
            val raw = payload.entries["${MigrationPackage.DIR}/$name"] ?: return
            try {
                block(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allOk = false
                AppLog.w(TAG, "apply section $name failed", e)
            }
        }

        section(MigrationPackage.CREDENTIALS_NAME) { raw ->
            val credentials = MigrationPackage.parseCredentials(raw) ?: error("invalid credentials.json")
            val outcome = vaultRepository.importEntries(credentials.map { it.toParsedEntry() })
            AppLog.i(TAG, "credentials: imported=${outcome.imported} skipped=${outcome.skipped}")
        }

        section(MigrationPackage.PROVIDERS_NAME) { raw ->
            val providers = json.decodeFromString<List<ProviderSetting>>(raw)
            settingsStore.update { current -> current.copy(providers = providers) }
            AppLog.i(TAG, "providers: restored ${providers.size}")
        }

        section(MigrationPackage.SSH_HOSTS_NAME) { raw ->
            val hosts = json.decodeFromString<List<SshHostEntity>>(raw)
            hosts.forEach { sshHostRepository.upsert(it) }
            AppLog.i(TAG, "ssh hosts: restored ${hosts.size}")
        }

        section(MigrationPackage.SCHEDULED_JOBS_NAME) { raw ->
            val jobs = json.decodeFromString<List<ScheduledJobEntity>>(raw)
            jobs.forEach { scheduledJobRepository.upsert(it) }
            AppLog.i(TAG, "scheduled jobs: restored ${jobs.size}")
        }

        return allOk
    }
}
