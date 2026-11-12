package me.rerere.rikkahub.data.log

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 批 1（开关与默认策略）的 AppLog 行为：
 *  - 开关默认开：从未碰过开关的全新安装，进 buffer 不需要先去日志页手动打开；
 *  - 默认只记 I/W/E：D 级诊断默认不进内存 buffer（仍进 logcat，logcat 路径
 *    是各公开方法里无条件的 `Log.*` 调用，不依赖本测试断言）；
 *  - 最低级别可持久化：降到 'D' 后重启仍保持，D 级才进 buffer。
 *
 * [AppLog] 是单例：内存里的开关/级别与 buffer 在同一 JVM 内跨测试方法共享，
 * 所以每个测试先把 SharedPreferences 清空、再 `startIfEnabled` 重载，
 * 断言只看本测试独有的 marker，不依赖执行顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLogTest {
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
        AppLog.clear()
        AppLog.startIfEnabled(context)
    }

    @After
    fun tearDown() {
        AppLog.clear()
        AppLog.setEnabled(context, true)
        AppLog.setMinLevel(context, 'I')
    }

    @Test
    fun `app log defaults to enabled on a fresh install`() {
        assertTrue(AppLog.isEnabled(context))
    }

    @Test
    fun `default minimum level drops d but keeps i w e`() {
        AppLog.d("AppLogTest", "marker-d-default")
        AppLog.i("AppLogTest", "marker-i-default")
        AppLog.w("AppLogTest", "marker-w-default")
        AppLog.e("AppLogTest", "marker-e-default")

        val messages = AppLog.getLogs().map { it.message }
        assertFalse(messages.contains("marker-d-default"))
        assertTrue(messages.contains("marker-i-default"))
        assertTrue(messages.contains("marker-w-default"))
        assertTrue(messages.contains("marker-e-default"))
    }

    @Test
    fun `lowering the minimum level to D lets d entries into the buffer`() {
        AppLog.setMinLevel(context, 'D')
        AppLog.d("AppLogTest", "marker-d-verbose")

        assertTrue(AppLog.getLogs().map { it.message }.contains("marker-d-verbose"))
    }

    @Test
    fun `minimum level survives a restart`() {
        AppLog.setMinLevel(context, 'D')
        // 模拟重启：重新从持久化开关重载（内存值丢弃、只认磁盘）。
        AppLog.startIfEnabled(context)
        assertEquals('D', AppLog.getMinLevel(context))

        AppLog.d("AppLogTest", "marker-d-after-restart")
        assertTrue(AppLog.getLogs().map { it.message }.contains("marker-d-after-restart"))
    }

    @Test
    fun `explicitly disabling the switch drops everything from the buffer`() {
        AppLog.setEnabled(context, false)
        AppLog.i("AppLogTest", "marker-i-disabled")
        AppLog.e("AppLogTest", "marker-e-disabled")

        assertTrue(AppLog.getLogs().isEmpty())
    }

    // ---- 批 4 重-P1-5：去重 key 归一化（剥掉重试计数）----

    @Test
    fun `retry messages with different attempt fractions merge within the window`() {
        // 同一错误的不同 attempt（(1/5) 与 (2/5)）在 3 秒窗内合并为一条，
        // repeat 累加。两次 append 相隔微秒级，跨时间桶边界的概率可忽略。
        AppLog.w("AppLogTest", "marker-retry-frac (1/5) failed")
        AppLog.w("AppLogTest", "marker-retry-frac (2/5) failed")

        val entries = AppLog.getLogs().filter { it.message.contains("marker-retry-frac") }
        assertEquals(1, entries.size)
        assertEquals(2, entries.single().repeat)
    }

    @Test
    fun `attempt and try counters are normalized for dedup`() {
        AppLog.w("AppLogTest", "marker-retry-attempt attempt 1 failed")
        AppLog.w("AppLogTest", "marker-retry-attempt attempt 2 failed")
        AppLog.w("AppLogTest", "marker-retry-try try 1 failed")
        AppLog.w("AppLogTest", "marker-retry-try try 2 failed")

        val attemptEntries = AppLog.getLogs().filter { it.message.contains("marker-retry-attempt") }
        assertEquals(1, attemptEntries.size)
        assertEquals(2, attemptEntries.single().repeat)

        val tryEntries = AppLog.getLogs().filter { it.message.contains("marker-retry-try") }
        assertEquals(1, tryEntries.size)
        assertEquals(2, tryEntries.single().repeat)
    }

    @Test
    fun `different errors do not merge`() {
        AppLog.w("AppLogTest", "marker-diff alpha failed")
        AppLog.w("AppLogTest", "marker-diff beta failed")

        val entries = AppLog.getLogs().filter { it.message.contains("marker-diff") }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.repeat == 1 })
    }

    @Test
    fun `merged entry keeps the first original message`() {
        // 归一化只作用于去重 key：buffer 里存的仍是首次出现的原始消息。
        AppLog.w("AppLogTest", "marker-retry-keep (1/5) failed")
        AppLog.w("AppLogTest", "marker-retry-keep (2/5) failed")

        val entries = AppLog.getLogs().filter { it.message.contains("marker-retry-keep") }
        assertEquals(1, entries.size)
        assertTrue(entries.single().message.contains("(1/5)"))
    }
}
