package me.rerere.rikkahub.data.ai

import java.io.IOException
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.utils.LogRedactor
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSink
import okio.buffer

/**
 * 单个请求体进入日志前允许的最大字节数。超过此值只记录摘要，不做全文读取与脱敏，
 * 避免内联图片等大请求体在读取与正则替换过程中产生多份等大字符串而耗尽堆内存。
 */
private const val MAX_LOGGED_BODY_BYTES = 1024L * 1024L

/**
 * 请求日志拦截器（挂在默认 OkHttpClient 的 network 拦截器链上）。
 *
 * 开关关时默认不记请求日志，但失败必须留痕：非 200 响应与传输失败（IOException）
 * 强制记一条。强制条目只含元数据（方法、脱敏后 URL/头、状态码、耗时、错误），
 * 不含请求体——开关关时用户未授权请求体入日志，且事后读 body 可能遇到一次性流；
 * 要看完整请求体请打开请求日志开关后复现。
 */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val requestLoggingEnabled = Logging.isRequestLoggingEnabled()

        // 开关开时才在发请求前读请求体（与旧行为一致：可重读的缓冲体）；
        // 开关关时跳过这一步，成功路径只多一次时钟读数 + 一次状态码比较。
        val requestBody = if (requestLoggingEnabled) readMaskedRequestBody(request.body) else null

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            // 传输失败：连响应都没有，强制记一条（不含请求体，见类注释）。
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = LogRedactor.maskUrl(request.url.toString()),
                    method = request.method,
                    requestHeaders = LogRedactor.maskHeaders(request.headers.toMap()),
                    requestBody = requestBody,
                    error = e.message
                ),
                force = true
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        // 开关开时全记；开关关时只记非 200——失败请求必须留一条可追的记录。
        if (requestLoggingEnabled || response.code != 200) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = LogRedactor.maskUrl(request.url.toString()),
                    method = request.method,
                    requestHeaders = LogRedactor.maskHeaders(request.headers.toMap()),
                    requestBody = requestBody,
                    responseCode = response.code,
                    responseHeaders = LogRedactor.maskHeaders(response.headers.toMap()),
                    durationMs = durationMs
                ),
                force = true
            )
        }

        return response
    }

    /**
     * 读请求体并脱敏（≤1MB 上限，见 [MAX_LOGGED_BODY_BYTES]）。只在请求日志
     * 开关开时调用；调用点在 `chain.proceed` 之前，与旧行为一致。
     */
    private fun readMaskedRequestBody(body: RequestBody?): String? {
        val declared = body?.contentLength() ?: return null
        if (declared > MAX_LOGGED_BODY_BYTES) {
            // 超大请求体（如内联 base64 图片、超长上下文）直接跳过全文读取与脱敏：
            // 读取 + 正则替换会在堆上复制数份同样大的字符串，足以触发 OOM 崩溃。
            return "[body omitted: $declared bytes exceeds the ${MAX_LOGGED_BODY_BYTES}B logging limit]"
        }
        // contentLength 未知时不能先全量缓冲再判上限：写入过程本身就可能撑爆堆。
        // 用限流 sink 在超限瞬间中断，只记录摘要；未超限才做全文脱敏。
        val buffer = Buffer()
        val sink = object : ForwardingSink(buffer) {
            override fun write(source: Buffer, byteCount: Long) {
                if (buffer.size + byteCount > MAX_LOGGED_BODY_BYTES) {
                    throw BodyTooLargeException()
                }
                super.write(source, byteCount)
            }
        }.buffer()
        return try {
            body.writeTo(sink)
            sink.flush()
            LogRedactor.maskText(buffer.readUtf8())
        } catch (_: BodyTooLargeException) {
            "[body omitted: exceeds the ${MAX_LOGGED_BODY_BYTES}B logging limit]"
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }
}

/** 请求体写入超限时中断缓冲的内部信号（只由限流 sink 抛出，不代表真实 IO 失败）。 */
private class BodyTooLargeException : RuntimeException()
