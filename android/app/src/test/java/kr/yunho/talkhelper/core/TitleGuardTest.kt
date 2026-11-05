package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleGuardTest {
    @Test
    fun dropsTitleTheUserDidNotSay() {
        assertEquals("내일은 못 가요.", TitleGuard.fix("신부님, 내일은 못 가요.", "내일 못 간다 그래"))
        assertEquals("알겠어요. 고마워요.", TitleGuard.fix("알겠어요. 고마워요, 신부님.", "알았어 고마워"))
        assertEquals("네. 내일 봐요.", TitleGuard.fix("네. 신부님 내일 봐요.", "그래 내일 봐"))
    }

    @Test
    fun restoresTitleTheUserSaid() {
        assertEquals("자매님, 내일은 못 가요.", TitleGuard.fix("신부님, 내일은 못 가요.", "자매님 내일 못 간다고 해"))
        assertEquals("내일 봐요, 자매님.", TitleGuard.fix("내일 봐요, 신부님.", "자매님한테 내일 보자고 해"))
    }

    @Test
    fun keepsTitleTheUserSaid() {
        assertEquals("신부님, 내일 봬요.", TitleGuard.fix("신부님, 내일 봬요.", "신부님께 내일 뵙자고 해"))
        assertEquals("자매님, 고마워요.", TitleGuard.fix("자매님, 고마워요.", "자매님 고마워"))
        assertEquals("오늘 미사 잘 다녀왔어요.", TitleGuard.fix("오늘 미사 잘 다녀왔어요.", "미사 잘 다녀왔다고 해"))
    }
}
