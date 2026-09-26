package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 可选挂载（/sdcard，开关控制）解析与访问留痕回调：
 * 关 → 走 rootfs 分支；开 → 解析到挂载源；固定挂载不触发回调。
 */
class WorkspaceManagerMountsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(
        enabled: Set<String>,
        onAccess: (String, String) -> Unit = { _, _ -> },
    ): WorkspaceManager =
        WorkspaceManager(
            baseDir = tmp.newFolder(),
            bindMounts = listOf(
                WorkspaceBindMount(source = tmp.newFolder(), target = "/skills"),
            ),
            optionalMounts =
                OptionalMounts(
                    mounts = mapOf(
                        "sdcard" to WorkspaceBindMount(source = File("/storage/emulated/0"), target = "/sdcard"),
                    ),
                    enabled = { enabled },
                    onAccess = onAccess,
                ),
        )

    @Test
    fun `optional mount disabled - sdcard path falls back to rootfs branch`() {
        val loc = manager(enabled = emptySet()).resolveRootfsPath("w", "/sdcard/DCIM/x.jpg")
        assertEquals("sdcard/DCIM/x.jpg", loc.relativePath)
        assertTrue(loc.rootDir.path.endsWith("linux"))
    }

    @Test
    fun `optional mount enabled - resolves to mount source with relative path`() {
        val loc = manager(enabled = setOf("sdcard")).resolveRootfsPath("w", "/sdcard/DCIM/x.jpg")
        assertEquals(File("/storage/emulated/0"), loc.rootDir)
        assertEquals("DCIM/x.jpg", loc.relativePath)
    }

    @Test
    fun `optional mount enabled - exact target resolves to source root`() {
        val loc = manager(enabled = setOf("sdcard")).resolveRootfsPath("w", "/sdcard")
        assertEquals(File("/storage/emulated/0"), loc.rootDir)
        assertEquals("", loc.relativePath)
    }

    @Test
    fun `access callback fires only for optional mounts`() {
        var logged: String? = null
        val mgr =
            manager(enabled = setOf("sdcard"), onAccess = { target, path -> logged = "$target:$path" })
        mgr.resolveRootfsPath("w", "/skills/a.txt")
        assertNull("fixed mounts must not trigger the audit callback", logged)
        mgr.resolveRootfsPath("w", "/sdcard/a.jpg")
        assertEquals("sdcard:/sdcard/a.jpg", logged)
    }

    @Test
    fun `fixed mounts resolve regardless of switch`() {
        val loc = manager(enabled = emptySet()).resolveRootfsPath("w", "/skills/a.txt")
        assertEquals("a.txt", loc.relativePath)
        assertTrue(loc.rootDir.path.contains("skills"))
    }
}
