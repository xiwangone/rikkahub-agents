package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一条多字段 + 按字段引用」（`$$名字.字段`）的契约测试。
 *
 * 钉住三件事：
 * 1. **老行为不能变** —— 不含 `.` 的引用是取主值，是所有现存配置的命脉；
 * 2. **字段规则要唯一** —— 裸白名单键优先、`custom.` 可显式拼写也可裸名回退、指纹不可引用；
 * 3. **同步缓存与异步解析器同源** —— 同一份字段规则（[CredentialMeta.resolveField]）
 *    在两条链路上必须给出同样的答案，否则会出现"表单里能用、env 注入里不能用"这类难查的分歧。
 *
 * 覆盖的是纯判定层（解析器要 Room/DataStore 才能构造，测试环境没有、也没装 mockk）：
 * `CredentialResolver.resolveRef` 里"查库 → 转交 fieldResolution"那两步不在本文件覆盖范围内。
 */
class VaultFieldReferenceTest {

    private fun entry(
        name: String,
        meta: Map<String, String>,
        valueEncrypted: String = "example-cipher-text",
    ) = VaultCredentialEntity(
        name = name,
        metaJson = CredentialMeta.encode(meta),
        valueEncrypted = valueEncrypted,
    )

    // ---------- 引用串切分 ----------

    @Test
    fun `reference without a dot is a main-value reference`() {
        assertEquals(VaultRef("EXAMPLE_TOKEN", null), parseVaultRef("EXAMPLE_TOKEN"))
        assertEquals(VaultRef("EXAMPLE_TOKEN", null), parseVaultRef("  EXAMPLE_TOKEN  "))
    }

    @Test
    fun `first dot separates name from field and later dots stay in the field`() {
        assertEquals(VaultRef("EXAMPLE_S3", "access_key_id"), parseVaultRef("EXAMPLE_S3.access_key_id"))
        // custom.<标签> 自带点：整段都要留给字段，切在第一个点才不会把备注名切没
        assertEquals(VaultRef("EXAMPLE_S3", "custom.备注.a"), parseVaultRef("EXAMPLE_S3.custom.备注.a"))
    }

    @Test
    fun `trailing dot yields an empty field rather than a nameless one`() {
        assertEquals(VaultRef("EXAMPLE_S3", ""), parseVaultRef("EXAMPLE_S3."))
        assertEquals(VaultRef("", ""), parseVaultRef("."))
    }

    // ---------- 字段解析规则 ----------

    @Test
    fun `field resolves a whitelisted plaintext key`() {
        val meta = mapOf("access_key_id" to "example-ak-id-0001", "region" to "ap-east-1")
        assertEquals("example-ak-id-0001", CredentialMeta.resolveField(meta, "access_key_id"))
        assertEquals("ap-east-1", CredentialMeta.resolveField(meta, "region"))
    }

    @Test
    fun `unknown field and blank field resolve to null`() {
        val meta = mapOf("region" to "ap-east-1")
        assertNull(CredentialMeta.resolveField(meta, "no_such_key"))
        assertNull(CredentialMeta.resolveField(meta, ""))
        assertNull(CredentialMeta.resolveField(meta, "   "))
        assertNull(CredentialMeta.resolveField(emptyMap(), "region"))
    }

    @Test
    fun `custom field is reachable both spelled with the prefix and by bare name`() {
        val meta = mapOf(CredentialMeta.CUSTOM_PREFIX + "平台" to "example-huawei-obs")
        assertEquals("example-huawei-obs", CredentialMeta.resolveField(meta, "custom.平台"))
        assertEquals("example-huawei-obs", CredentialMeta.resolveField(meta, "平台"))
    }

    @Test
    fun `standard key wins over a custom key sharing its name`() {
        // 冲突规则：同一字段名同时是白名单键和 custom.<同名> 时，以标准键为准
        val meta = mapOf(
            "region" to "ap-east-1",
            CredentialMeta.CUSTOM_PREFIX + "region" to "写在备注里的旧值",
        )
        assertEquals("ap-east-1", CredentialMeta.resolveField(meta, "region"))
        assertEquals("写在备注里的旧值", CredentialMeta.resolveField(meta, "custom.region"))
    }

    @Test
    fun `value fingerprint is not referenceable`() {
        val meta = mapOf("value_fp" to "0123456789abcdef", "region" to "ap-east-1")
        assertNull(CredentialMeta.resolveField(meta, "value_fp"))
        // 拒的是那条指纹值，不是这个名字：用户自己写的 custom.value_fp 备注仍可引用
        assertEquals(
            "example-note",
            CredentialMeta.resolveField(mapOf(CredentialMeta.CUSTOM_PREFIX + "value_fp" to "example-note"), "value_fp"),
        )
    }

    @Test
    fun `referenceable fields match the field resolver key for key`() {
        val meta = mapOf(
            "access_key_id" to "example-ak-id-0001",
            "value_fp" to "0123456789abcdef",
            CredentialMeta.CUSTOM_PREFIX + "平台" to "example-huawei-obs",
        )
        val fields = CredentialMeta.referenceableFields(meta)
        // 指纹被剔；custom. 同时注册显式键与裸名回退键
        assertEquals(setOf("access_key_id", "custom.平台", "平台"), fields.keys)
        fields.forEach { (key, value) -> assertEquals(value, CredentialMeta.resolveField(meta, key)) }
    }

