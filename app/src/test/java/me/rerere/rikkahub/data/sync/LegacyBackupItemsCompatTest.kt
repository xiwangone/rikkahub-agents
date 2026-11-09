package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧配置（含聚合项 FILES）兼容回归：展开必须包含 AVATARS 且**保留 FILES 标记**，
 * 这样 `Settings.activeWebDavConfig()/activeS3Config()` 在返回前展开后，
 * settings.json 的兼容判断仍能识别出旧配置（3.0.3 发布包正是丢在这里）。
 */
class LegacyBackupItemsCompatTest {

    @Test
    fun `webdav legacy FILES expands to granular items including avatars and keeps marker`() {
        val expanded = WebDavConfig(
            items = listOf(
                WebDavConfig.BackupItem.DATABASE,
                WebDavConfig.BackupItem.FILES,
            ),
        ).withLegacyExpanded().items.toSet()

        assertEquals(
            setOf(
                WebDavConfig.BackupItem.DATABASE,
                WebDavConfig.BackupItem.FILES,
                WebDavConfig.BackupItem.SKILLS,
                WebDavConfig.BackupItem.CHAT_FILES,
                WebDavConfig.BackupItem.FONTS_IMAGES,
                WebDavConfig.BackupItem.AVATARS,
                WebDavConfig.BackupItem.MEDIA_CREATION,
            ),
            expanded,
        )
    }

    @Test
    fun `webdav legacy config keeps settings json by compat`() {
        assertTrue(
            WebDavConfig(
                items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES),
            ).includesSettingsByLegacyCompat(),
        )
        // 只勾数据库的旧配置：历史上默认带 settings.json
        assertTrue(
            WebDavConfig(items = listOf(WebDavConfig.BackupItem.DATABASE))
                .includesSettingsByLegacyCompat(),
        )
        assertTrue(
            WebDavConfig(
                items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.SETTINGS),
            ).includesSettingsByLegacyCompat(),
        )
    }

    @Test
    fun `webdav compat still holds after expansion so early-expanded configs do not drop settings`() {
        // 真实链路：BackupVM 传入 settings.activeWebDavConfig()，该访问器先展开再交给 sync。
        // 展开后必须仍判定为“旧配置”，否则 settings.json 被静默丢弃（发布包缺陷）。
        assertTrue(
            WebDavConfig(
                items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES),
            ).withLegacyExpanded().includesSettingsByLegacyCompat(),
        )
        // 展开幂等：重复展开不应产生重复项，也不应丢掉 FILES 标记
        val twice = WebDavConfig(
            items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES),
        ).withLegacyExpanded().withLegacyExpanded()
        assertTrue(twice.includesSettingsByLegacyCompat())
        assertEquals(twice.items.size, twice.items.distinct().size)
    }

    @Test
    fun `webdav new granular config without settings does not include settings json`() {
        assertFalse(
            WebDavConfig(
                items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.AVATARS),
            ).includesSettingsByLegacyCompat(),
        )
        assertFalse(
            WebDavConfig(
                items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.PROVIDERS),
            ).includesSettingsByLegacyCompat(),
        )
    }

    @Test
    fun `s3 legacy FILES expands to granular items including avatars and keeps marker`() {
        val expanded = S3Config(
            items = listOf(
                S3Config.BackupItem.DATABASE,
                S3Config.BackupItem.FILES,
            ),
        ).withLegacyExpanded().items.toSet()

        assertEquals(
            setOf(
                S3Config.BackupItem.DATABASE,
                S3Config.BackupItem.FILES,
                S3Config.BackupItem.SKILLS,
                S3Config.BackupItem.CHAT_FILES,
                S3Config.BackupItem.FONTS_IMAGES,
                S3Config.BackupItem.AVATARS,
                S3Config.BackupItem.MEDIA_CREATION,
            ),
            expanded,
        )
    }

    @Test
    fun `s3 legacy compat detection matches webdav semantics`() {
        assertTrue(
            S3Config(
                items = listOf(S3Config.BackupItem.DATABASE, S3Config.BackupItem.FILES),
            ).includesSettingsByLegacyCompat(),
        )
        assertFalse(
            S3Config(
                items = listOf(S3Config.BackupItem.DATABASE, S3Config.BackupItem.AVATARS),
            ).includesSettingsByLegacyCompat(),
        )
    }

    @Test
    fun `s3 compat still holds after expansion`() {
        assertTrue(
            S3Config(
                items = listOf(S3Config.BackupItem.DATABASE, S3Config.BackupItem.FILES),
            ).withLegacyExpanded().includesSettingsByLegacyCompat(),
        )
    }
}
