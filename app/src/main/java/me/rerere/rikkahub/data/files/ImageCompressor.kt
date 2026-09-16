package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import me.rerere.rikkahub.data.log.AppLog
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 聊天图片附件的发送前压缩。
 *
 * 动机（实测踩到的）：原图直接进请求有两个后果 ——
 *  ① 体积大 → 远程模型（尤其 OCR 转述链路）容易超时或失败，表现为「图片发出去但没人看到」；
 *  ② 视觉模型对超出其输入上限的图会自行缩放，白传多余的字节。
 *
 * 做法沿用业界常见的保守参数：长边超过上限才等比缩小，JPEG 质量取「肉眼几乎无损」档；
 * GIF（动图）与非图片一律不动；任何异常都回退为「原样复制」，绝不让压缩挡住发送。
 */
internal object ImageCompressor {

    private const val TAG = "ImageCompressor"

    /** 长边上限：多数视觉模型的有效输入在 1.5k~2k 区间，2048 兼顾清晰度与体积。 */
    private const val MAX_DIMENSION = 2048

    /** JPEG 质量：88 在体积与观感之间取平衡（再高体积涨得比观感快）。 */
    private const val JPEG_QUALITY = 88

    /** 已足够小且尺寸合规的图不重压，避免无谓的有损转换。 */
    private const val SKIP_BYTES = 1_500_000L

    data class Result(
        val compressed: Boolean,
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    /** 判断该 MIME 是否适合压缩（GIF 会丢动图，SVG 是矢量，均排除）。 */
    fun isCompressibleImage(mime: String?): Boolean {
        val m = mime?.lowercase() ?: return false
        return m.startsWith("image/") && m != "image/gif" && m != "image/svg+xml"
    }

    /**
     * 把 [source] 压缩写入 [dest]。
     * 返回 null 表示「未压缩/压缩失败」——调用方应按原样复制处理（保证功能不退化）。
     */
    fun compressToFile(context: Context, source: Uri, dest: File): Result? = runCatching {
        // 1) 只读边界，先看尺寸与体积
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val needsResize = max(srcW, srcH) > MAX_DIMENSION
        val srcBytes = runCatching {
            context.contentResolver.openInputStream(source)?.use { ins -> ins.available().toLong() }
        }.getOrNull() ?: 0L
        if (!needsResize && srcBytes in 1..SKIP_BYTES) return null   // 已经够小，原样用

        // 2) 按 2 的幂采样粗降，避免先解码一张超大位图（OOM 风险）
        var sample = 1
        while (srcW / (sample * 2) >= MAX_DIMENSION && srcH / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(source)
            ?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null

        // 3) 精确缩放到长边上限
        val scale = MAX_DIMENSION.toFloat() / max(decoded.width, decoded.height)
        val finalBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }

        // 4) 写 JPEG（dest 的文件名由调用方给出 .jpg 后缀）
        dest.outputStream().use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        val result = Result(
            compressed = true,
            width = finalBitmap.width,
            height = finalBitmap.height,
            bytes = dest.length(),
        )
        finalBitmap.recycle()
        result
    }.onFailure {
        AppLog.w(TAG, "compressToFile failed for $source: ${it.message}; falling back to raw copy")
    }.getOrNull()
}
