package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planWorkspaceFolderExport]（#40 G4 文件夹导出）的纯函数覆盖。
 * 从 stale-tests 的 WorkspaceDetailVMTest 拆出；树形视图（flattenWorkspaceTree）的用例仍留在
 * stale-tests —— 本地文件页没有可展开的树形视图。
 */
class WorkspaceFolderExportPlanTest {

    private fun file(name: String, path: String) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = false,
        sizeBytes = 1,
        updatedAt = 0L,
    )

    private fun dir(name: String, path: String) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = true,
        sizeBytes = 0,
        updatedAt = 0L,
    )

    @Test
    fun `export plan enumerates parents before children with correct parent paths`() {
        val listing = mapOf(
            "notes" to listOf(dir("sub", "notes/sub"), file("a.txt", "notes/a.txt")),
            "notes/sub" to listOf(file("b.txt", "notes/sub/b.txt")),
        )

        val plan = planWorkspaceFolderExport("notes", listing)

        assertEquals(
            listOf(
                Triple("notes/sub", "notes", true),
                Triple("notes/sub/b.txt", "notes/sub", false),
                Triple("notes/a.txt", "notes", false),
            ),
            plan.map { Triple(it.sourcePath, it.parentPath, it.isDirectory) },
        )
    }

    @Test
    fun `export plan for an empty folder is empty`() {
        val plan = planWorkspaceFolderExport("empty", mapOf("empty" to emptyList()))
        assertEquals(emptyList<Any>(), plan)
    }

    @Test
    fun `a missing listing level is skipped instead of failing the whole export`() {
        val listing = mapOf("root" to listOf(dir("gone", "root/gone"), file("a.txt", "root/a.txt")))

        val plan = planWorkspaceFolderExport("root", listing)

        assertEquals(listOf("root/gone", "root/a.txt"), plan.map { it.sourcePath })
    }
}
