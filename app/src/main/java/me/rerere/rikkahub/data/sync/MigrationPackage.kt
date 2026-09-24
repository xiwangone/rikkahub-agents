package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 备份「迁移包」的结构与编解码（**纯逻辑**，不碰文件系统与数据库）。
 *
 * 解决的问题：常规备份包里的凭证（`VaultCredentialEntity.valueEncrypted`）与 provider 配置
 * 都是**本机 Keystore 加密**的 —— 文件级口令加密只防「被人看」，换机后本机密钥不在就解不开。
 * 迁移包在**导出时**把这两块解成明文、放进 zip 的 `migration/` 段，再用同一口令加密整个包；
 * 导入时用**新设备 Keystore** 重新加密写回。
 *
 * ⚠ 红线：`migration/credentials.json` 与 `migration/providers.json` **含明文密钥**。
 * 它们只存在于「口令加密前」的中间产物里 —— 用完即删，**不得**留在应用目录、**不得**上传云盘。
 */
@Serializable
data class MigrationManifest(
    val version: Int = MigrationPackage.CURRENT_VERSION,
    val exportedAt: Long = 0,
    /** 本次导出的备份项（如 DATABASE / SETTINGS），便于导入侧核对。 */
    val items: List<String> = emptyList(),
    /** 加密方式标记：目前只有口令加密。 */
    val encryption: String = ENCRYPTION_PASSWORD,
) {
    companion object {
        const val ENCRYPTION_PASSWORD = "password"
    }
}

/** 一条凭证的**明文**形态（仅供迁移包内部使用）。 */
@Serializable
data class MigrationCredential(
    val name: String,
    val value: String,
    val type: String = "",
    val group: String = "",
    val description: String = "",
    /** 非敏感元数据（endpoint / header / username 等）的 JSON 串，原样搬运。 */
    val metaJson: String? = null,
    /** SSH / GPG 类条目的公钥行（非敏感）。 */
    val publicKey: String? = null,
)

object MigrationPackage {

    /** 迁移包内明文段所在的目录名。 */
    const val DIR = "migration"
    const val MANIFEST_NAME = "MANIFEST.json"
    const val CREDENTIALS_NAME = "credentials.json"
    const val PROVIDERS_NAME = "providers.json"

    /** 结构版本：加字段/改语义时递增，导入侧据此拒绝不兼容的包。 */
    const val CURRENT_VERSION = 1

    private val migrationJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun buildManifest(
        exportedAt: Long,
        items: List<String>,
    ): MigrationManifest =
        MigrationManifest(
            version = CURRENT_VERSION,
            exportedAt = exportedAt,
            items = items,
            encryption = MigrationManifest.ENCRYPTION_PASSWORD,
        )

    fun encodeManifest(manifest: MigrationManifest): String = migrationJson.encodeToString(manifest)

    /** 解析 MANIFEST；非法 JSON 或缺关键字段时返回 null（调用方据此判定「不是迁移包」）。 */
    fun parseManifest(raw: String): MigrationManifest? =
        runCatching { migrationJson.decodeFromString<MigrationManifest>(raw) }
            .getOrNull()
            ?.takeIf { it.version in 1..CURRENT_VERSION && it.encryption == MigrationManifest.ENCRYPTION_PASSWORD }

    fun encodeCredentials(credentials: List<MigrationCredential>): String =
        migrationJson.encodeToString(credentials)

    /** 解析凭证明文段；非法 JSON 返回 null（不静默当成空清单，避免「导入后凭证全没了」）。 */
    fun parseCredentials(raw: String): List<MigrationCredential>? =
        runCatching { migrationJson.decodeFromString<List<MigrationCredential>>(raw) }.getOrNull()

    /**
     * zip 条目名（相对路径）里是否含迁移段 —— 用于把「迁移包」与常规备份包区分开，
     * 避免用户拿旧包走新流程（反之亦然）。
     */
    fun looksLikeMigrationPackage(entryNames: Collection<String>): Boolean =
        entryNames.any { name -> name.trimStart('/') == "$DIR/$MANIFEST_NAME" }
}
