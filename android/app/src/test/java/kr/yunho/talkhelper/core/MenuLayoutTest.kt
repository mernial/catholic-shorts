package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MenuLayoutTest {
    private fun check(p: MenuLayout.Placement, w: Float, h: Float) {
        val d = p.centers.zipWithNext { a, b -> hypot(a.first - b.first, a.second - b.second) }
        // 이웃 간격이 모두 같음
        d.forEach { assertEquals(d.first(), it, 0.5f) }
        // 서로 겹치지 않음
        assertTrue(d.first() >= p.size)
        // 모두 화면 안
        p.centers.forEach { (x, y) ->
            assertTrue(x - p.size / 2 >= -0.5f && x + p.size / 2 <= w + 0.5f)
            assertTrue(y - p.size / 2 >= -0.5f && y + p.size / 2 <= h + 0.5f)
        }
    }

    @Test
    fun evenAtRightEdgeMiddle() {
        val p = MenuLayout.place(7, 1040f, 1300f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f)
        check(p, 1080f, 2340f)
        assertEquals(234f, p.size, 0.1f)
    }

    @Test
    fun evenNearTopAndBottomAndCenter() {
        for (cy in listOf(40f, 2300f)) check(MenuLayout.place(7, 1040f, cy, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f), 1080f, 2340f)
        check(MenuLayout.place(7, 560f, 1200f, 1080f, 2340f, 516f, 234f, 24f, 12f, 150f), 1080f, 2340f)
    }

    @Test
    fun shrinksTogetherOnSmallScreen() {
        val p = MenuLayout.place(7, 700f, 400f, 720f, 900f, 516f, 234f, 24f, 12f, 120f)
        check(p, 720f, 900f)
        assertTrue(p.size < 234f)
    }
}
