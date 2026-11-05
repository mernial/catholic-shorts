package kr.yunho.talkhelper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kr.yunho.talkhelper.core.NearVoice
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 고급 받아쓰기용 녹음. 말이 끝나고 [silenceEndMs] 동안 조용하면 자동으로 멈춤.
 * 결과는 Whisper 가 쓰는 16kHz 모노 float 음성.
 */
class SilenceRecorder(
    private val silenceEndMs: Long = 3000,
    private val noSpeechMs: Long = 10_000,
    private val maxMs: Long = 60_000,
    /** 가까운 목소리만 듣기: 작은 소리(TV·옆 사람)는 말 시작으로 치지 않고, 받아쓰기 전에 지움 */
    private val nearOnly: Boolean = false,
) {
    @Volatile private var stopRequested = false

    fun stop() { stopRequested = true }

    /**
     * [onTick]: (지난 초, 말하는 중인지) — 메인 스레드
     * [onDone]: 녹음된 음성, 말을 못 들었으면 null — 메인 스레드
     */
    @SuppressLint("MissingPermission") // 호출 전에 마이크 권한을 확인함
    fun start(onTick: (Int, Boolean) -> Unit, onDone: (FloatArray?) -> Unit) {
        thread(name = "whisper-rec") {
            val rate = 16_000
            val samples = ShortArray((rate * maxMs / 1000).toInt())
            var count = 0
            var speechStarted = false
            val rec = try {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                Bg.main.post { onDone(null) }
                return@thread
            }
            // 휴대폰이 지원하면 주변 잡음 줄이기를 켬
            val ns = if (nearOnly && android.media.audiofx.NoiseSuppressor.isAvailable())
                runCatching { android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val levels = ArrayList<Double>()
            var threshold = 0.0
            try {
                rec.startRecording()
                val chunk = ShortArray(rate / 10) // 0.1초
                val start = System.currentTimeMillis()
                var lastVoice = start
                var noise = 0.0
                var chunks = 0
                var lastTick = -1
                while (!stopRequested) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    val room = samples.size - count
                    System.arraycopy(chunk, 0, samples, count, minOf(n, room))
                    count += minOf(n, room)

                    var sum = 0.0
                    for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
                    val rms = sqrt(sum / n)
                    levels += rms
                    chunks++
                    // 처음 0.5초로 주변 소음 크기를 잼
                    if (chunks <= 5) { noise += rms / 5; continue }
                    threshold = if (nearOnly) NearVoice.threshold(noise) else maxOf(noise * 3, 400.0)
                    val now = System.currentTimeMillis()
                    if (rms > threshold) { speechStarted = true; lastVoice = now }

                    val secs = ((now - start) / 1000).toInt()
                    if (secs != lastTick) { lastTick = secs; val s = speechStarted; Bg.main.post { onTick(secs, s) } }

                    if (speechStarted && now - lastVoice > silenceEndMs) break
                    if (!speechStarted && now - start > noSpeechMs) break
                    if (count >= samples.size) break
                }
            } finally {
                runCatching { rec.stop() }
                ns?.release()
                rec.release()
            }
            // 직접 '끝' 을 눌렀으면 소리가 작았어도 1초 이상 녹음된 것은 받아씀
            val usable = speechStarted || (stopRequested && count > rate)
            val result = when {
                !usable -> null
                nearOnly && speechStarted -> NearVoice.gate(samples, count, rate / 10, levels, threshold)
                else -> FloatArray(count) { samples[it] / 32768f }
            }
            Bg.main.post { onDone(result) }
        }
    }
}
