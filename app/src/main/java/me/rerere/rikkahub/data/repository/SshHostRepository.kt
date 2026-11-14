package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.log.AppLog

import me.rerere.rikkahub.data.datastore.ProviderCredentialCipher
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity

/** 密文前缀：用于区分「已是密文」与「历史明文」，实现平滑升级 + 幂等加密。 */
private const val ENC_PREFIX = "enc:v1:"

/**
 * SSH 主机存取。**本类是 ssh_hosts 的唯一出入口**（`SshHostDao` 只在此处使用），
 * 因此把三格敏感值（password / privateKey / passphrase）的**加解密放在这一层**：
 * 上层（工具 / 设置页 / 连接逻辑）拿到的永远是明文，写法与从前一致。
 *
 * 为什么这么做：这三格此前以明文落 Room；现在改为落盘即密文（AES-GCM，密钥在
 * AndroidKeyStore，随应用卸载清除），用户仍然照填、无需理解引用语法。
 * `$$凭据名` 引用是**另一套机制**（引用名本身不是秘密），与这里不冲突 ——
 * 它同样被加密存储，读取时原样还原。
 *
 * 兼容与失败语义：
 * - 历史明文（无前缀）读取时原样返回，不需要迁移；
 * - 密钥丢失（卸载重装、跨设备恢复备份）导致解密失败时**绝不把密文原文当密码发出**：
 *   单主机读取（[getByName]/[getByHost]，连接路径）抛 [UndecryptableCredentialException]，
 *   调用方须向用户明示「凭证无法解密」并请其重填；
 *   列表读取（[getAll]，仅供展示/备份/引用扫描，不作认证材料）逐主机宽容：
 *   解不开的字段保留密文原文（保盘上数据与备份可还原性），并记一条警告日志。
 *   宽容只适用于列表路径——任何连接用途必须走单主机读取，解不开时抛错。
 *   此分层与 providers 字段级加密的 degraded 口径一致：用时快速失败，盘上原密文不丢。
 */
/**
 * 已加密字段解不开：Keystore 密钥丢失（卸载重装、跨设备恢复备份）或密文损坏。
 * 密文原文绝不能作为密码/私钥发出——调用方须向用户明示「凭证无法解密」，请其重填。
 */
class UndecryptableCredentialException(
    val hostName: String,
    val field: String,
) : IllegalStateException("Credential cannot be decrypted: $field for host $hostName (the key may be lost). Re-enter the credentials for this host.")

class SshHostRepository(private val dao: SshHostDao) {
    /**
     * 列表读取：仅供展示/备份/引用扫描。解不开的字段保留密文原文（见类注释分层口径），
     * 绝不能把返回实体的 secret 字段当作认证材料——连接一律走 [getByName]/[getByHost]。
     */
    suspend fun getAll(): List<SshHostEntity> = dao.getAll().map { it.withSecretsDecrypted(lenient = true) }

    /** 单主机读取（连接路径）：任一加密字段解不开即抛 [UndecryptableCredentialException]。 */
    suspend fun getByName(name: String): SshHostEntity? = dao.getByName(name)?.withSecretsDecrypted(lenient = false)

    /** 单主机读取（连接路径）：任一加密字段解不开即抛 [UndecryptableCredentialException]。 */
    suspend fun getByHost(host: String): SshHostEntity? = dao.getByHost(host)?.withSecretsDecrypted(lenient = false)

    suspend fun upsert(host: SshHostEntity) {
        AppLog.i("SshHostRepo", "upsert: ${host.name} (${host.host}:${host.port})")
        dao.upsert(host.withSecretsEncrypted())
    }

    suspend fun deleteByName(name: String) {
        AppLog.i("SshHostRepo", "delete: $name")
        dao.deleteByName(name)
    }
}

private fun SshHostEntity.withSecretsEncrypted(): SshHostEntity = copy(
    password = password?.encryptSecret(),
    privateKey = privateKey?.encryptSecret(),
    passphrase = passphrase?.encryptSecret(),
)

private fun SshHostEntity.withSecretsDecrypted(lenient: Boolean): SshHostEntity = copy(
    password = password?.decryptSecret(name, "password", lenient),
    privateKey = privateKey?.decryptSecret(name, "privateKey", lenient),
    passphrase = passphrase?.decryptSecret(name, "passphrase", lenient),
)

private fun String.encryptSecret(): String =
    if (startsWith(ENC_PREFIX)) this else ENC_PREFIX + ProviderCredentialCipher.encrypt(this)

/**
 * 解密单个字段。解不开时绝不回退密文原文：
 * - 严格模式（连接路径）：抛 [UndecryptableCredentialException]，调用方明示用户；
 * - 宽容模式（列表路径）：记警告日志并保留密文原文，仅供展示/备份/引用扫描。
 */
private fun String.decryptSecret(hostName: String, field: String, lenient: Boolean): String =
    if (!startsWith(ENC_PREFIX)) {
        this
    } else {
        ProviderCredentialCipher.decrypt(removePrefix(ENC_PREFIX)) ?: run {
            if (lenient) {
                AppLog.w("SshHostRepo", "$field for host $hostName cannot be decrypted (the key may be lost); keeping the ciphertext in the list, the connection will report a clear error")
                this
            } else {
                throw UndecryptableCredentialException(hostName, field)
            }
        }
    }
