package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndSignalTest {
    @Test
    fun detectsClearEndWords() {
        assertTrue(EndSignal.isEnd("이거 토요일에 하자고 보내 줘"))
        assertTrue(EndSignal.isEnd("환영한다고 보내줘."))
        assertTrue(EndSignal.isEnd("내일 못 간다고 해 여기까지"))
        assertFalse(EndSignal.isEnd("이거 토요일에 하자고"))
    }

    @Test
    fun doesNotCutInTheMiddle() {
        assertFalse(EndSignal.isEnd("엄마한테 사진 보내"))
        assertFalse(EndSignal.isEnd("모임에 백 명 이상"))
        assertFalse(EndSignal.isEnd("오늘 일이 다 끝"))
        assertFalse(EndSignal.isEndSegment("엄마한테 사진 보내"))
        assertFalse(EndSignal.isEndSegment("모임에 백 명 이상"))
    }

    @Test
    fun separateShortWordEnds() {
        assertTrue(EndSignal.isEndSegment("끝"))
        assertTrue(EndSignal.isEndSegment("보내"))
        assertTrue(EndSignal.isEndSegment("전송."))
        assertTrue(EndSignal.isEndSegment("그럼 그렇게 보내 줘"))
    }

    @Test
    fun stripsOnlyPureSignals() {
        assertEquals("내일 못 간다고 해", EndSignal.strip("내일 못 간다고 해 끝"))
        assertEquals("알았어 고마워", EndSignal.strip("알았어 고마워 전송"))
        assertEquals("환영한다고 보내 줘", EndSignal.strip("환영한다고 보내 줘"))
    }
}
