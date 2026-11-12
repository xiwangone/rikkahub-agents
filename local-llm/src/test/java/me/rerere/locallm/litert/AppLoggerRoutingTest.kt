package me.rerere.locallm.litert

import android.util.Log
import me.rerere.common.log.AppLogger
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 批 2 框架收敛：local-llm 的直接 Log.* 已改 AppLogger。
 * 用录制 sink 证明改过的调用点真的走了 AppLogger，而不是只换了名字。
 */
class AppLoggerRoutingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(
                tag: String,
                message: String,
            ) {
                recorded += Triple('D', tag, message)
            }

            override fun i(
                tag: String,
                message: String,
            ) {
                recorded += Triple('I', tag, message)
            }

            override fun w(
                tag: String,
                message: String,
            ) {
                recorded += Triple('W', tag, message)
            }

            override fun w(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                recorded += Triple('W', tag, message)
            }

            override fun e(
                tag: String,
                message: String,
            ) {
                recorded += Triple('E', tag, message)
            }

            override fun e(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                recorded += Triple('E', tag, message)
            }
        }

    // AppLogger 没有卸载接口；测试后装一个直通 logcat 的 sink，让同 JVM 内
    // 后续测试的 AppLogger 调用恢复默认行为（只记录、不干预）。
    private val logcatSink =
        object : AppLogger.Sink {
            override fun d(
                tag: String,
                message: String,
            ) {
                Log.d(tag, message)
            }

            override fun i(
                tag: String,
                message: String,
            ) {
                Log.i(tag, message)
            }

            override fun w(
                tag: String,
                message: String,
            ) {
                Log.w(tag, message)
            }

            override fun w(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                Log.w(tag, message, tr)
            }

            override fun e(
                tag: String,
                message: String,
            ) {
                Log.e(tag, message)
            }

            override fun e(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                Log.e(tag, message, tr)
            }
        }

    @Before
    fun installRecordingSink() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun restoreLogcatSink() {
        AppLogger.install(logcatSink)
    }

    @Test
    fun `readDeclaredMaxTokens failure routes through AppLogger as error`() {
        // 目录不是文件：inputStream() 抛 FileNotFoundException，走 onFailure 分支。
        // 带 throwable 的 w 按批 2 级别口径（带异常的 w 转 e）应记为 e。
        val result = LiteRtModelFile.readDeclaredMaxTokens(tempFolder.root)
        assertNull(result)
        assertTrue(
            recorded.any { (level, tag, _) -> level == 'E' && tag == "LiteRtModelFile" },
        )
    }
}
