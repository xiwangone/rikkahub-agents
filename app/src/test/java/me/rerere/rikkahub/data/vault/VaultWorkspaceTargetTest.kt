package me.rerere.rikkahub.data.vault

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.TestWorkspaceRepository
import me.rerere.rikkahub.data.ai.tools.listBackedWorkspaceDao
import me.rerere.rikkahub.data.ai.tools.newTestWorkspaceRepository
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1-4 导出缺省目标回归（Robolectric，真实 WorkspaceRepository + 内存 DAO）：
 * - 缺省不再是“列表第一个”：第一个是 SSH 工作区时，缺省落在第一个本地工作区；
 * - 只有 SSH 工作区时缺省直接拒绝，不静默回退到远程；
 * - 显式指向 SSH 工作区时，缺省不带 confirm 拒绝、带 confirm=true 才放行；
 * - 显式指向本地工作区不需要 confirm。
 *
 * 注意：`resolveVaultWorkspace` 是 suspend（它读工作区列表是挂起调用），测试用
 * runBlocking 包起来；不要把它改成同步调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class VaultWorkspaceTargetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var harness: TestWorkspaceRepository? = null

    @After
    fun tearDown() {
        harness?.close()
        harness = null
    }

    private fun entity(id: String, name: String, ssh: Boolean) =
        WorkspaceEntity(
            id = id,
            name = name,
            root = "/ws/$id",
            createdAt = 0L,
            updatedAt = 0L,
            type = if (ssh) WorkspaceType.SSH.name else WorkspaceType.LOCAL.name,
        )

    private fun openHarness(vararg entities: WorkspaceEntity): TestWorkspaceRepository {
        val repo = newTestWorkspaceRepository(
            tempFolder.newFolder("ws-base"),
            listBackedWorkspaceDao(entities.toList()),
            RuntimeEnvironment.getApplication(),
        )
        harness = repo
        return repo
    }

    @Test
    fun `default skips leading ssh workspace and picks first local`() = runBlocking {
        val repo = openHarness(
            entity("ssh-1", "remote", ssh = true),
            entity("local-1", "home", ssh = false),
            entity("local-2", "second", ssh = false),
        )
        val target = resolveVaultWorkspace(repo.repository, null).getOrThrow()
        // 核心回归断言：旧实现这里会返回列表第一个 remote（SSH），明文直接落远端。
        assertEquals("local-1", target.id)
        assertEquals("home", target.name)
        assertTrue(target.isFallback)
        assertFalse(target.isSsh)
    }

    @Test
    fun `default fails when no local workspace exists`() = runBlocking {
        val repo = openHarness(entity("ssh-1", "remote", ssh = true))
        val failure = resolveVaultWorkspace(repo.repository, null).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("No local workspace"))
    }

    @Test
    fun `default fails when no workspace at all`() = runBlocking {
        val repo = openHarness()
        val failure = resolveVaultWorkspace(repo.repository, null).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("No workspace"))
    }

    @Test
    fun `explicit ssh workspace needs confirm`() = runBlocking {
        val repo = openHarness(
            entity("local-1", "home", ssh = false),
            entity("ssh-1", "remote", ssh = true),
        )
        val target = resolveVaultWorkspace(repo.repository, "remote").getOrThrow()
        assertEquals("ssh-1", target.id)
        assertFalse(target.isFallback)
        assertTrue(target.isSsh)
        // 缺省不确认 → 拒绝；显式 confirm=true → 放行。
        assertNotNull(remoteTargetNeedsConfirm(target, confirm = false))
        assertNull(remoteTargetNeedsConfirm(target, confirm = true))
    }

    @Test
    fun `explicit local workspace needs no confirm`() = runBlocking {
        val repo = openHarness(
            entity("ssh-1", "remote", ssh = true),
            entity("local-1", "home", ssh = false),
        )
        val target = resolveVaultWorkspace(repo.repository, "home").getOrThrow()
        assertFalse(target.isSsh)
        assertNull(remoteTargetNeedsConfirm(target, confirm = false))
    }

    @Test
    fun `explicit unknown workspace fails`() = runBlocking {
        val repo = openHarness(entity("local-1", "home", ssh = false))
        val failure = resolveVaultWorkspace(repo.repository, "no-such").exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("not found"))
    }
}
