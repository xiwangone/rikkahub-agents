package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * `takeWorkspaceChangeSnapshot` 回归（2026-11-12 快照修复面），走真实仓库链路：
 *
 * - 本地工作区且未指定扫描根时用完整深度（[WorkspaceChangePolicy.MAX_DEPTH]），
 *   深层文件在快照里；
 * - 超时（[WorkspaceChangePolicy.SNAPSHOT_TIMEOUT_MS] = 3 秒）返回空快照并标 `skipped`，
 *   调用方据此跳过 diff；墙钟时间仍被超时 bound 住，不会无限挂。
 *
 * 超时测试用有状态假 DAO 触发：第一次 `getById` 快返实体（供深度判定），之后
 * `getById` 挂起（模拟慢后端），超时在 3 秒后取消。两个超时测试各约 3 秒。
 *
 * 局限：远程分支（`isSsh()` → `REMOTE_ROOT_MAX_DEPTH = 1`）无法在无真 SSH 服务器
 * 的单测里端到端观测——`backendOf` 对 SSH 实体必建真实 `SshWorkspaceBackend`；
 * 该分支的深度语义（`maxDepth = 1` 时 2 层以上不在快照里）由
 * [WorkspaceChangeSnapshotTest.maxDepthOneOnlyScansOneLevel] 覆盖。
 *
 * 取消异常重抛：`takeSnapshot` 的两个挂起点与 `takeWorkspaceChangeSnapshot` 的 `readText`
 * lambda 均已按 `WorkspaceRepositoryHelpers.kt:282` 的既定模式重抛 `CancellationException`
 *（与本批生产修复同批，见说明第 5 节），超时取消能一路抛回 `withTimeoutOrNull`，走到
 * `skipped = true` 的分支；本文件按设计断言 `skipped = true`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class WorkspaceChangeSnapshotRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun localWorkspaceEmptyRootUsesFullDepth() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val baseDir = tempFolder.newFolder("ws-base")
        val harness = newTestWorkspaceRepository(
            baseDir,
            listBackedWorkspaceDao(listOf(LOCAL_ENTITY)),
            context,
        )
        try {
            harness.filesDir.mkdirs()
            File(harness.filesDir, "root.txt").writeText("root")
            File(harness.filesDir, "d1").mkdirs()
            File(harness.filesDir, "d1/a.txt").writeText("a")
            File(harness.filesDir, "d1/d2").mkdirs()
            File(harness.filesDir, "d1/d2/b.txt").writeText("b")
            File(harness.filesDir, "d1/d2/d3").mkdirs()
            File(harness.filesDir, "d1/d2/d3/c.txt").writeText("c")

            val snapshot = takeWorkspaceChangeSnapshot(harness.repository, LOCAL_ID, "")
            assertFalse(snapshot.skipped)
            assertTrue("root.txt" in snapshot.files)
            assertTrue("d1/a.txt" in snapshot.files)
            assertTrue("d1/d2/b.txt" in snapshot.files)
            assertTrue("d1/d2/d3/c.txt" in snapshot.files)
        } finally {
            harness.close()
        }
    }

    @Test
    fun snapshotTimeoutReturnsEmptySnapshotMarkedSkipped() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val baseDir = tempFolder.newFolder("ws-base")
        val harness = newTestWorkspaceRepository(
            baseDir,
            HangingWorkspaceDao(LOCAL_ENTITY),
            context,
        )
        try {
            val start = System.currentTimeMillis()
            val snapshot = takeWorkspaceChangeSnapshot(harness.repository, LOCAL_ID, "")
            val elapsed = System.currentTimeMillis() - start
            assertTrue("snapshot should be skipped on timeout, was: $snapshot", snapshot.skipped)
            assertTrue(snapshot.files.isEmpty())
            assertTrue("snapshot should return within the timeout budget, took ${elapsed}ms", elapsed < 10_000)
        } finally {
            harness.close()
        }
    }

    @Test
    fun snapshotTimeoutIsBoundedInWallClock() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val baseDir = tempFolder.newFolder("ws-base")
        val harness = newTestWorkspaceRepository(
            baseDir,
            HangingWorkspaceDao(LOCAL_ENTITY),
            context,
        )
        try {
            val start = System.currentTimeMillis()
            val snapshot = takeWorkspaceChangeSnapshot(harness.repository, LOCAL_ID, "")
            val elapsed = System.currentTimeMillis() - start
            assertTrue("snapshot should return within the timeout budget, took ${elapsed}ms", elapsed < 10_000)
            assertTrue(snapshot.files.isEmpty())
        } finally {
            harness.close()
        }
    }

    /** 第一次 `getById` 快返实体，之后挂起（模拟慢后端），供超时测试触发。 */
    private class HangingWorkspaceDao(private val entity: WorkspaceEntity) : WorkspaceDAO {
        private val calls = AtomicInteger(0)

        override suspend fun getById(id: String): WorkspaceEntity? {
            return if (calls.incrementAndGet() == 1) {
                entity
            } else {
                awaitCancellation()
            }
        }

        override fun listFlow(): Flow<List<WorkspaceEntity>> = MutableStateFlow(listOf(entity))
        override suspend fun upsert(workspace: WorkspaceEntity) {}
        override suspend fun getAll(): List<WorkspaceEntity> = listOf(entity)
        override fun getAllSync(): List<WorkspaceEntity> = listOf(entity)
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long) = 0
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long) = 0
        override suspend fun setTags(id: String, tags: String, updatedAt: Long) = 0
        override suspend fun deleteById(id: String) = 0
    }

    companion object {
        private const val LOCAL_ID = "local-id"
        private val LOCAL_ENTITY = WorkspaceEntity(
            id = LOCAL_ID,
            name = "Local",
            root = "default",
            createdAt = 1,
            updatedAt = 1,
        )
    }
}
