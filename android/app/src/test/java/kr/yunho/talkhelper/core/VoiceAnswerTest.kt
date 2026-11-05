package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAnswerTest {
    @Test
    fun understandsShortAnswers() {
        for (s in listOf("보내", "응 보내 줘", "네", "그래", "좋아요", "오케이", "전송")) assertEquals(s, VoiceAnswer.SEND, VoiceAnswer.of(s))
        for (s in listOf("다시", "다시 할게", "새로 말할래")) assertEquals(s, VoiceAnswer.AGAIN, VoiceAnswer.of(s))
        for (s in listOf("아니", "취소", "보내지 마", "안 보내", "그만")) assertEquals(s, VoiceAnswer.CANCEL, VoiceAnswer.of(s))
        for (s in listOf("", "음")) assertEquals(s, VoiceAnswer.UNKNOWN, VoiceAnswer.of(s))
    }
}
