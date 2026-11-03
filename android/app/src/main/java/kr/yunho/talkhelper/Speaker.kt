package kr.yunho.talkhelper

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 휴대폰에 들어 있는 음성 엔진으로 한국어를 읽어줌 */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var app: Context
    private val pending = ArrayList<String>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (tts == null) tts = TextToSpeech(app, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale.KOREAN
        ready = true
        pending.forEach { say(it, true) }
        pending.clear()
    }

    /** [interrupt] 가 true 면 지금 읽던 것을 멈추고 바로 읽음 */
    fun speak(ctx: Context, text: String, interrupt: Boolean = true) {
        init(ctx)
        if (!ready) { if (interrupt) pending.clear(); pending += text; return }
        if (interrupt) tts?.stop()
        say(text, true)
    }

    private fun say(text: String, queue: Boolean) {
        val t = tts ?: return
        t.setSpeechRate(Prefs(app).speechRate)
        // 엔진이 한 번에 읽을 수 있는 길이가 정해져 있어 문장 단위로 나눔
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?。…\\n])"))) {
            if (sb.length + s.length > max && sb.isNotEmpty()) { chunks += sb.toString(); sb.clear() }
            sb.append(s.take(max))
        }
        if (sb.isNotBlank()) chunks += sb.toString()
        for (c in chunks) t.speak(c, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    fun stop() { tts?.stop(); pending.clear() }
}
