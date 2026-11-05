package kr.yunho.talkhelper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechHintsTest {
    @Test
    fun takesNamesFromScreen() {
        val screen = "신현주 테클라\n진짜네요\n오후 2:30\n나: 에이 바보 같은 말씀을 하시네요 ㅎㅎ\n장영태 이레네오\n즈카리아 위원장님, 축일 축하합니다."
        val w = SpeechHints.words(screen, "소공동체 모임")
        assertTrue("테클라" in w)
        assertTrue("신현주 테클라" in w)
        assertTrue("이레네오" in w)
        assertTrue("소공동체 모임" in w)
        assertTrue("자매님" in w)
        assertFalse(w.any { it.contains("2:30") })
        assertFalse(w.any { it.startsWith("나:") })
    }
}
