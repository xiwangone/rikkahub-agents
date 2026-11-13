package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2 目的地绑定 + 类型校验契约测试（纯函数，不碰库、不碰 Android）。
 *
 * 三条阴性断言都在这里：目的地不匹配拒绝、类型混用拒绝、无界面默认拒绝
 * （无界面那条在 [McpAlwaysAllowExclusionTest]——NO_ALWAYS_ALLOW 集合与
 * 自动审批守卫由 `ToolApprovalDefaults.isAutoApprovable` 钉住）。
 */
class CredentialDestinationTest {

    private fun entry(type: String = "", metaJson: String = "", publicKey: String = "") =
        VaultCredentialEntity(
            name = "TEST_CRED",
            type = type,
            metaJson = metaJson,
            valueEncrypted = "enc",
            publicKey = publicKey,
        )

    // 私钥形态的值（不写 PEM 标记字面量：公开面检查会拦；值内容在本测试里只是不透明字符串，
    // 真实私钥结构的推断路径由基线 CredentialTypeTest 覆盖）。
    private val sshKeyValue = "ssh-private-key-value"

    // ── 目的地绑定：SSH ──────────────────────────────────────────────

    @Test fun `ssh unbound credential allows any host`() {
        val reason = CredentialBinding.check(
            entry(type = CredentialType.SSH_KEY),
            sshKeyValue,
            CredentialUse.SSH_KEY,
            CredentialDestination.Ssh("any.example.com", 22, "root"),
        )
        assertNull(reason)
    }

    @Test fun `ssh bound host matches and other host is rejected`() {
        val bound = entry(
            type = CredentialType.SSH_KEY,
            metaJson = """{"endpoint":"db.example.com"}""",
        )
        assertNull(
            CredentialBinding.check(
                bound, sshKeyValue, CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("db.example.com", 22, "root"),
            ),
        )
        val reason = CredentialBinding.check(
            bound, sshKeyValue, CredentialUse.SSH_KEY,
            CredentialDestination.Ssh("evil.example.com", 22, "root"),
        )
        assertTrue(reason!!.contains("该凭证未授权用于此目的地"))
    }

