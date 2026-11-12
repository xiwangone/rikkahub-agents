package me.rerere.rikkahub.data.agentrun

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.log.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 批 5 (级别与 tag) 级-P1-2: [AgentRunRepository] 与 [AgentRunBootRecovery]
 * 的日志级别。
 *
 * - 本次操作失败 (open/setStatus/markTerminal/markAllProcessLost
 *   抛错) 记 e; runRecovery 外层的 e 行按现有生产代码不可达 (仓库内部已
 *   吞掉 DAO 错, 见 `runRecovery with dao failure logs one i line and
 *   returns 0`); 预期内状态 (非终态 markTerminal 是幂等 no-op、翻转 stranded
 *   行是扫荡的预期结果) 记 i; 超 cap 的 metadata 丢弃是降级继续, 记 w。
 *
 * [AppLog] 的调用包在 `logSafe` 里, 纯 JVM 下 `android.util.Log` 未 mock
 * 会直接抛、日志根本进不了 buffer, 所以这组断言必须走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentRunLoggingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 全新安装状态: 清空开关文件, 再按默认值重载。
        context
            .getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AppLog.clear()
        AppLog.startIfEnabled(context)
    }

    @After
    fun tearDown() {
        AppLog.clear()
        AppLog.setEnabled(context, true)
        AppLog.setMinLevel(context, 'I')
    }

    /** 每种 DAO 调用都抛错的桩, 驱动各条失败日志。 */
    private class ThrowingAgentRunDao : AgentRunDao {
        override suspend fun insert(row: AgentRun): Unit = error("boom-test-dao")

        override suspend fun update(row: AgentRun): Unit = error("boom-test-dao")

        override suspend fun getById(id: String): AgentRun? = error("boom-test-dao")

        override suspend fun findStranded(beforeMs: Long): List<AgentRun> = error("boom-test-dao")

        override suspend fun findInFlight(): List<AgentRun> = error("boom-test-dao")

        override fun observeRecent(limit: Int): Flow<List<AgentRun>> = error("boom-test-dao")

        override suspend fun getRecent(limit: Int): List<AgentRun> = error("boom-test-dao")

        override suspend fun getByDomainId(kind: String, domainId: String, limit: Int): List<AgentRun> =
            error("boom-test-dao")

        override suspend fun getChildren(parentRunId: String): List<AgentRun> = error("boom-test-dao")

        override suspend fun markProcessLost(ids: List<String>, now: Long): Int = error("boom-test-dao")

        override suspend fun count(): Int = error("boom-test-dao")

        override suspend fun countByStatusSince(status: String, sinceMs: Long): Int = error("boom-test-dao")

        override suspend fun purgeOldest(cap: Int): Unit = error("boom-test-dao")
    }

    private fun runRow(id: String, status: AgentRunStatus, updatedAtMs: Long): AgentRun =
        AgentRun(
            id = id,
            kind = AgentRunKind.Cron.wire,
            domainId = "domain-$id",
            parentRunId = null,
            status = status.name,
            createdAtMs = updatedAtMs,
            updatedAtMs = updatedAtMs,
            startedAtMs = if (status == AgentRunStatus.queued) null else updatedAtMs,
            finishedAtMs = if (status.isTerminal) updatedAtMs else null,
            lastError = null,
            metadataJson = null,
        )

    private fun entries(level: Char, prefix: String) =
        AppLog.getLogs().filter { it.level == level && it.message.startsWith(prefix) }

    @Test
    fun `open failure logs one e line and still returns an id`() = runBlocking {
        val repo = AgentRunRepository(ThrowingAgentRunDao())
        val id = repo.open(AgentRunKind.Cron, "domain-open-fail")

        assertTrue(id.isNotBlank())
        assertEquals(1, entries('E', "open(").size)
    }

    @Test
    fun `setStatus failure logs one e line`() = runBlocking {
        val repo = AgentRunRepository(ThrowingAgentRunDao())
        repo.setStatus("missing-id", AgentRunStatus.succeeded)

        assertEquals(1, entries('E', "setStatus(").size)
    }

    @Test
    fun `markTerminal with a non-terminal status logs one i line`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        val id = repo.open(AgentRunKind.Cron, "domain-markterminal")

        repo.markTerminal(id, AgentRunStatus.queued)

        val lines = entries('I', "markTerminal(")
        assertEquals(1, lines.size)
        assertTrue(lines[0].message.contains("non-terminal"))
    }

    @Test
    fun `markTerminal failure logs one e line`() = runBlocking {
        val repo = AgentRunRepository(ThrowingAgentRunDao())
        repo.markTerminal("missing-id", AgentRunStatus.succeeded)

        assertEquals(1, entries('E', "markTerminal(").size)
    }

    @Test
    fun `markAllProcessLost failure logs one e line`() = runBlocking {
        val repo = AgentRunRepository(ThrowingAgentRunDao())
        repo.markAllProcessLost(listOf("x"))

        assertEquals(1, entries('E', "markAllProcessLost failed").size)
    }

    @Test
    fun `oversize metadata logs one w line and the row still opens`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        val big = buildJsonObject { put("blob", "x".repeat(16 * 1024)) }

        val id = repo.open(AgentRunKind.Cron, "domain-bigmeta", metadata = big)

        assertTrue(dao.snapshot().any { it.id == id })
        assertEquals(1, entries('W', "metadata_json over").size)
    }

    @Test
    fun `runRecovery flipping stranded rows logs one i line`() = runBlocking {
        val dao = FakeAgentRunDao()
        val repo = AgentRunRepository(dao)
        val old = System.currentTimeMillis() - 60L * 60 * 1000 // 1 小时前, 超过 30 分钟阈值
        dao.insert(runRow("run-stranded-1", AgentRunStatus.running, old))

        val notified = mutableListOf<List<AgentRun>>()
        val recovery = AgentRunBootRecovery(repo) { notified += it }
        val flipped = recovery.runRecovery()

        assertEquals(1, flipped)
        assertEquals(1, notified.size)
        assertEquals(1, entries('I', "runRecovery: flipped").size)
    }

    /**
     * DAO 失败时 runRecovery 的真实行为: [AgentRunRepository.getStranded] 内部
     * runCatching 吞掉 DAO 错并返回空列表, runRecovery 见空列表记 1 条 i 行
     * "runRecovery: no stranded runs" 并返回 0; 外层 onFailure 的 e 行
     * "runRecovery failed" 按现有生产代码不可达 (getStranded /
     * markAllProcessLost / notifyStranded 都在内部吞掉异常)。该死分支是
     * 后续设计决定 (删掉还是让 getStranded 透传), 不在本批做; 本例只断言
     * 真实行为。
     */
    @Test
    fun `runRecovery with dao failure logs one i line and returns 0`() = runBlocking {
        val recovery = AgentRunBootRecovery(AgentRunRepository(ThrowingAgentRunDao()))

        assertEquals(0, recovery.runRecovery())
        assertEquals(1, entries('I', "runRecovery: no stranded runs").size)
        assertEquals(0, entries('E', "runRecovery failed").size)
    }
}
