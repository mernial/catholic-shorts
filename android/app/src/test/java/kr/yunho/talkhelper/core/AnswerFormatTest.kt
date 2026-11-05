package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerFormatTest {
    @Test
    fun putsEachSectionOnItsOwnLines() {
        val a = "결론: 토요일 저녁 7시로 정함\n진행: 김미카엘 토요일 제안, 이안나 일요일 요청, 토요일로 정리\n결정: 11월 9일, 저녁 7시, 성당 교육관\n근거: 11월 5일 오후 3시 김미카엘님 '그럼 토요일, 7시로 할게요'"
        val expected = """
결론
   토요일 저녁 7시로 정함

진행
   · 김미카엘 토요일 제안
   · 이안나 일요일 요청
   · 토요일로 정리

결정
   · 11월 9일
   · 저녁 7시
   · 성당 교육관

근거
   11월 5일 오후 3시 김미카엘님 '그럼 토요일, 7시로 할게요'
""".trim()
        assertEquals(expected, AnswerFormat.format(a))
    }

    @Test
    fun keepsPlainText() {
        assertEquals("결론\n   대화에서 찾지 못했습니다", AnswerFormat.format("결론: 대화에서 찾지 못했습니다"))
    }
}
