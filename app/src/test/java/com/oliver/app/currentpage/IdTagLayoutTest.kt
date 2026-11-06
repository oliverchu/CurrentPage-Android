package com.oliver.app.currentpage

import com.oliver.app.currentpage.ui.overlay.IdTagLayout
import com.oliver.app.currentpage.ui.overlay.TagRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdTagLayoutTest {

    private val screenW = 1080f
    private val screenH = 2400f

    @Test
    fun placesLabelAboveRightWhenFree() {
        val box = TagRect(100f, 500f, 400f, 600f)

        val label = IdTagLayout.findFreeRect(
            box = box,
            width = 100f,
            height = 30f,
            placed = emptyList(),
            screenWidth = screenW,
            screenHeight = screenH,
            gap = 2f,
        )

        assertNotNull(label)
        assertEquals(300f, label!!.left, 0.01f)
        assertEquals(470f, label.top, 0.01f)
        assertEquals(400f, label.right, 0.01f)
        assertEquals(500f, label.bottom, 0.01f)
    }

    @Test
    fun movesBelowWhenAboveIsOccupied() {
        val box = TagRect(100f, 500f, 400f, 600f)
        val occupied = TagRect(300f, 470f, 400f, 500f)

        val label = IdTagLayout.findFreeRect(
            box = box,
            width = 100f,
            height = 30f,
            placed = listOf(occupied),
            screenWidth = screenW,
            screenHeight = screenH,
            gap = 2f,
        )

        assertNotNull(label)
        assertEquals(600f, label!!.top, 0.01f)
        assertEquals(630f, label.bottom, 0.01f)
    }

    @Test
    fun clampsLabelIntoLeftScreenEdge() {
        val box = TagRect(0f, 500f, 50f, 600f)

        val label = IdTagLayout.findFreeRect(
            box = box,
            width = 100f,
            height = 30f,
            placed = emptyList(),
            screenWidth = screenW,
            screenHeight = screenH,
            gap = 2f,
        )

        assertNotNull(label)
        assertTrue(label!!.left >= 0f)
        assertTrue(label.right <= screenW)
    }

    @Test
    fun returnsNullWhenEveryCandidateCollides() {
        val box = TagRect(100f, 500f, 400f, 600f)
        val everything = TagRect(0f, 0f, screenW, screenH)

        val label = IdTagLayout.findFreeRect(
            box = box,
            width = 100f,
            height = 30f,
            placed = listOf(everything),
            screenWidth = screenW,
            screenHeight = screenH,
            gap = 2f,
        )

        assertNull(label)
    }

    @Test
    fun keepsOneGapBetweenLabels() {
        val box = TagRect(100f, 500f, 400f, 600f)
        val labelW = 100f
        val labelH = 30f
        val first = IdTagLayout.findFreeRect(
            box, labelW, labelH, emptyList(), screenW, screenH, gap = 4f,
        )!!
        val nearlyTouching = TagRect(
            first.left,
            first.top - labelH - 2f, // 只隔 2f，小于设定的 4f 间隙
            first.left + labelW,
            first.top - 2f,
        )

        val second = IdTagLayout.findFreeRect(
            box, labelW, labelH, listOf(first, nearlyTouching), screenW, screenH, gap = 4f,
        )

        assertNotNull(second)
        assertTrue(second!!.top >= box.bottom)
    }
}
