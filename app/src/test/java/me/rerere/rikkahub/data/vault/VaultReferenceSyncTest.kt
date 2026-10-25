package me.rerere.rikkahub.data.vault

import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.telegram.TelegramBotConfig
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引用改写与引用提取的边界契约。
 *
 * 这两者决定"改名/合并是否安全"：改多了会误伤别的引用，改少了会留下悬空引用。
 * 前缀相似（`$$A` vs `$$AB`）是最容易出错的地方，必须钉住。
 */
class VaultReferenceSyncTest {

    @Test
    fun `renames an exact reference`() {
        assertEquals(
            "Bearer \$\$NEW_NAME",
            VaultReferenceSync.renameInText("Bearer \$\$OLD_NAME", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `does not touch a longer name sharing the prefix`() {
        assertEquals(
            "\$\$OLD_NAME_EXTRA",
            VaultReferenceSync.renameInText("\$\$OLD_NAME_EXTRA", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `renames inside a multi-value list`() {
        assertEquals(
            "\$\$NEW_NAME,\$\$OTHER",
            VaultReferenceSync.renameInText("\$\$OLD_NAME,\$\$OTHER", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `leaves plain text untouched`() {
        assertEquals("OLD_NAME without prefix", VaultReferenceSync.renameInText("OLD_NAME without prefix", "OLD_NAME", "NEW_NAME"))
    }

    @Test
    fun `no-op when names are equal or blank`() {
        assertEquals("\$\$A", VaultReferenceSync.renameInText("\$\$A", "A", "A"))
        assertEquals("\$\$A", VaultReferenceSync.renameInText("\$\$A", "", "B"))
        assertEquals("\$\$A", VaultReferenceSync.renameInText("\$\$A", "A", ""))
    }

    @Test
    fun `extractRefs finds all referenced names`() {
        assertEquals(
            listOf("A", "B"),
            VaultReferenceLocator.extractRefs("x \$\$A y \$\$B z"),
        )
    }

    @Test
    fun `extractRefs returns empty for plain text`() {
        assertEquals(emptyList<String>(), VaultReferenceLocator.extractRefs("no refs here"))
        assertEquals(emptyList<String>(), VaultReferenceLocator.extractRefs(""))
    }

    @Test
    fun `extractRefs deduplicates`() {
        assertEquals(listOf("A"), VaultReferenceLocator.extractRefs("\$\$A and \$\$A"))
    }

    // ---------- 字段引用（`$$名字.字段`）：改名链路必须认得、且不能改坏 ----------

    @Test
    fun `renames the credential part of a field reference and keeps the field`() {
        assertEquals(
            "\$\$NEW_NAME.access_key_id",
            VaultReferenceSync.renameInText("\$\$OLD_NAME.access_key_id", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `extractRefs reads the credential name out of a field reference`() {
        // 悬空引用检查据此判定"这条引用指向哪个凭证"：把 `A.region` 当成一个名叫 A.region 的凭证就漏报了
        assertEquals(listOf("A"), VaultReferenceLocator.extractRefs("\$\$A.region"))
    }

    @Test
    fun `rename updates every newly supported settings credential without touching unrelated fields`() {
        val reference = "Bearer \$\$OLD_NAME"
        val original = Settings().copy(
            mediaGenerationProviders = listOf(MediaGenerationProviderSetting.OpenAI(apiKey = reference)),
            searchServices = listOf(
                SearchServiceOptions.TavilyOptions(apiKey = reference),
                SearchServiceOptions.SearXNGOptions(password = reference),
            ),
            asrProviders = listOf(ASRProviderSetting.Step(apiKey = reference)),
            ttsProviders = listOf(TTSProviderSetting.OpenAI(apiKey = reference)),
            s3Config = S3Config(secretAccessKey = reference),
            s3Configs = listOf(S3Config(id = "synthetic-profile", secretAccessKey = reference)),
            uploadS3Config = S3Config(name = "synthetic-upload", secretAccessKey = reference),
            networkSetting = NetworkSetting(proxyPassword = reference),
            webBridgePassword = reference,
            webBridgePrivateKeyPath = reference,
            webServerAccessPassword = reference,
        )

        val (updated, changed) = VaultReferenceSync.renameCredentialReferencesInSettings(
            settings = original,
            oldName = "OLD_NAME",
            newName = "NEW_NAME",
        )

        assertEquals(12, changed)
        assertEquals(reference, original.mediaGenerationProviders.single().apiKey)
        assertEquals("Bearer \$\$NEW_NAME", updated.mediaGenerationProviders.single().apiKey)
        assertEquals("Bearer \$\$NEW_NAME", (updated.searchServices[0] as SearchServiceOptions.TavilyOptions).apiKey)
        assertEquals("Bearer \$\$NEW_NAME", (updated.searchServices[1] as SearchServiceOptions.SearXNGOptions).password)
        assertEquals("Bearer \$\$NEW_NAME", (updated.asrProviders.single() as ASRProviderSetting.Step).apiKey)
        assertEquals("Bearer \$\$NEW_NAME", (updated.ttsProviders.single() as TTSProviderSetting.OpenAI).apiKey)
        assertEquals("Bearer \$\$NEW_NAME", updated.s3Config.secretAccessKey)
        assertEquals("Bearer \$\$NEW_NAME", updated.s3Configs.single().secretAccessKey)
        assertEquals("Bearer \$\$NEW_NAME", updated.uploadS3Config.secretAccessKey)
        assertEquals("Bearer \$\$NEW_NAME", updated.networkSetting.proxyPassword)
        assertEquals("Bearer \$\$NEW_NAME", updated.webBridgePassword)
        assertEquals("Bearer \$\$NEW_NAME", updated.webBridgePrivateKeyPath)
        assertEquals("Bearer \$\$NEW_NAME", updated.webServerAccessPassword)
        assertEquals(original.titlePrompt, updated.titlePrompt)
    }

    @Test
    fun `rename updates Telegram token and proxy references but preserves literals`() {
        val original = TelegramBotConfig(
            token = "\$\$OLD_NAME",
            proxyPassword = "Bearer \$\$OLD_NAME",
        )
        val (updated, changed) = VaultReferenceSync.renameTelegramCredentialReferences(
            config = original,
            oldName = "OLD_NAME",
            newName = "NEW_NAME",
        )

        assertEquals(2, changed)
        assertEquals("\$\$OLD_NAME", original.token)
        assertEquals("\$\$NEW_NAME", updated.token)
        assertEquals("Bearer \$\$NEW_NAME", updated.proxyPassword)
        val (literal, literalChanged) = VaultReferenceSync.renameTelegramCredentialReferences(
            TelegramBotConfig(token = "synthetic-literal-token", proxyPassword = "synthetic-literal-password"),
            "OLD_NAME",
            "NEW_NAME",
        )
        assertEquals(0, literalChanged)
        assertEquals("synthetic-literal-token", literal.token)
        assertEquals("synthetic-literal-password", literal.proxyPassword)
    }
}
