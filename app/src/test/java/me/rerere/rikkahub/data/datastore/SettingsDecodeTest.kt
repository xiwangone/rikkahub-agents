package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SettingsDecodeTest {
    @Test
    fun `missing stored setting uses schema default`() {
        assertEquals(listOf("schema-default"), decodePersistedSetting(null, listOf("schema-default")))
    }

    @Test
    fun `valid stored setting decodes`() {
        assertEquals(listOf("provider-a"), decodePersistedSetting("[\"provider-a\"]", emptyList<String>()))
    }

    @Test
    fun `malformed present setting fails instead of returning a default`() {
        try {
            decodePersistedSetting("not-json", emptyList<String>())
            fail("Malformed persisted data must not silently become a default")
        } catch (_: IllegalArgumentException) {
            // Expected: the caller surfaces this as a failed initialization.
        }
    }

    /**
     * 回归：`SearchServiceOptions.DEFAULT` 的声明类型若是具体子类，这里泛型会被窄化成
     * `List<DuckDuckGoOptions>`，多态判别键 `type` 被 `ignoreUnknownKeys` 当未知键吞掉，
     * 盘上的 exa/tavily/… 全部静默解成 DuckDuckGo —— 即「添加任何搜索服务都变 DuckDuckGo」。
     * 调用形状必须与 PreferencesStore 的 searchServices 一致（用 DEFAULT 当 default 实参）。
     */
    @Test
    fun `persisted search service keeps its polymorphic type when decoded with the DEFAULT fallback`() {
        val raw = JsonInstant.encodeToString(listOf(SearchServiceOptions.create("exa")))
        assertEquals(true, raw.contains("\"type\":\"exa\""))

        val back: List<SearchServiceOptions> = decodePersistedSetting(raw, listOf(SearchServiceOptions.DEFAULT))

        assertEquals(1, back.size)
        assertEquals("exa", back.single().typeKey)
        assertEquals("Exa", back.single().displayName)
    }

    /** 逐类型往返：写入 → 读回，typeKey 必须与写入时一致（窄化会让这里成片变成 duckduckgo）。 */
    @Test
    fun `every addable search service type round-trips through the persisted format`() {
        val services = SearchServiceOptions.ADDABLE_TYPES.keys.map { SearchServiceOptions.create(it) }
        val raw = JsonInstant.encodeToString(services)

        val back: List<SearchServiceOptions> = decodePersistedSetting(raw, listOf(SearchServiceOptions.DEFAULT))

        assertEquals(services.map { it.typeKey }, back.map { it.typeKey })
        assertEquals(services.map { it.displayName }, back.map { it.displayName })
        assertEquals(services.map { it.javaClass }, back.map { it.javaClass })
    }

    /** 直接钉死根因：DEFAULT 的声明类型必须是 sealed 基类，不能是具体子类。 */
    @Test
    fun `DEFAULT is declared as the sealed base so inference cannot narrow the element type`() {
        val returnType = SearchServiceOptions.Companion
            .javaClass
            .getDeclaredMethod("getDEFAULT")
            .returnType

        assertEquals(SearchServiceOptions::class.java, returnType)
    }
}
