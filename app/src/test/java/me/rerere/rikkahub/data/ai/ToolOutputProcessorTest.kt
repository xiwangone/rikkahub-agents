package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolOutputProcessorTest {
    @Test
    fun `truncation preview keeps original part order and text metadata`() {
        val firstMetadata = buildJsonObject { put("kind", "first") }
        val secondMetadata = buildJsonObject { put("kind", "second") }
        val image = UIMessagePart.Image(url = "file:///image.png")
        val video = UIMessagePart.Video(url = "file:///video.mp4")
        val output = listOf(
            UIMessagePart.Text("abc", firstMetadata),
            image,
            UIMessagePart.Text("def", secondMetadata),
            video,
        )

        val result = applyTruncationPreview(output, preview = "abc\nde", summary = "summary\n")

        assertEquals(4, result.size)
        assertEquals("summary\nabc", (result[0] as UIMessagePart.Text).text)
        assertSame(image, result[1])
        assertEquals("de", (result[2] as UIMessagePart.Text).text)
        assertSame(video, result[3])
        assertEquals(firstMetadata, (result[0] as UIMessagePart.Text).metadata)
        assertEquals(secondMetadata, (result[2] as UIMessagePart.Text).metadata)
    }
}
