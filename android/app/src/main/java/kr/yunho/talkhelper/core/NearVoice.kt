package kr.yunho.talkhelper.core

/**
 * '가까운 목소리만 듣기'. 휴대폰 가까이에서 말하는 소리는 주변 소리(TV, 옆 사람)보다 훨씬 크므로
 * 일정 크기보다 작은 부분은 조용하게 지워서 받아쓰기에 넘김.
 */
object NearVoice {
    /** 0.1초 단위 소리 크기(RMS, 16비트 기준)가 이보다 작으면 '멀리서 나는 소리'로 봄 */
    const val MIN_NEAR_RMS = 1200.0
    /** 말 앞뒤가 잘리지 않게 큰 소리 주변 0.3초는 남김 */
    const val HANGOVER = 3

    /** 주변 소음 크기 [noise] 에 맞춘 '가까운 목소리' 기준 */
    fun threshold(noise: Double) = maxOf(noise * 4, MIN_NEAR_RMS)

    /**
     * [samples] 를 [chunk] 개씩 나눈 각 구간의 크기가 [rms] 일 때, 기준보다 작은 구간(앞뒤 여유 제외)을 0 으로.
     * 결과는 Whisper 용 -1~1 float
     */
    fun gate(samples: ShortArray, count: Int, chunk: Int, rms: List<Double>, threshold: Double): FloatArray {
        val loud = BooleanArray(rms.size) { rms[it] >= threshold }
        val keep = BooleanArray(rms.size) { i -> (maxOf(0, i - HANGOVER)..minOf(rms.size - 1, i + HANGOVER)).any { loud[it] } }
        return FloatArray(count) { i ->
            val c = i / chunk
            if (c < keep.size && !keep[c]) 0f else samples[i] / 32768f
        }
    }
}
