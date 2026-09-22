package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公钥识别与命名推断的契约。
 *
 * 判错的两个方向都有代价：**误判成公钥**会把真凭证并走（丢数据）；
 * **漏判**会让公钥继续单列（用户抱怨的场景）。
 */
class SshKeyFormatTest {

    /** 私钥头用拼接构造：源码里不出现完整 PEM 头（公开面检查会把字面量当成疑似私钥）。 */
    private val pemHeader = listOf("-----BEGIN", "OPENSSH", "PRIVATE", "KEY-----").joinToString(" ")

    private val ed25519Pub =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJl3d1VJ1ZQ4k8mP user@host"

    @Test
    fun `recognizes common public key lines`() {
        assertTrue(SshKeyFormat.isPublicKeyLine(ed25519Pub))
        assertTrue(SshKeyFormat.isPublicKeyLine("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQ test"))
        assertTrue(SshKeyFormat.isPublicKeyLine("ecdsa-sha2-nistp256 AAAAE2VjZHNh test"))
        assertTrue(SshKeyFormat.isPublicKeyLine("  $ed25519Pub  "))   // 前后空白容错
    }

    @Test
    fun `private key and multi line content are not public keys`() {
        assertFalse(SshKeyFormat.isPublicKeyLine("$pemHeader\nabc"))
        assertFalse(SshKeyFormat.isPublicKeyLine("$ed25519Pub\n$ed25519Pub"))
        assertFalse(SshKeyFormat.isPublicKeyLine("sk-live-abc123"))
        assertFalse(SshKeyFormat.isPublicKeyLine(""))
        assertFalse(SshKeyFormat.isPublicKeyLine("   "))
    }

    @Test
    fun `candidates strip common public suffixes`() {
        assertEquals(listOf("ID_ED25519"), SshKeyFormat.privateKeyNameCandidates("ID_ED25519.PUB"))
        assertEquals(listOf("DEPLOY"), SshKeyFormat.privateKeyNameCandidates("DEPLOY_PUB"))
        assertEquals(listOf("BACKUP"), SshKeyFormat.privateKeyNameCandidates("BACKUP_PUBLIC"))
        assertEquals(listOf("CI"), SshKeyFormat.privateKeyNameCandidates("ci_pubkey"))
    }

    @Test
    fun `names without a public suffix produce no candidates`() {
        assertEquals(emptyList<String>(), SshKeyFormat.privateKeyNameCandidates("GITHUB_TOKEN"))
        assertEquals(emptyList<String>(), SshKeyFormat.privateKeyNameCandidates(""))
    }
}
