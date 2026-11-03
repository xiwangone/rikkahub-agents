package me.rerere.rikkahub.data.ai.tools.local

import android.annotation.SuppressLint
import android.os.Build
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.content.pm.PackageManager
import me.rerere.rikkahub.BuildConfig
import java.security.MessageDigest
import me.rerere.rikkahub.RikkaHubApp
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.common.android.Logging
import me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks
import me.rerere.rikkahub.ui.pages.setting.doctor.Severity
import me.rerere.rikkahub.utils.LogRedactor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * AI 自诊断/自管理工具（第一批，纯读，风险零）。
 *
 * 复用现有组件：
 *  - get_app_health: DoctorChecks.runAll() 的 8 类检查快照
 *  - read_app_logs:   AppLog 缓冲 + LogRedactor 脱敏
 *  - test_model:      提供商「测试连接」核心逻辑（非流式/流式/工具调用）
 */

// ---------- get_app_health ----------

/**
 * 对 [DoctorChecks.runAll] 的完整诊断快照做结构化输出。
 * 复用 Doctor 页全部检查项，返回 JSON：健康计数 + 每项 id/category/label/detail/severity。
 */
internal suspend fun appHealthPayload(
    doctorChecks: DoctorChecks,
    context: Context,
): String {
        val checks = doctorChecks.runAll()
        val ok = checks.count { it.severity == Severity.OK }
        val info = checks.count { it.severity == Severity.INFO }
        val warn = checks.count { it.severity == Severity.WARN }
        val fail = checks.count { it.severity == Severity.FAIL }
        val payload = buildJsonObject {
            put("ok", ok)
            put("info", info)
            put("warn", warn)
            put("fail", fail)
            put("total", checks.size)
            put("checks", JsonArray(checks.map { check ->
                buildJsonObject {
                    put("id", check.id)
                    put("category", check.category.name)
                    put("label", context.getString(check.labelRes))
                    put("detail", check.detail)
                    put("severity", check.severity.name)
                }
            }))
        }
    return payload.toString()
}

/**
 * keyword 支持**多个**（逗号 / 中文逗号 / 分号 / 空白分隔，命中任一即可）：排查时常要同时看
 * 几条链路（如 `ChatService,GenerationLoop`），分开调用既费往返又容易丢时间线。
 */
private fun parseKeywords(params: JsonObject): List<String> =
    params["keyword"]?.jsonPrimitive?.contentOrNull
        ?.split(',', '，', ';', ' ', '\t')
        ?.map { it.trim().lowercase(Locale.getDefault()) }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

// ---------- read_app_logs ----------

/**
 * 按级别 / 关键字 / 条数读取应用日志，返回前先过 [LogRedactor.maskText] 脱敏，
 * 防止崩溃堆栈泄露 API key / 连接串。
 */
