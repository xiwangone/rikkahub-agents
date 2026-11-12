package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.WorkspaceManager
import me.rerere.rikkahub.data.workspace.WorkspaceMountSwitch
import java.io.File

/**
 * 共享测试 harness：用假 DAO + 真 [WorkspaceManager] 搭一个能跑的 [WorkspaceRepository]。
 *
 * 搭法照抄 [WorkspaceApplyEditsAtomicityTest]：不 fake Repository 本身，走真实生产链路
 * （DAO 只是内存假实现；`listFiles`/`readText` 走真实宿主文件直读，不依赖真 SSH）。
 * 名字解析测试与快照仓库测试共用；用完后调用 [TestWorkspaceRepository.close] 释放
 * DataStore / AppScope / SSH 连接池。
 */
internal class TestWorkspaceRepository(
    val repository: WorkspaceRepository,
    val filesDir: File,
    private val dataStoreScope: CoroutineScope,
    private val appScope: AppScope,
    private val sshPool: SshConnectionPool,
) {
    fun close() {
        runCatching { sshPool.close() }
        runCatching { appScope.cancel() }
        runCatching { dataStoreScope.cancel() }
    }
}

internal fun newTestWorkspaceRepository(
    baseDir: File,
    dao: WorkspaceDAO,
    context: Application,
    root: String = "default",
): TestWorkspaceRepository {
    val dataStoreScope = CoroutineScope(Dispatchers.Default)
    val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
        context.preferencesDataStoreFile("test-settings")
    }
    val appScope = AppScope()
    val sshPool = SshConnectionPool()
    val manager = WorkspaceManager(baseDir)
    val repository = WorkspaceRepository(
        dao = dao,
        manager = manager,
        rootfsInstaller = RootfsInstaller(manager),
        settingsStore = SettingsStore(context, appScope, dataStore),
        mountSwitch = WorkspaceMountSwitch(),
        sshPool = sshPool,
        vaultRepository = CredentialVaultRepository(
            dao = object : VaultCredentialDao {
                override suspend fun getAll(): List<VaultCredentialEntity> = emptyList()
                override suspend fun getByName(name: String): VaultCredentialEntity? = null
                override suspend fun upsert(entry: VaultCredentialEntity): Long = 0L
                override suspend fun insertIfAbsent(entry: VaultCredentialEntity): Long = 0L
                override suspend fun update(entry: VaultCredentialEntity) = Unit
                override suspend fun updateAll(entries: List<VaultCredentialEntity>) = Unit
                override suspend fun delete(entry: VaultCredentialEntity) = Unit
                override suspend fun count(): Int = 0
                override suspend fun clearAll() = Unit
            },
            auditDao = object : VaultAuditLogDao {
                override suspend fun getRecent(limit: Int): List<VaultAuditLogEntity> = emptyList()
                override suspend fun queryAudit(
                    credential: String,
                    action: String,
                    sinceMs: Long,
                    limit: Int,
                ): List<VaultAuditLogEntity> = emptyList()

                override suspend fun insert(entry: VaultAuditLogEntity) = Unit
                override suspend fun deleteOlderThan(cutoffMs: Long, protectedActions: List<String>) = Unit
                override suspend fun trimTo(keep: Int, protectedActions: List<String>) = Unit
                override suspend fun count(): Int = 0
                override suspend fun findRollupTarget(
                    credentialName: String,
                    caller: String,
                    action: String,
                    conversationId: String?,
                    sinceMs: Long,
                ): VaultAuditLogEntity? = null

                override suspend fun bumpCount(id: Long, nowMs: Long) = Unit
                override suspend fun clearAll() = Unit
            },
            vaultPreferences = VaultPreferences(context),
        ),
    )
    return TestWorkspaceRepository(
        repository = repository,
        filesDir = File(File(baseDir, root), "files"),
        dataStoreScope = dataStoreScope,
        appScope = appScope,
        sshPool = sshPool,
    )
}

/** 由固定实体列表支撑的内存 DAO；`getById`/`getAll` 走真实生产链路的查询语义。 */
internal fun listBackedWorkspaceDao(entities: List<WorkspaceEntity>): WorkspaceDAO {
    val store = entities.toMutableList()
    return object : WorkspaceDAO {
        override fun listFlow(): Flow<List<WorkspaceEntity>> = MutableStateFlow(store.toList())
        override suspend fun getById(id: String): WorkspaceEntity? = store.firstOrNull { it.id == id }
        override suspend fun upsert(workspace: WorkspaceEntity) {
            store.removeAll { it.id == workspace.id }
            store.add(workspace)
        }

        override suspend fun getAll(): List<WorkspaceEntity> = store.toList()
        override fun getAllSync(): List<WorkspaceEntity> = store.toList()
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long) = 0
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long) = 0
        override suspend fun setTags(id: String, tags: String, updatedAt: Long) = 0
        override suspend fun deleteById(id: String): Int =
            if (store.removeAll { it.id == id }) 1 else 0
    }
}
