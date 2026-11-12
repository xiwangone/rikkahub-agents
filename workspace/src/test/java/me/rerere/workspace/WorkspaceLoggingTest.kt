package me.rerere.workspace

import java.io.File
import java.io.IOException
import me.rerere.common.log.AppLogger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 批 3 覆-P0-3：workspace 失败路径记日志。
 *
 * 用录制 sink 证明失败调用点真的走了 AppLogger。workspace 模块的单测没有
 * `isReturnDefaultValues`，android.util.Log 是 stub，测试后装空 sink
 * 而不是 logcat sink，避免后续测试的 AppLogger 调用撞上 stub。
 */
class WorkspaceLoggingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                recorded += Triple('D', tag, message)
            }

            override fun i(tag: String, message: String) {
                recorded += Triple('I', tag, message)
            }

            override fun w(tag: String, message: String) {
                recorded += Triple('W', tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('W', tag, message)
            }

            override fun e(tag: String, message: String) {
                recorded += Triple('E', tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('E', tag, message)
            }
        }

    private val noopSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) = Unit

            override fun i(tag: String, message: String) = Unit

            override fun w(tag: String, message: String) = Unit

            override fun w(tag: String, message: String, tr: Throwable?) = Unit

            override fun e(tag: String, message: String) = Unit

            override fun e(tag: String, message: String, tr: Throwable?) = Unit
        }

    @Before
    fun setUp() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun tearDown() {
        AppLogger.install(noopSink)
    }

    @Test
    fun `replaceFilePreservingTarget failure logs e with target path`() {
        val tempFile = tempFolder.newFile("config.json.tmp")
        tempFile.writeText("""{"a":1}""")
        val targetFile = tempFolder.newFile("config.json")
        targetFile.writeText("""{"a":0}""")

        // rename 序列：放新文件失败 → 备份旧目标成功 → 再放新文件失败。
        // rename lambda 只决定返回值，不真的移动文件；目标文件真实存在，
        // 所以不走恢复分支，只走 e 日志那一行。
        val renameCalls = mutableListOf<Int>()
        val rename: (File, File) -> Boolean = { _, _ ->
            renameCalls += renameCalls.size + 1
            renameCalls.size == 2
        }
        try {
            replaceFilePreservingTarget(tempFile, targetFile, rename)
            fail("expected replaceFilePreservingTarget to throw")
        } catch (_: IOException) {
            // expected: Could not install file; original target is preserved at ...
        }
        assertTrue(
            "expected an E log with the target path, got: $recorded",
            recorded.any { (level, tag, message) ->
                level == 'E' && tag == "AtomicFileReplacement" &&
                    "replaceFilePreservingTarget failed" in message &&
                    targetFile.absolutePath in message
            },
        )
    }
}
