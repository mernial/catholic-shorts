package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class PhotoFindTest {
    @Test
    fun readsPhotoTimeFromAnswer() {
        val a = "3월 5일 오후 3시 12분에 김미카엘님이 성가대 연습 사진을 올렸어요.\n사진시각: 2024-03-05 15:12\n찾을말: 성가대"
        assertEquals(LocalDateTime.of(2024, 3, 5, 15, 12), PhotoFind.photoTime(a))
        val (text, word) = ChatSearch.splitAnswer(a)
        assertEquals("3월 5일 오후 3시 12분에 김미카엘님이 성가대 연습 사진을 올렸어요.", text)
        assertEquals("성가대", word)
        assertNull(PhotoFind.photoTime("대화에서 찾지 못했습니다."))
    }

    @Test
    fun readsTimeFromFileNames() {
        assertEquals(LocalDateTime.of(2024, 3, 5, 15, 12, 3), PhotoFind.nameTime("KakaoTalk_20240305_151203123.jpg"))
        assertEquals(LocalDateTime.of(2024, 3, 5, 15, 12, 0), PhotoFind.nameTime("2024-03-05 15.12.jpg"))
        assertNull(PhotoFind.nameTime("photo.jpg"))
        assertEquals(true, PhotoFind.isImage("a.JPG"))
        assertEquals(false, PhotoFind.isImage("a.txt"))
    }

    @Test
    fun picksNearestPhotos() {
        val at = LocalDateTime.of(2024, 3, 5, 15, 12)
        val files = listOf(
            "far" to at.plusMinutes(30),
            "near" to at.plusSeconds(40),
            "before" to at.minusMinutes(2),
        )
        assertEquals(listOf("near", "before"), PhotoFind.pick(files, at))
    }
}
