package me.rerere.speech.http

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BoundedBodyTest {
    @Test
    fun readBoundedBytesReturnsSmallBody() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals("audio".toByteArray().toList(), "audio".toResponseBody().readBoundedBytes(16).toList())
        assertEquals(bytes.toList(), bytes.toResponseBody().readBoundedBytes(16).toList())
    }

    @Test
    fun readBoundedBytesRejectsOversizedBody() {
        val body = ByteArray(129).toResponseBody()
        val error = assertThrows(IOException::class.java) {
            body.readBoundedBytes(128)
        }
        assertTrue(error.message!!.contains("exceeds 128 bytes"))
    }

    @Test
    fun readBoundedStringDecodesSmallBody() {
        assertEquals("你好", "你好".toResponseBody().readBoundedString(128))
    }

    @Test
    fun truncateForErrorKeepsShortText() {
        assertEquals("boom", "boom".truncateForError())
    }

    @Test
    fun truncateForErrorCapsLongTextAndReportsLength() {
        val text = "x".repeat(5000)
        val snippet = text.truncateForError()
        assertTrue(snippet.length < 2000)
        assertTrue(snippet.contains("truncated, 5000 chars total"))
    }
}
