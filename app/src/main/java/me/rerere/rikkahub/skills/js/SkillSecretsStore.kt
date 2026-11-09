package me.rerere.rikkahub.skills.js

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.data.crypto.KeystoreAesKey
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "SkillSecretsStore"

/**
 * Phase 18 — encrypted per-skill secret storage. Skills that hit a third-party API
 * (Wikipedia behind auth, OpenWeatherMap, etc.) ask the user for an API key which we
 * persist locally encrypted. The `run_js` tool reads via [get] when the LLM passes
 * `secret_key` in args.
 *
 * Encryption: AES/GCM with an Android-Keystore-backed master key. If the keystore is
 * unavailable the write is refused (no plaintext fallback): a third-party API key
 * written in plaintext to SharedPreferences could be extracted by backup/root. The
 * failure is recorded in the `security_degraded` flag so the Settings UI can warn the
 * user; a later successful write clears the flag again. Entries written by older builds
 * with the plaintext fallback marker are rejected on read for the same reason.
 *
 * Key per (skillName, secretName) so different skills can't read each other's secrets.
 * Backup is excluded (keys aren't transferable; re-entry on new device is intentional).
 */
class SkillSecretsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun set(skillName: String, secretName: String, value: String) {
        val (encrypted, iv) = encrypt(value)
        prefs.edit {
            putString(prefKey(skillName, secretName), encrypted)
            putString(ivKey(skillName, secretName), iv)
        }
    }

    fun get(skillName: String, secretName: String): String? {
        val encrypted = prefs.getString(prefKey(skillName, secretName), null) ?: return null
        // Read the IV from the current namespace, falling back to the legacy
        // `skill_secret_iv_` key written before the namespace split so secrets
        // stored by older builds still decrypt.
        val iv = prefs.getString(ivKey(skillName, secretName), null)
            ?: prefs.getString(legacyIvKey(skillName, secretName), null)
            ?: return null
        return decrypt(encrypted, iv)
    }

    fun remove(skillName: String, secretName: String) {
        prefs.edit {
            remove(prefKey(skillName, secretName))
            remove(ivKey(skillName, secretName))
            remove(legacyIvKey(skillName, secretName))
        }
    }

    /** All (skillName, secretName) pairs the user currently has stored. */
    fun list(): List<Pair<String, String>> {
        // New writes put IVs under their own `skill_iv_` namespace (disjoint from
        // `skill_secret_`), so a secret never shares a prefix with an IV.
        //
        // The one hard case is legacy data: legacy IVs were written under
        // `skill_secret_iv_<skill>__<name>`, which is byte-for-byte identical to the
        // *secret* key of a skill literally named `iv_<skill>`. They are told apart by
        // the fact that a legacy IV always has a sibling secret key
        // `skill_secret_<skill>__<name>` present; a real `iv_`-named secret does not.
        // So a `skill_secret_iv_*` key is treated as a legacy IV (and skipped) only
        // when that sibling exists, which both keeps `iv_`-named skills' secrets and
        // avoids surfacing legacy IVs as phantom secret entries.
        val allKeys = prefs.all.keys
        return allKeys.mapNotNull { k ->
            if (!k.startsWith(SECRET_PREFIX)) return@mapNotNull null
            val rest = k.removePrefix(SECRET_PREFIX)
            val sep = rest.indexOf("__")
            if (sep <= 0) return@mapNotNull null
            if (k.startsWith(LEGACY_IV_PREFIX)) {
                // Candidate legacy IV: skip it iff the secret it would belong to exists.
                val ivRest = k.removePrefix(LEGACY_IV_PREFIX)
                if (allKeys.contains(SECRET_PREFIX + ivRest)) return@mapNotNull null
            }
            rest.substring(0, sep) to rest.substring(sep + 2)
        }
    }

    /** Remove every secret stored for a skill, used when the user uninstalls it. */
    fun removeAllForSkill(skillName: String) {
        // Match the secret key, the current IV key, and the legacy IV key so an
        // uninstall written by an older build leaves nothing behind.
        val toRemove = prefs.all.keys.filter {
            it.startsWith("$SECRET_PREFIX$skillName" + "__") ||
                it.startsWith("$IV_PREFIX$skillName" + "__") ||
                it.startsWith("$LEGACY_IV_PREFIX$skillName" + "__")
        }
        if (toRemove.isEmpty()) return
        prefs.edit { toRemove.forEach { remove(it) } }
    }

    // -- crypto -------------------------------------------------------------

    private fun encrypt(plain: String): Pair<String, String> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        // Keystore 恢复可用：一次成功写入即清除此前的降级标记，否则瞬时故障会永久误报。
        prefs.edit { remove(PREF_KEY_SECURITY_DEGRADED) }
        encode(encrypted) to encode(iv)
    } catch (t: Throwable) {
        // Keystore 不可用时不再把明文（Base64 只是编码不是加密）落盘：
        // 第三方 API key 明文写进 SharedPreferences 会被备份/root 提取。
        // 标记降级并拒绝写入，调用方按异常提示用户。
        prefs.edit { putBoolean(PREF_KEY_SECURITY_DEGRADED, true) }
        AppLog.e(TAG, "Keystore-backed encrypt failed; refusing to store secret in plaintext", t)
        throw IllegalStateException("Skill secret storage unavailable (keystore failure); secret NOT saved", t)
    }

    /** Whether a keystore failure has been observed since the last successful write. */
    fun isSecurityDegraded(): Boolean =
        prefs.getBoolean(PREF_KEY_SECURITY_DEGRADED, false)

    private fun decrypt(encrypted: String, iv: String): String? = try {
        if (iv == FALLBACK_IV_MARKER) {
            // 旧版明文回退条目（Base64 只是编码不是加密）：拒绝解密，调用方按"无密钥"
            // 处理并提示用户重新输入，绝不把明文密钥再交回给技能。
            AppLog.w(TAG, "Rejecting legacy plaintext skill secret entry; re-entry required")
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, decode(iv)))
            String(cipher.doFinal(decode(encrypted)), StandardCharsets.UTF_8)
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "Decrypt failed", t)
        null
    }

    private fun getOrCreateKey(): SecretKey = KeystoreAesKey.getOrCreate(KEY_ALIAS)

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun decode(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    private fun prefKey(skill: String, name: String): String = "$SECRET_PREFIX${skill}__${name}"
    private fun ivKey(skill: String, name: String): String = "$IV_PREFIX${skill}__${name}"

    // Legacy IV key shape (`skill_secret_iv_<skill>__<name>`) written before the IV
    // namespace was split out. Kept for backward-read so secrets stored by older
    // builds still decrypt; new writes use [ivKey].
    private fun legacyIvKey(skill: String, name: String): String =
        "$LEGACY_IV_PREFIX${skill}__${name}"

    companion object {
        private const val PREFS_NAME = "skill_secrets"
        private const val KEY_ALIAS = "rikkahub_skill_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FALLBACK_IV_MARKER = "__no_keystore__"
        private const val PREF_KEY_SECURITY_DEGRADED = "security_degraded"

        // Secrets and IVs live under disjoint top-level prefixes. The old scheme put
        // IVs under `skill_secret_iv_`, a superset of the secret prefix, which forced
        // list() into an exclusion filter that wrongly dropped any skill whose name
        // started with `iv_`. The disjoint prefixes make list() a plain prefix match.
        private const val SECRET_PREFIX = "skill_secret_"
        private const val IV_PREFIX = "skill_iv_"
        private const val LEGACY_IV_PREFIX = "skill_secret_iv_"
    }
}
