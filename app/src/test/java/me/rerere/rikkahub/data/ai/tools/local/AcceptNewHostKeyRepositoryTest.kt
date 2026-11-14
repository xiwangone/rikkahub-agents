package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AcceptNewHostKeyRepository 的 TOFU 语义回归（Robolectric，跑真实 JSch KnownHosts，
 * 不连网、不起 SSH 服务端）：
 * - 首次见到主机：check 返回 OK，且密钥已写入 known_hosts 文件（重载后仍认）；
 * - 换 key：check 返回 CHANGED，文件里的旧记录不动（阴性断言：换 key 必须失败）；
 * - 已知密钥：check 返回 OK，文件一字不动；
 * - installHostKeyVerification：会话配成 StrictHostKeyChecking=yes 且挂上 wrapper；
 * - 旧 vault_known_hosts 一次性并入共用文件，共用侧已有记录时以共用为准；
 * - 终端页的 VerifyingHostKeyRepository（已移入共享文件）：未知/变更只记录不信任。
 *
 * 端到端不在此覆盖：换 key 时会话 connect() 抛错，需要服务端做 KEX
 * 握手，本地没有可用的服务端夹具——此处只做到 wrapper 返回 CHANGED + 会话
 * 配成 "yes"（JSch 对该组合的行为是抛错，见 AcceptNewHostKeyRepository 注释），
 * 需服务端握手的复现标「未验到」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AcceptNewHostKeyRepositoryTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val host = "example.test"
    private val otherHost = "other.test"

    /**
     * Ed25519 公钥的 SSH wire blob：string "ssh-ed25519" + string 32 字节原始公钥。
     * 不用 JSch 的 KeyPair.genKeyPair：它在 Conscrypt 为默认提供者时把生成的公钥
     * 强转 EdECPublicKey 会抛 ClassCastException；这里只用 JDK 生成器取 X.509
     * 编码尾部的 32 字节原始公钥，不做任何强转。
     */
    private fun genKeyBlob(): ByteArray {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = pair.public.encoded.takeLast(32).toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { w ->
            w.writeInt("ssh-ed25519".length)
            w.write("ssh-ed25519".toByteArray(Charsets.US_ASCII))
            w.writeInt(raw.size)
            w.write(raw)
        }
        return out.toByteArray()
    }

    /** ssh-rsa wire blob: string "ssh-rsa" + mpint e + mpint n (JDK generator, no JSch KeyPair). */
    private fun genRsaKeyBlob(): ByteArray {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pub = gen.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { w ->
            for (part in listOf("ssh-rsa".toByteArray(Charsets.US_ASCII), pub.publicExponent.toByteArray(), pub.modulus.toByteArray())) {
                w.writeInt(part.size)
                w.write(part)
            }
        }
        return out.toByteArray()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun newKnownHostsFile(name: String = "known_hosts"): File {
        val file = tempFolder.newFile(name)
        file.writeText("")
        return file
    }

    private fun newRepo(file: File, port: Int = 22): Pair<JSch, AcceptNewHostKeyRepository> {
        val jsch = JSch()
        jsch.setKnownHosts(file.absolutePath)
        val repo = AcceptNewHostKeyRepository(jsch.hostKeyRepository, port = port) { persistKnownHosts(jsch) }
        return jsch to repo
    }

    @Test
    fun `first sight records the key and returns OK`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file)
        val blob = genKeyBlob()

        assertEquals(HostKeyRepository.OK, repo.check(host, blob))
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(listOf("$host ssh-ed25519 ${b64(blob)}"), lines)

        // 重载后仍认：落盘格式是标准 known_hosts(5)。
        val reloaded = JSch()
        reloaded.setKnownHosts(file.absolutePath)
        assertEquals(HostKeyRepository.OK, reloaded.hostKeyRepository.check(host, blob))
    }

    @Test
    fun `changed key is refused and the stored key is untouched`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file)
        val first = genKeyBlob()
        val second = genKeyBlob()
        assertEquals(HostKeyRepository.OK, repo.check(host, first))
        val stored = file.readText(Charsets.UTF_8)

        assertEquals(HostKeyRepository.CHANGED, repo.check(host, second))
        assertEquals("changed key must not rewrite the trust store", stored, file.readText(Charsets.UTF_8))

        val reloaded = JSch()
        reloaded.setKnownHosts(file.absolutePath)
        assertEquals(HostKeyRepository.CHANGED, reloaded.hostKeyRepository.check(host, second))
        assertEquals(HostKeyRepository.OK, reloaded.hostKeyRepository.check(host, first))
    }

    @Test
    fun `known key passes without rewriting the file`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file)
        val blob = genKeyBlob()
        assertEquals(HostKeyRepository.OK, repo.check(host, blob))
        val stored = file.readBytes()

        assertEquals(HostKeyRepository.OK, repo.check(host, blob))
        assertTrue("known key must not touch the file", stored.contentEquals(file.readBytes()))
    }

    @Test
    fun `install wires yes and the wrapper on the session`() {
        val file = newKnownHostsFile()
        val jsch = JSch()
        jsch.setKnownHosts(file.absolutePath)
        val session = jsch.getSession("u", "127.0.0.1", 22)

        installHostKeyVerification(jsch, session)

        assertEquals("yes", session.getConfig("StrictHostKeyChecking"))
        assertTrue(session.hostKeyRepository is AcceptNewHostKeyRepository)
    }

    @Test
    fun `legacy vault import merges missing entries, app store wins`() {
        val keyA = genKeyBlob()
        val keyB = genKeyBlob()
        val vaultFile = tempFolder.newFile("vault_known_hosts")
        vaultFile.writeText(
            "$host ssh-ed25519 ${b64(keyA)}\n" +
                "$otherHost ssh-ed25519 ${b64(keyB)}\n" +
                "# comment line\n" +
                "broken line\n" +
                "|1|hashedhost ssh-ed25519 ${b64(keyA)}\n",
        )

        // 共用库为空：两条有效记录并入。
        val appFile = newKnownHostsFile("app_known_hosts")
        val (appJsch, _) = newRepo(appFile)
        assertEquals(2, importLegacyVaultKnownHosts(appJsch.hostKeyRepository, vaultFile))
        assertEquals(HostKeyRepository.OK, appJsch.hostKeyRepository.check(host, keyA))
        assertEquals(HostKeyRepository.OK, appJsch.hostKeyRepository.check(otherHost, keyB))
        // 再导一次不重复。
        assertEquals(0, importLegacyVaultKnownHosts(appJsch.hostKeyRepository, vaultFile))

        // 共用库已有另一把 key：host 的 vault 条目是旧的，丢弃；otherHost 是新来的，并入。
        val appFile2 = newKnownHostsFile("app_known_hosts2")
        val (appJsch2, repo2) = newRepo(appFile2)
        assertEquals(HostKeyRepository.OK, repo2.check(host, keyB))
        assertEquals(1, importLegacyVaultKnownHosts(appJsch2.hostKeyRepository, vaultFile))
        assertEquals(HostKeyRepository.CHANGED, appJsch2.hostKeyRepository.check(host, keyA))
        assertEquals(HostKeyRepository.OK, appJsch2.hostKeyRepository.check(host, keyB))
        assertEquals(HostKeyRepository.OK, appJsch2.hostKeyRepository.check(otherHost, keyB))
    }

    @Test
    fun `getHostKey with null type reflects recorded keys`() {
        val file = newKnownHostsFile()
        val (jsch, repo) = newRepo(file)
        assertTrue(jsch.hostKeyRepository.getHostKey(host, null).isNullOrEmpty())
        val blob = genKeyBlob()
        assertEquals(HostKeyRepository.OK, repo.check(host, blob))
        assertFalse(jsch.hostKeyRepository.getHostKey(host, null).isNullOrEmpty())
        assertTrue(jsch.hostKeyRepository.getHostKey(otherHost, null).isNullOrEmpty())
    }

    @Test
    fun `verifying repository records unknown and changed keys without trusting`() {
        val file = newKnownHostsFile()
        val jsch = JSch()
        jsch.setKnownHosts(file.absolutePath)
        val verifying = VerifyingHostKeyRepository(jsch.hostKeyRepository)
        val first = genKeyBlob()
        val second = genKeyBlob()

        assertEquals(HostKeyRepository.NOT_INCLUDED, verifying.check(host, first))
        val unknown = verifying.unknownKey
        assertNotNull(unknown)
        assertEquals("ssh-ed25519", unknown!!.type)
        assertEquals(b64(first), unknown.key)
        assertNull(verifying.changedKey)

        // 已确认的密钥写入后（模拟 trustHostKey），再连即认。
        jsch.hostKeyRepository.add(HostKey(host, first), null)
        assertEquals(HostKeyRepository.OK, verifying.check(host, first))
        assertNull(verifying.unknownKey)
        assertNull(verifying.changedKey)

        assertEquals(HostKeyRepository.CHANGED, verifying.check(host, second))
        val changed = verifying.changedKey
        assertNotNull(changed)
        assertEquals(b64(first), changed!!.first.key)
        assertEquals(b64(second), changed.second.key)
        assertNull(verifying.unknownKey)
    }

    // 阴性：JSch 只比较同类型密钥，已知主机换一种类型出示会得到 NOT_INCLUDED。
    // 2.28.7 的行为：只比较同类型密钥；不拦的话，客户端没记录的算法可被当成"首次连接"。
    @Test
    fun `a new key type for a known host is refused, not trusted as first use`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file)
        val ed = genKeyBlob()
        assertEquals(HostKeyRepository.OK, repo.check(host, ed))
        val stored = file.readText(Charsets.UTF_8)

        assertEquals(HostKeyRepository.CHANGED, repo.check(host, genRsaKeyBlob()))
        assertEquals("a refused key must not be recorded", stored, file.readText(Charsets.UTF_8))
        // 另一台主机首次出示 RSA 仍按首次信任
        assertEquals(HostKeyRepository.OK, repo.check(otherHost, genRsaKeyBlob()))
    }

    @Test
    fun `known host lookup understands the bracketed non-22 port form`() {
        val file = newKnownHostsFile()
        val (jsch, repo) = newRepo(file)
        assertEquals(HostKeyRepository.OK, repo.check("[$host]:2222", genKeyBlob()))

        assertTrue(hasKnownHostKey(jsch.hostKeyRepository, host, 2222))
        assertFalse(hasKnownHostKey(jsch.hostKeyRepository, host, 22))
        assertFalse(hasKnownHostKey(jsch.hostKeyRepository, otherHost, 2222))
    }

    // 跨形态：同一 host:port 可记在裸 host（别名会话）或 "[host]:port"（直连会话）两种形态下，
    // DNS 会在会话之间翻转。裸形态查到已记在 "[host]:port" 下的同把密钥时是同一台服务器，
    // 接受并收敛记录；换类型时仍拒绝；全新 host:port 仍首次信任。
    @Test
    fun `same key under bracketed form is accepted on the bare alias path`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file, port = 2222)
        val ed = genKeyBlob()
        assertEquals(HostKeyRepository.OK, repo.check("[$host]:2222", ed))

        // DNS 开始解析后走别名（裸 host）路径，出示同一把密钥：接受。
        assertEquals(HostKeyRepository.OK, repo.check(host, ed))
        // 记录收敛：文件里现在两种形态都有，下次委托层直接命中。
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertTrue(lines.any { it.startsWith("[$host]:2222 ssh-ed25519") })
        assertTrue(lines.any { it.startsWith("$host ssh-ed25519") })
        assertEquals(HostKeyRepository.OK, repo.check(host, ed))
    }

    @Test
    fun `new key type under bracketed form is refused on the bare alias path`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file, port = 2222)
        val ed = genKeyBlob()
        assertEquals(HostKeyRepository.OK, repo.check("[$host]:2222", ed))
        val stored = file.readText(Charsets.UTF_8)

        assertEquals(HostKeyRepository.CHANGED, repo.check(host, genRsaKeyBlob()))
        assertEquals("a refused key must not be recorded", stored, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `genuinely new host on a non-22 port is still trusted`() {
        val file = newKnownHostsFile()
        val (_, repo) = newRepo(file, port = 2222)
        assertEquals(HostKeyRepository.OK, repo.check(otherHost, genKeyBlob()))
    }

    @Test
    fun `port 22 first use is not blocked by a key known only on port 2222`() {
        val file = newKnownHostsFile()
        val ed = genKeyBlob()
        val (_, repo2222) = newRepo(file, port = 2222)
        assertEquals(HostKeyRepository.OK, repo2222.check("[$host]:2222", ed))

        // 同一主机名、不同端口是不同条目：22 端口首次出示新密钥仍按首次信任。
        val (_, repo22) = newRepo(file, port = 22)
        assertEquals(HostKeyRepository.OK, repo22.check(host, genKeyBlob()))
    }

    @Test
    fun `host-key trust options cannot be overridden per host`() {
        assertTrue(isProtectedSshOption("StrictHostKeyChecking"))
        assertTrue(isProtectedSshOption(" stricthostkeychecking "))
        assertTrue(isProtectedSshOption("prefer_known_host_key_types"))
        assertFalse(isProtectedSshOption("ConnectTimeout"))
        assertFalse(isProtectedSshOption("ServerAliveInterval"))
        // HostKeyAlias / UserKnownHostsFile 故意不在受保护集合里：JSch 只在装了
        // OpenSSH 配置文件仓库（本应用从不装）时才从 session config 读它们，
        // session.setConfig 的值在 JSch 里没有任何读取方（2.28.7 源码核实），
        // ssh_options 写它们是无效操作，无需过滤。
        assertFalse(isProtectedSshOption("HostKeyAlias"))
        assertFalse(isProtectedSshOption("UserKnownHostsFile"))
    }
}
