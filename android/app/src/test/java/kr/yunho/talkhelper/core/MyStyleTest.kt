package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyStyleTest {
    private val chat = """
        청년부 님과 카카오톡 대화
        저장한 날짜 : 2024. 3. 6. 오전 9:00

        2024년 3월 5일 화요일
        2024. 3. 5. 오후 3:10, 김미카엘 : 신부님 모임 언제 할까요?
        2024. 3. 5. 오후 3:12, 윤호 신부 : 토요일 저녁에 보자 ㅎㅎ
        2024. 3. 5. 오후 3:12, 윤호 신부 : 사진
        2024. 3. 5. 오후 3:13, 윤호 신부 : 다들 시간 괜찮지~?
        2024. 3. 5. 오후 3:14, 이안나 : 네 좋아요
        2024. 3. 5. 오후 3:15, 김미카엘 : 넵
    """.trimIndent()

    @Test
    fun parsesKakaoExportLines() {
        assertEquals("김미카엘" to "신부님 모임 언제 할까요?", MyStyle.parse("2024. 3. 5. 오후 3:10, 김미카엘 : 신부님 모임 언제 할까요?"))
        assertEquals("김미카엘" to "넵", MyStyle.parse("2024년 3월 5일 오후 3:15, 김미카엘 : 넵"))
        assertEquals("김미카엘" to "넵", MyStyle.parse("[김미카엘] [오후 3:15] 넵"))
        assertNull(MyStyle.parse("2024년 3월 5일 화요일"))
    }

    @Test
    fun picksMyRecentTextMessages() {
        assertEquals(listOf("김미카엘", "윤호 신부", "이안나").toSet(), MyStyle.senders(chat).toSet())
        assertEquals(listOf("토요일 저녁에 보자 ㅎㅎ", "다들 시간 괜찮지~?"), MyStyle.examples(chat, "윤호 신부"))
        assertEquals(listOf("좋아요"), MyStyle.fromScreen("김미카엘\n모임 언제?\n나: 좋아요\n나: 사진"))
    }

    @Test
    fun guessesWhoIsMe() {
        assertEquals("윤호 신부", MyStyle.guessMe("윤호", listOf(listOf("김미카엘", "윤호 신부"))))
        assertEquals("윤호 신부", MyStyle.guessMe("", listOf(listOf("김미카엘", "윤호 신부"), listOf("엄마", "윤호 신부"))))
        assertNull(MyStyle.guessMe("", listOf(listOf("김미카엘", "윤호 신부"))))
    }

    @Test
    fun myStyleGoesIntoPrompt() {
        val u = Prompts.user(Mode.MINE, "알았어 고마워", null, myStyle = listOf("넵 ㅎㅎ", "내일 봬요~"))
        assertEquals(true, u.contains("<내평소말투>\n- 넵 ㅎㅎ\n- 내일 봬요~\n</내평소말투>"))
        assertEquals(false, Prompts.user(Mode.POLITE, "알았어", null).contains("내평소말투"))
        assertEquals(true, Prompts.system(Mode.MINE).contains("<내평소말투>"))
    }
}
