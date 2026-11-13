package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2：`isVaultHostKeyChange` 只认"密钥已变更"的确切报错。
 *
 * 旧版是裸子串（"HostKey" / "host key"），会把 "HostKey algorithm … not supported"
 * 这类与变更无关的失败也判成 host_key_changed，信封 hint 还会教模型去调
 * ssh_forget_host_key——没变过的 key 被忘掉，等于帮攻击者清信任。
 */
class VaultHostKeyChangeTest {

    @Test fun `jsch changed-key message is recognised`() {
        assertTrue(isVaultHostKeyChange("HostKey has been changed: example.com"))
        assertTrue(isVaultHostKeyChange("hostkey has been changed"))
    }

    @Test fun `openssh changed-identification message is recognised`() {
        assertTrue(isVaultHostKeyChange("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!"))
    }

    @Test fun `unrelated hostkey errors are not host key changes`() {
        assertFalse(isVaultHostKeyChange("HostKey algorithm 'ssh-rsa' not supported"))
        assertFalse(isVaultHostKeyChange("no hostkey alg"))
        assertFalse(isVaultHostKeyChange("unknown host key type"))
    }

    @Test fun `generic failures and null are not host key changes`() {
        assertFalse(isVaultHostKeyChange("Connection timed out"))
        assertFalse(isVaultHostKeyChange(null))
        assertFalse(isVaultHostKeyChange(""))
    }
}
