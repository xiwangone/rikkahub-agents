package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 发送前把「本地 URI 形式」的图片规范化成模型真能收到的形式。
 *
 * 背景（实测，见痛点 P103）：`me.rerere.ai.util.encodeBase64` 只认 `file://` / `data:` / `http(s)`，
 * 遇到 **`content://`**（相册选图最常见）会直接抛异常，而调用处把异常吞成空文本 → 图片**静默消失**；
 * 模型于是只能看到 [DocumentAsPromptTransformer] 插入的 `<UploadFile path="…"/>` 路径文本，
 * 表现为「有时能看到图、有时只看到路径」。
 *
 * 修法：本 transformer 在发送前用 contentResolver 把 `content://` 图片**读成 data URL**
 * （只做二进制搬运，不解码重编码 → 不损画质、不额外吃内存）；读失败时替换为**明确的占位文本**
 *（而不是留空），让模型与用户都知道"这张图没能发出去"。
 *
 * 只处理 `content://`：`file://`（已落盘）、`data:`、`http(s)` 均保持原样 —— 其中 `http(s)`
 * 需要"下载后编码"（涉及 SSRF 与体积上限，见排期）。
 */
object ImageSourceNormalizeTransformer : InputMessageTransformer {

    private const val CONTENT_SCHEME = "content://"
    private const val UNAVAILABLE = "[Image unavailable: could not read image data from the device]"

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> =
        withContext(Dispatchers.IO) {
            // 多数请求没有 content:// 图片 → 原样返回（引用相等，零开销）
            val hasContentImage =
                messages.any { msg ->
                    msg.parts.any { it is UIMessagePart.Image && it.url.startsWith(CONTENT_SCHEME) }
                }
            if (!hasContentImage) return@withContext messages

            messages.map { msg ->
                msg.copy(
                    parts =
                        msg.parts.map { part ->
                            if (part is UIMessagePart.Image && part.url.startsWith(CONTENT_SCHEME)) {
                                toDataUrlOrPlaceholder(ctx.context, part)
                            } else {
                                part
                            }
                        },
                )
            }
        }

    // 名字刻意避开 `normalize`（与 Kotlin 标准库 String.normalize() 同名，会被结构化自检误判成跨文件引用 private）
    private fun toDataUrlOrPlaceholder(
        context: Context,
        image: UIMessagePart.Image,
    ): UIMessagePart {
        val dataUrl =
            runCatching {
                val uri = Uri.parse(image.url)
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                val bytes =
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("openInputStream returned null")
                if (bytes.isEmpty()) error("empty image stream")
                "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
            }.getOrNull()

        return if (dataUrl != null) {
            image.copy(url = dataUrl)
        } else {
            // 明确占位：宁可让模型与用户看到"没发出去"，也不要静默变成空内容
            UIMessagePart.Text(UNAVAILABLE)
        }
    }
}
