package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 工作区名字解析回归（2026-11-12 名字 bug 的修复面）：
 *
 * - `workspace_shell` 是唯一没接 [resolveTargetWorkspaceId] 的工具，之前把 `workspace`
 *   参数原样当 id 传给 `executeCommand`，传名字时 `dao.getById(名字)` 拿不到就报错；
 *   3.0.5 起 schema 描述改成 "Prefer the name" 后该 bug 开始暴露。
 * - 名字解析本身走 [me.rerere.rikkahub.data.repository.WorkspaceRepository.resolveWorkspaceId]：
 *   id 精确命中 → 名字大小写不敏感唯一命中；重名/未知/空白返回 null，由调用方报错并列候选。
 *
 * 不 fake Repository 本身，走真实生产链路（假 DAO + 真 WorkspaceManager，见
 * [WorkspaceRepositoryTestHarness]）。工具级测试只覆盖「未知名字在快照/执行之前就报错」
 * 这一接线点；名字解析到正确 id 后的实际命令执行需要真 shell/rootfs，不在这里测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class WorkspaceNameResolutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: TestWorkspaceRepository
    private val repository get() = harness.repository

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("ws-base")
        val context: Application = RuntimeEnvironment.getApplication()
        harness = newTestWorkspaceRepository(baseDir, listBackedWorkspaceDao(ENTITIES), context)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun resolveWorkspaceId_returnsIdOnExactIdHit() = runBlocking {
        assertEquals("ws-a-id", repository.resolveWorkspaceId("ws-a-id"))
        assertEquals("ws-b-id", repository.resolveWorkspaceId("ws-b-id"))
    }

    @Test
    fun resolveWorkspaceId_resolvesNameCaseInsensitively() = runBlocking {
        assertEquals("ws-a-id", repository.resolveWorkspaceId("alpha"))
        assertEquals("ws-a-id", repository.resolveWorkspaceId("ALPHA"))
        assertEquals("ws-a-id", repository.resolveWorkspaceId("AlPhA"))
        assertEquals("ws-b-id", repository.resolveWorkspaceId("beta"))
    }

    @Test
    fun resolveWorkspaceId_returnsNullForDuplicateName() = runBlocking {
        assertNull(repository.resolveWorkspaceId("Shared"))
        assertNull(repository.resolveWorkspaceId("shared"))
        assertNull(repository.resolveWorkspaceId("SHARED"))
    }

    @Test
    fun resolveWorkspaceId_returnsNullForUnknownName() = runBlocking {
        assertNull(repository.resolveWorkspaceId("Nope"))
        assertNull(repository.resolveWorkspaceId("no-such-id"))
    }

    @Test
    fun resolveWorkspaceId_returnsNullForBlankQuery() = runBlocking {
        assertNull(repository.resolveWorkspaceId(""))
        assertNull(repository.resolveWorkspaceId("   "))
    }

    @Test
    fun resolveWorkspaceId_prefersIdOverName() = runBlocking {
        // "ws-a-id" 同时是 ws-a 的 id 与 ws-other 的名字：id 精确命中优先。
        assertEquals("ws-a-id", repository.resolveWorkspaceId("ws-a-id"))
    }

    @Test
    fun resolveTargetWorkspaceId_returnsBoundIdWhenWorkspaceOmitted() = runBlocking {
        assertEquals(BOUND_ID, resolveTargetWorkspaceId(repository, buildJsonObject {}, BOUND_ID))
    }

    @Test
    fun resolveTargetWorkspaceId_returnsBoundIdWhenWorkspaceBlank() = runBlocking {
        val args = buildJsonObject { put("workspace", "   ") }
        assertEquals(BOUND_ID, resolveTargetWorkspaceId(repository, args, BOUND_ID))
    }

    @Test
    fun resolveTargetWorkspaceId_returnsBoundIdWhenWorkspaceEqualsBoundId() = runBlocking {
        val args = buildJsonObject { put("workspace", BOUND_ID) }
        assertEquals(BOUND_ID, resolveTargetWorkspaceId(repository, args, BOUND_ID))
    }

    @Test
    fun resolveTargetWorkspaceId_acceptsExistingId() = runBlocking {
        val args = buildJsonObject { put("workspace", "ws-b-id") }
        assertEquals("ws-b-id", resolveTargetWorkspaceId(repository, args, BOUND_ID))
    }

    @Test
    fun resolveTargetWorkspaceId_resolvesNameToId() = runBlocking {
        val args = buildJsonObject { put("workspace", "Alpha") }
        assertEquals("ws-a-id", resolveTargetWorkspaceId(repository, args, BOUND_ID))
    }

    @Test
    fun resolveTargetWorkspaceId_resolvesNameCaseInsensitively() = runBlocking {
        val args = buildJsonObject { put("workspace", "aLpHa") }
        assertEquals("ws-a-id", resolveTargetWorkspaceId(repository, args, BOUND_ID))
    }

    @Test
    fun resolveTargetWorkspaceId_errorsWithCandidatesForUnknownName() = runBlocking {
        val args = buildJsonObject { put("workspace", "NoSuch") }
        assertResolveErrorContains(
            { resolveTargetWorkspaceId(repository, args, BOUND_ID) },
            "unknown workspace id or name",
            "NoSuch",
            "Alpha (ws-a-id)",
            "Beta (ws-b-id)",
        )
    }

    @Test
    fun resolveTargetWorkspaceId_errorsWithCandidatesForUnknownId() = runBlocking {
        val args = buildJsonObject { put("workspace", "no-such-id") }
        assertResolveErrorContains(
            { resolveTargetWorkspaceId(repository, args, BOUND_ID) },
            "unknown workspace id or name",
            "no-such-id",
            "Alpha (ws-a-id)",
        )
    }

    @Test
    fun resolveTargetWorkspaceId_errorsAsAmbiguousForDuplicateName() = runBlocking {
        val args = buildJsonObject { put("workspace", "Shared") }
        assertResolveErrorContains(
            { resolveTargetWorkspaceId(repository, args, BOUND_ID) },
            "ambiguous workspace name",
            "Shared",
            "2 workspaces",
            "Shared (dup-1)",
            "Shared (dup-2)",
        )
    }

    @Test
    fun shellTool_unknownWorkspaceNameErrorsWithCandidatesBeforeExecution() = runBlocking {
        val tools = createWorkspaceTools(BOUND_ID, repository)
        val shell = tools.firstOrNull { it.name == "workspace_shell" }
            ?: error("workspace_shell tool not found")
        val args: JsonObject = buildJsonObject {
            put("command", "echo hi")
            put("workspace", "NoSuchName")
        }
        assertResolveErrorContains(
            { shell.execute(args) },
            "unknown workspace id or name",
            "NoSuchName",
            "Alpha (ws-a-id)",
        )
    }

    private suspend fun assertResolveErrorContains(
        block: suspend () -> Any?,
        vararg parts: String,
    ) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            for (part in parts) {
                assertTrue(
                    "error message should contain <$part>, was: <$message>",
                    message.contains(part),
                )
            }
        }
    }

    companion object {
        private const val BOUND_ID = "bound-id"

        private val ENTITIES = listOf(
            WorkspaceEntity(id = BOUND_ID, name = "Bound", root = "bound", createdAt = 1, updatedAt = 1),
            WorkspaceEntity(id = "ws-a-id", name = "Alpha", root = "a", createdAt = 1, updatedAt = 1),
            WorkspaceEntity(id = "ws-b-id", name = "Beta", root = "b", createdAt = 1, updatedAt = 1),
            WorkspaceEntity(id = "dup-1", name = "Shared", root = "dup1", createdAt = 1, updatedAt = 1),
            WorkspaceEntity(id = "dup-2", name = "Shared", root = "dup2", createdAt = 1, updatedAt = 1),
            // 名字与 ws-a 的 id 相同：验证 id 精确命中优先于名字命中。
            WorkspaceEntity(id = "ws-other", name = "ws-a-id", root = "other", createdAt = 1, updatedAt = 1),
        )
    }
}
