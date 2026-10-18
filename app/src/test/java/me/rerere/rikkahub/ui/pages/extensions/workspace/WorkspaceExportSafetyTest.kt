package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceExportSafetyTest {
    @Test fun rootfsPathsAreAbsoluteForEachStorageArea() {
        assertEquals("/workspace/docs/a.md", workspaceRootfsAbsolutePath(WorkspaceStorageArea.FILES, "docs/a.md"))
        assertEquals("/etc/os-release", workspaceRootfsAbsolutePath(WorkspaceStorageArea.LINUX, "etc/os-release"))
    }

    @Test fun zipEntryRejectsTraversalAndAbsoluteOrSeparatorNames() {
        assertNull(safeWorkspaceZipEntryName("docs", "../secret", false))
        assertNull(safeWorkspaceZipEntryName("docs", "/absolute", false))
        assertNull(safeWorkspaceZipEntryName("../escape", "ok.txt", false))
        assertEquals("docs/sub/file.txt", safeWorkspaceZipEntryName("docs", "sub/file.txt", false))
        assertEquals("docs/sub/", safeWorkspaceZipEntryName("docs", "sub", true))
    }
}
