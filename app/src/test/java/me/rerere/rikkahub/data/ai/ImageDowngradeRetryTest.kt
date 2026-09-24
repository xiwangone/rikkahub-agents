package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 图片能力不匹配的失败驱动降级（P102 方案 B）：
 * ① 判据只认「图片不支持」这一种失败 —— 网络、5xx 等不该触发去图；
 * ② 去图要彻底（不留 Image part）且**留明确占位**（不静默删掉，模型要知道这里原本有图）。
 */
class ImageDowngradeRetryTest {

    private fun messageWithImages(vararg urls: String) =
        UIMessage(
            role = MessageRole.USER,
            parts =
                buildList {
                    add(UIMessagePart.Text("看下这张图"))
                    urls.forEach { url -> add(UIMessagePart.Image(url = url)) }
                },
        )

    @Test
    fun `only image-unsupported failures qualify for downgrade`() {
        assertTrue(
            shouldDowngradeImagesOnFailure(
                RuntimeException("bad request"),
                "This model does not support image input",
            ),
        )
        assertFalse(
            "网络/超时类失败不属于能力不匹配",
            shouldDowngradeImagesOnFailure(IOException("connection reset"), "socket timeout"),
        )
        assertFalse(
            "普通服务端错误不该触发去图",
            shouldDowngradeImagesOnFailure(RuntimeException("boom"), "503 server unavailable"),
        )
    }

    @Test
    fun `image parts are replaced by an explicit placeholder`() {
        val result =
            stripImagePartsForUnsupportedModel(
                listOf(messageWithImages("data:image/png;base64,AAA", "data:image/png;base64,BBB")),
            )

        assertEquals(2, result.replaced)
        val parts = result.messages.single().parts
        assertEquals(3, parts.size)
        assertTrue("去图后不应残留 Image part", parts.none { it is UIMessagePart.Image })
        assertEquals(
            2,
            parts.count { it is UIMessagePart.Text && it.text == IMAGE_DOWNGRADE_PLACEHOLDER },
        )
        assertEquals("原有文本要保留", "看下这张图", (parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `messages without images are returned untouched`() {
        val plain = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("只有文字")))
        val result = stripImagePartsForUnsupportedModel(listOf(plain))

        assertEquals(0, result.replaced)
        assertEquals(plain, result.messages.single())
    }
}
