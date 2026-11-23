package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 隐私政策页可达性与七语言文案回归：设置搜索索引必须登记隐私政策路由，
 * 13 条 `privacy_policy_*` 字符串必须在 7 个语言的 strings.xml 里齐全，
 * 且占位符集合一致。
 *
 * 纯 JVM 测试：[settingsSearchIndex] 只拼数据类与 R 常量，不碰 Android 框架；
 * 字符串校验直接解析源码树下的 strings.xml（Gradle 单测工作目录为模块目录，
 * 从 user.dir 向上找 src/main/res 亦可覆盖 IDE 以工程根目录运行的情况）。
 */
class PrivacyPolicyTest {

    @Test
    fun `privacy policy is reachable from settings search`() {
        val entry = settingsSearchIndex(developerMode = false)
            .firstOrNull { it.route == Screen.SettingPrivacyPolicy }
        assertTrue("settings search index must contain the privacy policy page", entry != null)
        assertEquals(R.string.privacy_policy_title, entry!!.titleRes)
    }

    @Test
    fun `privacy policy strings exist in all seven languages with matching placeholders`() {
        val keys = listOf(
            "privacy_policy_title",
            "privacy_policy_desc",
            "privacy_policy_intro",
            "privacy_policy_data_title",
            "privacy_policy_data_body",
            "privacy_policy_network_title",
            "privacy_policy_network_body",
            "privacy_policy_storage_title",
            "privacy_policy_storage_body",
            "privacy_policy_permissions_title",
            "privacy_policy_permissions_body",
            "privacy_policy_source_title",
            "privacy_policy_source_body",
        )
        val langDirs = listOf(
            "values",
            "values-zh",
            "values-b+zh+Hant",
            "values-ja",
            "values-ko-rKR",
            "values-ru",
            "values-ar",
        )
        val resDir = findResDir()
        val byLang = langDirs.associateWith { dir ->
            parseStrings(File(resDir, "$dir/strings.xml"))
        }
        for (key in keys) {
            val placeholderSets = byLang.mapValues { (dir, map) ->
                val text = map[key]
                assertTrue("$key missing in $dir/strings.xml", text != null)
                PLACEHOLDER_REGEX.findAll(text!!).map { it.value }.toSet()
            }
            assertEquals(
                "placeholder mismatch for $key: $placeholderSets",
                1,
                placeholderSets.values.distinct().size,
            )
        }
    }

    private fun findResDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "src/main/res/values/strings.xml")
            if (candidate.isFile) return File(dir, "src/main/res")
            dir = dir.parentFile
        }
        error("cannot locate src/main/res from user.dir=${System.getProperty("user.dir")}")
    }

    private fun parseStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val map = mutableMapOf<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes.getNamedItem("name")?.nodeValue
                ?: error("string without name in $file")
            map[name] = el.textContent
        }
        return map
    }

    private companion object {
        val PLACEHOLDER_REGEX = Regex("%\\d*\\$[sd]|%[sd]")
    }
}
