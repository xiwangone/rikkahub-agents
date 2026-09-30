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
 * - 密钥丢失（卸载重装、跨设备恢复备份）导致解密失败时**回退原值**，不丢数据，
 *   由连接层报错让用户重填。
 */
class SshHostRepository(private val dao: SshHostDao) {
    suspend fun getAll(): List<SshHostEntity> = dao.getAll().map { it.withSecretsDecrypted() }

    suspend fun getByName(name: String): SshHostEntity? = dao.getByName(name)?.withSecretsDecrypted()

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

private fun SshHostEntity.withSecretsDecrypted(): SshHostEntity = copy(
    password = password?.decryptSecret(),
    privateKey = privateKey?.decryptSecret(),
    passphrase = passphrase?.decryptSecret(),
)

private fun String.encryptSecret(): String =
    if (startsWith(ENC_PREFIX)) this else ENC_PREFIX + ProviderCredentialCipher.encrypt(this)

private fun String.decryptSecret(): String =
    if (!startsWith(ENC_PREFIX)) {
        this
    } else {
        ProviderCredentialCipher.decrypt(removePrefix(ENC_PREFIX)) ?: this
    }
