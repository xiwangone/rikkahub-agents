package me.rerere.ai.agent

import kotlinx.coroutines.flow.FlowCollector

/** A failed delivery is terminal, even if an upstream adapter accidentally catches its exception. */
class FailureTrackingCollector<T>(private val downstream: FlowCollector<T>) : FlowCollector<T> {
    var failure: Throwable? = null
        private set

    fun throwIfFailed() {
        failure?.let { throw it }
    }

    override suspend fun emit(value: T) = emitFrom { value }

    /** Include preview preparation in the delivery boundary so it cannot replay a model request. */
    // 有意 catch Throwable：投递边界要把任何失败锁存为终态（不论异常类型），只挑一部分会把真实失败漏出边界。
    @Suppress("TooGenericExceptionCaught")
    suspend fun emitFrom(value: suspend () -> T) {
        throwIfFailed()
        try {
            downstream.emit(value())
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }
}
