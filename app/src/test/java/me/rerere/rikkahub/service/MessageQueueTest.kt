package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `blank input is ignored`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("   ")))
        queue.enqueue(emptyList())

        assertEquals(0, queue.size)
    }

    @Test
    fun `pausing stops dispatch but keeps queued content for resume`() {
        val queue = MessageQueue()
        queue.enqueue(text("keep me"))
        queue.pause()

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)

        queue.resume()
        assertFalse(queue.state.value.paused)
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        assertNotNull(queue.beginEdit(id))
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `cancelled edit only releases the placeholder`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)

        // 放弃编辑：不返回待清理条目，内容保持原样
        assertNull(queue.finishEdit(id))
        assertFalse(queue.state.value.messages.single().isEditing)
        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a queued message returns it and keeps the rest in order`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))

        val removed = queue.remove(queue.state.value.messages.first().id)
        assertEquals(text("first"), removed!!.parts)
        assertEquals(1, queue.size)
        assertNull(queue.remove(Uuid.random()))
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `drainAll takes everything and clears the queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))

        assertEquals(listOf(text("first"), text("second")), queue.drainAll().map { it.parts })
        assertTrue(queue.state.value.messages.isEmpty())
        assertTrue(queue.drainAll().isEmpty())
    }

    @Test
    fun `clear empties messages and resets paused`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()

        queue.clear()

        assertTrue(queue.state.value.messages.isEmpty())
        assertFalse(queue.state.value.paused)
    }
}
