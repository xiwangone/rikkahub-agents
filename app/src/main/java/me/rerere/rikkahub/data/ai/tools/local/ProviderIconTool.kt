package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.ProviderIconOverrides
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** 下载图标上限：2 MiB；超过直接拒绝，不落盘、不写覆盖表。 */
internal const val PROVIDER_ICON_MAX_BYTES = 2 * 1024 * 1024

/** 下载图标在 filesDir 下的存放目录。 */
internal const val PROVIDER_ICON_DIR = "provider-icons"

private const val PROVIDER_ICON_DOWNLOAD_TIMEOUT_MS = 30_000L

/**
 * 按名解析结果（纯函数）。
 *
 * 提供商用名区分：精确匹配优先；无精确命中时按忽略大小写匹配——唯一命中即采纳，
 * 多个命中是不区分大小写的重名，报错让调用方澄清，不猜。
 */
internal sealed interface ProviderNameResolution {
    data class Found(val name: String) : ProviderNameResolution

    data class NotFound(val available: List<String>) : ProviderNameResolution

    data class Ambiguous(val matches: List<String>) : ProviderNameResolution
}

internal fun resolveProviderName(names: List<String>, query: String): ProviderNameResolution {
    val exact = names.filter { it == query }
    if (exact.size == 1) return ProviderNameResolution.Found(exact.first())
    if (exact.size > 1) return ProviderNameResolution.Ambiguous(exact)
    val folded = names.filter { it.equals(query, ignoreCase = true) }
    return when {
        folded.isEmpty() -> ProviderNameResolution.NotFound(names)
        folded.size == 1 -> ProviderNameResolution.Found(folded.first())
        else -> ProviderNameResolution.Ambiguous(folded)
    }
}

internal enum class DownloadedImageType(val extension: String) {
    SVG("svg"),
    PNG("png"),
}

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * 按字节嗅探下载内容是不是图片（只认 SVG / PNG）。
 *
 * 不信任 Content-Type：错误页、反爬页常以图片类型或 text/html 混装返回，
 * 以字节为准。SVG 允许 `<?xml ...?>` 声明头，但 `<svg` 必须出现在前 2 KiB 内，
 * 且 `<html` 先出现时一律判为不是图片。
 */
internal fun detectDownloadedImageType(bytes: ByteArray): DownloadedImageType? {
    if (bytes.size >= PNG_MAGIC.size && bytes.copyOf(PNG_MAGIC.size).contentEquals(PNG_MAGIC)) {
        return DownloadedImageType.PNG
    }
    val head = bytes.copyOf(minOf(bytes.size, 2048)).decodeToString().trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    val svgAt = head.indexOf("<svg", ignoreCase = true)
    if (svgAt < 0) return null
    val htmlAt = head.indexOf("<html", ignoreCase = true)
    if (htmlAt >= 0 && htmlAt < svgAt) return null
    return DownloadedImageType.SVG
}

/** 提供商名做文件名：只留字母数字与 `._-`，其余字符换 `_`；只剩点号时回退。 */
internal fun sanitizeProviderIconBase(providerName: String): String {
    val base = providerName.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("").trim('.')
    return base.ifBlank { "provider" }
}

internal fun providerIconFileName(providerName: String, type: DownloadedImageType): String =
    "${sanitizeProviderIconBase(providerName)}.${type.extension}"

/**
 * 运行时更新 / 替换提供商图标（写工具，不在诊断工具里）。
 *
 * 入参 `provider` 是提供商名（精确匹配）；`icon` 三种取值：
 * - `http(s)://...`：原生侧下载、校验是 SVG/PNG（上限 2 MiB），存到 files/provider-icons/，
 *   覆盖表记本地路径；下载失败、超限、不是图片都报错，不写覆盖表。
 * - `assets/icons/` 里已有的品牌文件名（如 `openrouter.svg`）：直接记 `asset:` 覆盖。
 * - 空串：清掉该提供商的覆盖，恢复自动（品牌匹配 / 首字母）。
 *
 * 查无此名、重名、下载失败、不是图片一律走工具错误信封返回，不静默。
 */