    @Test
    fun `referenceable fields read from the stored json`() {
        val fields = CredentialMeta.referenceableFields(
            CredentialMeta.encode(mapOf("endpoint" to "https://api.example.com/v1")),
        )
        assertEquals(mapOf("endpoint" to "https://api.example.com/v1"), fields)
        assertEquals(emptyMap<String, String>(), CredentialMeta.referenceableFields(""))
    }

    // ---------- 解析器的字段判定（不碰库的那一层）----------

    @Test
    fun `missing entry is reported as missing, not as a missing field`() {
        val resolution = CredentialResolver.fieldResolution(null, "EXAMPLE_S3", "access_key_id")
        assertTrue(resolution is CredentialResolution.Missing)
    }

    @Test
    fun `field grants the plaintext meta value and keeps the entry`() {
        val e = entry("EXAMPLE_S3", mapOf("access_key_id" to "example-ak-id-0001"))
        val resolution = CredentialResolver.fieldResolution(e, "EXAMPLE_S3", "access_key_id")
        assertEquals("example-ak-id-0001", (resolution as CredentialResolution.Granted).value)
        assertEquals("EXAMPLE_S3", resolution.entry.name)
    }

    @Test
    fun `field never yields the encrypted main value`() {
        val e = entry("EXAMPLE_S3", mapOf("access_key_id" to "example-ak-id-0001"))
        // `.value` 之类不是元数据键：字段语法碰不到主值，主值只有 `$$名字` 一条路
        assertTrue(CredentialResolver.fieldResolution(e, "EXAMPLE_S3", "value") is CredentialResolution.MissingField)
        assertTrue(CredentialResolver.fieldResolution(e, "EXAMPLE_S3", "sk") is CredentialResolution.MissingField)
    }

    @Test
    fun `missing field message names both the credential and the field`() {
        val e = entry("EXAMPLE_S3", mapOf("region" to "ap-east-1"))
        val resolution = CredentialResolver.fieldResolution(e, "EXAMPLE_S3", "access_key_id")
        assertTrue(resolution is CredentialResolution.MissingField)
        val message = resolution.message
        assertTrue(message.contains("EXAMPLE_S3"))
        assertTrue(message.contains("access_key_id"))
    }

    // ---------- 同步解析缓存（provider 密钥 / MCP 头 / S3 / WebDAV 走这条路）----------

    @Test
    fun `cache carries both main values and field keys`() {
        val entries = listOf(
            entry(
                "EXAMPLE_S3",
                mapOf(
                    "access_key_id" to "example-ak-id-0001",
                    "region" to "ap-east-1",
                    "value_fp" to "0123456789abcdef",
                    CredentialMeta.CUSTOM_PREFIX + "平台" to "example-huawei-obs",
                ),
            ),
            entry("EXAMPLE_TOKEN", emptyMap()),
        )
        val cache = VaultProviderKeyRefs.buildCache(entries) { e -> "secret-of-${e.name}" }
        assertEquals("secret-of-EXAMPLE_S3", cache["EXAMPLE_S3"])
        assertEquals("example-ak-id-0001", cache["EXAMPLE_S3.access_key_id"])
        assertEquals("ap-east-1", cache["EXAMPLE_S3.region"])
        assertEquals("example-huawei-obs", cache["EXAMPLE_S3.custom.平台"])
        assertEquals("example-huawei-obs", cache["EXAMPLE_S3.平台"])
        assertEquals("secret-of-EXAMPLE_TOKEN", cache["EXAMPLE_TOKEN"])
        assertNull("指纹不得进缓存", cache["EXAMPLE_S3.value_fp"])
    }

    @Test
    fun `field key never overrides an existing whole-name key`() {
        // 历史脏数据兜底：库里真有一条名字带点的条目时，整名优先 —— 与解析层同一条规则
        val entries = listOf(
            entry("EXAMPLE_S3", mapOf("region" to "ap-east-1")),
            entry("EXAMPLE_S3.region", emptyMap(), valueEncrypted = "legacy-cipher"),
        )
        val cache = VaultProviderKeyRefs.buildCache(entries) { e -> "secret-of-${e.name}" }
        assertEquals("secret-of-EXAMPLE_S3.region", cache["EXAMPLE_S3.region"])
    }

    @Test
    fun `undecryptable entry still exposes its plaintext fields`() {
        // 主值解不开（密钥变更）不该连带把明文标识也变没：AK ID 仍然可读可用
        val cache = VaultProviderKeyRefs.buildCache(
            listOf(entry("EXAMPLE_S3", mapOf("access_key_id" to "example-ak-id-0001"))),
        ) { null }
        assertNull(cache["EXAMPLE_S3"])
        assertEquals("example-ak-id-0001", cache["EXAMPLE_S3.access_key_id"])
    }
}
