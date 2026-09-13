package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract checks for the agent's tool surface.
 *
 * These are the parts of a tool definition that fail *quietly*: nothing crashes locally, but the
 * provider rejects the declaration or the model is told to send something the schema cannot
 * carry. All three checks scan the real tool sources (static analysis, same approach as
 * [ToolSchemaArrayItemsTest]) because instantiating the factories needs Context + DataStore + Koin.
 *
 *  1. `name` is snake_case — providers echo it back in tool-call results and route on it; a dot,
 *     dash, space or uppercase letter breaks that on several vendors.
 *  2. every entry of `required = listOf(...)` is also declared as a `properties` key in the same
 *     file — otherwise the model is told a field is mandatory that it can never send.
 *  3. every `array`-typed property declares `items` (Gemini rejects the whole function
 *     declaration otherwise; the block-scoped version of this lives in [ToolSchemaArrayItemsTest]).
 */
class ToolSchemaContractTest {

    private val toolDirs: List<String> = listOf(
        "src/main/java/me/rerere/rikkahub/data/ai/tools/local",
        "src/main/java/me/rerere/rikkahub/data/ai/tools",
        "src/main/java/me/rerere/rikkahub/data/ai/mcp/control",
        "src/main/java/me/rerere/rikkahub/subagent",
        "src/main/java/me/rerere/rikkahub/workflow",
    )

    private fun moduleRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        if (File(cwd, "src/main").isDirectory) return cwd
        val maybeApp = File(cwd, "app")
        if (File(maybeApp, "src/main").isDirectory) return maybeApp
        error("Could not locate the :app module root from cwd=$cwd")
    }

    private fun toolFiles(): List<File> {
        val root = moduleRoot()
        return toolDirs
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    /** `Tool(\n name = "x"` — the constructor shape every tool factory in this repo uses. */
    private val toolNameRegex = Regex("""Tool\(\s*name\s*=\s*"([^"]+)"""")

    /** snake_case, 2..40 chars, starting with a letter. */
    private val validToolName = Regex("^[a-z][a-z0-9_]{1,39}$")

    @Test
    fun `every tool name is snake_case`() {
        val violations = mutableListOf<String>()
        for (file in toolFiles()) {
            for (m in toolNameRegex.findAll(file.readText())) {
                val name = m.groupValues[1]
                if (!validToolName.matches(name)) violations += "${file.name}: '$name'"
            }
        }
        assertTrue("tool names must be snake_case (2..40 chars):\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `every required field is declared in the same tool file`() {
        val requiredRegex = Regex("""required\s*=\s*listOf\(([^)]*)\)""")
        val keyLiteral = Regex(""""([A-Za-z0-9_]+)"""")
        val violations = mutableListOf<String>()

        for (file in toolFiles()) {
            val text = file.readText()
            // Names the file actually declares as JSON-schema keys (properties or nested objects).
            val declaredKeys = Regex("""put\(\s*"([A-Za-z0-9_]+)"""").findAll(text)
                .map { it.groupValues[1] }.toSet()

            for (m in requiredRegex.findAll(text)) {
                val fields = keyLiteral.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
                for (field in fields) {
                    if (field !in declaredKeys) {
                        val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                        violations += "${file.name}:$line requires '$field' but never declares it"
                    }
                }
            }
        }
        assertTrue(
            "`required` must only list fields the schema actually declares:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `array-typed properties declare items in the same file`() {
        val arrayRegex = Regex("""put\(\s*"type"\s*,\s*"array"\s*\)""")
        val violations = mutableListOf<String>()

        for (file in toolFiles()) {
            val text = file.readText()
            val arrays = arrayRegex.findAll(text).count()
            if (arrays == 0) continue
            val items = Regex("""put\(\s*"items"""").findAll(text).count()
            if (items < arrays) {
                violations += "${file.name}: $arrays array-typed properties but only $items `items` declarations"
            }
        }
        assertTrue(
            "every `array` property needs an `items` (file-level count check; the block-scoped " +
                "version is ToolSchemaArrayItemsTest):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
