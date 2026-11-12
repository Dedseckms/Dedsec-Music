package ru.dedseckms.music.desktop

import org.junit.Test
import java.awt.Rectangle
import java.awt.Point
import kotlin.test.assertEquals

class WindowResizeTest {
    @Test fun maximizedDragRestoresThePreviousSizeUnderTheGrabPoint() {
        assertEquals(Rectangle(350, 112, 1200, 800), restoreDragBounds(Rectangle(100, 100, 1200, 800),
            Rectangle(0, 0, 1920, 1080), Point(960, 18), Point(950, 130), 38))
    }
    @Test fun restoredDragWorksOnAMonitorWithNegativeCoordinates() {
        assertEquals(Rectangle(-1850, -198, 1200, 800), restoreDragBounds(Rectangle(10, 10, 1200, 800),
            Rectangle(-1920, -200, 1920, 1080), Point(-1824, -182), Point(-1790, -180), 38))
    }
    @Test fun topLeftResizeKeepsOppositeEdgesAndRespectsMinimums() {
        val original = Rectangle(100, 100, 1200, 800)
        assertEquals(Rectangle(340, 300, 960, 600), resizeBounds(original, 500, 500, 5, 960, 600))
        assertEquals(Rectangle(0, 50, 1300, 850), resizeBounds(original, -100, -50, 5, 960, 600))
    }
    @Test fun bottomRightResizeDoesNotMoveTheWindow() {
        assertEquals(Rectangle(100, 100, 1400, 900), resizeBounds(Rectangle(100, 100, 1200, 800), 200, 100, 10, 960, 600))
    }
}
