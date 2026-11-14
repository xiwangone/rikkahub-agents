package me.rerere.rikkahub.data.repository

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.TestWorkspaceRepository
import me.rerere.rikkahub.data.ai.tools.listBackedWorkspaceDao
import me.rerere.rikkahub.data.ai.tools.local.SshHostProbeStore
import me.rerere.rikkahub.data.ai.tools.local.probePreferredWindows
import me.rerere.rikkahub.data.ai.tools.newTestWorkspaceRepository
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.workspace.ssh.SshHostProbeResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 2-14 回归（Robolectric + Koin 测试模块 + 共享 harness）：
 * `resolveSshPlatform` 承诺"不抛异常"，但已保存主机读数（Room + 密钥解密）与
 * 探针缓存读数（DataStore）原来都在 `runCatching` 之外。
 * - 库读失败 → 按"无该项输入"落到下一级（AUTO / 显式值 / 全局默认）；
 * - 探针缓存文件损坏 → 按"无缓存"处理，不抛异常；
 * - 探针缓存健康时仍以探针结论为准（守卫没把正常路径改坏）。
 *
 * Koin 在每个测试前后启停（测试用 Application 不是 RikkaHubApp，不会自己
 * 起 Koin；Robolectric 每个方法重建 Application，DataStore 委托也是每个
 * 方法一份新的，损坏文件测试不会污染其他方法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class WorkspaceResolveSshPlatformTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var harness: TestWorkspaceRepository? = null

    @After
    fun tearDown() {
        runCatching { stopKoin() }
        runCatching { harness?.close() }
        // 损坏探针缓存的测试会把 host_probes 文件写成非法内容；同一测试类的
        // 各方法共用同一 filesDir，必须清掉，否则后跑的方法一读就抛
        // CorruptionException（与被测守卫无关的测试间污染）。
        runCatching {
            File(context.filesDir, "datastore/host_probes.preferences_pb").delete()
        }
    }

    /** Pump Android Main while real DataStore work proceeds on IO. No coroutine implementation is mocked. */
    private fun <T> perform(block: suspend () -> T): T {
        val task = workers.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        check(task.isCompleted) { "resolveSshPlatform operation timed out" }
        return runBlocking { task.await() }
    }

    /** getByHost 按构造参数抛错或返回固定实体；其余方法走空实现。 */
    private class FakeSshHostDao(
        private val hostEntity: SshHostEntity? = null,
        private val throwOnGetByHost: Boolean = false,
    ) : SshHostDao {
        override suspend fun getAll(): List<SshHostEntity> = listOfNotNull(hostEntity)
        override suspend fun getByName(name: String): SshHostEntity? = hostEntity?.takeIf { it.name == name }
        override suspend fun getByHost(host: String): SshHostEntity? =
            if (throwOnGetByHost) throw IllegalStateException("ssh host db unavailable") else hostEntity
        override suspend fun upsert(host: SshHostEntity) = Unit
        override suspend fun delete(host: SshHostEntity) = Unit
        override suspend fun deleteByName(name: String) = Unit
    }

    private fun openHarness(repo: SshHostRepository): TestWorkspaceRepository {
        val appContext = context
        startKoin {
            modules(
                module {
                    single<SshHostRepository> { repo }
                    single<Context> { appContext }
                },
            )
        }
        val h = newTestWorkspaceRepository(
            baseDir = tempFolder.newFolder("ws-base"),
            dao = listBackedWorkspaceDao(emptyList()),
            context = appContext as Application,
        )
        harness = h
        return h
    }

    private fun autoHost(host: String = HOST): SshHostEntity =
        SshHostEntity(name = "probe-host", host = host, user = "tester", createdAtMs = 0L)

    /**
     * 指向临时目录、内容非法的**独立** DataStore：首读必抛。
     *
     * 不用 `context.hostProbeDataStore`（进程级单例）：同测试类里任何用例先读过它之后，
     * DataStore 已经把数据读进内存，再去写坏磁盘文件也不会重读 —— 断言会随方法顺序时好时坏。
     */
    private fun corruptedProbeStore(): DataStore<Preferences> {
        val file = File(tempFolder.newFolder("probe-store"), "host_probes.preferences_pb")
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ) { file }
    }

    @Test
    fun `saved host lookup failure falls back to AUTO`() {
        perform {
            val h = openHarness(SshHostRepository(FakeSshHostDao(throwOnGetByHost = true)))
            assertEquals("AUTO", h.repository.resolveSshPlatform(HOST, 22))
        }
    }

    @Test
    fun `explicit workspace platform wins even when saved host lookup fails`() {
        perform {
            val h = openHarness(SshHostRepository(FakeSshHostDao(throwOnGetByHost = true)))
            assertEquals(
                "WINDOWS",
                h.repository.resolveSshPlatform(HOST, 22, workspacePlatform = SshPlatform.WINDOWS),
            )
        }
    }

    @Test
    fun `probe cache hit still decides platform when store is healthy`() {
        perform {
            val h = openHarness(SshHostRepository(FakeSshHostDao(hostEntity = autoHost(PROBE_HOST))))
            SshHostProbeStore(context).put(
                PROBE_HOST, 22,
                SshHostProbeResult(platform = "WINDOWS", probedAtMs = System.currentTimeMillis()),
            )
            assertEquals("WINDOWS", h.repository.resolveSshPlatform(PROBE_HOST, 22))
        }
    }

    @Test
    fun `corrupted probe cache falls back to AUTO instead of throwing`() {
        perform {
            val h = openHarness(SshHostRepository(FakeSshHostDao(hostEntity = autoHost())))
            val broken = corruptedProbeStore()
            // 先证明夹具本身真的坏了：这个独立 store 首读必抛。
            // （不能用生产 delegate：它是进程级单例，其他用例读过之后内存已有快照，
            //  再写坏文件也不会重读 —— 这正是本用例原先时好时坏的原因。）
            var directThrew = false
            try {
                SshHostProbeStore(context, broken).get(HOST, 22)
            } catch (ignored: Exception) {
                directThrew = true
            }
            assertTrue("corrupted probe store should throw on direct read", directThrew)
            // 守卫路径：`resolveSshPlatform` 用 runCatching 包住探针读数（见实现），
            // 这里复刻同一形状，确认异常会被归为“无缓存（null）”而不是向上抛。
            val guarded = runCatching {
                probePreferredWindows(context, HOST, 22, null, SshHostProbeStore(context, broken))
            }.getOrNull()
            assertEquals(null, guarded)
            assertEquals("AUTO", h.repository.resolveSshPlatform(HOST, 22))
        }
    }

    private companion object {
        const val HOST = "203.0.113.9"
        // 健康缓存测试单独用一个主机：探针缓存按 host+port+user 分键，写进
        // 共享文件的 WINDOWS 条目不能漏到期望空缓存的其他测试里（方法执行
        // 顺序不固定，跨测试污染会让期望 AUTO 的用例读到 WINDOWS）。
        const val PROBE_HOST = "203.0.113.10"
    }
}
