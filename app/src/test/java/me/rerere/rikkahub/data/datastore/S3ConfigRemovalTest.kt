package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S3 配置删除（Settings.withoutS3Config）的纯逻辑回归：
 * 删当前生效配置时生效配置顺延为剩余首个；删最后一个（列表仅剩一个或旧单配置模式）
 * 必须拒绝；不存在的 id 不做任何改动。
 */
class S3ConfigRemovalTest {

    private fun config(id: String, name: String) = S3Config(id = id, name = name)

    @Test
    fun `deleting the active config promotes the remaining one`() {
        val first = config("a", "first")
        val second = config("b", "second")
        val settings = Settings(
            s3Configs = listOf(first, second),
            activeS3ConfigId = "b",
            s3Config = second,
        )
        val result = settings.withoutS3Config("b")
        assertEquals(listOf(first), result?.s3Configs)
        assertEquals("a", result?.activeS3ConfigId)
        assertEquals(first, result?.s3Config)
    }

    @Test
    fun `deleting a non-active config keeps the active one`() {
        val first = config("a", "first")
        val second = config("b", "second")
        val settings = Settings(
            s3Configs = listOf(first, second),
            activeS3ConfigId = "a",
            s3Config = first,
        )
        val result = settings.withoutS3Config("b")
        assertEquals(listOf(first), result?.s3Configs)
        assertEquals("a", result?.activeS3ConfigId)
        assertEquals(first, result?.s3Config)
    }

    @Test
    fun `deleting the only listed config is refused`() {
        val only = config("a", "only")
        val settings = Settings(
            s3Configs = listOf(only),
            activeS3ConfigId = "a",
            s3Config = only,
        )
        assertNull(settings.withoutS3Config("a"))
    }

    @Test
    fun `deleting the legacy single config is refused`() {
        val legacy = config("legacy", "legacy")
        val settings = Settings(s3Config = legacy)
        assertNull(settings.withoutS3Config("legacy"))
    }

    @Test
    fun `unknown id leaves settings unchanged`() {
        val first = config("a", "first")
        val second = config("b", "second")
        val settings = Settings(
            s3Configs = listOf(first, second),
            activeS3ConfigId = "a",
            s3Config = first,
        )
        assertEquals(settings, settings.withoutS3Config("missing"))
        val legacySettings = Settings(s3Config = config("legacy", "legacy"))
        assertEquals(legacySettings, legacySettings.withoutS3Config("missing"))
    }
}
