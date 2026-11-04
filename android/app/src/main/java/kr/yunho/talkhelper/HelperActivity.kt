package kr.yunho.talkhelper

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.HelperException

/**
 * 카톡 위 '도우미' 버튼을 누르면 뜨는 화면.
 * 버튼 한 번: 바로 듣기 → 말이 끝나면 Claude 가 정중하게 다듬기 → 3초 뒤 자동 전송 (그 사이 취소 가능).
 * 설정에서 자동 전송을 끄면 다듬은 문장을 확인하고 '보내기'를 눌러야 전송.
 */
class HelperActivity : Activity() {

    companion object {
        /** 버튼을 누른 순간 카톡 화면에 보이던 글 (대화 맥락) */
        @Volatile var pendingScreenText: String? = null
        private const val REQ_MIC = 7
        private const val COUNTDOWN = 3
        private const val MAX_RETRY = 3
    }

    private lateinit var prefs: Prefs
    private var screenText: String? = null

    private lateinit var micButton: Button
    private lateinit var heardView: TextView
    private lateinit var typedInput: EditText
    private lateinit var status: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var resultEdit: EditText
    private lateinit var resultTitle: TextView
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var againRow: LinearLayout

    // 자동 전송 카운트다운
    private var countdown = 0
    private val tick = object : Runnable {
        override fun run() {
            if (countdown <= 0) { send(); return }
            resultTitle.text = "${countdown}초 뒤에 보냅니다…"
            sendButton.text = "📤  지금 보내기"
            countdown--
            Bg.main.postDelayed(this, 1000)
        }
    }

