package me.rerere.rikkahub.data.preferences

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 工具授权持久化（[ToolApprovalPreferences]）：授权 / 撤销 / 全部撤销。
 *
 * 前缀规则本身（[isWorkspaceToolName]、[migrateWorkspaceToolsFrom]）已有
 * [ToolApprovalPreferencesWorkspaceFilterTest] 覆盖纯函数部分；这里测落盘行为：
 * DataStore delegate 在顶层（`tool_approval.preferences_pb`），Robolectric 下可直接构造。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ToolApprovalPreferencesTest {

    private val context: Application by lazy {
        RuntimeEnvironment.getApplication()
    }

    private val prefs: ToolApprovalPreferences by lazy { ToolApprovalPreferences(context) }

    @Before
    fun setUp() = runBlocking {
        prefs.revokeAll()
        prefs.setYolo(false)
    }

    @Test
    fun `grant then revoke removes only that tool`() = runBlocking {
        prefs.grantAlways("read_file")
        prefs.grantAlways("open_url")
        assertEquals(setOf("read_file", "open_url"), prefs.current())

        prefs.revoke("read_file")
        assertEquals(setOf("open_url"), prefs.current())
    }

    @Test
    fun `revokeAll clears every grant`() = runBlocking {
        prefs.grantAlways("read_file")
        prefs.grantAlways("open_url")

        prefs.revokeAll()
        assertTrue(prefs.current().isEmpty())
    }

    @Test
    fun `workspace tool grant is refused`() = runBlocking {
        prefs.grantAlways("workspace_shell")
        assertTrue(prefs.current().isEmpty())
    }

    @Test
    fun `revoking an unknown tool is a no-op`() = runBlocking {
        prefs.grantAlways("read_file")

        prefs.revoke("open_url")
        assertEquals(setOf("read_file"), prefs.current())
    }

    @Test
    fun `yolo flag defaults false and persists`() = runBlocking {
        assertFalse(prefs.currentYolo())
        prefs.setYolo(true)
        assertTrue(prefs.currentYolo())
        prefs.setYolo(false)
        assertFalse(prefs.currentYolo())
    }
}
