package me.rerere.rikkahub.data.ai

import okhttp3.OkHttpClient

/**
 * 自建 OkHttpClient 的统一入口：挂完整请求日志拦截器。
 *
 * 与 DI client（[me.rerere.rikkahub.di.DataSourceModule]）同口径：用
 * [OkHttpClient.Builder.addNetworkInterceptor]，只在请求日志开关打开时记，
 * 关时直接放行；URL query 中的 key、Authorization / api-key 类 header 值
 * 与 body 里的明文 token 由 [me.rerere.rikkahub.utils.LogRedactor] 脱敏。
 *
 * 只给「请求目标可信、能脱敏」的自建 client 用：URL path 含 token
 * （Telegram Bot API）、header 名用户可配（VaultHttpTools）、body/query
 * 含域名明文（DoH）的 client 改挂 :common 的
 * [me.rerere.common.android.SafeRequestLoggingInterceptor]（只记 host），
 * 见各调用点注释。
 */
object LoggedOkHttpClients {
    fun builder(): OkHttpClient.Builder =
        OkHttpClient.Builder().addNetworkInterceptor(RequestLoggingInterceptor())
}
