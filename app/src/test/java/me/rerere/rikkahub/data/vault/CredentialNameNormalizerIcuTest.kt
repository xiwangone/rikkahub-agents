package me.rerere.rikkahub.data.vault

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实 ICU 音译的契约（Robolectric，跑真 `android.icu.text.Transliterator`）。
 *
 * 只钉"中文 → 拼音大写蛇形"这一条，不钉具体音节切分之外的任何细节：
 * 若 ICU 版本变了输出形态（比如音节间不再空格），这里先报错、再按实际输出调整期望，
 * 而不是让 `normalizeName` 静默退回 CRED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class CredentialNameNormalizerIcuTest {

    @Test
    fun `qiniu cloud transliterates to pinyin`() {
        assertEquals("QI_NIU_YUN", CredentialNameNormalizer.normalizeName("七牛云"))
    }

    @Test
    fun `shangtang transliterates to pinyin`() {
        assertEquals("SHANG_TANG", CredentialNameNormalizer.normalizeName("商汤"))
    }

    @Test
    fun `mixed chinese and latin transliterates`() {
        assertEquals("AI_SI_API", CredentialNameNormalizer.normalizeName("爱思API"))
    }
}
