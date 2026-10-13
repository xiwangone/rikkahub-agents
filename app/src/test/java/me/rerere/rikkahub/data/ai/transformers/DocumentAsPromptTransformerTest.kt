package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Coverage for [DocumentAsPromptTransformer.resolveWorkspacePathForFile] — the pure,
 * android.net.Uri-free part of the path-advertising gate shared by Document and Image parts
 * (issue #37, second bullet: generated images should be advertised the same way uploaded
 * documents are, but only for files that really live under filesDir/upload).
 *
 * Note: the android.net.Uri parsing step (`String.toUri()`) that sits in front of this
 * function is NOT covered here. The :app module's JVM unit tests run against the unmocked
 * Android stub jar (no Robolectric, no `unitTests.isReturnDefaultValues`), so
 * `android.net.Uri.parse` throws and is swallowed by the surrounding `runCatching`, making
 * `data:`/`http(s)` vs. `file:` URLs indistinguishable in this test environment (see the same
 * caveat in DownloadToolTest.kt). That parsing step is exercised implicitly by production use;
 * this test covers the part that is actually testable in isolation: given a real local file,
 * is it inside the upload folder, and if so what workspace path is advertised.
 */
class DocumentAsPromptTransformerTest {

    @Test
    fun `file inside the upload folder resolves to its workspace path`() {
        val file = File(File("/data/user/0/pkg/files/upload"), "abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertEquals("/upload/abc.png", path)
    }

    @Test
    fun `file outside the upload folder resolves to null`() {
        val file = File(File("/data/user/0/pkg/files/other"), "abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertNull(path)
    }

    @Test
    fun `file with no parent resolves to null`() {
        val file = File("abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertNull(path)
    }

    // ---- 大附件引用式呈现（附件解耦 part B）----
    //
    // 只覆盖纯函数部分：buildReferencePrompt / excerptOf 不依赖 android.net.Uri 与
    // TransformerContext，可在 :app 的 JVM 单测里直接驱动；transform() 的接线（读设置项、
    // 取 ctx.hasReadableFileTool）由生产使用覆盖，与上文 resolveWorkspacePath 的测试边界一致。

    private fun referencePrompt(
        workspacePath: String? = "/upload/big.txt",
        content: String = "x".repeat(8_001),
        hasReadableFileTool: Boolean = true,
        inlineLimitChars: Int = 8_000,
    ) = DocumentAsPromptTransformer.buildReferencePrompt(
        fileName = "big.txt",
        workspacePath = workspacePath,
        content = content,
        inlineLimitChars = inlineLimitChars,
        hasReadableFileTool = hasReadableFileTool,
    )

    @Test
    fun `attachment at or below the threshold stays inline`() {
        assertNull(referencePrompt(content = "x".repeat(8_000)))
        assertNull(referencePrompt(content = "x".repeat(10)))
    }

    @Test
    fun `reference mode requires a readable file tool in this turn`() {
        assertNull(referencePrompt(hasReadableFileTool = false))
    }

    @Test
    fun `reference mode requires a resolvable workspace path`() {
        assertNull(referencePrompt(workspacePath = null))
    }

    @Test
    fun `oversized attachment becomes a reference with name path and bounded excerpt`() {
        val content = "HEAD_MARK" + "x".repeat(50_000) + "TAIL_MARK"
        val prompt = referencePrompt(content = content)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<UploadFile name=\"big.txt\" path=\"/upload/big.txt\""))
        assertTrue(prompt.contains("</UploadFile>"))
        assertTrue(prompt.contains("workspace_read_file"))
        assertTrue(prompt.contains("HEAD_MARK"))
        assertTrue(prompt.contains("TAIL_MARK"))
        assertTrue(prompt.contains("..."))
        // 正文不进 prompt：输出必须有界，远小于原文
        assertTrue(prompt.length < content.length / 10)
        assertTrue(prompt.length < 3_000)
    }

    @Test
    fun `excerpt is bounded per side and contiguous when the content is short`() {
        // 1_500 > M(1_000) 但 <= 2M：头尾相接，给全量、不插省略号
        val contiguous = "y".repeat(1_500)
        assertEquals(contiguous, DocumentAsPromptTransformer.excerptOf(contiguous))

        val long = "y".repeat(2_500)
        val excerpt = DocumentAsPromptTransformer.excerptOf(long)
        assertTrue(excerpt.length <= 1_000 + "\n...\n".length + 1_000)
        assertTrue(excerpt.startsWith("y".repeat(1_000)))
        assertTrue(excerpt.endsWith("y".repeat(1_000)))
    }
}
