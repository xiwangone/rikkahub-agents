package me.rerere.rikkahub.ui.pages.mediacreation

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaCreationNodeSelectionTest {
    @Test
    fun enteringCanSelectLongPressedNodeAndToolbarStartsEmpty() {
        val node = Uuid.random()
        assertEquals(setOf(node), MediaCreationNodeSelection.enter(null, node))
        assertEquals(emptySet<Uuid>(), MediaCreationNodeSelection.enter(null, null))
    }

    @Test
    fun toggleAddsAndRemovesNodesWithoutLeavingSelectionMode() {
        val first = Uuid.random()
        val second = Uuid.random()
        val selected = MediaCreationNodeSelection.enter(null, first)

        assertEquals(setOf(first, second), MediaCreationNodeSelection.toggle(selected, second))
        assertEquals(emptySet<Uuid>(), MediaCreationNodeSelection.toggle(selected, first))
        assertNull(MediaCreationNodeSelection.toggle(null, first))
    }

    @Test
    fun clearAndSelectAllKeepModeWhileExitLeavesIt() {
        val first = Uuid.random()
        val second = Uuid.random()
        val active = setOf(first)

        assertEquals(emptySet<Uuid>(), MediaCreationNodeSelection.clear(active))
        assertEquals(setOf(first, second), MediaCreationNodeSelection.selectAll(active, setOf(first, second)))
        assertNull(MediaCreationNodeSelection.selectAll(null, setOf(first, second)))
        assertNull(MediaCreationNodeSelection.exit())
    }
}
