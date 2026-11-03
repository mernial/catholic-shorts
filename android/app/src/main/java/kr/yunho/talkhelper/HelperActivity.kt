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
 * 말하기 → Claude 가 정중하게 다듬기 → 확인 → 카톡 입력창에 넣고 전송.
 */
class HelperActivity : Activity() {

    companion object {
        /** 버튼을 누른 순간 카톡 화면에 보이던 글 (대화 맥락) */
        @Volatile var pendingScreenText: String? = null
        private const val REQ_MIC = 7
    }

    private lateinit var prefs: Prefs
    private var screenText: String? = null

    private lateinit var micButton: Button
    private lateinit var heardView: TextView
    private lateinit var typedInput: EditText
    private lateinit var status: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var resultEdit: EditText

    // 쉬어 가며 말해도 되도록 '다 말했어요'를 누를 때까지 이어서 들음
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
        if (prefs.apiKey.isNotBlank() && hasMic()) toggleMic()
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
        panel.add(Ui.text(ctx, "편하게 말씀하세요. 예) \"이거 토요일에 하자고 해\"", 16f, color = Ui.MUTED), 6)

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
        resultBox.add(Ui.text(ctx, "이렇게 보낼까요? (눌러서 고칠 수 있어요)", 15f, color = Ui.MUTED), 0)
        resultEdit = Ui.input(ctx, "", multiline = true).apply { textSize = 22f * prefs.textScale }
        resultBox.add(resultEdit, 6)
        resultBox.add(Ui.button(ctx, "📤  보내기", Ui.BROWN, Ui.YELLOW, sp = 24f) { send() }.apply { minHeight = Ui.dp(ctx, 84) }, 10)
        val again = Ui.hbox(ctx)
        again.add(Ui.button(ctx, "🎤 다시 말하기", Ui.CARD, sp = 16f) { resultBox.visibility = View.GONE; toggleMic() })
        again.add(Ui.button(ctx, "🔄 다시 다듬기", Ui.CARD, sp = 16f) { if (lastSpoken.isNotBlank()) rewrite(lastSpoken) })
        resultBox.add(again, 8)
        panel.add(resultBox, 14)

        if (prefs.apiKey.isBlank()) {
            panel.add(Ui.text(ctx, "⚠️ '카톡 도우미' 앱을 열어 Claude API 키를 먼저 넣어 주세요.", 17f, color = Ui.RED))
        }

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
        heard.clear(); partial = ""
        resultBox.visibility = View.GONE
        listening = true; finishing = false
        micButton.text = "✅  다 말했어요"
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
            if (finishing) finishSpeech() else if (listening) listen()
        }

        override fun onError(error: Int) {
            when {
                finishing -> finishSpeech()
                !listening -> {}
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { resetMic(); showStatus("마이크 권한이 필요해요.", Ui.RED) }
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    { resetMic(); showStatus("인터넷 연결을 확인해 주세요.", Ui.RED) }
                // 말이 잠시 끊긴 것 → 계속 듣기
                else -> Bg.main.postDelayed({ if (listening) listen() }, 200)
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
                resultBox.visibility = View.VISIBLE
                resultBox.post { (resultBox.parent?.parent as? ScrollView)?.smoothScrollTo(0, resultBox.bottom) }
            }.onFailure { showStatus((it as? HelperException)?.message ?: "문제가 생겼어요: ${it.message}", Ui.RED) }
        }
    }

    // ───────── 보내기 ─────────

    private fun send() {
        val text = resultEdit.text.toString().trim()
        if (text.isEmpty()) return
        val app = applicationContext
        val pressSend = prefs.autoPressSend
        val svc = KakaoAccessibilityService.instance
        finish() // 이 화면이 닫혀 카톡이 다시 앞에 오면 입력창에 넣음
        if (svc == null) { copyFallback(app, text); return }
        svc.typeAndSend(text, pressSend, { ok ->
            when {
                !ok -> copyFallback(app, text)
                pressSend -> toast(app, "보냈습니다")
                else -> toast(app, "입력창에 넣었어요. 전송을 눌러 주세요")
            }
        })
    }

    private fun copyFallback(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("답장", text))
        toast(ctx, "글을 복사했어요. 카톡 입력창을 길게 눌러 '붙여넣기' 해 주세요")
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
