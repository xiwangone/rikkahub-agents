package me.rerere.rikkahub.data.ai.tools.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshSftpToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun `ssh local path resolves tool_outputs inside app files dir`() {
        val fakeFilesDir = tmp.newFolder("app_files")
        val outDir = File(fakeFilesDir, "tool_outputs").apply { mkdirs() }
        val target = File(outDir, "run1.txt").apply { writeText("x") }
        val resolved = resolveSshLocalPath("/tool_outputs/run1.txt") { fakeFilesDir }
        assertEquals(target.canonicalPath, resolved)
        assertTrue(File(resolved).isFile)
    }

    @Test fun `ssh local path resolves upload inside app files dir`() {
        val fakeFilesDir = tmp.newFolder("app_files")
        val uploadDir = File(fakeFilesDir, "upload").apply { mkdirs() }
        val target = File(uploadDir, "report.pdf").apply { writeText("x") }
        val resolved = resolveSshLocalPath("/upload/report.pdf") { fakeFilesDir }
        assertEquals(target.canonicalPath, resolved)
        assertTrue(File(resolved).isFile)
    }

    @Test fun `ssh local path traversal falls back to raw path`() {
        val fakeFilesDir = tmp.newFolder("app_files")
        File(fakeFilesDir, "tool_outputs").mkdirs()
        val resolved = resolveSshLocalPath("/tool_outputs/../secrets.txt") { fakeFilesDir }
        assertEquals("/tool_outputs/../secrets.txt", resolved)
    }

    @Test fun `ssh local path passes plain absolute paths through`() {
        val fakeFilesDir = tmp.newFolder("app_files")
        val resolved = resolveSshLocalPath("/sdcard/Download/a.txt") { fakeFilesDir }
        assertEquals("/sdcard/Download/a.txt", resolved)
    }
}
