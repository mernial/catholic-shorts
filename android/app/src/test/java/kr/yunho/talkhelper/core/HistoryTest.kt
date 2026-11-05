package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test
    fun joinsOverlappingPages() {
        val now = listOf("다", "라", "마", "바")
        val older = listOf("가", "나", "다", "라")
        assertEquals(listOf("가", "나", "다", "라", "마", "바"), History.prepend(now, older))
        assertFalse(History.reachedTop(now, older))
    }

    @Test
    fun detectsTopOfChat() {
        val now = listOf("가", "나", "다")
        assertTrue(History.reachedTop(now, listOf("가", "나")))
        assertEquals(now, History.prepend(now, emptyList()))
    }

    @Test
    fun keepsPagesWithoutOverlap() {
        assertEquals(listOf("가", "나", "다", "라"), History.prepend(listOf("다", "라"), listOf("가", "나")))
    }
}
