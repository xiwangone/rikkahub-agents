package me.rerere.rikkahub.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEntityTagsTest {

    private fun entity(tags: String) = WorkspaceEntity(
        id = "w1",
        name = "A",
        root = "w1",
        createdAt = 0L,
        updatedAt = 0L,
        tags = tags,
    )

    @Test
    fun `valid json array parses`() {
        assertEquals(listOf("稳定主场", "实验"), entity("""["稳定主场","实验"]""").workspaceTags())
    }

    @Test
    fun `empty array parses to empty list`() {
        assertTrue(entity("[]").workspaceTags().isEmpty())
    }

    @Test
    fun `malformed json falls back to empty list`() {
        assertTrue(entity("{broken").workspaceTags().isEmpty())
    }
}
