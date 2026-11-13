package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P1-6 主机精确匹配边界回归（纯 JVM，直接测 JSch 的 known_hosts 查找）。
 *
 * 背景：vault 侧旧的 `isFirstKnownHostConnect` 用 `line.startsWith(host)` 做前缀匹配，
 * `203.0.113.1` 会被 `203.0.113.10` 那行误判成"已知主机"，随后用 `yes` 建连失败再误报
 * host_key_changed。P0-1 删掉了这个函数，改走 `jsch.hostKeyRepository.getHostKey(host, null)`。
 * 本文件把"精确匹配"钉成测试：万一将来换实现（或 JSch 行为有变），前缀误判一回来就红。
 *
 * 注意：本文件只依赖 JSch（不依赖 P0-1 的新类），在干净基线与叠加态都能跑。
 */
class HostKeyExactMatchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 生成一把真 RSA 密钥，写成 known_hosts 文件，返回配好该文件的 JSch。
     *
     * 注意不用 Ed25519：JSch 2.28.7 的 EdDSA 生成会把密钥 cast 成 JDK 的 `EdECPublicKey`，
     * 单测环境默认 provider 是 Conscrypt，它的 `OpenSslEdDsaPublicKey` 没实现那个接口，
     * `genKeyPair(ED25519)` 直接 ClassCastException。RSA 只 cast 标准 `RSAPublicKey`，
     * 不受影响；主机匹配断言与密钥类型无关。 */
    private fun jschWithKnownHosts(vararg hosts: String): JSch {
        val jsch = JSch()
        val pair = KeyPair.genKeyPair(jsch, KeyPair.RSA)
        val out = ByteArrayOutputStream()
        pair.writePublicKey(out, "test")
        val pubLine = out.toString(Charsets.UTF_8).trim()
        val file = tempFolder.newFile("known_hosts")
        file.writeText(hosts.joinToString("\n") { host -> "$host $pubLine" } + "\n")
        jsch.setKnownHosts(file.absolutePath)
        return jsch
    }

    @Test
    fun `prefix host does not match longer entry`() {
        val jsch = jschWithKnownHosts("203.0.113.10")
        // 核心边界：203.0.113.1 不是 203.0.113.10 的前缀匹配——必须判成"首次连接"。
        assertTrue(jsch.hostKeyRepository.getHostKey("203.0.113.1", null).isNullOrEmpty())
        assertFalse(jsch.hostKeyRepository.getHostKey("203.0.113.10", null).isNullOrEmpty())
        assertTrue(jsch.hostKeyRepository.getHostKey("203.0.113.100", null).isNullOrEmpty())
    }

    @Test
    fun `hostname prefix does not match longer entry`() {
        val jsch = jschWithKnownHosts("host10.example.com")
        assertTrue(jsch.hostKeyRepository.getHostKey("host1.example.com", null).isNullOrEmpty())
        assertFalse(jsch.hostKeyRepository.getHostKey("host10.example.com", null).isNullOrEmpty())
    }

    @Test
    fun `port qualified entry does not match bare host`() {
        val jsch = jschWithKnownHosts("[203.0.113.1]:2222")
        // host(+port) 精确匹配：带端口的条目只对 "[host]:port" 这个完整 token 生效，
        // 裸 host 查不到——调用方传裸 host 时按首次连接走 TOFU，不会错认成已知。
        assertTrue(jsch.hostKeyRepository.getHostKey("203.0.113.1", null).isNullOrEmpty())
        assertFalse(jsch.hostKeyRepository.getHostKey("[203.0.113.1]:2222", null).isNullOrEmpty())
    }
}
