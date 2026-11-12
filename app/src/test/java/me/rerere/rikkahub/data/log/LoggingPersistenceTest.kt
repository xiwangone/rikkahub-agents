package me.rerere.rikkahub.data.log

import android.app.Application
import android.content.Context
import me.rerere.common.android.Logging
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 批 1：请求日志开关默认关、但持久化。用户在日志页显式开过之后，重启不复位；
 * 从未碰过开关的全新安装，重启后仍是关。
 *
 * [Logging] 是单例：内存里的开关值在同一 JVM 内跨测试方法共享，
 * 所以每个测试先把 SharedPreferences 清空、再 `startIfEnabled` 重载，
 * 不依赖执行顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoggingPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 全新安装状态：清空开关文件，再按默认值重载。
        context
            .getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Logging.clear()
        Logging.startIfEnabled(context)
    }

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    @Test
    fun `request logging defaults to off on a fresh install`() {
        assertFalse(Logging.isRequestLoggingEnabled())
    }

    @Test
    fun `request logging switch survives a restart once enabled`() {
        Logging.setRequestLoggingEnabled(context, true)
        // 模拟重启：重新从持久化开关重载（内存值丢弃、只认磁盘）。
        Logging.startIfEnabled(context)
        assertTrue(Logging.isRequestLoggingEnabled())
    }

    @Test
    fun `request logging switch survives a restart once disabled again`() {
        Logging.setRequestLoggingEnabled(context, true)
        Logging.setRequestLoggingEnabled(context, false)
        Logging.startIfEnabled(context)
        assertFalse(Logging.isRequestLoggingEnabled())
    }
}
