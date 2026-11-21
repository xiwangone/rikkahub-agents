package me.rerere.workspace.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跳板认证类型按实际凭证材料推导：无私钥、无口令时是 none 认证（token 用户名中继场景），
 * 不能默认落到 PASSWORD——否则 newJSch 会报“SSH password is required”，
 * 无凭证的跳板机被误判为缺口令。
 */
class SshAuthTypeTest {

    @Test
    fun `no credential material derives NONE`() {
        assertEquals(SshAuthType.NONE, authTypeForCredentials(null, null))
        assertEquals(SshAuthType.NONE, authTypeForCredentials("", ""))
        assertEquals(SshAuthType.NONE, authTypeForCredentials("   ", "  "))
    }

    @Test
    fun `private key takes precedence over password`() {
        assertEquals(SshAuthType.PRIVATE_KEY, authTypeForCredentials("key", null))
        assertEquals(SshAuthType.PRIVATE_KEY, authTypeForCredentials("key", "secret"))
        assertEquals(SshAuthType.PRIVATE_KEY, authTypeForCredentials("  key  ", ""))
    }

    @Test
    fun `password alone derives PASSWORD`() {
        assertEquals(SshAuthType.PASSWORD, authTypeForCredentials(null, "secret"))
        assertEquals(SshAuthType.PASSWORD, authTypeForCredentials("", "secret"))
    }
}
