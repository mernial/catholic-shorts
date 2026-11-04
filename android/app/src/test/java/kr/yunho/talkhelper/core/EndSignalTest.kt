package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndSignalTest {
    @Test
    fun detectsEndWords() {
        assertTrue(EndSignal.isEnd("이거 토요일에 하자고 보내 줘"))
        assertTrue(EndSignal.isEnd("환영한다고 보내줘."))
        assertTrue(EndSignal.isEnd("내일 못 간다고 해 끝"))
        assertTrue(EndSignal.isEnd("알았어 고마워 전송"))
        assertFalse(EndSignal.isEnd("이거 토요일에 하자고"))
        assertFalse(EndSignal.isEnd("내일 몇 시에"))
    }

    @Test
    fun stripsOnlyPureSignals() {
        assertEquals("내일 못 간다고 해", EndSignal.strip("내일 못 간다고 해 끝"))
        assertEquals("알았어 고마워", EndSignal.strip("알았어 고마워 전송"))
        assertEquals("환영한다고 보내 줘", EndSignal.strip("환영한다고 보내 줘"))
    }
}
