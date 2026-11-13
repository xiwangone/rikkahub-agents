package me.rerere.rikkahub.data.repository

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO 模式后台补探针的启动语义回归（P0 核心：工具返回不等探测完成）：
 * 默认钩子把块发到后台协程——调用返回时块尚未跑完，慢探测不会拖住调用方。
 * 真连接上的端到端时序（命令接近超时 + 探测很慢仍返回命令结果）需要真实 sshd，
 * execOneShot 又无法在单测里假造 JSch Session，单测做不了，见说明“未验到”。
 */
class HostProbeSupplementLaunchTest {

    @Test
    fun `launch returns immediately without waiting for the slow block`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        launchHostProbeSupplement {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            finished.set(true)
        }
        // 块已在后台启动，但调用早已返回：块仍被门闩卡住、未跑完。
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse(finished.get())
        release.countDown()
    }
}
