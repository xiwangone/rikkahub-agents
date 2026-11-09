package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.VaultAuditLogDao
import me.rerere.rikkahub.data.db.dao.VaultCredentialDao
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity
import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultPreferences
import me.rerere.rikkahub.data.workspace.WorkspaceMountSwitch
import me.rerere.workspace.HostShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellRunner
import me.rerere.workspace.ssh.SshConnectionPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `workspace_apply_edits` 的原子性回归：文档承诺“任一处失败不写任何文件”，但写盘循环
 * 在中途写失败时直接返回，不回滚已写的文件，留半套改动。
 *
 * 可测性说明：WorkspaceRepository 是 final 类、方法非 open，测试依赖里也没有 mock 框架，
 * 所以不 fake Repository 本身，而是走真实生产链路到底——真实 WorkspaceRepository +
 * 真实 WorkspaceManager/LocalWorkspaceBackend，只把 WorkspaceManager 构造器提供的
 * WorkspaceShellRunner 缝注入一个替身：除指定失败路径外，把写盘脚本里的 /workspace/
 * 改写成临时 filesDir 后交给宿主 sh 真正执行（mkdir/cat/stat 都真实跑），指定路径则
 * 返回 exit 1 模拟写盘失败。读路径本来就是宿主文件直读，不经过 shell。
 * 因此断言的是真实 runApplyEdits 写盘循环的行为，不是替身的行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class WorkspaceApplyEditsAtomicityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var dataStoreScope: CoroutineScope? = null
    private var appScope: AppScope? = null
    private var sshPool: SshConnectionPool? = null

    @After
    fun tearDown() {
        runCatching { sshPool?.close() }
        runCatching { appScope?.cancel() }
        runCatching { dataStoreScope?.cancel() }
        // NB: @Config(application = Application::class) above is load-bearing:
        // Robolectric recreates the Application before every test method, and
        // RikkaHubApp.onCreate calls startKoin — with the real Application the
        // second method would die with KoinApplicationAlreadyStartedException
        // (and stopKoin() in tearDown is no good either: onTerminate needs Koin
        // alive). Plain Application avoids Koin entirely; this test's production
        // path (runApplyEdits) never touches Koin.
    }

    @Test
    fun `second file write failure leaves first file untouched`() = runBlocking {
        val harness = openHarness(failPath = PATH_B)

        val result = harness.tool().execute(applyEditsArgs())
        val text = (result.single() as UIMessagePart.Text).text

        assertTrue("expected a write-failure error, got: $text", text.contains("write failed"))
        // 原子性：先写成功的 a.txt 必须被回滚（或根本没写），不能留半套改动。
        assertEquals("hello old\n", harness.fileA.readText())
        assertEquals("world old\n", harness.fileB.readText())
    }

    @Test
    fun `all writes succeed when no failure injected`() = runBlocking {
        // 对照组：证明替身链路本身确实能写盘，排除原子性测试“因写不进去而空过”。
        val harness = openHarness(failPath = null)

        val result = harness.tool().execute(applyEditsArgs())
        val text = (result.single() as UIMessagePart.Text).text

        assertTrue("expected success JSON, got: $text", text.contains("\"fileCount\":2"))
        assertEquals("hello new\n", harness.fileA.readText())
        assertEquals("world new\n", harness.fileB.readText())
    }

    private fun applyEditsArgs() = buildJsonObject {
        put("files", buildJsonArray {
            addJsonObject {
                put("path", PATH_A)
                put("edits", buildJsonArray {
                    addJsonObject { put("old_text", "hello old"); put("new_text", "hello new") }
                })
            }
            addJsonObject {
                put("path", PATH_B)
                put("edits", buildJsonArray {
                    addJsonObject { put("old_text", "world old"); put("new_text", "world new") }
                })
            }
        })
    }

    private fun openHarness(failPath: String?): Harness {
        val baseDir = tempFolder.newFolder("ws-base")
        val entity = WorkspaceEntity(
            id = WORKSPACE_ID,
            name = "test",
            root = ROOT,
            createdAt = 0L,
            updatedAt = 0L,
        )
        val dao = object : WorkspaceDAO {
            override fun listFlow(): Flow<List<WorkspaceEntity>> = flowOf(listOf(entity))
            override suspend fun getById(id: String): WorkspaceEntity? =
                entity.takeIf { id == WORKSPACE_ID }

            override suspend fun upsert(workspace: WorkspaceEntity) = Unit
            override suspend fun getAll(): List<WorkspaceEntity> = listOf(entity)
            override fun getAllSync(): List<WorkspaceEntity> = listOf(entity)
            override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int = 0
            override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int = 0
            override suspend fun setTags(id: String, tags: String, updatedAt: Long): Int = 0
            override suspend fun deleteById(id: String): Int = 0
        }
        // 与 WorkspaceManager.filesDir(ROOT) 同一路：baseDir/<root>/files。
        val filesDir = File(File(baseDir, ROOT), "files").apply { mkdirs() }
        val manager = WorkspaceManager(baseDir, shellRunner = ScriptRunner(filesDir, failPath))

        val context: Application = RuntimeEnvironment.getApplication()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStoreDir = tempFolder.newFolder("datastore")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = dataStoreScope!!,
            produceFile = { File(dataStoreDir, "settings.preferences_pb") },
        )
        appScope = AppScope()
        val settingsStore = SettingsStore(context, appScope!!, dataStore)
        sshPool = SshConnectionPool()
        val repository = WorkspaceRepository(
            dao = dao,
            manager = manager,
            rootfsInstaller = RootfsInstaller(manager),
            settingsStore = settingsStore,
            mountSwitch = WorkspaceMountSwitch(),
            sshPool = sshPool!!,
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
        val fileA = File(filesDir, "a.txt").apply { writeText("hello old\n") }
        val fileB = File(filesDir, "b.txt").apply { writeText("world old\n") }
        return Harness(repository, fileA, fileB)
    }

    private class Harness(
        private val repository: WorkspaceRepository,
        val fileA: File,
        val fileB: File,
    ) {
        fun tool() = createApplyEditsTool(
            workspaceId = WORKSPACE_ID,
            layout = WorkspaceLayout(
                workspaceDir = WorkspaceManager.ROOTFS_WORKSPACE_DIR,
                writableRoots = listOf(WorkspaceManager.ROOTFS_WORKSPACE_DIR),
            ),
            needsApproval = { false },
            workspaceRepository = repository,
        )
    }

    /**
     * 写盘脚本的替身 shell：LocalWorkspaceBackend.writeAbsoluteText 是把
     * `mkdir -p / cat > <path> / stat` 脚本交给 WorkspaceShellRunner 执行。
     * [failPath] 命中的写盘返回 exit 1；其余把 rootfs 绝对路径改写成宿主 filesDir
     * 后交给宿主 sh 真正执行，文件是真落盘的。
     */
    private class ScriptRunner(
        private val filesDir: File,
        private val failPath: String?,
    ) : WorkspaceShellRunner {
        private val host = HostShellRunner()

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            val target = CAT_TARGET.find(context.command)?.groupValues?.get(1)
            // writeAbsoluteText 先写同目录临时文件再改名到位：cat 的目标是
            // "<path>.tmp-<uuid>" 而非 <path> 本身，按 failPath 的临时文件前缀匹配。
            if (target != null && failPath != null &&
                (target == failPath || target.startsWith("$failPath.tmp-"))
            ) {
                return WorkspaceCommandResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "simulated write failure for $target",
                )
            }
            val rewritten = context.command.replace(
                "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/",
                filesDir.absolutePath + "/",
            )
            return host.execute(context.copy(command = rewritten))
        }

        override fun start(context: WorkspaceShellContext): Process =
            throw UnsupportedOperationException("background shell is not used by this test")

        companion object {
            private val CAT_TARGET = Regex("""cat > '([^']+)'""")
        }
    }

    companion object {
        private const val WORKSPACE_ID = "ws-1"
        private const val ROOT = "default"
        private val PATH_A = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/a.txt"
        private val PATH_B = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/b.txt"
    }
}
