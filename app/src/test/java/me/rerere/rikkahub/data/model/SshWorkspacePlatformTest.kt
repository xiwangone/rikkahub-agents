package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.repository.toSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.ssh.SshAuthType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程工作区平台选择：旧 JSON 兼容 + 表单到持久化配置的透传。
 *
 * 决议链本身（工作区显式 > 已保存主机显式 > banner 缓存 > 全局默认）依赖
 * Koin/Context/Repository，纯单测覆盖不到，见 [me.rerere.rikkahub.data.ai.tools.local.SshPlatformResolutionTest]
 * 对纯函数 [me.rerere.rikkahub.data.ai.tools.local.RemotePlatform.resolveWindows] 的覆盖；
 * 端到端行为（Windows 远端走 pwsh 包装）不在本测试覆盖范围。
 */
class SshWorkspacePlatformTest {

    @Test
    fun `old JSON without platform decodes to AUTO`() {
        val decoded = JsonInstant.decodeFromString<SshWorkspaceSettings>(
            """{"host":"example.com","username":"user","remoteDir":"/home/user"}""",
        )
        assertEquals(SshPlatform.AUTO, decoded.platform)
    }

    @Test
    fun `platform WINDOWS round-trips through JSON`() {
        val decoded = JsonInstant.decodeFromString<SshWorkspaceSettings>(
            """{"host":"example.com","username":"user","remoteDir":"/home/user","platform":"WINDOWS"}""",
        )
        assertEquals(SshPlatform.WINDOWS, decoded.platform)
        assertEquals(
            SshPlatform.WINDOWS,
            JsonInstant.decodeFromString<SshWorkspaceSettings>(
                JsonInstant.encodeToString(decoded),
            ).platform,
        )
    }

    @Test
    fun `draft platform is carried into saved settings`() {
        val settings = SshWorkspaceDraft(
            name = "ws",
            host = "example.com",
            username = "user",
            authType = SshAuthType.PASSWORD,
            passwordRef = "MY_PASS",
            remoteDir = "/home/user",
            platform = SshPlatform.WINDOWS,
        ).toSettings(null)
        assertEquals(SshPlatform.WINDOWS, settings.platform)
    }

    @Test
    fun `draft without platform defaults to AUTO in saved settings`() {
        val settings = SshWorkspaceDraft(
            name = "ws",
            host = "example.com",
            username = "user",
            authType = SshAuthType.PASSWORD,
            passwordRef = "MY_PASS",
            remoteDir = "/home/user",
        ).toSettings(null)
        assertEquals(SshPlatform.AUTO, settings.platform)
    }
}
