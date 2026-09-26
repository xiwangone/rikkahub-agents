package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dpkg package count from status file`() {
        val linuxDir = tmp.newFolder()
        File(linuxDir, "var/lib/dpkg").mkdirs()
        File(linuxDir, "var/lib/dpkg/status").writeText(
            "Package: bash\nStatus: install ok installed\n\nPackage: coreutils\nStatus: install ok installed\n",
        )
        assertEquals(2, countPackages(linuxDir))
    }

    @Test
    fun `alpine package count from apk installed db`() {
        val linuxDir = tmp.newFolder()
        File(linuxDir, "lib/apk/db").mkdirs()
        File(linuxDir, "lib/apk/db/installed").writeText("P:alpine-baselayout\nV:3.4.0\n\nP:busybox\nV:1.36\n")
        assertEquals(2, countPackages(linuxDir))
    }

    @Test
    fun `missing package db returns null`() {
        assertNull(countPackages(tmp.newFolder()))
    }

    @Test
    fun `disk usage sums files recursively including subdirs`() {
        val dir = tmp.newFolder()
        File(dir, "a.txt").writeText("12345")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.bin").writeText("1234567890")
        val stats = collectWorkspaceStats(dir, dir, kernel = "5.4.0")
        assertEquals(15L, stats.rootBytes)
        assertNull(stats.packageCount)
        assertEquals("5.4.0", stats.kernel)
    }

    @Test
    fun `missing workspace dir yields nulls and kernel passthrough`() {
        val stats = collectWorkspaceStats(File("/nonexistent-ws"), File("/nonexistent-linux"), kernel = null)
        assertNull(stats.rootBytes)
        assertNull(stats.packageCount)
        assertNull(stats.kernel)
    }
}
