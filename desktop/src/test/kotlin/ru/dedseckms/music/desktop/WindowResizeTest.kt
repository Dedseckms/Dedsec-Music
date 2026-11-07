package ru.dedseckms.music.desktop

import org.junit.Test
import java.awt.Rectangle
import kotlin.test.assertEquals

class WindowResizeTest {
    @Test fun topLeftResizeKeepsOppositeEdgesAndRespectsMinimums() {
        val original = Rectangle(100, 100, 1200, 800)
        assertEquals(Rectangle(340, 300, 960, 600), resizeBounds(original, 500, 500, 5, 960, 600))
        assertEquals(Rectangle(0, 50, 1300, 850), resizeBounds(original, -100, -50, 5, 960, 600))
    }
    @Test fun bottomRightResizeDoesNotMoveTheWindow() {
        assertEquals(Rectangle(100, 100, 1400, 900), resizeBounds(Rectangle(100, 100, 1200, 800), 200, 100, 10, 960, 600))
    }
}