    @Test fun `ssh endpoint port and user constrain the match`() {
        val bound = entry(
            type = CredentialType.SSH_KEY,
            metaJson = """{"endpoint":"deploy@db.example.com:2222"}""",
        )
        assertNull(
            CredentialBinding.check(
                bound, sshKeyValue, CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("db.example.com", 2222, "deploy"),
            ),
        )
        // 端口不对
        assertTrue(
            CredentialBinding.check(
                bound, sshKeyValue, CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("db.example.com", 22, "deploy"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
        // 用户不对
        assertTrue(
            CredentialBinding.check(
                bound, sshKeyValue, CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("db.example.com", 2222, "root"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
    }

    @Test fun `ssh endpoint accepts ssh scheme prefix and comma list, host is case-insensitive`() {
        val bound = entry(
            type = CredentialType.SSH_KEY,
            metaJson = """{"endpoint":"ssh://a.example.com, b.example.com"}""",
        )
        assertNull(
            CredentialBinding.check(
                bound, sshKeyValue, CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("B.EXAMPLE.COM", 22, "root"),
            ),
        )
        assertTrue(
            CredentialBinding.check(
                bound, sshKeyValue, CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("c.example.com", 22, "root"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
    }

    // ── 目的地绑定：HTTP ─────────────────────────────────────────────

    @Test fun `http unbound credential allows any url`() {
        val reason = CredentialBinding.check(
            entry(type = CredentialType.API_KEY),
            "tok",
            CredentialUse.HTTP_HEADER,
            CredentialDestination.Http("https://any.example.com/v1"),
        )
        assertNull(reason)
    }

    @Test fun `http bound base url matches path under it and rejects other hosts`() {
        val bound = entry(
            type = CredentialType.API_KEY,
            metaJson = """{"endpoint":"https://api.example.com/v1"}""",
        )
        assertNull(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/v1/users"),
            ),
        )
        assertNull(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/v1"),
            ),
        )
        // /v1 不命中 /v10（路径段边界）
        assertTrue(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/v10/users"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
        assertTrue(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://evil.example.com/v1/users"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
    }

    @Test fun `http endpoint scheme and port must match, scheme-less endpoint means https`() {
        val bound = entry(
            type = CredentialType.API_KEY,
            metaJson = """{"endpoint":"api.example.com"}""",
        )
        assertNull(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/x"),
            ),
        )
        assertTrue(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("http://api.example.com/x"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
        val withPort = entry(
            type = CredentialType.API_KEY,
            metaJson = """{"endpoint":"https://api.example.com:8443"}""",
        )
        assertNull(
            CredentialBinding.check(
                withPort, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com:8443/x"),
            ),
        )
        assertTrue(
            CredentialBinding.check(
                withPort, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/x"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
    }

    @Test fun `http endpoint without path allows any path, loopback http allowed`() {
        val bound = entry(
            type = CredentialType.API_KEY,
            metaJson = """{"endpoint":"http://localhost:8080"}""",
        )
        assertNull(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("http://localhost:8080/anything/at/all"),
            ),
        )
        assertTrue(
            CredentialBinding.check(
                bound, "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("http://127.0.0.1:8080/x"),
            )!!.contains("该凭证未授权用于此目的地"),
        )
    }

    // ── 类型校验 ────────────────────────────────────────────────────

    @Test fun `ssh key auth requires ssh-key type`() {
        val key = entry(type = CredentialType.SSH_KEY)
        assertNull(
            CredentialBinding.check(key, sshKeyValue, CredentialUse.SSH_KEY, CredentialDestination.Ssh("h", 22, "u")),
        )
        val apiKey = entry(type = CredentialType.API_KEY)
        assertTrue(
            CredentialBinding.check(apiKey, "tok", CredentialUse.SSH_KEY, CredentialDestination.Ssh("h", 22, "u"))
                !!.contains("凭证类型与用途不匹配"),
        )
    }

    @Test fun `ssh password auth requires basic-auth type, api key as password is rejected`() {
        val pwd = entry(type = CredentialType.BASIC_AUTH)
        assertNull(
            CredentialBinding.check(pwd, "s3cret", CredentialUse.SSH_PASSWORD, CredentialDestination.Ssh("h", 22, "u")),
        )
        // 任务书点名的口子：auth=password + 任意 api key 名字
        val apiKey = entry(type = CredentialType.API_KEY)
        val reason = CredentialBinding.check(
            apiKey, "sk-xxx", CredentialUse.SSH_PASSWORD, CredentialDestination.Ssh("h", 22, "u"),
        )
        assertTrue(reason!!.contains("凭证类型与用途不匹配"))
    }

    @Test fun `http header accepts api-key and cloud-ak, rejects ssh-key and totp`() {
        assertNull(
            CredentialBinding.check(
                entry(type = CredentialType.API_KEY), "tok", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/"),
            ),
        )
        assertNull(
            CredentialBinding.check(
                entry(type = CredentialType.CLOUD_AK), "sk", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/"),
            ),
        )
        assertTrue(
            CredentialBinding.check(
                entry(type = CredentialType.SSH_KEY), sshKeyValue, CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/"),
            )!!.contains("凭证类型与用途不匹配"),
        )
        assertTrue(
            CredentialBinding.check(
                entry(type = CredentialType.TOTP), "seed", CredentialUse.HTTP_HEADER,
                CredentialDestination.Http("https://api.example.com/"),
            )!!.contains("凭证类型与用途不匹配"),
        )
    }

    @Test fun `custom type is rejected for all three uses`() {
        val custom = entry(type = CredentialType.CUSTOM)
        assertTrue(
            CredentialBinding.check(custom, "v", CredentialUse.SSH_KEY, CredentialDestination.Ssh("h", 22, "u"))
                !!.contains("凭证类型与用途不匹配"),
        )
        assertTrue(
            CredentialBinding.check(custom, "v", CredentialUse.SSH_PASSWORD, CredentialDestination.Ssh("h", 22, "u"))
                !!.contains("凭证类型与用途不匹配"),
        )
        assertTrue(
            CredentialBinding.check(custom, "v", CredentialUse.HTTP_HEADER, CredentialDestination.Http("https://h/"))
                !!.contains("凭证类型与用途不匹配"),
        )
    }

    @Test fun `unclassified type falls back to infer from value structure`() {
        // 未分类 + 有公钥 → 按 ssh-key 计（值含私钥结构的推断路径由基线 CredentialTypeTest 覆盖，
        // 那里已有 PEM 标记字面量，本测试不再重复写）
        assertNull(
            CredentialBinding.check(
                entry(type = "", publicKey = "ssh-ed25519 AAAA"), "whatever", CredentialUse.SSH_KEY,
                CredentialDestination.Ssh("h", 22, "u"),
            ),
        )
        // 未分类 + 普通值 → infer 为 custom-fields，口令认证拒绝
        assertTrue(
            CredentialBinding.check(
                entry(type = ""), "s3cret", CredentialUse.SSH_PASSWORD,
                CredentialDestination.Ssh("h", 22, "u"),
            )!!.contains("凭证类型与用途不匹配"),
        )
    }

    @Test fun `destination is checked before type`() {
        val boundApiKey = entry(
            type = CredentialType.API_KEY,
            metaJson = """{"endpoint":"a.example.com"}""",
        )
        // 目的地不对 + 类型也不对 → 报目的地（任务书第 1 项优先）
        val reason = CredentialBinding.check(
            boundApiKey, "tok", CredentialUse.SSH_PASSWORD,
            CredentialDestination.Ssh("b.example.com", 22, "u"),
        )
        assertTrue(reason!!.contains("该凭证未授权用于此目的地"))
    }

    @Test fun `effectiveType prefers stored type over inference`() {
        // 库里标了 basic-auth，即使值像私钥也按 basic-auth 计
        assertEquals(
            CredentialType.BASIC_AUTH,
            CredentialBinding.effectiveType(entry(type = CredentialType.BASIC_AUTH), sshKeyValue),
        )
    }
}
