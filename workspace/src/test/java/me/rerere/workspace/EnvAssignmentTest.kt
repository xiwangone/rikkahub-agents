package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归 2026-09-10 实测的静默失败：`workspace_shell(env=…)` 注入的变量在命令里恒为空。
 *
 * 根因是 proot 启动链用 `/usr/bin/env -i`（清空继承环境），只在
 * `ProcessBuilder.environment()` 里 set 是无效的——必须把变量作为 `env` 的 `K=V`
 * 参数传进去（见 [ProotShellRunner.buildCommand]）。本测试锁定该渲染逻辑。
 */
class EnvAssignmentTest {

    @Test
    fun `renders each entry as KEY=VALUE`() {
        assertEquals(
            listOf("FOO=bar", "TOKEN=abc123"),
            buildEnvAssignments(linkedMapOf("FOO" to "bar", "TOKEN" to "abc123")),
        )
    }

    @Test
    fun `keeps values containing equals and spaces intact`() {
        assertEquals(
            listOf("A=b=c", "B=hello world"),
            buildEnvAssignments(linkedMapOf("A" to "b=c", "B" to "hello world")),
        )
    }

    @Test
    fun `drops invalid keys so env does not abort`() {
        assertEquals(
            listOf("OK=1"),
            buildEnvAssignments(
                linkedMapOf(
                    "" to "empty-key",
                    "BAD=KEY" to "value",
                    "OK" to "1",
                ),
            ),
        )
    }

    @Test
    fun `empty map renders nothing`() {
        assertEquals(emptyList<String>(), buildEnvAssignments(emptyMap()))
    }

    @Test
    fun `strips NUL from values`() {
        assertEquals(listOf("K=ab"), buildEnvAssignments(mapOf("K" to "a\u0000b")))
    }
}
