package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 凭证使用目的的**授权门契约**。
 *
 * 为什么钉住：解析器 [CredentialResolver] 按 purpose 决定要不要过会话授权门，而构造解析器时
 * 若不传 `isAuthorized`，对它来说"需要授权门"的 purpose 会**恒返回 NotAuthorized**；
 * 更糟的是下游可能把这种失败写成"解密失败"，把排查带向密钥损坏（真实踩过：导出通道被
 * 一次重构改成走解析器但漏传授权，从此 100% 失败且文案误导）。
 *
 * 本测试把"哪些 purpose 需要授权"变成**显式清单**：新增 purpose 时必须在这里显式决定，
 * 顺带保证审计 action 名可区分（否则事后分不清是哪条通道取的）。
 */
class CredentialPurposeTest {

    @Test
    fun `authorization-gated purposes are exactly the value-exporting ones`() {
        val gated = CredentialPurpose.values().filter { it.requiresAuthorization }.map { it.name }.toSet()
        assertEquals(setOf("ENV_INJECT", "EXPORT"), gated)
    }

    @Test
    fun `audit action names are unique`() {
        val actions = CredentialPurpose.values().map { it.action }
        assertEquals("审计 action 名必须唯一（否则事后无法区分通道）", actions.size, actions.toSet().size)
    }
}
