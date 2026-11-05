package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NearVoiceTest {
    @Test
    fun keepsLoudNearSpeechAndSilencesFarSound() {
        val chunk = 10
        // 구간 0~9: 멀리서 나는 작은 소리(500), 10~11: 가까운 말(5000), 12~29: 다시 작은 소리
        val levels = List(30) { if (it in 10..11) 5000.0 else 500.0 }
        val samples = ShortArray(300) { (if (it / chunk in 10..11) 5000 else 500).toShort() }
        val out = NearVoice.gate(samples, 300, chunk, levels, NearVoice.threshold(400.0))
        assertEquals(0f, out[0])                       // 멀리서 나는 소리는 지움
        assertEquals(500 / 32768f, out[7 * chunk])     // 말 바로 앞 0.3초는 남김
        assertEquals(5000 / 32768f, out[10 * chunk])   // 가까운 말은 그대로
        assertEquals(500 / 32768f, out[14 * chunk])    // 말 바로 뒤 0.3초는 남김
        assertEquals(0f, out[20 * chunk])
    }

    @Test
    fun thresholdFollowsNoise() {
        assertEquals(NearVoice.MIN_NEAR_RMS, NearVoice.threshold(100.0), 0.0)
        assertEquals(4000.0, NearVoice.threshold(1000.0), 0.0)
    }
}
