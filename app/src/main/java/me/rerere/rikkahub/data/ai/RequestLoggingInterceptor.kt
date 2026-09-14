package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.utils.LogRedactor
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * 单个请求体进入日志前允许的最大字节数。超过此值只记录摘要，不做全文读取与脱敏——
 * 内联 base64 图片、超长上下文等大请求体，会让脱敏过程在堆上复制数份等大的字符串，
 * 可能触发 OutOfMemoryError。
 */
private const val MAX_LOGGED_BODY_BYTES = 1024L * 1024L

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // 记录前对敏感信息脱敏：Authorization / api-key 等 header 值、URL query 中的 key、body 中的明文 token
        val requestHeaders = LogRedactor.maskHeaders(request.headers.toMap())
        val requestUrl = LogRedactor.maskUrl(request.url.toString())
        val requestBody = request.body?.let { body ->
            val declared = body.contentLength()
            when {
                // 超大请求体（如内联 base64 图片、超长上下文）直接跳过全文读取与脱敏：
                // 读取 + 正则替换会在堆上复制数份同样大的字符串，足以触发 OOM 崩溃。
                declared > MAX_LOGGED_BODY_BYTES ->
                    "[body omitted: $declared bytes exceeds the ${MAX_LOGGED_BODY_BYTES}B logging limit]"

                else -> {
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    val actual = buffer.size
                    if (actual > MAX_LOGGED_BODY_BYTES) {
                        "[body omitted: $actual bytes exceeds the ${MAX_LOGGED_BODY_BYTES}B logging limit]"
                    } else {
                        LogRedactor.maskText(buffer.readUtf8())
                    }
                }
            }
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = requestUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = requestUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
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
