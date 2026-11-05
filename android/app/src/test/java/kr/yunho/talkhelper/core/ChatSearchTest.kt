package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    private fun bigChat(): String = buildString {
        append("청년부 단톡방 카카오톡 대화\n저장한 날짜 : 2026-11-05\n\n")
        repeat(2000) { append("2026년 10월 1일 오후 3:${it % 60}, 박베드로 : 오늘 날씨 좋네요 $it\n") }
        append("2026년 10월 28일 오전 9:03, 김미카엘 : 성가대 연습은 토요일 저녁 6시 반입니다\n")
        repeat(500) { append("2026년 11월 1일 오전 10:${it % 60}, 이수산나 : 넵 $it\n") }
    }

    @Test
    fun keywordsDropQuestionWords() {
        val k = ChatSearch.keywords("성가대 연습 날짜가 언제였지?")
        assertTrue(k.contains("성가대"))
        assertTrue(k.contains("연습"))
        assertTrue(!k.contains("언제"))
    }

    @Test
    fun excerptKeepsRelevantLineAndStaysSmall() {
        val chat = bigChat()
        val ex = ChatSearch.excerpt(chat, "성가대 연습 언제라고 했지?")
        assertTrue(ex.contains("토요일 저녁 6시 반"))
        assertTrue(ex.length <= ChatSearch.MAX_CHARS + 200)
        assertTrue(ex.length < chat.length)
    }

    @Test
    fun shortChatIsSentWhole() {
        val chat = "a\nb\nc"
        assertEquals(chat, ChatSearch.excerpt(chat, "아무거나"))
    }

    @Test
    fun roomNameFromExport() {
        assertEquals("김미카엘", ChatSearch.roomName("김미카엘 님과 카카오톡 대화\n저장한 날짜 : 2026. 11. 5."))
        assertEquals("청년부 단톡방", ChatSearch.roomName("청년부 단톡방 카카오톡 대화\n저장한 날짜"))
    }

    @Test
    fun splitAnswerTakesSearchWord() {
        val (text, word) = ChatSearch.splitAnswer("토요일 저녁 6시 반입니다.\n10월 28일 김미카엘님: '성가대 연습은 토요일 저녁 6시 반입니다'\n찾을말: 성가대 연습")
        assertEquals("성가대 연습", word)
        assertTrue(!text.contains("찾을말"))
        val (t2, w2) = ChatSearch.splitAnswer("대화에서 찾지 못했습니다.")
        assertEquals("대화에서 찾지 못했습니다.", t2)
        assertEquals(null, w2)
    }
}
