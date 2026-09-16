package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户删掉内置技能后必须**保持删除**（重启不复活）。
 *
 * 删除会连带清掉技能目录内的 `.seeded` 哨兵，仅凭磁盘状态无法区分「从未安装」与
 * 「装过又被删」；因此删除记录存放在技能目录之外，由本函数决定要记什么。
 */
class DeletedBundledSkillTrackingTest {

    @Test
    fun `deleting a bundled skill records its name`() {
        val after = deletedBundledSkillsAfterDelete(
            current = emptySet(),
            deletedName = "summarize",
            isBundled = true,
        )

        assertEquals(setOf("summarize"), after)
    }

    @Test
    fun `deleting a user-created skill records nothing`() {
        val after = deletedBundledSkillsAfterDelete(
            current = setOf("summarize"),
            deletedName = "my-own-skill",
            isBundled = false,
        )

        assertEquals(setOf("summarize"), after)
    }

    @Test
    fun `deleting the same bundled skill twice stays a single entry`() {
        val once = deletedBundledSkillsAfterDelete(setOf(), "summarize", true)
        val twice = deletedBundledSkillsAfterDelete(once, "summarize", true)

        assertEquals(1, twice.size)
        assertTrue("summarize" in twice)
    }

    @Test
    fun `records accumulate across different bundled skills`() {
        val after = deletedBundledSkillsAfterDelete(
            current = deletedBundledSkillsAfterDelete(setOf(), "a-skill", true),
            deletedName = "b-skill",
            isBundled = true,
        )

        assertEquals(setOf("a-skill", "b-skill"), after)
    }
}
