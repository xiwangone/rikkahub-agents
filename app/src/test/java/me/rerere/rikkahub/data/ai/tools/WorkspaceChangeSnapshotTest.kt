package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorkspaceChangeDiff.takeSnapshot` 回归（2026-11-12 快照修复面）：
 *
 * - 软链目录不入队（`!entry.isSymlink` 才下钻），避免沿软链循环下钻；
 * - `visited` 去重：同一目录只扫一次，防重复入队与软链循环；
 * - `SKIPPED_DIRS` 含 `.nvm/.bun/.local/.npm/.config/.android`；
 * - `maxDepth` 生效（远程根扫描用 `REMOTE_ROOT_MAX_DEPTH = 1` 的语义）。
 *
 * 纯函数测试：用内存假树直测 `takeSnapshot`，不依赖真文件系统与真 SSH。
 * `takeWorkspaceChangeSnapshot` 的「远程且 cwd 为空 → 深度 1」判定分支本身
 * （`getById()?.isSsh()`）需要真 SSH 后端，无法在无服务器的单测里端到端观测，
 * 故只测 `takeSnapshot(maxDepth = REMOTE_ROOT_MAX_DEPTH)` 的语义；本地分支
 * （本地工作区空 cwd 用 `MAX_DEPTH`）见 [WorkspaceChangeSnapshotRepositoryTest]。
 */
class WorkspaceChangeSnapshotTest {

    @Test
    fun symlinkDirectoryIsNotDescended() = runBlocking {
        val tree = FakeTree(
            dirs = mapOf(
                "" to listOf(file("a.txt"), dir("linkdir", isSymlink = true), dir("realdir")),
                "linkdir" to listOf(file("linkdir/secret.txt")),
                "realdir" to listOf(file("realdir/b.txt")),
            ),
        )
        val snapshot = WorkspaceChangeDiff.takeSnapshot(
            listFiles = tree::listFiles,
            readText = tree::readText,
        )
        assertTrue("a.txt" in snapshot.files)
        assertTrue("realdir/b.txt" in snapshot.files)
        assertTrue("linkdir/secret.txt" !in snapshot.files)
        assertTrue("linkdir" !in tree.listedPaths)
        assertTrue("realdir" in tree.listedPaths)
        assertFalse(snapshot.skipped)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun visitedPreventsDoubleScanOfSameDirectory() = runBlocking {
        val tree = FakeTree(
            dirs = mapOf(
                "" to listOf(dir("a"), dir("b")),
                "a" to listOf(dir("b"), file("a/x.txt")),
                "b" to listOf(file("b/y.txt")),
            ),
        )
        val snapshot = WorkspaceChangeDiff.takeSnapshot(
            listFiles = tree::listFiles,
            readText = tree::readText,
        )
        assertEquals(1, tree.listedPaths.count { it == "b" })
        assertTrue("a/x.txt" in snapshot.files)
        assertTrue("b/y.txt" in snapshot.files)
    }

    @Test
    fun visitedPreventsInfiniteLoopOnCycle() = runBlocking {
        // "a" 里有一条指回根目录 "" 的目录（软链循环的内存替身）：必须终止且根只扫一次。
        val tree = FakeTree(
            dirs = mapOf(
                "" to listOf(dir("a"), file("root.txt")),
                "a" to listOf(dir(""), file("a/x.txt")),
            ),
        )
        val snapshot = WorkspaceChangeDiff.takeSnapshot(
            listFiles = tree::listFiles,
            readText = tree::readText,
        )
        assertEquals(1, tree.listedPaths.count { it == "" })
        assertTrue("root.txt" in snapshot.files)
        assertTrue("a/x.txt" in snapshot.files)
        assertEquals(1, snapshot.files.keys.count { it == "root.txt" })
    }

    @Test
    fun skippedDirsAreNotDescended() = runBlocking {
        val skipped = listOf(".nvm", ".bun", ".local", ".npm", ".config", ".android", ".git", "node_modules")
        val dirs = mutableMapOf<String, List<WorkspaceFileEntry>>()
        val rootEntries = mutableListOf<WorkspaceFileEntry>()
        for (name in skipped) {
            rootEntries += dir(name)
            dirs[name] = listOf(file("$name/x.txt"))
        }
        rootEntries += dir("src")
        dirs["src"] = listOf(file("src/main.txt"))
        dirs[""] = rootEntries
        val tree = FakeTree(dirs = dirs)
        val snapshot = WorkspaceChangeDiff.takeSnapshot(
            listFiles = tree::listFiles,
            readText = tree::readText,
        )
        assertTrue(
            WorkspaceChangePolicy.SKIPPED_DIRS.containsAll(
                setOf(".nvm", ".bun", ".local", ".npm", ".config", ".android"),
            ),
        )
        for (name in skipped) {
            assertTrue("$name should not be listed", name !in tree.listedPaths)
            assertTrue("$name/x.txt" !in snapshot.files)
        }
        assertTrue("src" in tree.listedPaths)
        assertTrue("src/main.txt" in snapshot.files)
    }

    @Test
    fun maxDepthOneOnlyScansOneLevel() = runBlocking {
        val dirs = mapOf(
            "" to listOf(dir("sub"), file("root.txt")),
            "sub" to listOf(dir("sub/deep"), file("sub/mid.txt")),
            "sub/deep" to listOf(file("sub/deep/deep.txt")),
        )
        val shallowTree = FakeTree(dirs = dirs)
        val shallow = WorkspaceChangeDiff.takeSnapshot(
            listFiles = shallowTree::listFiles,
            readText = shallowTree::readText,
            maxDepth = WorkspaceChangePolicy.REMOTE_ROOT_MAX_DEPTH,
        )
        assertTrue("root.txt" in shallow.files)
        assertTrue("sub/mid.txt" in shallow.files)
        assertTrue("sub/deep/deep.txt" !in shallow.files)
        assertTrue("sub/deep" !in shallowTree.listedPaths)

        val deepTree = FakeTree(dirs = dirs)
        val deep = WorkspaceChangeDiff.takeSnapshot(
            listFiles = deepTree::listFiles,
            readText = deepTree::readText,
            maxDepth = WorkspaceChangePolicy.MAX_DEPTH,
        )
        assertTrue("sub/deep/deep.txt" in deep.files)
    }

    @Test
    fun textFileContentsAreCachedAndBinaryFilesAreNot() = runBlocking {
        val tree = FakeTree(
            dirs = mapOf("" to listOf(file("a.txt"), file("b.bin"))),
            texts = mapOf("a.txt" to "hello"),
        )
        val snapshot = WorkspaceChangeDiff.takeSnapshot(
            listFiles = tree::listFiles,
            readText = tree::readText,
        )
        assertEquals("hello", snapshot.contents["a.txt"])
        assertTrue("b.bin" !in snapshot.contents)
        assertTrue("a.txt" in snapshot.files)
        assertTrue("b.bin" in snapshot.files)
        assertFalse(snapshot.truncated)
        assertFalse(snapshot.skipped)
    }

    private class FakeTree(
        private val dirs: Map<String, List<WorkspaceFileEntry>>,
        private val texts: Map<String, String> = emptyMap(),
    ) {
        val listedPaths = mutableListOf<String>()

        suspend fun listFiles(path: String): List<WorkspaceFileEntry> {
            listedPaths += path
            return dirs[path].orEmpty()
        }

        suspend fun readText(path: String): String? = texts[path]
    }

    private companion object {
        fun file(path: String, size: Long = 10, updatedAt: Long = 1) =
            WorkspaceFileEntry(
                path = path,
                name = path.substringAfterLast('/'),
                isDirectory = false,
                sizeBytes = size,
                updatedAt = updatedAt,
            )

        fun dir(path: String, isSymlink: Boolean = false) =
            WorkspaceFileEntry(
                path = path,
                name = path.substringAfterLast('/'),
                isDirectory = true,
                sizeBytes = 0,
                updatedAt = 1,
                isSymlink = isSymlink,
            )
    }
}
