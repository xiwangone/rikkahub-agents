package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.net.URI

private const val TAG = "OcrTransformer"

// Hard ceiling on a single OCR/vision describe call. Without it, an OCR model that is
// misconfigured, dead, or itself not vision-capable blocks the whole generation forever.
// On Telegram that wedges the per-chat mutex, so every later message queues until the user
// sends /new — the symptom this bound exists to prevent.
private const val OCR_TIMEOUT_MS = 60_000L

/**
 * Which `file:` image urls in [messages] still need OCR, i.e. are not covered by
 * [isCached]. Pure so the "should we even show a status / do any work" decision can be
 * unit-tested without a real cache.
 */
internal fun selectUncachedImageUrls(
    messages: List<UIMessage>,
    isCached: (String) -> Boolean,
): List<String> = messages
    .asSequence()
    .flatMap { it.parts.asSequence() }
    .filterIsInstance<UIMessagePart.Image>()
    .map { it.url }
    .filter { it.startsWith("file:") }
    .filterNot(isCached)
    .toList()

/**
 * OCR 缓存 key：图片 URL + 文件 mtime/size + OCR 模型 id。
 *
 * 只用裸 URL 时，原地改图、换 OCR 模型都会继续命中旧文本；mtime/size 与模型 id
 * 进 key 后，这两类变化都会重新识别（口径同 WorkspaceFilesPage 的 Coil key：
 * path + updatedAt + sizeBytes）。文件读不到（已删/路径非法）时退化为 URL + 模型 id，
 * 仍比裸 URL 强（换模型必失效）。
 */
internal fun buildOcrCacheKey(
    url: String,
    ocrModelId: String,
    lastModified: Long?,
    length: Long?,
): String = "$url|${lastModified ?: -1}|${length ?: -1}|$ocrModelId"

/** 读文件 mtime/size 后拼缓存 key。 */
internal fun resolveOcrCacheKey(url: String, ocrModelId: String): String {
    val stat = runCatching {
        val file = File(URI(url))
        if (file.isFile) file.lastModified() to file.length() else null
    }.getOrNull()
    return buildOcrCacheKey(url, ocrModelId, stat?.first, stat?.second)
}

/** OCR 结果包一层展示标签。 */
internal fun wrapOcrResult(content: String): String = """
    <image_file_ocr>
       $content
    </image_file_ocr>
    * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
""".trimIndent()

/**
 * 空白模型输出是否可缓存：否。
 *
 * 空白通常是临时失败或模型配置问题；缓存它会让该图永久读不出（同文件内超时路径
 * 明确不缓存，是同一条规则的另一半）。
 */
internal fun shouldCacheOcrResult(rawText: String): Boolean = rawText.isNotBlank()

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 256,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            onStoreFailure = { op, e -> AppLog.w(TAG, "ocr cache store $op failed", e) },
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            // 模型本身支持图片输入 → 直接透传，不做 OCR。
            // 注意：此处不再打日志 —— 每个 step 的每次消息变换都会走到这里，
            // 「no OCR」属于零信息量的高频噪音，会挤掉真正的排查线索。
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        val ocrModelId = get<SettingsStore>().settingsFlow.value.ocrModelId.toString()
        return withContext(Dispatchers.IO) {
            try {
                val uncachedUrls = selectUncachedImageUrls(messages) {
                    cache.get(resolveOcrCacheKey(it, ocrModelId)) != null
                }
                if (uncachedUrls.isNotEmpty()) {
                    val uncachedSet = uncachedUrls.toSet()
                    val triggers = messages.flatMap { message ->
                        message.parts
                            .filterIsInstance<UIMessagePart.Image>()
                            .filter { it.url in uncachedSet }
                            .map { "${message.role}:${it.url}" }
                    }
                    AppLog.i(
                        TAG,
                        "transform: ${triggers.size} uncached image(s) trigger OCR: ${triggers.take(3)}"
                    )
                    ctx.processingStatus.value = ctx.context.getString(R.string.ocr_status_recognizing)
                }
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String = runCatching {
        val settings = get<SettingsStore>().settingsFlow.value
        // Check cache first
        val cacheKey = resolveOcrCacheKey(part.url, settings.ocrModelId.toString())
        cache.get(cacheKey)?.let { cachedResult ->
            AppLog.i(TAG, "performOcr: cache hit for ${part.url}")
            return cachedResult
        }

        val model = settings.findModelById(settings.ocrModelId)
        if (model == null) {
            AppLog.w(TAG, "performOcr: no OCR model configured (id=${settings.ocrModelId}); image NOT read: ${part.url}")
            return "[Image: not read — no OCR model is configured, so this image's content is unavailable]"
        }
        val providerSetting = model.findProvider(settings.providers)
        if (providerSetting == null) {
            AppLog.w(TAG, "performOcr: OCR model ${model.modelId} has no usable provider; image NOT read: ${part.url}")
            return "[Image: not read — the OCR model's provider is unavailable]"
        }
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = withTimeoutOrNull(OCR_TIMEOUT_MS) {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }
        if (result == null) {
            AppLog.w(TAG, "performOcr: timed out after ${OCR_TIMEOUT_MS}ms for ${part.url}")
            // Not cached: a timeout is usually transient/config-related, so a later retry
            // should be allowed to reach the model again.
            return "[Image: could not be read — the OCR model did not respond in time]"
        }
        val rawText = result.message.toText()
        val content = rawText.ifBlank { "[ERROR, OCR failed]" }
        // OCR text is user content: log the length only, never the text itself.
        AppLog.i(TAG, "performOcr: ok (${content.length} chars) for ${part.url}")
        val ocrResult = wrapOcrResult(content)

        // 空白输出不缓存：通常是临时失败或模型配置问题，缓存会让该图永久读不出。
        if (shouldCacheOcrResult(rawText)) cache.put(cacheKey, ocrResult)
        return ocrResult
    }.getOrElse {
        // Let a real cancellation (e.g. the user's /stop) propagate instead of swallowing
        // it into a fake OCR-failure string, which would defeat cooperative cancellation.
        if (it is kotlinx.coroutines.CancellationException) throw it
        "[ERROR, OCR failed: $it]"
    }
}
