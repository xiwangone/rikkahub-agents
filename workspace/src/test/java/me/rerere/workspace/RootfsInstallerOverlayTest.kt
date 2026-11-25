package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsInstallerOverlayTest {

    @Test
    fun `updateFromFile overwrites read-only files and symlinks while preserving config`() {
        val baseDir = Files.createTempDirectory("rootfs_overlay_test").toFile()
        try {
            val manager = WorkspaceManager(baseDir)
            val root = "ws1"
            manager.ensureWorkspace(root)
            val linuxDir = manager.linuxDir(root)

            // Seed existing rootfs with /bin/sh (makes hasRootfs == true)
            File(linuxDir, "bin").mkdirs()
            File(linuxDir, "bin/sh").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true, false)
            }

            // Seed read-only file (0444), read-only parent dir, dangling symlink, and preserved config
            val etcDir = File(linuxDir, "etc/apt").apply { mkdirs() }
            val sourcesList = File(etcDir, "sources.list").apply { writeText("deb http://custom.mirror/ubuntu noble main\n") }
            val shadow = File(linuxDir, "etc/shadow").apply {
                writeText("old-shadow\n")
                setWritable(false, false)
            }
            val roDir = File(linuxDir, "etc/rodir").apply { mkdirs() }
            val roInner = File(roDir, "item.txt").apply {
                writeText("old\n")
                setWritable(false, false)
            }
            roDir.setWritable(false, false)
            Files.createSymbolicLink(File(linuxDir, "etc/dangling").toPath(), File("missing_target").toPath())

            // Build a tar.gz archive updating shadow, rodir/item.txt, dangling, and shipping default sources.list
            val archive = File(baseDir, "update.tar.gz")
            val tarBytes = buildTar(
                listOf(
                    TarEntry("etc/shadow", "new-shadow\n".toByteArray(), 0b100_000_000),
                    TarEntry("etc/rodir/item.txt", "new\n".toByteArray(), 0b110_100_100),
                    TarEntry("etc/dangling", "now-a-file\n".toByteArray(), 0b110_100_100),
                    TarEntry("etc/apt/sources.list", "deb http://archive.ubuntu.com/ubuntu noble main\n".toByteArray(), 0b110_100_100),
                ),
            )
            archive.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gz -> gz.write(tarBytes) }
            }

            val installer = RootfsInstaller(manager)
            installer.updateFromFile(root, archive)

            assertEquals("new-shadow\n", shadow.readText())
            assertEquals("new\n", roInner.readText())
            assertTrue(File(linuxDir, "etc/dangling").isFile)
            assertEquals("now-a-file\n", File(linuxDir, "etc/dangling").readText())
            // Preserved user mirror config must not be overwritten
            assertEquals("deb http://custom.mirror/ubuntu noble main\n", sourcesList.readText())
        } finally {
            makeAllWritable(baseDir)
            baseDir.deleteRecursively()
        }
    }

    private fun makeAllWritable(dir: File) {
        dir.walkTopDown().forEach { runCatching { it.setWritable(true) } }
    }

    private data class TarEntry(val name: String, val data: ByteArray, val mode: Int)

    private fun buildTar(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val header = ByteArray(512)
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)
            writeOctal(header, 100, 8, e.mode)
            writeOctal(header, 108, 8, 0)
            writeOctal(header, 116, 8, 0)
            writeOctal(header, 124, 12, e.data.size)
            writeOctal(header, 136, 12, 1_700_000_000)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            System.arraycopy("ustar".toByteArray(), 0, header, 257, 5)
            val sum = header.sumOf { it.toInt() and 0xff }
            writeOctal(header, 148, 7, sum)
            header[155] = 0
            out.write(header)
            out.write(e.data)
            val pad = (512 - (e.data.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun writeOctal(buf: ByteArray, offset: Int, len: Int, value: Int) {
        val s = value.toString(8).padStart(len - 1, '0')
        System.arraycopy(s.toByteArray(Charsets.US_ASCII), 0, buf, offset, len - 1)
        buf[offset + len - 1] = 0
    }
}
