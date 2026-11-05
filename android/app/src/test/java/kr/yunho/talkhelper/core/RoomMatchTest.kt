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
}
