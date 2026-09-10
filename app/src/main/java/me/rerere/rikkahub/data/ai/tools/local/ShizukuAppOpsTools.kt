package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.shizuku.ShizukuManager

/**
 * Shizuku 应用管理组（批次 A，对齐 AxManager 能力面）。
 *
 * 权限等级 = shell UID（同 `adb shell`，无 root）。写类工具全部需要在
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults] 登记审批，参数经
 * 包名白名单校验（[PACKAGE_REGEX]），防 `;`/`|` 拼接注入。
 *
 * 边界（如实回报，不假装成功）：
 * - `appops set` 部分 op 需 root（如 WRITE_SECURE_SETTINGS）→ 报 insufficient_permission
 * - `pm disable-user` 对系统应用可能失败 → 报真实错误
 * - `pm uninstall` 仅第三方应用；系统应用返回 failure
 */

/** 包名白名单：仅字母数字点下划线，禁空格/分号/管道等注入字符 */
private val PACKAGE_REGEX = Regex("^[a-zA-Z0-9._]{1,255}$")

/** 常见 appops 操作白名单（保守：只允许已知 ops，未知一律拒绝） */
private val APPOPS_WHITELIST = setOf(
    "CAMERA", "RECORD_AUDIO", "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG",
    "WRITE_CALL_LOG", "SEND_SMS", "READ_SMS", "WRITE_SMS", "READ_PHONE_STATE",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
    "POST_NOTIFICATIONS", "VIBRATE", "BLUETOOTH_CONNECT", "NFC", "SYSTEM_ALERT_WINDOW",
    "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE", "GET_USAGE_STATS",
)

/** appops mode 白名单 */
private val APPOPS_MODE_WHITELIST = setOf("allow", "ignore", "deny", "default")

private const val DEFAULT_TIMEOUT_MS = 30_000

private fun validPackage(pkg: String?): Boolean = pkg != null && PACKAGE_REGEX.matches(pkg)

private fun toolResult(json: String): List<UIMessagePart> = listOf(UIMessagePart.Text(json))

/** 统一执行入口：校验 → ShizukuManager.exec → 透传结构化结果 */
private suspend fun shizukuExec(
    context: Context,
    command: String,
): List<UIMessagePart> = toolResult(ShizukuManager.exec(context, command, DEFAULT_TIMEOUT_MS).toString())

// ---------- app_force_stop ----------

/** 强停应用（am force-stop）。写类，需审批。 */
fun appForceStopTool(context: Context): Tool = Tool(
    name = "app_force_stop",
    description = "Force-stop an app by package id (am force-stop, shell UID). Kills the app's process and stops background services — the app can be relaunched by the user. Side-effecting, approval required.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package id, e.g. com.example.app")
                })
            },
            required = listOf("package"),
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        if (!validPackage(pkg)) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_package") }.toString())
        }
        shizukuExec(context, "am force-stop $pkg")
    },
)

// ---------- app_disable / app_enable ----------

/** 禁用应用（pm disable-user --user 0）。写类，需审批。可恢复（app_enable）。 */
fun appDisableTool(context: Context): Tool = Tool(
    name = "app_disable",
    description = "Disable an app for user 0 (pm disable-user --user 0). The app disappears from the launcher and cannot run until re-enabled with app_enable. Side-effecting, approval required.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package id, e.g. com.example.app")
                })
            },
            required = listOf("package"),
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        if (!validPackage(pkg)) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_package") }.toString())
        }
        shizukuExec(context, "pm disable-user --user 0 $pkg")
    },
)

/** 重新启用被禁用的应用（pm enable）。写类，需审批。 */
fun appEnableTool(context: Context): Tool = Tool(
    name = "app_enable",
    description = "Re-enable a previously disabled app (pm enable). Side-effecting, approval required.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package id, e.g. com.example.app")
                })
            },
            required = listOf("package"),
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        if (!validPackage(pkg)) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_package") }.toString())
        }
        shizukuExec(context, "pm enable $pkg")
    },
)

// ---------- app_uninstall ----------

/** 卸载应用（pm uninstall --user 0，保留数据可重装）。写类，需审批。 */
fun appUninstallTool(context: Context): Tool = Tool(
    name = "app_uninstall",
    description = "Uninstall an app for user 0 (pm uninstall --user 0). The app's data is preserved but it stops being installed for this user; system apps fail. Side-effecting, approval required.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package id, e.g. com.example.app")
                })
            },
            required = listOf("package"),
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        if (!validPackage(pkg)) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_package") }.toString())
        }
        shizukuExec(context, "pm uninstall --user 0 $pkg")
    },
)

// ---------- appops_get ----------

/** 读应用 AppOps 状态（appops get）。纯读，不审批。 */
fun appOpsGetTool(context: Context): Tool = Tool(
    name = "appops_get",
    description = "Read an app's AppOps permission-op states (appops get). Read-only, no approval needed. Returns each op's mode (allow/ignore/deny).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package id, e.g. com.example.app")
                })
            },
            required = listOf("package"),
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        if (!validPackage(pkg)) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_package") }.toString())
        }
        shizukuExec(context, "appops get $pkg")
    },
)

// ---------- appops_set ----------

/** 设置 AppOps 权限模式（appops set）。写类，需审批；白名单 op/mode。 */
fun appOpsSetTool(context: Context): Tool = Tool(
    name = "appops_set",
    description = "Set an app's AppOps permission-op mode (appops set <pkg> <op> <mode>). Whitelisted ops only (camera/location/contacts/sms/storage/notifications etc). Some ops require root and will report insufficient_permission. Side-effecting, approval required.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package id, e.g. com.example.app")
                })
                put("op", buildJsonObject {
                    put("type", "string")
                    put("description", "AppOps op, e.g. CAMERA, RECORD_AUDIO, READ_CONTACTS, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "Mode: allow | ignore | deny | default")
                })
            },
            required = listOf("package", "op", "mode"),
        )
    },
    execute = { input ->
        val pkg = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        val op = input.jsonObject["op"]?.jsonPrimitive?.contentOrNull?.uppercase()
        val mode = input.jsonObject["mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (!validPackage(pkg)) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_package") }.toString())
        }
        if (op == null || op !in APPOPS_WHITELIST) {
            return@Tool toolResult(buildJsonObject { put("error", "op_not_whitelisted"); put("op", op ?: "") }.toString())
        }
        if (mode == null || mode !in APPOPS_MODE_WHITELIST) {
            return@Tool toolResult(buildJsonObject { put("error", "invalid_mode") }.toString())
        }
        shizukuExec(context, "appops set $pkg $op $mode")
    },
)
