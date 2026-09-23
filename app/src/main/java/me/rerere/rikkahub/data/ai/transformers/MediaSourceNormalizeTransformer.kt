package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Base64OutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * 发送前把「本地 / 远程来源」的媒体规范化成模型真能收到的形式（痛点 P103 / P104 / P105）。
 *
 * 背景：`me.rerere.ai.util.encodeBase64` 的各重载只认很窄的来源 ——
 * 图片认 `file://` / `data:` / `http(s)`（且把 http URL **原文当 base64** 发出去，等于没发），
 * 视频与音频**只认 `file://`**，其余一律抛异常；而所有 provider 调用点都用 `.onSuccess { … }` 接，
 * 失败既不写日志也不留占位 → 附件**静默消失**。用户侧表现就是"有时能看到图、有时只看到路径"。
 *
 * 本 transformer 在发送前统一兜住：
 *  1. `content://`（相册 / 文件选择器最常见）→ contentResolver 读流成 data URL（Image / Video / Audio，带体积上限）；
 *  2. `http(s)://` 图片 → 先下载再压缩编码（此前是拿 URL 原文当 base64 发）；
 *  3. 任一环节失败 → **明确占位文本**（而不是留空静默丢）；
 *  4. 单次请求的媒体总预算 —— 超出后按顺序把后面的降级为占位。
 *
 * 安全（http(s) 下载）：只允许**公网**主机（拒回环 / 私网 / 链路本地 / 组播；对域名先解析再逐个校验，
 * 降低 DNS rebinding 面），限制响应体积与超时；失败即占位。
 *
 * 注：工具函数放在**文件级**（而非 object 内），既避开 detekt 的 `TooManyFunctions`，
 * 也避免"私有扩展函数"被结构化自检误判成跨文件引用。
 */
object MediaSourceNormalizeTransformer : InputMessageTransformer {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(REMOTE_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> =
        withContext(Dispatchers.IO) {
            if (messages.none { msg -> msg.parts.any { isMediaPart(it) } }) {
                return@withContext messages
            }

            // ① 来源规范化：content:// → data URL；http(s) 图片 → 下载 + 压缩编码
            val normalized =
                messages.map { msg ->
                    msg.copy(parts = msg.parts.map { part -> normalizeSource(ctx.context, part) })
                }

            // ② 总预算：逐条编码（走 encodeBase64 缓存，不产生额外解码）累计，超出即降级为占位
            var used = 0
            normalized.map { msg ->
                msg.copy(
                    parts =
                        msg.parts.map { part ->
                            if (!isMediaPart(part)) {
                                part
                            } else {
                                val length = encodedLengthOf(part)
                                if (used + length > TOTAL_BASE64_BUDGET) {
                                    UIMessagePart.Text(OVER_BUDGET)
                                } else {
                                    used += length
                                    part
                                }
                            }
                        },
                )
            }
        }

    private fun normalizeSource(
        context: Context,
        part: UIMessagePart,
    ): UIMessagePart {
        val url = mediaUrlOf(part) ?: return part
        return when {
            url.startsWith(CONTENT_SCHEME) -> fromContent(context, part, url)
            url.startsWith(HTTP_SCHEME) || url.startsWith(HTTPS_SCHEME) -> fromRemote(part, url)
            else -> part
        }
    }

    /** content:// → data URL（只做二进制搬运，不解码重编码）。 */
    private fun fromContent(
        context: Context,
        part: UIMessagePart,
        url: String,
    ): UIMessagePart {
        return try {
            val uri = Uri.parse(url)
            val resolver = context.contentResolver
            val declaredLength = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (declaredLength > MAX_CONTENT_BYTES) return UIMessagePart.Text(TOO_LARGE)
            val mime = resolver.getType(uri) ?: defaultMimeOf(part)
            val stream = resolver.openInputStream(uri) ?: return UIMessagePart.Text(UNAVAILABLE)
            val bytes = stream.use { readAtMost(it, MAX_CONTENT_BYTES) }
            if (bytes.isEmpty()) return UIMessagePart.Text(UNAVAILABLE)
            withMediaUrl(part, "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
        } catch (_: TooLargeException) {
            UIMessagePart.Text(TOO_LARGE)
        } catch (_: Exception) {
            UIMessagePart.Text(UNAVAILABLE)
        }
    }

    /** http(s) 图片 → 下载 + 压缩 + 编码；视频 / 音频不做内联（体积与模型支持都不划算）。 */
    private fun fromRemote(
        part: UIMessagePart,
        url: String,
    ): UIMessagePart {
        if (part !is UIMessagePart.Image) return UIMessagePart.Text(UNAVAILABLE)
        val host = runCatching { URI(url).host }.getOrNull() ?: return UIMessagePart.Text(UNAVAILABLE)
        if (!isPubliclyRoutable(host)) return UIMessagePart.Text(BLOCKED_HOST)
        return try {
            val bytes = downloadMedia(httpClient, url)
            val base64 = compressImageToBase64(bytes)
            withMediaUrl(part, "data:image/jpeg;base64,$base64")
        } catch (_: TooLargeException) {
            UIMessagePart.Text(TOO_LARGE)
        } catch (_: Exception) {
            UIMessagePart.Text(UNAVAILABLE)
        }
    }
}

// ---------------------------------------------------------------------------
// 文件级工具（对象外，保持 MediaSourceNormalizeTransformer 函数数在阈值内）
// ---------------------------------------------------------------------------

private const val CONTENT_SCHEME = "content://"
private const val HTTP_SCHEME = "http://"
private const val HTTPS_SCHEME = "https://"

/** content:// 单附件读入上限（视频 / 音频可能很大，超限直接占位，不撑爆请求体）。 */
private const val MAX_CONTENT_BYTES = 12L * 1024 * 1024

/** http(s) 单附件下载上限（先看 Content-Length 预检，再流式截断）。 */
private const val MAX_REMOTE_BYTES = 10L * 1024 * 1024
private const val REMOTE_TIMEOUT_SECONDS = 10L

/** 单次请求所有媒体 base64 的总预算（约合 4.5 MB 原始），超出部分降级为占位。 */
private const val TOTAL_BASE64_BUDGET = 6 * 1024 * 1024

/** 远程图片压缩参数（与 FileEncoder 的口径保持一致：2048 长边 + JPEG q85）。 */
private const val REMOTE_MAX_DIMENSION = 2048
private const val REMOTE_JPEG_QUALITY = 85

private const val UNAVAILABLE = "[Attachment unavailable: could not read its data on the device]"
private const val TOO_LARGE = "[Attachment omitted: exceeds the size limit for inline sending]"
private const val BLOCKED_HOST = "[Attachment omitted: remote host is not allowed]"
private const val OVER_BUDGET = "[Attachment omitted: request media budget exceeded]"

private fun isMediaPart(part: UIMessagePart): Boolean =
    part is UIMessagePart.Image || part is UIMessagePart.Video || part is UIMessagePart.Audio

private fun mediaUrlOf(part: UIMessagePart): String? =
    when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Audio -> part.url
        else -> null
    }

private fun withMediaUrl(
    part: UIMessagePart,
    newUrl: String,
): UIMessagePart =
    when (part) {
        is UIMessagePart.Image -> part.copy(url = newUrl)
        is UIMessagePart.Video -> part.copy(url = newUrl)
        is UIMessagePart.Audio -> part.copy(url = newUrl)
        else -> part
    }

/** 已编码后的 base64 长度；拿不到（编码失败）按 0 计，交给后续环节处理。 */
private fun encodedLengthOf(part: UIMessagePart): Int =
    when (part) {
        is UIMessagePart.Image -> encodeBase64OfImage(part)
        is UIMessagePart.Video -> part.encodeBase64().getOrNull()?.length ?: 0
        is UIMessagePart.Audio -> part.encodeBase64().getOrNull()?.length ?: 0
        else -> 0
    }

private fun encodeBase64OfImage(image: UIMessagePart.Image): Int =
    image.encodeBase64().getOrNull()?.base64?.length ?: 0

private fun downloadMedia(
    client: OkHttpClient,
    url: String,
): ByteArray {
    val request = Request.Builder().url(url).header("Accept", "image/*").build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("empty response body")
        if (body.contentLength() > MAX_REMOTE_BYTES) throw TooLargeException()
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, 64 * 1024)
            if (read == -1L) break
            total += read
            if (total > MAX_REMOTE_BYTES) throw TooLargeException()
        }
        return buffer.readByteArray()
    }
}

