package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMatchTest {
    private val saved = listOf("신현주 테클라", "청년부", "가족방")

    @Test
    fun matchesDespiteSpacingAndCounts() {
        assertEquals("신현주 테클라", RoomMatch.find("신현주테클라", saved, emptyMap()))
        assertEquals("청년부", RoomMatch.find("청년부 12", saved, emptyMap()))
        assertEquals("가족방", RoomMatch.find("우리 가족방", saved, emptyMap()))
        assertNull(RoomMatch.find("성가대", saved, emptyMap()))
        assertNull(RoomMatch.find(null, saved, emptyMap()))
    }

    @Test
    fun remembersExportedRoom() {
        val aliases = mapOf(RoomMatch.norm("💕 우리 엄마") to "내보낸 대화")
        assertEquals("내보낸 대화", RoomMatch.find("💕 우리 엄마", saved + "내보낸 대화", aliases))
    }

    @Test
    fun recognizesRoomByMessagesOnScreen() {
        val chats = mapOf(
            "청년부" to "2024. 3. 5. 오후 3:10, 김미카엘 : 모임 토요일 저녁에 할까요?\n2024. 3. 5. 오후 3:12, 윤호 : 좋아요 토요일로 해요",
            "가족방" to "2024. 3. 5. 오후 3:10, 엄마 : 밥 잘 챙겨 먹어라\n2024. 3. 5. 오후 3:12, 윤호 : 네 엄마 잘 먹고 있어요",
        )
        val screen = listOf("청년부 12", "김미카엘", "모임 토요일 저녁에 할까요?", "오후 3:10", "나: 좋아요 토요일로 해요", "새로 온 메시지입니다")
        assertEquals("청년부", RoomMatch.byContent(screen, chats))
        assertNull(RoomMatch.byContent(listOf("전혀 다른 대화 내용이에요"), chats))
    }
}