fun updateProviderIconTool(
    context: Context,
    settingsStore: SettingsStore,
    okHttpClient: OkHttpClient,
): Tool = Tool(
    name = "update_provider_icon",
    description = """
        Set or clear a provider's icon by provider name (exact match). icon is an http(s) image URL (SVG/PNG, max 2 MiB, downloaded and stored locally) or a bundled brand file name from assets/icons (e.g. 'openrouter.svg'). Pass an empty icon to clear the override and restore automatic icons. Use diagnostics to list provider names first when unsure.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider", buildJsonObject {
                    put("type", "string")
                    put("description", "Provider name, exact match, e.g. 'mazal'.")
                })
                put("icon", buildJsonObject {
                    put("type", "string")
                    put("description", "http(s) image URL (SVG/PNG), a bundled brand file name (e.g. 'openrouter.svg'), or empty string to clear the override.")
                })
            },
            required = listOf("provider", "icon"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val providerQuery = params.optString("provider")?.trim().orEmpty()
        val icon = params.optString("icon")?.trim()
        if (providerQuery.isBlank()) {
            return@Tool errEnvelope(
                error = ToolErrors.INVALID_ARGUMENT,
                message = "provider is required",
                hint = "Pass the provider name exactly as shown in the provider list.",
            )
        }
        if (icon == null) {
            return@Tool errEnvelope(
                error = ToolErrors.INVALID_ARGUMENT,
                message = "icon is required (empty string clears the override)",
                hint = "Pass an http(s) image URL, a bundled brand file name, or \"\" to clear.",
            )
        }
        val settings = settingsStore.settingsFlow.first()
        val names = settings.providers.map { it.name }
        when (val resolved = resolveProviderName(names, providerQuery)) {
            is ProviderNameResolution.NotFound -> {
                return@Tool errEnvelope(
                    error = ToolErrors.NOT_FOUND,
                    message = "no provider named '$providerQuery'",
                    hint = "Use diagnostics to list provider names; the name must match exactly.",
                    extra = ToolErrors.extraOf("available" to resolved.available.joinToString(", ")),
                )
            }
            is ProviderNameResolution.Ambiguous -> {
                return@Tool errEnvelope(
                    error = ToolErrors.INVALID_ARGUMENT,
                    message = "multiple providers named '$providerQuery'",
                    hint = "Rename one of them first, then retry with the exact name.",
                    extra = ToolErrors.extraOf("matches" to resolved.matches.joinToString(", ")),
                )
            }
            is ProviderNameResolution.Found -> {
                val name = resolved.name
                if (icon.isBlank()) {
                    clearProviderIconOverride(context, settingsStore, name)
                    return@Tool okResult(name, null, "cleared")
                }
                val stored = when {
                    isHttpUrl(icon) -> {
                        when (val downloaded = downloadAndStoreProviderIcon(context, okHttpClient, name, icon)) {
                            is IconDownloadOutcome.Stored -> downloaded.storedValue
                            is IconDownloadOutcome.Failed -> return@Tool downloaded.envelope
                        }
                    }
                    else -> {
                        val assetNames = runCatching { context.assets.list("icons")?.toSet() }.getOrNull()
                        if (assetNames != null && icon in assetNames) {
                            ProviderIconOverrides.encodeAsset(icon)
                        } else {
                            return@Tool errEnvelope(
                                error = ToolErrors.INVALID_ARGUMENT,
                                message = "'$icon' is neither an http(s) image URL nor a bundled brand file name",
                                hint = "Pass an http(s) image URL, a bundled brand file name (e.g. 'openrouter.svg'), or \"\" to clear.",
                            )
                        }
                    }
                }
                settingsStore.update { current ->
                    current.copy(providerIconOverrides = current.providerIconOverrides + (name to stored))
                }
                return@Tool okResult(name, stored, if (stored.startsWith(ProviderIconOverrides.FILE_PREFIX)) "url" else "asset")
            }
        }
    },
)

private fun isHttpUrl(value: String): Boolean {
    val lower = value.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

private fun errEnvelope(
    error: String,
    message: String,
    hint: String? = null,
    extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
): List<UIMessagePart.Text> = listOf(
    UIMessagePart.Text(
        ToolErrors.envelopeFor(error = error, message = message, hint = hint, extra = extra).toString()
    )
)

private fun okResult(provider: String, icon: String?, source: String): List<UIMessagePart.Text> {
    val json = buildJsonObject {
        put("provider", provider)
        if (icon == null) put("icon", JsonNull) else put("icon", icon)
        put("source", source)
    }
    return listOf(UIMessagePart.Text(json.toString()))
}

/** 清掉覆盖：删表项；旧值是下载文件时连文件一起删。 */
private suspend fun clearProviderIconOverride(context: Context, settingsStore: SettingsStore, name: String) {
    val old = settingsStore.settingsFlow.first().providerIconOverrides[name]
    settingsStore.update { current ->
        current.copy(providerIconOverrides = current.providerIconOverrides - name)
    }
    val oldFile = ProviderIconOverrides.parse(old)
    if (oldFile is me.rerere.rikkahub.utils.ProviderIconSource.LocalFile) {
        runCatching { File(context.filesDir, oldFile.relativePath).delete() }
    }
}

/** 下载结果：成功带要写入覆盖表的 `file:` 值，失败带已拼好的错误信封。 */
internal sealed interface IconDownloadOutcome {
    data class Stored(val storedValue: String) : IconDownloadOutcome

    data class Failed(val envelope: List<UIMessagePart.Text>) : IconDownloadOutcome
}

/** 单次下载的结果：字节或已拼好的错误信封。 */
internal sealed interface IconFetchResult {
    data class Bytes(val bytes: ByteArray) : IconFetchResult

    data class Failed(val envelope: List<UIMessagePart.Text>) : IconFetchResult
}

/**
 * 经 egress 守卫下载一次；超时 / HTTP 失败 / 超限 / IO 失败都返回 [IconFetchResult.Failed]，不抛。
 * 与落盘拆开是为了让下载函数不过复杂（detekt 圈复杂度上限 20）。
 */
private suspend fun fetchIconBytes(
    guarded: OkHttpClient,
    request: Request,
    url: String,
): IconFetchResult {
    fun fetchFailed(error: String, message: String, hint: String? = null) =
        IconFetchResult.Failed(errEnvelope(error = error, message = message, hint = hint))

    return try {
        val downloaded = withTimeoutOrNull(PROVIDER_ICON_DOWNLOAD_TIMEOUT_MS) {
            guarded.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withTimeoutOrNull fetchFailed(
                        error = ToolErrors.TOOL_FAILED,
                        message = "icon download failed: HTTP ${resp.code} from '$url'",
                        hint = "The URL must return a direct SVG/PNG image (HTTP 200).",
                    )
                }
                val (data, truncated) = readBounded(resp.body.byteStream(), PROVIDER_ICON_MAX_BYTES)
                if (truncated) {
                    return@withTimeoutOrNull fetchFailed(
                        error = ToolErrors.INVALID_ARGUMENT,
                        message = "icon from '$url' exceeds the 2 MiB limit",
                        hint = "Use a smaller image file.",
                    )
                }
                data
            }
        }
        when (downloaded) {
            null -> fetchFailed(
                error = ToolErrors.TIMEOUT,
                message = "icon download from '$url' exceeded the 30s limit",
                hint = "The host is slow or unreachable; try a different URL.",
            )
            is IconFetchResult.Failed -> downloaded
            is ByteArray -> IconFetchResult.Bytes(downloaded)
            // downloaded 只可能是上面三种；else 只为满足 when 穷尽检查，正常走不到。
            else -> fetchFailed(
                error = ToolErrors.INTERNAL,
                message = "unexpected icon download result",
                hint = "Retry the download.",
            )
        }
    } catch (e: InterruptedIOException) {
        fetchFailed(
            error = ToolErrors.TIMEOUT,
            message = "icon download from '$url' exceeded the 30s limit (${e.message ?: "interrupted"})",
            hint = "The host is slow or unreachable; try a different URL.",
        )
    } catch (e: IOException) {
        val blocked = e.message?.contains("blocked_private_address") == true
        fetchFailed(
            error = if (blocked) "blocked_address" else "network_error",
            message = e.message ?: e::class.java.simpleName,
            hint = if (blocked) "The URL points to a private address; public image URLs only." else "Check the network and the URL, then retry.",
        )
    }
}

/** 下载并落盘；失败返回 [IconDownloadOutcome.Failed]，不写覆盖表、不留半截文件。 */
private suspend fun downloadAndStoreProviderIcon(
    context: Context,
    okHttpClient: OkHttpClient,
    providerName: String,
    url: String,
): IconDownloadOutcome {
    fun failed(error: String, message: String, hint: String? = null) =
        IconDownloadOutcome.Failed(errEnvelope(error = error, message = message, hint = hint))

    val request = try {
        Request.Builder().url(url).get().build()
    } catch (e: IllegalArgumentException) {
        return failed(
            error = ToolErrors.INVALID_ARGUMENT,
            message = "invalid icon URL '$url': ${e.message}",
            hint = "Pass a valid http(s) image URL.",
        )
    }
    val bytes = when (val fetched = fetchIconBytes(okHttpClient.withEgressGuard(), request, url)) {
        is IconFetchResult.Failed -> return IconDownloadOutcome.Failed(fetched.envelope)
        is IconFetchResult.Bytes -> fetched.bytes
    }
    val type = detectDownloadedImageType(bytes) ?: return failed(
        error = ToolErrors.INVALID_ARGUMENT,
        message = "download from '$url' is not an SVG or PNG image",
        hint = "Pass a direct link to an .svg or .png file.",
    )
    val dir = File(context.filesDir, PROVIDER_ICON_DIR).apply { mkdirs() }
    val fileName = providerIconFileName(providerName, type)
    // 同一提供商换了格式（svg ↔ png）时清掉旧文件，不留孤儿。
    val base = sanitizeProviderIconBase(providerName)
    dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("$base.") && it.name != fileName }
        ?.forEach { runCatching { it.delete() } }
    runCatching { File(dir, fileName).writeBytes(bytes) }.onFailure {
        return failed(
            error = ToolErrors.INTERNAL,
            message = "failed to save downloaded icon: ${it.message}",
            hint = "Check app storage and retry.",
        )
    }
    return IconDownloadOutcome.Stored(ProviderIconOverrides.encodeFile("$PROVIDER_ICON_DIR/$fileName"))
}
