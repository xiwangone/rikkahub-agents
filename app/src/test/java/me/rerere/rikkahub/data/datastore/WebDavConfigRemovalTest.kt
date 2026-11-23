package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WebDAV 配置删除（Settings.withoutWebDavConfig）的纯逻辑回归：
 * 删当前生效配置时生效配置顺延为剩余首个；删最后一个（列表仅剩一个或旧单配置模式）
 * 必须拒绝；不存在的 id 不做任何改动。
 */
class WebDavConfigRemovalTest {

    private fun config(id: String, name: String) = WebDavConfig(id = id, name = name)

    @Test
    fun `deleting the active config promotes the remaining one`() {
        val first = config("a", "first")
        val second = config("b", "second")
        val settings = Settings(
            webDavConfigs = listOf(first, second),
            activeWebDavConfigId = "b",
            webDavConfig = second,
        )
        val result = settings.withoutWebDavConfig("b")
        assertEquals(listOf(first), result?.webDavConfigs)
        assertEquals("a", result?.activeWebDavConfigId)
        assertEquals(first, result?.webDavConfig)
    }

    @Test
    fun `deleting a non-active config keeps the active one`() {
        val first = config("a", "first")
        val second = config("b", "second")
        val settings = Settings(
            webDavConfigs = listOf(first, second),
            activeWebDavConfigId = "a",
            webDavConfig = first,
        )
        val result = settings.withoutWebDavConfig("b")
        assertEquals(listOf(first), result?.webDavConfigs)
        assertEquals("a", result?.activeWebDavConfigId)
        assertEquals(first, result?.webDavConfig)
    }

    @Test
    fun `deleting the only listed config is refused`() {
        val only = config("a", "only")
        val settings = Settings(
            webDavConfigs = listOf(only),
            activeWebDavConfigId = "a",
            webDavConfig = only,
        )
        assertNull(settings.withoutWebDavConfig("a"))
    }

    @Test
    fun `deleting the legacy single config is refused`() {
        val legacy = config("legacy", "legacy")
        val settings = Settings(webDavConfig = legacy)
        assertNull(settings.withoutWebDavConfig("legacy"))
    }

    @Test
    fun `unknown id leaves settings unchanged`() {
        val first = config("a", "first")
        val second = config("b", "second")
        val settings = Settings(
            webDavConfigs = listOf(first, second),
            activeWebDavConfigId = "a",
            webDavConfig = first,
        )
        assertEquals(settings, settings.withoutWebDavConfig("missing"))
        val legacySettings = Settings(webDavConfig = config("legacy", "legacy"))
        assertEquals(legacySettings, legacySettings.withoutWebDavConfig("missing"))
    }
}
