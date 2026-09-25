package me.rerere.rikkahub.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住 [ImportedDatabaseReconciler] 的两个 schema 常量与导出的 schema 文件一致。
 *
 * 理由：这两个常量是"导入/恢复的库该被 stamp 成哪个 schema"的锚点，其注释本身就写着
 * "bump 版本时必须同步更新，否则**静默失配**"；而静默失配的后果是导入后首启崩溃。
 * 有了这条测试，改了版本却忘了同步常量会在**单测里直接红**，而不是等到用户装包后进不去。
 */
class ImportedDatabaseReconcilerConstantsTest {
    private val schemaDir: File? =
        listOf(
            "app/schemas/me.rerere.rikkahub.data.db.AppDatabase",
            "schemas/me.rerere.rikkahub.data.db.AppDatabase",
        ).map(::File).firstOrNull { it.isDirectory }

    @Test
    fun `expected version and identity hash match the exported schema`() {
        val dir = schemaDir
        assertTrue("找不到 schema 导出目录（尝试过 app/schemas/… 与 schemas/…）", dir != null)

        val schemaFile = File(dir, "${ImportedDatabaseReconciler.EXPECTED_VERSION}.json")
        assertTrue("schema 导出缺失：${schemaFile.path}", schemaFile.isFile)

        val database = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        assertEquals(
            "EXPECTED_VERSION 与 ${schemaFile.name} 不一致",
            ImportedDatabaseReconciler.EXPECTED_VERSION.toString(),
            database["version"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "EXPECTED_IDENTITY_HASH 与 ${schemaFile.name} 不一致",
            ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH,
            database["identityHash"]!!.jsonPrimitive.content,
        )
    }
}