    // 말이 끝나면(잠시 조용해지면) 자동으로 듣기를 마침. 말을 시작하기 전 침묵은 몇 번 기다려 줌
    private var retries = 0
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var finishing = false
    private val heard = StringBuilder()
    private var partial = ""
    private var lastSpoken = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stopRecognizer()
        start()
    }

    private fun start() {
        screenText = pendingScreenText
        pendingScreenText = null
        heard.clear(); partial = ""; lastSpoken = ""
        build()
        // 버튼을 누르자마자 바로 듣기 시작
        if (prefs.apiKey.isBlank()) showStatus("⚠️ '카톡 도우미' 앱을 열어 Claude API 키를 먼저 넣어 주세요.", Ui.RED)
        else toggleMic() // 마이크 권한이 없으면 여기서 허용을 물음
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ───────── 화면 ─────────

    private fun build() {
        val ctx = this
        val panel = Ui.vbox(ctx, 18).apply { background = Ui.rounded(ctx, Ui.BG, 24) }

        val head = Ui.hbox(ctx)
        head.add(Ui.text(ctx, "정중한 답장 만들기", 22f, bold = true), weight = 3f)
        head.add(Ui.button(ctx, "닫기", Ui.CARD, sp = 16f) { finish() }, weight = 1f)
        panel.add(head, 0)
        panel.add(Ui.text(ctx, "편하게 말씀하세요. 예) \"이거 토요일에 하자고 해\"\n말이 끝나면 정중하게 바꿔서 보내 드려요.", 16f, color = Ui.MUTED), 6)

        micButton = Ui.button(ctx, "", sp = 24f) { toggleMic() }.apply { minHeight = Ui.dp(ctx, 96) }
        panel.add(micButton, 14)
        resetMic()
        heardView = Ui.text(ctx, "", 20f).apply { visibility = View.GONE }
        panel.add(heardView)

        typedInput = Ui.input(ctx, "말 대신 글로 쓰기 (대충 써도 됩니다)", multiline = true).apply { visibility = View.GONE }
        panel.add(typedInput)
        val typeRow = Ui.hbox(ctx)
        typeRow.add(Ui.button(ctx, "⌨️ 글로 쓰기", Ui.CARD, sp = 16f) {
            stopRecognizer(); resetMic()
            typedInput.visibility = View.VISIBLE
            typedInput.requestFocus()
        })
        typeRow.add(Ui.button(ctx, "✨ 다듬기", Ui.CARD, sp = 16f) {
            val t = typedInput.text.toString().trim()
            if (t.isNotEmpty()) rewrite(t) else typedInput.visibility = View.VISIBLE
        })
        panel.add(typeRow, 8)

        status = Ui.text(ctx, "", 18f, color = Ui.MUTED).apply { visibility = View.GONE }
        panel.add(status)

        resultBox = Ui.vbox(ctx, 12, Ui.MINE).apply { visibility = View.GONE }
        resultTitle = Ui.text(ctx, "", 17f, bold = true, color = Ui.BROWN)
        resultBox.add(resultTitle, 0)
        resultEdit = Ui.input(ctx, "", multiline = true).apply {
            textSize = 22f * prefs.textScale
            // 고치려고 누르면 자동 전송을 멈춤
            setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) stopCountdown(); false }
        }
        resultBox.add(resultEdit, 6)
        sendButton = Ui.button(ctx, "📤  보내기", Ui.BROWN, Ui.YELLOW, sp = 24f) { send() }.apply { minHeight = Ui.dp(ctx, 84) }
        resultBox.add(sendButton, 10)
        cancelButton = Ui.button(ctx, "✋  취소 (보내지 않기)", Ui.CARD, Ui.RED, sp = 20f) { stopCountdown() }.apply { minHeight = Ui.dp(ctx, 72) }
        resultBox.add(cancelButton, 8)
        againRow = Ui.hbox(ctx)
        againRow.add(Ui.button(ctx, "🎤 다시 말하기", Ui.CARD, sp = 16f) { stopCountdown(); resultBox.visibility = View.GONE; toggleMic() })
        againRow.add(Ui.button(ctx, "🔄 다시 다듬기", Ui.CARD, sp = 16f) { stopCountdown(); if (lastSpoken.isNotBlank()) rewrite(lastSpoken) })
        resultBox.add(againRow, 8)
        panel.add(resultBox, 14)

        val scroll = ScrollView(ctx).apply { addView(panel) }
        val root = FrameLayout(ctx).apply {
            setOnClickListener { finish() } // 바깥을 누르면 닫힘
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            lp.topMargin = Ui.dp(ctx, 40)
            addView(scroll, lp)
        }
        panel.setOnClickListener { } // 패널 안쪽은 닫히지 않게
        setContentView(root)
    }

    private fun showStatus(s: String?, color: Int = Ui.MUTED) {
        status.text = s ?: ""
        status.setTextColor(color)
        status.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // ───────── 음성 입력 ─────────

    private fun toggleMic() {
        if (listening) { stopListening(); return }
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus("이 휴대폰에서 음성 인식을 쓸 수 없습니다. '글로 쓰기'를 이용해 주세요.", Ui.RED)
            return
        }
        stopCountdown()
        heard.clear(); partial = ""; retries = 0
        resultBox.visibility = View.GONE
        listening = true; finishing = false
        micButton.text = "🎙️  듣고 있어요 (누르면 끝)"
        micButton.background = Ui.rounded(this, Ui.GREEN, 18)
        micButton.setTextColor(Ui.CARD)
        heardView.text = "듣고 있어요… 편하게 말씀하세요"
        heardView.visibility = View.VISIBLE
        showStatus(null)
        listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleMic()
        else showStatus("마이크를 허용해야 말로 답장할 수 있어요.", Ui.RED)
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        runCatching { r.startListening(i) }.onFailure { showStatus("음성 인식을 시작하지 못했어요.", Ui.RED); resetMic() }
    }

    private fun stopListening() {
        listening = false
        finishing = true
        micButton.text = "다듬는 중…"
        micButton.isEnabled = false
        runCatching { recognizer?.stopListening() }
        // 인식기가 결과를 주지 않는 경우를 대비
        Bg.main.postDelayed({ if (finishing) finishSpeech() }, 2500)
    }

    private fun stopRecognizer() {
        listening = false; finishing = false
        runCatching { recognizer?.cancel() }
    }

    private fun finishSpeech() {
        finishing = false
        if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
        resetMic()
        val spoken = heard.toString().trim()
        if (spoken.isBlank()) { heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요."; return }
        heardView.text = "내가 한 말: $spoken"
        rewrite(spoken)
    }

    private fun resetMic() {
        listening = false
        micButton.isEnabled = true
        micButton.text = "🎤  눌러서 말하기"
        micButton.background = Ui.rounded(this, Ui.BROWN, 18)
        micButton.setTextColor(Ui.YELLOW)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            heardView.text = (heard.toString() + " " + partial).trim().ifBlank { "듣고 있어요…" }
        }

        override fun onResults(b: Bundle?) {
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) heard.append(' ').append(best)
            partial = ""
            heardView.text = heard.toString().trim().ifBlank { "듣고 있어요…" }
            when {
                finishing -> finishSpeech()
                !listening -> {}
                // 말이 끝났으면 바로 다듬기로
                heard.isNotBlank() -> { listening = false; finishSpeech() }
                // 아직 아무 말도 없었으면 조금 더 기다림
                retries++ < MAX_RETRY -> listen()
                else -> { resetMic(); heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요." }
            }
        }

        override fun onError(error: Int) {
            when {
                finishing -> finishSpeech()
                !listening -> {}
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { resetMic(); showStatus("마이크 권한이 필요해요.", Ui.RED) }
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    { resetMic(); showStatus("인터넷 연결을 확인해 주세요.", Ui.RED) }
                // 말한 내용이 있으면 끝난 것으로 보고 다듬기
                heard.isNotBlank() || partial.isNotBlank() -> { listening = false; finishSpeech() }
                // 아직 말을 시작하지 않았으면 조금 더 기다림
                retries++ < MAX_RETRY -> Bg.main.postDelayed({ if (listening) listen() }, 200)
                else -> { resetMic(); heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요." }
            }
        }
    }

    // ───────── 다듬기 ─────────

    private fun rewrite(spoken: String) {
        val claude = ClaudeProvider.get(this) ?: return showStatus("'카톡 도우미' 앱에서 Claude API 키를 먼저 넣어 주세요.", Ui.RED)
        lastSpoken = spoken
        showStatus("정중하게 다듬는 중… 잠시만요")
        resultBox.visibility = View.GONE
        Bg.run({ claude.rewrite(spoken, screenText, prefs.myName, prefs.myRole) }) { r ->
            if (isFinishing) return@run
            r.onSuccess { text ->
                showStatus(null)
                resultEdit.setText(text)
                resultEdit.clearFocus()
                resultBox.visibility = View.VISIBLE
                if (prefs.autoSend) startCountdown() else stopCountdown()
                resultBox.post { (resultBox.parent?.parent as? ScrollView)?.smoothScrollTo(0, resultBox.bottom) }
            }.onFailure { showStatus((it as? HelperException)?.message ?: "문제가 생겼어요: ${it.message}", Ui.RED) }
        }
    }

    // ───────── 보내기 ─────────

    private fun startCountdown() {
        cancelButton.visibility = View.VISIBLE
        againRow.visibility = View.GONE
        countdown = COUNTDOWN
        Bg.main.removeCallbacks(tick)
        tick.run()
    }

    /** 자동 전송을 멈추고 직접 확인하는 화면으로 */
    private fun stopCountdown() {
        Bg.main.removeCallbacks(tick)
        countdown = 0
        if (!::resultTitle.isInitialized) return
        resultTitle.text = "이렇게 보낼까요? (눌러서 고칠 수 있어요)"
        sendButton.text = "📤  보내기"
        cancelButton.visibility = View.GONE
        againRow.visibility = View.VISIBLE
    }

    private fun send() {
        Bg.main.removeCallbacks(tick)
        val text = resultEdit.text.toString().trim()
        if (text.isEmpty()) return
        val app = applicationContext
        val pressSend = prefs.autoPressSend
        val svc = KakaoAccessibilityService.instance
        finish() // 이 화면이 닫혀 카톡이 다시 앞에 오면 입력창에 넣음
        if (svc == null) { copyFallback(app, text); return }
        svc.typeAndSend(text, pressSend, { r ->
            when (r) {
                KakaoAccessibilityService.SendResult.SENT -> toast(app, "보냈습니다")
                KakaoAccessibilityService.SendResult.TYPED_ONLY ->
                    toast(app, if (pressSend) "입력창에 넣었어요. 오른쪽 전송 버튼을 눌러 주세요" else "입력창에 넣었어요. 전송을 눌러 주세요")
                KakaoAccessibilityService.SendResult.FAILED -> copyFallback(app, text)
            }
        })
    }

    private fun copyFallback(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("답장", text))
        toast(ctx, "글을 복사했어요. 카톡 입력창을 길게 눌러 '붙여넣기' 해 주세요")
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        Bg.main.removeCallbacks(tick)
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
