package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingBubbleEdgeHideTest {
    private val screenWidth = 1080
    private val bubbleWidth = 216
    private val visible = 60
    private val threshold = 72

    @Test fun flushRightHidesToSliver() {
        val target = computeEdgeHideTargetX(screenWidth, bubbleWidth, 0, visible, threshold)
        assertEquals(-156, target?.first)
        assertEquals(FloatingBubbleEdge.RIGHT, target?.second)
    }

    @Test fun nearRightEdgeHides() {
        val target = computeEdgeHideTargetX(screenWidth, bubbleWidth, 50, visible, threshold)
        assertEquals(FloatingBubbleEdge.RIGHT, target?.second)
    }

    @Test fun flushLeftHidesToSliver() {
        val target = computeEdgeHideTargetX(screenWidth, bubbleWidth, 864, visible, threshold)
        assertEquals(1020, target?.first)
        assertEquals(FloatingBubbleEdge.LEFT, target?.second)
    }

    @Test fun nearLeftEdgeHides() {
        val target = computeEdgeHideTargetX(screenWidth, bubbleWidth, 800, visible, threshold)
        assertEquals(FloatingBubbleEdge.LEFT, target?.second)
    }

    @Test fun middleStaysPut() {
        assertNull(computeEdgeHideTargetX(screenWidth, bubbleWidth, 400, visible, threshold))
    }

    @Test fun restoreTargetsDockAtEdges() {
        assertEquals(0, computeEdgeRestoreTargetX(screenWidth, bubbleWidth, FloatingBubbleEdge.RIGHT))
        assertEquals(864, computeEdgeRestoreTargetX(screenWidth, bubbleWidth, FloatingBubbleEdge.LEFT))
    }
}
