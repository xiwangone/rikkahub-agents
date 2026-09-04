package me.rerere.rikkahub.data.sync

import android.content.Context
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File

/**
 * 备份加密执行管理器：统一 WebDAV / S3 / 本地备份的"按需加密/解密"入口。
 *
 * 数据流：
 * - 备份：prepareBackupFile 产出明文 zip → [maybeEncrypt] → enabled 时加密为
 *   `.zip.enc` 上传（保留原始 zip 名 + .enc 后缀，list/过滤按后缀识别）；
 *   未启用时原样返回明文 zip，与旧版完全兼容。
 * - 恢复：下载/选定文件 → [maybeDecrypt] → 若为 .enc（magic 识别）则用口令解密回
 *   临时 zip 再交给 restoreFromBackupFile；明文 zip 原样放行。
 *
 * 口令来源：[Settings.backupEncryptionPasswordEnc]（AndroidKeyStore 包裹的"记住口令"）。
 * 启用加密但本机无口令（新设备恢复/用户清除了记住）→ 抛 [IllegalStateException]，
 * 由 UI 捕获并弹框要求输入口令后重试（[decryptWithPassword] 走显式口令路径）。
 */
class BackupEncryptionManager(
    private val settingsStore: SettingsStore,
) {
    private fun currentSettings(): Settings = settingsStore.settingsFlow.value

    val isEnabled: Boolean
        get() = currentSettings().backupEncryptionEnabled

    /** 记住的口令明文；未记住 / 密钥丢失返回 null。 */
    val rememberedPassword: String?
        get() {
            val enc = currentSettings().backupEncryptionPasswordEnc
            if (enc.isBlank()) return null
            return BackupPasswordCipher.decrypt(enc)
        }

    /**
     * 备份侧：按全局开关加密 [plainZip]。
     * @return 待上传文件（加密时为新 .enc 文件，临时目录；否则原 [plainZip]）。
     */
    fun maybeEncrypt(plainZip: File): File {
        if (!isEnabled) return plainZip
        val password = rememberedPassword
            ?: throw IllegalStateException("备份加密已开启，但本机未记住口令。请先在「备份与恢复 → 加密设置」设置口令。")
        val encFile = File(plainZip.parentFile, plainZip.name + ".enc")
        BackupCrypto.encryptFile(plainZip, encFile, password)
        return encFile
    }

    /**
     * 恢复侧：判断文件是否加密容器，是则用 [password]（或记住的口令）解密。
     * @return 可交给 restoreFromBackupFile 的明文 zip（临时文件，调用方负责删除）。
     * @throws IllegalArgumentException 口令错误 / 文件损坏。
     * @throws IllegalStateException 加密文件但无口令可用。
     */
    fun maybeDecrypt(backupFile: File, password: String? = null): File {
        if (!BackupCrypto.isEncrypted(backupFile)) return backupFile
        val pwd = password ?: rememberedPassword
            ?: throw IllegalStateException("备份文件已加密，需要口令才能恢复。")
        val plainFile = File(backupFile.parentFile, backupFile.name.removeSuffix(".enc") + ".plain")
        BackupCrypto.decryptFile(backupFile, plainFile, pwd)
        return plainFile
    }
}
