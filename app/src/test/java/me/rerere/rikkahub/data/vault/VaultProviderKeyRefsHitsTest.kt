package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * provider「取用命中」队列的契约（审计第二注入点的入队侧）。
 *
 * 锁死三条容易被后续改动破坏的性质：
 * 1. 同一请求内同名只记一次（高频轮询不该把审计表刷满）；
 * 2. 单请求条数有上限（超出丢弃，不无界增长）；
 * 3. drain 取走即清空、reset 直接丢弃（保证归属不串到下一次请求）。
 */
class VaultProviderKeyRefsHitsTest {

    @Test
    fun `hits are deduplicated and drained exactly once`() {
        VaultProviderKeyRefs.resetExpanded()
        VaultProviderKeyRefs.recordExpanded("CRED_A")
        VaultProviderKeyRefs.recordExpanded("CRED_A")
        VaultProviderKeyRefs.recordExpanded("CRED_B")

        assertEquals(listOf("CRED_A", "CRED_B"), VaultProviderKeyRefs.drainExpanded())
        assertTrue(VaultProviderKeyRefs.drainExpanded().isEmpty())
    }

    @Test
    fun `hits are capped per request`() {
        VaultProviderKeyRefs.resetExpanded()
        repeat(VaultProviderKeyRefs.MAX_HITS_PER_REQUEST + 5) { i ->
            VaultProviderKeyRefs.recordExpanded("CRED_$i")
        }

        assertEquals(VaultProviderKeyRefs.MAX_HITS_PER_REQUEST, VaultProviderKeyRefs.drainExpanded().size)
    }

    @Test
    fun `reset drops pending hits`() {
        VaultProviderKeyRefs.recordExpanded("CRED_A")
        VaultProviderKeyRefs.resetExpanded()

        assertTrue(VaultProviderKeyRefs.drainExpanded().isEmpty())
    }
}
