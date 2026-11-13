package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SSH 主机探针触发模式（设置项 ssh_host_probe）的纯逻辑回归：
 * - 偏好值映射（未知/空值回退默认仅手动）；
 * - Settings() 默认仅手动（不改默认行为：旧版本不自动探）。
 * codec 落盘往返（SettingsStore 编解码）走 Robolectric 持久化测试，见
 * SshHostProbeStoreTest 的同类模式；本类只覆盖纯映射与默认值。
 */
class SshHostProbeModeTest {

    @Test
    fun `fromPreference maps all three modes`() {
        assertEquals(SshHostProbeMode.AUTO, SshHostProbeMode.fromPreference("AUTO"))
        assertEquals(SshHostProbeMode.MANUAL_ONLY, SshHostProbeMode.fromPreference("MANUAL_ONLY"))
        assertEquals(SshHostProbeMode.OFF, SshHostProbeMode.fromPreference("OFF"))
    }

    @Test
    fun `fromPreference falls back to manual-only for unknown or missing values`() {
        assertEquals(SshHostProbeMode.MANUAL_ONLY, SshHostProbeMode.fromPreference("BOGUS"))
        assertEquals(SshHostProbeMode.MANUAL_ONLY, SshHostProbeMode.fromPreference(null))
        assertEquals(SshHostProbeMode.MANUAL_ONLY, SshHostProbeMode.fromPreference(""))
    }

    @Test
    fun `Settings defaults to manual-only probe mode`() {
        assertEquals(SshHostProbeMode.MANUAL_ONLY, Settings().sshHostProbe)
        // 邻居字段不受影响：默认平台仍是自动。
        assertEquals(SshPlatform.AUTO, Settings().defaultSshPlatform)
    }
}
