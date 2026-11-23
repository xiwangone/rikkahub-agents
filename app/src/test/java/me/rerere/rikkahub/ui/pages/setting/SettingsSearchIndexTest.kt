package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    @Test
    fun `no duplicate routes with developer mode off`() {
        val entries = settingsSearchIndex(developerMode = false)
        val routes = entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `no duplicate routes with developer mode on`() {
        val entries = settingsSearchIndex(developerMode = true)
        val routes = entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `every entry has non-zero resource ids`() {
        val entries = settingsSearchIndex(developerMode = true)
        entries.forEach { entry ->
            assertTrue("titleRes must be non-zero for ${entry.route}", entry.titleRes != 0)
            assertTrue("groupRes must be non-zero for ${entry.route}", entry.groupRes != 0)
            entry.descriptionRes?.let {
                assertTrue("descriptionRes must be non-zero for ${entry.route}", it != 0)
            }
        }
    }

    @Test
    fun `developer row only present when the flag is set`() {
        val withoutDeveloper = settingsSearchIndex(developerMode = false)
        val withDeveloper = settingsSearchIndex(developerMode = true)

        assertFalse(withoutDeveloper.any { it.route == Screen.Developer })
        assertTrue(withDeveloper.any { it.route == Screen.Developer })
        assertEquals(withoutDeveloper.size + 1, withDeveloper.size)
    }

    @Test
    fun `network sub-pages are searchable`() {
        // 偏好设置 → 网络下的 5 个子页必须进搜索索引，否则搜「路由」「代理」「DNS」等找不到入口。
        val routes = settingsSearchIndex(developerMode = false).map { it.route }
        assertTrue(routes.contains(Screen.SettingNetworkRouting))
        assertTrue(routes.contains(Screen.SettingNetworkProxy))
        assertTrue(routes.contains(Screen.SettingNetworkDns))
        assertTrue(routes.contains(Screen.SettingNetworkHostOverrides))
        assertTrue(routes.contains(Screen.SettingNetworkTunnel))
    }
}
