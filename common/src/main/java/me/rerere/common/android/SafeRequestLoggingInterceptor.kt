package me.rerere.common.android

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 最小请求日志拦截器，供不依赖 :app 的模块（:ai、:speech、:search 等）使用。
 *
 * 与 :app 的 [RequestLoggingInterceptor] 不同，这里只记 method + scheme://host +
 * 状态码 + 耗时 + error，**不记 path、query、headers、body**：这些模块的请求里
 * token 常出现在 URL path（Telegram Bot API）或 header 名由用户配置
 * （VaultHttpTools），query/body 也可能含敏感内容（DoH 域名），而 :common 层
 * 没有 LogRedactor 做脱敏，索性一律不记。
 *
 * 开关与 :app 一致：只在 [Logging.isRequestLoggingEnabled] 为真时记；关时直接放行，
 * 不读 body、不做任何拷贝。
 */
class SafeRequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }
        val request = chain.request()
        val url = request.url
        val portSuffix =
            if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        val hostUrl = "${url.scheme}://${url.host}$portSuffix"
        val startTime = System.currentTimeMillis()
        return try {
            val response = chain.proceed(request)
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = TAG,
                    url = hostUrl,
                    method = request.method,
                    responseCode = response.code,
                    durationMs = System.currentTimeMillis() - startTime,
                ),
            )
            response
        } catch (e: IOException) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = TAG,
                    url = hostUrl,
                    method = request.method,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = e.message,
                ),
            )
            throw e
        }
    }

    companion object {
        private const val TAG = "HTTP"
    }
}
