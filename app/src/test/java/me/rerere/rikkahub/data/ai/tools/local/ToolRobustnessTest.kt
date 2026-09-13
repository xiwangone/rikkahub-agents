package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustness checks for the tool surface.
 *
 * An agent loop feeds a tool whatever the model produced, so hostile input is normal input:
 * missing fields, wrong JSON types, unknown keys, absurdly long strings, control characters,
 * and top-level arrays instead of objects. None of it may take the process down with an
 * *uncontrolled* exception (NullPointerException / ClassCastException / IndexOutOfBounds /
 * UnsupportedOperation / …).
 *
 * A tool may instead:
 *  - answer with a structured envelope (its job), or
 *  - throw a *controlled* exception (IllegalArgument / IllegalState / Security / IOException /
 *    Cancellation) — the loop converts those into an error result by contract.
 *
 * Complements [me.rerere.rikkahub.data.ai.tools.ToolSchemaContractTest] (static schema shape) and
 * the per-tool suites (happy paths): this one only asserts "bad input cannot crash the run".
 */
class ToolRobustnessTest {

    private val malformedArgs: List<String> = listOf(
        "{}",
        """{"path":null}""",
        """{"path":123}""",
        """{"path":[]}""",
        """{"path":{"nested":1}}""",
        """{"unknown_key":"x"}""",
        """{"path":"${"a".repeat(5000)}"}""",
        "[]",
        """{"path":"/sdcard/\u0000evil"}""",
        """{"by":[],"value":{}}""",
    )

    /** Tools constructible on the host JVM — validation paths return before any Context call. */
    private fun tools(): List<Tool> = listOf(
        listFilesTool(),
        readFileTool(),
        createDirectoryTool(),
        fileInfoTool(),
        deleteFileTool(),
        findFilesTool(),
        zipFilesTool(NULL_CONTEXT),
        unzipFileTool(NULL_CONTEXT),
        listZipContentsTool(NULL_CONTEXT),
        browserCurrentUrlTool(),
        browserClickTool(),
        browserGetTextTool(),
        findNodeTool(),
        clickNodeTool(),
        // callLogTool is intentionally absent: every code path reaches
        // android.provider.CallLog.Calls.CONTENT_URI, a framework constant that is null on a
        // host JVM, so it would surface an environment NPE instead of a contract problem.
    )

    private fun isControlled(e: Throwable): Boolean =
        e is IllegalArgumentException ||
            e is IllegalStateException ||
            e is SecurityException ||
            e is java.io.IOException ||
            e is kotlinx.coroutines.CancellationException

    @Test
    fun `malformed arguments never raise uncontrolled exceptions`() {
        val problems = mutableListOf<String>()
        for (tool in tools()) {
            for (args in malformedArgs) {
                try {
                    val parts = runBlocking { tool.execute(Json.parseToJsonElement(args)) }
                    if (parts.isEmpty()) problems += "${tool.name} <- $args : returned an empty result list"
                } catch (e: Throwable) {
                    if (!isControlled(e)) {
                        problems += "${tool.name} <- $args : ${e::class.java.simpleName}: ${e.message}"
                    }
                }
            }
        }
        assertTrue(
            "tools raised uncontrolled exceptions on malformed input:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `every tool failure answer carries an error envelope`() {
        // When a tool answers with text on a malformed call, the payload must be a JSON object
        // carrying an `error` key — a bare prose string would leave the model unable to branch.
        val problems = mutableListOf<String>()
        for (tool in tools()) {
            for (args in listOf("{}", """{"unknown_key":"x"}""")) {
                val text = try {
                    execTool(tool, args)
                } catch (e: Throwable) {
                    continue // controlled (or already reported above)
                }
                if (text.isNotBlank() && !text.contains("\"error\"")) {
                    val head = text.take(80).replace("\n", " ")
                    problems += "${tool.name} <- $args : no error envelope ($head)"
                }
            }
        }
        assertTrue(
            "tool failures must answer with an `error` envelope:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }
}