private fun compressImageToBase64(bytes: ByteArray): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, REMOTE_MAX_DIMENSION)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("failed to decode remote image")
    return try {
        val out = ByteArrayOutputStream()
        Base64OutputStream(out, Base64.NO_WRAP).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, REMOTE_JPEG_QUALITY, stream)
        }
        out.toString(Charsets.ISO_8859_1.name())
    } finally {
        bitmap.recycle()
    }
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= maxDimension) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** 只允许公网主机：拒回环 / 私网 / 链路本地 / 组播 / 常见内网域名，并逐个校验解析结果。 */
private fun isPubliclyRoutable(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized == "localhost" || normalized.endsWith(".localhost")) return false
    if (normalized.endsWith(".local") || normalized.endsWith(".internal") || normalized.endsWith(".home")) {
        return false
    }
    val addresses =
        runCatching { InetAddress.getAllByName(normalized) }.getOrNull() ?: return false
    if (addresses.isEmpty()) return false
    return addresses.all { isPublicAddress(it) }
}

private fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
    if (address.isLinkLocalAddress || address.isSiteLocalAddress) return false
    if (address.isMulticastAddress) return false
    val raw = address.address
    if (raw.size != 16) return true
    val first = raw[0].toInt() and 0xff
    if (first and 0xfe == 0xfc) return false // ULA fc00::/7
    val firstTenZero = raw.copyOfRange(0, 10).all { it == 0.toByte() }
    val nextTwoAreFf = (raw[10].toInt() and 0xff) == 0xff && (raw[11].toInt() and 0xff) == 0xff
    // 保守：v4-mapped（::ffff:a.b.c.d）一律不内联
    return !(firstTenZero && nextTwoAreFf)
}

private fun readAtMost(
    stream: InputStream,
    limit: Long,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw TooLargeException()
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun defaultMimeOf(part: UIMessagePart): String =
    when (part) {
        is UIMessagePart.Image -> "image/png"
        is UIMessagePart.Video -> "video/mp4"
        is UIMessagePart.Audio -> "audio/mpeg"
        else -> "application/octet-stream"
    }

private class TooLargeException : Exception("attachment exceeds size limit")