internal fun appLogsPayload(context: Context, params: JsonObject): String {
        // 默认**排除 D 级**：逐 token / 流式分片是噪音大户（已单独缓冲），抓错误时会把有效行冲走；
        // 要看详细仍可显式 `level=D`。I/W/E 是诊断主线（消息收发 / 落库 / 取消 / 审批），默认可见。
        val level = params["level"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.uppercase(Locale.US)?.take(1)
        val keywords = parseKeywords(params)
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50
        // summary=true：只回统计（level 分布 / Top tag / 时间范围 / 总数），不给原始行——
        // 让 AI 先看摘要再按 keyword 精准取行，避免一上来拉原始日志把上下文淹掉。
        val summaryOnly =
            params["summary"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true

        val matched = AppLog.getLogs()
            .filter { e ->
                if (level == null) e.level.toString() != "D" else e.level.toString() == level
            }
            .filter { e ->
                keywords.isEmpty() ||
                    keywords.any { k ->
                        e.tag.lowercase(Locale.getDefault()).contains(k) ||
                            e.message.lowercase(Locale.getDefault()).contains(k)
                    }
            }

        if (summaryOnly) {
            val levelCounts = matched.groupingBy { it.level.toString() }.eachCount()
            val topTags =
                matched.groupingBy { it.tag }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(10)
            return buildJsonObject {
                put("total", matched.size)
                put(
                    "levelCounts",
                    buildJsonObject { levelCounts.forEach { (k, v) -> put(k, v) } },
                )
                put(
                    "topTags",
                    buildJsonArray {
                        topTags.forEach { (tag, count) ->
                            add(buildJsonObject { put("tag", tag); put("count", count) })
                        }
                    },
                )
                if (matched.isNotEmpty()) {
                    val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())
                    put("firstAt", fmt.format(Instant.ofEpochMilli(matched.first().timestamp)))
                    put("lastAt", fmt.format(Instant.ofEpochMilli(matched.last().timestamp)))
                }
                put("hint", "仅统计；默认不含 D 级（要详细传 level=D）；要具体行时带 keyword/level 再调用（limit 默认 50）")
            }.toString()
        }

        val entries = matched.take(limit.coerceIn(1, 500))

        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault()).withZone(ZoneId.systemDefault())
        val raw = entries.joinToString("\n") { e ->
            "${fmt.format(Instant.ofEpochMilli(e.timestamp))} ${e.level} ${e.tag}: ${e.message}"
        }
        // 头部给「命中多少 / 实际回多少」+ 当前筛选口径：截断不再靠猜，也不会把
        // 「没匹配」与「被 limit 截掉」混为一谈。
        val header = buildString {
            append("[app logs] matched=${matched.size} shown=${entries.size}")
            append(" · level=${level ?: "default(I/W/E; use level=D for verbose)"}")
            if (keywords.isNotEmpty()) append(" · keyword=${keywords.joinToString("|")}")
            if (entries.size < matched.size) append(" · truncated: narrow with keyword/level/limit")
            append('\n')
        }
    return LogRedactor.maskText(header + raw)
}

// ---------- read_request_logs ----------

/**
 * 读取 HTTP 请求/响应摘要日志（common Logging 的 RequestLog，纯内存 100 条），
 * 返回前过 [LogRedactor.maskText] 脱敏。用于排查 API 调用失败/限流/端点错误等
 * 一手证据——read_app_logs 只覆盖 AppLog，请求日志是唯一能看到实际请求结果的入口。
 */
internal fun requestLogsPayload(context: Context, params: JsonObject): String {
        val keywords = parseKeywords(params)
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50

        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault()).withZone(ZoneId.systemDefault())
        val entries = Logging.getRequestLogs()
            .filter { e ->
                keywords.isEmpty() || keywords.any { k ->
                    e.url.lowercase(Locale.getDefault()).contains(k) ||
                        (e.error?.lowercase(Locale.getDefault())?.contains(k) == true)
                }
            }
            .take(limit.coerceIn(1, 100))

        val raw = entries.joinToString("\n") { e ->
            "${fmt.format(Instant.ofEpochMilli(e.timestamp))} [HTTP] ${e.method} ${e.url} code=${e.responseCode ?: "-"} dur=${e.durationMs ?: "-"}ms err=${e.error ?: "-"}"
        }
    return LogRedactor.maskText(raw)
}

// ---------- read_crash_snapshot ----------

/**
 * 读取崩溃快照（主线程未捕获异常的现场）：堆栈 + AppLog 尾部 + 请求尾部 + 生命周期尾部。
 *
 * 快照保留最近 3 次（crash-latest / crash-1 / crash-2），便于回溯复发问题；文件由
 * CrashHandler 在崩溃时同步写出，因此即使进程随即退出也可读。
 */
internal fun crashSnapshotPayload(context: Context, params: JsonObject): String {
        val which = params["which"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase(Locale.US)?.takeIf { s -> s.isNotEmpty() } ?: "latest"
        val dir = context.getDir("crash", Context.MODE_PRIVATE)
        // 快照按时间归档（crash-<时间戳>.txt），取修改时间最新的几份；which=latest/1/2 依次新→旧
        val snapshots = dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }?.toList().orEmpty()
        val file = when (which) {
            "1" -> snapshots.getOrNull(1)
            "2" -> snapshots.getOrNull(2)
            else -> snapshots.firstOrNull()
        }
        val text =
            file?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") } ?: ""
        val available = snapshots.take(6).joinToString(", ") { it.name }
        val header = "[${file?.name ?: "-"}] available snapshots: ${available.ifEmpty { "(none)" }}"
        val body = text.take(20_000)
        // 快照写入时已脱敏，这里再兜底一次（旧快照或异常路径可能未覆盖）
        val out = if (body.isBlank()) "$header\n(no crash snapshot)" else "$header\n$body"
    return LogRedactor.maskText(out)
}

