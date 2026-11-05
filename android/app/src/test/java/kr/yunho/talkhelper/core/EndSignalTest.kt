package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndSignalTest {
    @Test
    fun endsOnlyOnQuotedSend() {
        assertTrue(EndSignal.isEnd("토요일에 하자고 보내 줘"))
        assertTrue(EndSignal.isEnd("내일 못 간다고 보내"))
        assertTrue(EndSignal.isEnd("고맙다고 보내줘."))
        assertTrue(EndSignal.isEnd("수고하셨습니다 라고 보내 주세요"))
        assertTrue(EndSignal.isEnd("언제 오냐고 전송해"))
    }

    @Test
    fun doesNotCutInTheMiddle() {
        assertFalse(EndSignal.isEnd("내일까지 자료 보내 줘"))
        assertFalse(EndSignal.isEnd("엄마한테 사진 보내"))
        assertFalse(EndSignal.isEnd("모임에 백 명 이상"))
        assertFalse(EndSignal.isEnd("오늘 일이 다 끝"))
        assertFalse(EndSignal.isEnd("보내"))
        assertFalse(EndSignal.isEnd("토요일에 하자고 보내 준 사진 잘 받았어요"))
    }

    @Test
    fun stripsOnlyPureSignals() {
        assertEquals("알았어 고마워", EndSignal.strip("알았어 고마워 전송"))
        assertEquals("환영한다고 보내 줘", EndSignal.strip("환영한다고 보내 줘"))
    }
}
