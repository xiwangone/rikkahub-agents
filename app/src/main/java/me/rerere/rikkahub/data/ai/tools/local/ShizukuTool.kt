package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.shizuku.ShizukuManager

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val MIN_TIMEOUT_MS = 1_000
/** Matches ssh_exec's ceiling (300s / 5 min). */
private const val MAX_TIMEOUT_MS = 300_000
private const val MAX_BATCH_COMMANDS = 20

/**
 * Run shell command(s) with Shizuku's privileges (the shell UID, the same level `adb shell`
 * gets, no root, no su). Requires the Shizuku app installed, its service running, and
 * permission granted from Settings -> Shizuku; the permission is never requested from this
 * tool or automatically, only from an explicit tap on that settings screen.
 *
 * Batch (批次 C): `commands` runs several commands in one call, each through its own
 * ShizukuManager.exec (the AIDL service has no multi-command primitive yet), results merged.
 * `probe_root` runs `command -v su` and reports whether a su binary exists — informational
 * only; this tool never invokes root.
 *
 * Approval-gated in [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults]; the `command`
 * / each `commands` entry is checked against
 * [me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard] like every other shell surface.
 */
fun shizukuExecTool(context: Context): Tool = Tool(
    name = "shizuku_exec",
    description = """Run a shell command (or a batch of commands) with Shizuku's privileges (the shell UID - the same level adb shell gets, no root). Pass `command` for a single command or `commands` (array, max $MAX_BATCH_COMMANDS) to run several in sequence; results are merged. Set `probe_root=true` to also report whether a su binary exists (informational; this tool never uses root). Returns stdout, stderr, and exit code. Requires the Shizuku app installed, its service running, and permission granted from Settings -> Shizuku.""".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run, e.g. 'pm list packages -3'. Mutually exclusive with commands.")
                })
                put("commands", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional batch: array of shell commands run in sequence (max $MAX_BATCH_COMMANDS). Use instead of command for multi-step work.")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Per-command timeout in milliseconds. Default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS.")
                })
                put("probe_root", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, also probe whether a su binary exists and report it (informational; never used). Default false.")
                })
            },
            required = emptyList(),
        )
    },
    execute = { input ->
        val single = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        val batch =
            input.jsonObject["commands"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.take(MAX_BATCH_COMMANDS)
                ?: emptyList()
        val commands = when {
            !single.isNullOrBlank() -> listOf(single)
            batch.isNotEmpty() -> batch
            else -> return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command or commands is required") }.toString()
                )
            )
        }
        val timeoutMs =
            (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_MS)
                .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val probeRoot = input.jsonObject["probe_root"]?.jsonPrimitive?.booleanOrNull ?: false

        val results = buildJsonArray {
            commands.forEach { cmd ->
                add(
                    buildJsonObject {
                        put("command", cmd)
                        put(
                            "result",
                            JsonPrimitive(ShizukuManager.exec(context, cmd, timeoutMs).toString()),
                        )
                    }
                )
            }
        }
        val out = buildJsonObject {
            put("results", results)
            put(
                "root_boundary",
                JsonPrimitive("shell UID — no root; su is not used by this tool"),
            )
            if (probeRoot) {
                val probe = ShizukuManager.exec(context, "command -v su || echo no-su", 5_000).toString()
                put("root_probe", JsonPrimitive(if (probe.contains("no-su")) "no su binary" else probe))
            }
        }
        listOf(UIMessagePart.Text(out.toString()))
    }
)