// ---------- perf ----------

/**
 * perf kind: 进程运行时长 / 堆占用 / 线程数。启动时刻由 Application 进程级记录（见 RikkaHubApp）。
 */
internal fun perfPayload(context: Context): String {
    val runtime = Runtime.getRuntime()
    val uptimeMs = android.os.SystemClock.elapsedRealtime() - RikkaHubApp.processStartElapsedMs
    return buildJsonObject {
        put("uptimeMinutes", uptimeMs / 60_000)
        put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576)
        put("heapMaxMb", runtime.maxMemory() / 1_048_576)
        put("activeThreads", Thread.activeCount())
    }.toString()
}

// ---------- read_lifecycle_logs ----------

/**
 * 读取进程生命周期记录：每次进程启动的原因（正常启动 / 疑似被系统杀死 / 崩溃重启）、
 * 前台后台切换、以及内存回收级别（TRIM_UI_HIDDEN 等）。
 *
 * 这是判断「退到后台再回来像重启、会话历史不见」类问题的直接证据：若日志里出现
 * PROCESS_START 且原因为「疑似被系统杀死」，说明是进程级回收而非界面问题。
 */
internal fun lifecycleLogsPayload(context: Context, params: JsonObject): String {
        val lines = (params["lines"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 500)
        val raw =
            me.rerere.rikkahub.data.log.FileLogSink.recentLines(
                me.rerere.rikkahub.data.log.FileLogSink.KIND_LIFECYCLE,
                lines,
            )
    return if (raw.isBlank()) "(no lifecycle records yet)" else raw
}

/**
 * 与 [appLogsPayload] 同源，但**从磁盘文件读**：内存缓冲重启即空，文件按天保留、可回看历史与重启前。
 * 只含 I/W/E 级（D 级按设计不落盘）。支持 `lines`（尾部行数，默认 200，最大 2000）与 `keyword` 过滤。
 */
internal fun fileLogsPayload(context: Context, params: JsonObject): String {
    val lines = (params["lines"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 2000)
    val days = (params["days"]?.jsonPrimitive?.intOrNull ?: 7).coerceIn(1, 7)
    val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val raw =
        me.rerere.rikkahub.data.log.FileLogSink.recentLines(
            me.rerere.rikkahub.data.log.FileLogSink.KIND_APP,
            lines,
            days,
        )
    val filtered =
        if (keyword.isEmpty()) {
            raw
        } else {
            raw.lineSequence().filter { it.contains(keyword, ignoreCase = true) }.joinToString("\n")
        }
    return if (filtered.isBlank()) "(no app log records on disk in the last $days day(s))" else filtered
}

// ---------- get_build_info ----------

/**
 * 当前安装包的构建身份：版本号、是否 debug、安装/更新时间、签名指纹 SHA-256。
 * 用于 AI 在测试前确认"测的是哪个包"，以及排查"改了没生效"（装错包 / 进程跑旧码）。
 */
@SuppressLint("PackageManagerGetSignatures")
internal fun buildInfoPayload(context: Context): String {
            val pm = context.packageManager
            val packageName = context.packageName
            val info =
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    }
                }.getOrNull()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())
            val signerSha256 =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching {
                        val cert = info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                        cert?.let { bytes ->
                            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                        }
                    }.getOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    runCatching {
                        val cert = info?.signatures?.firstOrNull()?.toByteArray()
                        cert?.let { bytes ->
                            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                        }
                    }.getOrNull()
                }
            val payload =
                buildJsonObject {
                    put("packageName", packageName)
                    put("versionName", BuildConfig.VERSION_NAME)
                    put("versionCode", BuildConfig.VERSION_CODE)
                    put("isDebugBuild", BuildConfig.DEBUG)
                    put("firstInstallTime", info?.firstInstallTime?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "unknown")
                    put("lastUpdateTime", info?.lastUpdateTime?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "unknown")
                    put("signerSha256", signerSha256 ?: "unknown")
                    put("processId", android.os.Process.myPid())
                }
    return payload.toString()
}
