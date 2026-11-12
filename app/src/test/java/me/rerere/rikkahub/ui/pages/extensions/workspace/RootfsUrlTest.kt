package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rootfs 归档链接校验（[isValidRootfsUrl]/[isRootfsArchiveName]）：安装前先拦
 * 格式不对的链接——只判非空时手滑多打个字符也能点安装，要等几分钟下载失败
 * 才知道错了。
 */
class RootfsUrlTest {

    @Test
    fun `valid archive urls pass`() {
        assertTrue(isValidRootfsUrl("https://example.com/debian.tar.gz"))
        assertTrue(isValidRootfsUrl("http://example.com/rootfs.tar.xz"))
        assertTrue(isValidRootfsUrl("https://example.com/rootfs.txz"))
        assertTrue(isValidRootfsUrl("https://example.com/ROOTFS.TAR.GZ"))
        assertTrue(isValidRootfsUrl("  https://example.com/rootfs.tar.gz  "))
    }

    @Test
    fun `non-http scheme fails`() {
        assertFalse(isValidRootfsUrl("ftp://example.com/rootfs.tar.gz"))
        assertFalse(isValidRootfsUrl("file:///rootfs.tar.gz"))
        assertFalse(isValidRootfsUrl("example.com/rootfs.tar.gz"))
    }

    @Test
    fun `wrong suffix fails`() {
        assertFalse(isValidRootfsUrl("https://example.com/rootfs.zip"))
        assertFalse(isValidRootfsUrl("https://example.com/rootfs.tar"))
        assertFalse(isValidRootfsUrl("https://example.com/rootfs"))
        assertFalse(isValidRootfsUrl("https://example.com/"))
        assertFalse(isValidRootfsUrl(""))
        assertFalse(isValidRootfsUrl("   "))
    }

    @Test
    fun `archive name check`() {
        assertTrue(isRootfsArchiveName("debian.tar.gz"))
        assertTrue(isRootfsArchiveName("rootfs.tar.xz"))
        assertTrue(isRootfsArchiveName("rootfs.txz"))
        assertFalse(isRootfsArchiveName("rootfs.zip"))
        assertFalse(isRootfsArchiveName("rootfs.tar"))
        assertFalse(isRootfsArchiveName("rootfs"))
    }
}
