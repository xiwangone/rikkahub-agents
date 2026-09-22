package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 凭证库「导出 → 导入」往返契约测试（四种格式的对称性）。
 *
 * 为什么需要这一层：导入与导出是**两份手工维护的对称实现**（分散在 [VaultExporter] /
 * [VaultFormats] / [CredentialImporter]），任一侧改了而另一侧忘改，用户看到的现象就是
 * 「导出的东西导不回来」或「值里平白多出反斜杠」——只有把导出物再喂回导入端比对才能拦住。
 *
 * 样例值一律使用 `example-*` 假值（公开面检查会拦下密钥形态的字符串）。
 */
class CredentialImportRoundTripTest {

    private val password = "example-passphrase-0001"

    /** 覆盖：普通值 / shell 特殊字符 / 多行值 / 逗号与引号 / 结尾反斜杠。 */
    private val samples = listOf(
        VaultExporter.Quad(
            name = "EXAMPLE_API_KEY",
            plaintext = "example-api-value-0001",
            description = "示例接口密钥",
            group = "AI",
            type = CredentialType.API_KEY,
            // 非敏感元数据（白名单产出）：验证四条通道都能往返搬运
            metaJson = CredentialMeta.encode(
                mapOf("endpoint" to "https://api.example.com/v1", "header" to "Authorization"),
            ),
        ),
        VaultExporter.Quad(
            name = "EXAMPLE_SPECIAL",
            // 双引号 / 反斜杠 / 美元符 / 反引号：覆盖 load-creds.sh 双引号转义的正反两向
            plaintext = "example-a\"b\\c\$1d`e",
            description = "含 shell 特殊字符",
            group = "Git",
            type = CredentialType.API_KEY,
        ),
        VaultExporter.Quad(
            name = "EXAMPLE_MULTILINE",
            plaintext = "example-line-one\nexample-line-two\nexample-line-three",
            description = "多行值",
            group = "SSH",
            publicKey = "ssh-ed25519 example-public-key-placeholder",
            type = CredentialType.SSH_KEY,
        ),
        VaultExporter.Quad(
            name = "EXAMPLE_CSV_EDGE",
            plaintext = "example-a,b\"c",
            description = "含逗号与引号",
            group = "MCP",
            type = CredentialType.CUSTOM,
        ),
        VaultExporter.Quad(
            name = "EXAMPLE_TRAILING_BACKSLASH",
            // 值的最后一个字符是反斜杠：load-creds 导出后行尾是 `\\"`，易被误判成「引号未闭合」
            plaintext = "example-trailing\\",
            description = "结尾反斜杠",
            group = "Other",
            type = CredentialType.BASIC_AUTH,
        ),
    )

    private val expected = samples.map {
        CredentialImporter.ParsedEntry(
            name = it.name,
            value = it.plaintext,
            description = it.description,
            group = it.group,
            publicKey = it.publicKey,
            type = it.type,
            metaJson = it.metaJson,
        )
    }

    @Test
    fun `vault bundle round trips`() {
        val json = VaultExporter.exportWithGroups(password, samples)
        assertSameEntries(VaultExporter.importEntries(json, password))
    }

    @Test
    fun `load creds round trips`() {
        assertSameEntries(CredentialImporter.parse(VaultExporter.toLoadCreds(samples)))
    }

    @Test
    fun `csv round trips`() {
        // CSV 只有 name/value/description/group 四列，故不比对类型与公钥
        assertSameEntries(
            actual = VaultFormats.fromCsv(VaultFormats.toCsv(samples)).map { it.toParsedEntry() },
            carriesType = false,
            carriesPublicKey = false,
        )
    }

    @Test
    fun `bitwarden json round trips`() {
        assertSameEntries(
            actual = VaultFormats.fromBitwarden(VaultFormats.toBitwarden(samples)).map { it.toParsedEntry() },
            carriesType = false,
            carriesPublicKey = false,
        )
    }

    @Test
    fun `each exported payload is detected as its own format`() {
        assertEquals(
            CredentialImporter.Format.VAULT,
            CredentialImporter.detectFormat("example.vault", VaultExporter.exportWithGroups(password, samples)),
        )
        assertEquals(
            CredentialImporter.Format.LOADCREDS,
            CredentialImporter.detectFormat("example.sh", VaultExporter.toLoadCreds(samples)),
        )
        assertEquals(
            CredentialImporter.Format.CSV,
            CredentialImporter.detectFormat("example.csv", VaultFormats.toCsv(samples)),
        )
        assertEquals(
            CredentialImporter.Format.BITWARDEN,
            CredentialImporter.detectFormat("example.json", VaultFormats.toBitwarden(samples)),
        )
    }

    /**
     * 往返比对：按名对齐后逐字段比对。
     *
     * @param carriesType / @param carriesPublicKey 仅当该格式本身带「类型 / SSH 公钥」字段时为真
     *   （CSV 与 Bitwarden 无对应字段，导入后为未分类/空，由后续 [CredentialType.infer] 补全）。
     */
    private fun assertSameEntries(
        actual: List<CredentialImporter.ParsedEntry>,
        carriesType: Boolean = true,
        carriesPublicKey: Boolean = true,
    ) {
        assertEquals("条目名集合不一致", expected.map { it.name }.toSet(), actual.map { it.name }.toSet())
        val byName = actual.associateBy { it.name }
        expected.forEach { e ->
            val a = byName.getValue(e.name)
            assertEquals("${e.name} 的 value", e.value, a.value)
            assertEquals("${e.name} 的 description", e.description, a.description)
            assertEquals("${e.name} 的 group", e.group, a.group)
            if (carriesPublicKey) assertEquals("${e.name} 的 publicKey", e.publicKey, a.publicKey)
            if (carriesType) assertEquals("${e.name} 的 type", e.type, a.type)
            // 元数据也必须往返（批 B 的验收点）；空 meta 同样比对
            assertEquals("${e.name} 的 metaJson", e.metaJson, a.metaJson)
        }
    }
}
