package me.rerere.ai.util

import android.content.ContextWrapper
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRU key 轮询的持久化缓存不得明文落盘真实 API key。
 *
 * `KeyRoulette.lru()` 把 `Map<providerId, Map<apiKey, lastUsed>>` 以 JSON 明文写到
 * `cacheDir/lru_key_roulette.json`。直接配真 key 的用户，其 key 会以明文躺在
 * cache 目录里，任何能读应用缓存的途径都能直接拿走。
 *
 * 正确行为：缓存文件里不出现任何明文 key（例如只存 key 的哈希）。
 *
 * 注意：本测试预期在 bug 修复前失败——失败即证实该问题。
 */
class KeyRoulettePlaintextTest {

    // 明显是测试夹具的假 key：测试夹具占位串，不含任何真实凭证。
    private val testApiKey = "fixture-key-0000-not-a-credential"

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `lru cache file must not contain the plaintext api key`() {
        val cacheDir = newTempCacheDir()
        val roulette = KeyRoulette.lru(fakeContext(cacheDir))

        roulette.next(testApiKey, providerId = "plaintext-test-single")

        val content = waitForCacheFile(cacheDir)
        assertFalse(
            "lru_key_roulette.json must not contain the plaintext API key",
            content.contains(testApiKey),
        )
    }

    @Test
    fun `lru cache file must not contain any of several plaintext api keys`() {
        val cacheDir = newTempCacheDir()
        val roulette = KeyRoulette.lru(fakeContext(cacheDir))
        val secondKey = "fixture-key-1111-not-a-credential"

        // 多 key 才会真正走轮询；两个 key 都可能被写进缓存。
        roulette.next("$testApiKey, $secondKey", providerId = "plaintext-test-multi")
        roulette.next("$testApiKey, $secondKey", providerId = "plaintext-test-multi")

        val content = waitForCacheFile(cacheDir)
        assertFalse(
            "lru_key_roulette.json must not contain the plaintext API key",
            content.contains(testApiKey),
        )
        assertFalse(
            "lru_key_roulette.json must not contain the plaintext API key",
            content.contains(secondKey),
        )
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /**
     * 只提供真实 cacheDir 的最小 Context。
     *
     * 本模块单测没有 Robolectric/mock 依赖；`android.test.mock.MockContext`
     * 不在单测 classpath 上（`android.test` 包未解析）。改用 framework 自带的
     * concrete 类 `ContextWrapper`，传 null base、只覆写 getCacheDir——
     * LruKeyRoulette 只用 `context.cacheDir`，其余方法不会被调到。
     */
    private fun fakeContext(cacheDir: File) = object : ContextWrapper(null) {
        override fun getCacheDir(): File = cacheDir
    }

    private fun newTempCacheDir(): File {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "key-roulette-plaintext-test-${System.nanoTime()}",
        )
        assertTrue("failed to create temp cache dir: $dir", dir.mkdirs())
        tempDirs.add(dir)
        return dir
    }

    /**
     * saveCache 走单线程写盘 executor，是异步的：轮询直至文件出现再读。
     * 文件始终不出现时直接失败，避免把“没写盘”误读成“没泄漏”。
     */
    private fun waitForCacheFile(cacheDir: File): String {
        val file = File(cacheDir, "lru_key_roulette.json")
        val deadline = System.currentTimeMillis() + 5_000L
        while (!file.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("lru cache file was not written: $file", file.exists())
        return file.readText()
    }
}
