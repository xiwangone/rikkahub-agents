package me.rerere.rikkahub.data.ai.tools.registry

import java.io.File
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.ASK_USER_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.SurfaceTier
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具统一索引一致性自检：定义 / 注册 / 渲染三方对齐。
 *
 * 新增工具时必须在 [ToolIndex] 登记一行，否则以下测试失败
 * （“实现了却没注册”自动检查）。
 */
class ToolIndexTest {

    @Test
    fun `no duplicate tool names`() {
        val names = ToolIndex.entries.map { it.toolName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `all registered renderers are indexed with matching class name`() {
        for ((toolName, renderer) in ToolUIRegistry.rendererMap) {
            val entry = ToolIndex.find(toolName)
            assertNotNull("渲染器已注册但索引缺失: $toolName", entry)
            val declared = renderer::class.simpleName
            // 别名渲染器是匿名 object：Kotlin 的 `KClass.simpleName` 对匿名对象返回 null
            // （或形如 `Xxx$yyy$1`），而索引登记的是别名 val 名 —— 二者无法用类名对齐，
            // 此时只要求索引有一行登记。
            val isAnonymous = declared == null || declared.contains('$')
            assertTrue(
                "索引登记的渲染器与注册表不一致: $toolName (索引=${entry!!.renderer}, 注册表=$declared)",
                if (isAnonymous) !entry.renderer.isNullOrBlank() else entry.renderer == declared,
            )
        }
    }

    @Test
    fun `all indexed renderer claims are actually registered`() {
        val keys = ToolUIRegistry.registeredKeys
        for (entry in ToolIndex.withDedicatedRenderer()) {
            assertTrue(
                "索引声称有专用渲染器但注册表无此 key: ${entry.toolName}",
                entry.toolName in keys,
            )
        }
    }

    @Test
    fun `all catalog options are indexed`() {
        val indexedVia = ToolIndex.entries.map { it.registeredVia }.toSet()
        for (option in LocalToolCatalog.all) {
            assertTrue(
                "LocalToolOption 已存在但索引未登记其工具: ${option::class.simpleName}",
                "LocalToolOption.${option::class.simpleName}" in indexedVia,
            )
        }
    }

    @Test
    fun `unregistered tools are explicitly known`() {
        // 已定义但未接入注入路径：新增时必须人工复核后在此登记，禁止悄悄新增
        // 2026-10-06 整合：recent_chats/conversation_search 已恢复注入，web_extract 死代码已除名
        val expected = emptySet<String>()
        val actual = ToolIndex.unregistered().map { it.toolName }.toSet()
        assertEquals(
            "未注册工具清单变化，请复核后更新索引与本测试: 新增=${actual - expected} 减少=${expected - actual}",
            expected,
            actual,
        )
    }

    @Test
    fun `ask_user uses interactive invocation and default rendering`() {
        val entry = ToolIndex.find("ask_user")
        assertNotNull(entry)
        assertEquals(ToolIndex.InvocationKind.INTERACTIVE, entry!!.invocation)
        // 独立交互分支不进渲染框架
        assertEquals(null, entry.renderer)
    }

    @Test
    fun `automatic compaction renderer is indexed as display only`() {
        val entry = ToolIndex.find("context_compaction")
        assertNotNull(entry)
        assertEquals(ToolIndex.InvocationKind.DISPLAY_ONLY, entry!!.invocation)
        assertEquals("ContextCompactionToolUI", entry.renderer)
        assertEquals("ContextCompactionPresentation", entry.registeredVia)
    }

    @Test
    fun `index lookups work`() {
        val entry = ToolIndex.find("telegram_send_message")
        assertNotNull(entry)
        assertTrue(entry!!.definitionSite.contains("TelegramTool.kt"))
        assertEquals("LocalToolOption.TelegramBot", entry.registeredVia)
        assertEquals("TelegramSendToolUI", entry.renderer)

        assertTrue(ToolIndex.byRegistration("LocalToolOption.TelegramBot").size >= 14)
        assertTrue(ToolIndex.withDedicatedRenderer().isNotEmpty())
        assertTrue(ToolIndex.defaultRendered().isNotEmpty())
    }

    @Test
    fun `tier matches policy decision`() {
        // 索引档位必须与 ToolSurfacePolicy 判定一致（单一来源，防漂移）
        for (entry in ToolIndex.entries) {
            assertEquals(
                "tier mismatch for ${entry.toolName}",
                ToolSurfacePolicy.tierOf(entry.toolName),
                entry.tier,
            )
        }
        // 三档都有覆盖
        assertTrue(ToolIndex.byTier(SurfaceTier.HOT).isNotEmpty())
        assertTrue(ToolIndex.byTier(SurfaceTier.WARM).isNotEmpty())
        assertTrue(ToolIndex.byTier(SurfaceTier.COLD).isNotEmpty())
    }

    @Test
    fun `registered tool names are a subset of the index`() {
        // 注册表工具名集合 ⊆ ToolIndex 工具名集合：新增工具只注册不登记时失败。
        // 源码解析是 fail-closed——找不到源码目录即失败，不静默通过。
        val registered = registeredToolNames()
        val indexed = ToolIndex.entries.map { it.toolName }.toSet()
        val missing = registered - indexed - REGISTERED_BUT_NOT_INDEXED
        assertTrue("注册了但索引缺失: $missing", missing.isEmpty())
    }

    companion object {
        /**
         * 注册了但有意不进索引的工具名。新增条目必须人工复核后在此登记：
         * - get_current_time：AppDiagnosticsTools 的模型探针里内联构造的临时工具，
         *   不在助手工具面；
         * - web_extract：工厂存在但全仓无调用的死代码（2026-10-06 已除名），
         *   不补索引、也不在本批删工厂（删是另立项清理）。
         */
        private val REGISTERED_BUT_NOT_INDEXED = setOf("get_current_time", "web_extract")

        private val TOOL_NAME_PATTERN = Regex("\\bTool\\(\\s*name\\s*=\\s*\"([^\"]+)\"")

        private fun registeredToolNames(): Set<String> {
            // Gradle 下工作目录是 app/ 模块目录；仓库根目录直接跑时是根目录。
            // 两套候选只取真实存在的目录，取不到任何一个即 fail-closed。
            val candidates = listOf(
                "src/main/java",
                "app/src/main/java",
                "../agent-tools/src/main/java",
                "agent-tools/src/main/java",
                "../ai/src/main/java",
                "ai/src/main/java",
            )
            val roots = candidates.map(::File).filter { it.isDirectory }
            assertTrue(
                "cannot locate Kotlin sources from ${File(".").absolutePath}",
                roots.isNotEmpty(),
            )
            val names = mutableSetOf<String>()
            for (root in roots) {
                root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .forEach { file ->
                        TOOL_NAME_PATTERN.findAll(file.readText()).forEach { match ->
                            names += match.groupValues[1]
                        }
                    }
            }
            // ask_user 经 ASK_USER_TOOL_NAME 常量构造，正则抓不到，显式补上；
            // browser_* 是动态装配（BrowserToolDefaults.ALL_TOOLS），同样补上。
            names += ASK_USER_TOOL_NAME
            names += BrowserToolDefaults.ALL_TOOLS
            return names
        }
    }
}
