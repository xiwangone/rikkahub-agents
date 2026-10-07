package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        // 协程取消要传导到底层 Call，否则被中止的请求会在后台继续跑完（连接 / 流量 / 电量泄漏）。
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { cause, _, _ ->
                        response.closeQuietly()
                    }
                }
            },
        )
    }
