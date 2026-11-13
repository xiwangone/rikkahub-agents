package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.HostAddressOverride
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主机覆写确认（[resolveHostOverridesToSave]）：直接保存草稿原样。
 * 删光后确认必须保存空列表——旧的“草稿为空就回退已保存值”兜底会把删光悄悄丢掉。
 */
class SettingNetworkHostOverridesTest {

    private val entries = listOf(
        HostAddressOverride(host = "a.example.com", address = "1.2.3.4"),
        HostAddressOverride(host = "b.example.com", address = "5.6.7.8", enabled = false),
    )

    @Test
    fun `empty drafts save empty`() {
        assertEquals(emptyList<HostAddressOverride>(), resolveHostOverridesToSave(emptyList()))
    }

    @Test
    fun `non-empty drafts save as-is including disabled entries`() {
        assertEquals(entries, resolveHostOverridesToSave(entries))
    }

    @Test
    fun `edited drafts win as-is`() {
        val edited = listOf(HostAddressOverride(host = "c.example.com", address = "9.9.9.9"))
        assertEquals(edited, resolveHostOverridesToSave(edited))
    }
}
