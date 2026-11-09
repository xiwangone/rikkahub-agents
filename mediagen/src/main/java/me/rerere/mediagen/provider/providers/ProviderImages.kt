package me.rerere.mediagen.provider.providers

import me.rerere.mediagen.model.MediaGenerationInput
import java.io.File
import kotlin.io.encoding.Base64

internal fun imageMimeType(format: String?, fallback: String): String = when (format?.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> fallback
}

// 结果地址通常带签名参数，扩展名在 path 末尾
internal fun String.urlFileExtension(): String? =
    substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf(String::isNotEmpty)

/**
 * 把图片输入转成 JSON 接口可直接引用的地址：公网地址和 data URI 原样返回，本地文件读出后编码成 data URI。
 * 会读取整个文件，需要在 IO 线程调用。
 */
internal fun MediaGenerationInput.Image.toUrlOrDataUri(): String {
    if (REMOTE_PREFIXES.any { url.startsWith(it, ignoreCase = true) }) return url
    val file = File(url.removePrefix("file://"))
    require(file.exists()) { "Image file does not exist: $url" }
    require(file.length() <= MAX_LOCAL_IMAGE_BYTES) {
        "Image file is too large: ${file.length()} bytes (max $MAX_LOCAL_IMAGE_BYTES)"
    }
    val mimeType = LOCAL_IMAGE_MIME_TYPES[file.extension.lowercase()]
        ?: throw IllegalArgumentException("Unsupported image file type: ${file.extension}")
    return "data:$mimeType;base64,${Base64.encode(file.readBytes())}"
}

// 本地参考图整文件读入内存再 base64 化, 上限避免超大文件直接占满内存.
internal const val MAX_LOCAL_IMAGE_BYTES = 20 * 1024 * 1024

private val REMOTE_PREFIXES = listOf("http://", "https://", "data:")

private val LOCAL_IMAGE_MIME_TYPES = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "gif" to "image/gif",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "heic" to "image/heic",
    "heif" to "image/heif",
)
