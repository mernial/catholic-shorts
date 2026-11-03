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
import kr.yunho.talkhelper.core.ChatLine
import kr.yunho.talkhelper.core.HelperException
import kr.yunho.talkhelper.core.Mode

/**
 * 도우미 화면. 카톡 위 버튼이나 앱의 대화방 목록에서 열림.
 *  - 이 방 카톡 요약해서 들려주기
 *  - 말투 고르기 → 말로 하기 → Claude 가 다듬기 → 확인 → 보내기
 */
class HelperActivity : Activity() {

    companion object {
        const val EXTRA_ROOM = "room"
        const val EXTRA_FROM_KAKAO = "fromKakao"
        /** 카톡 위 버튼을 눌렀을 때 그 순간 카톡 화면에 보이던 글 */
        @Volatile var pendingScreenText: String? = null
        private const val REQ_MIC = 7
    }

    private lateinit var prefs: Prefs
    private var room = ""
    private var fromKakao = false
    private var screenText: String? = null
    private var mode = Mode.POLITE

    private lateinit var summaryBox: TextView
    private lateinit var modeButtons: Map<Mode, Button>
    private lateinit var micButton: Button
    private lateinit var heardView: TextView
    private lateinit var typedInput: EditText
    private lateinit var resultBox: LinearLayout
    private lateinit var resultEdit: EditText
    private lateinit var status: TextView
    private lateinit var sendButton: Button

    // 음성 인식: 어르신이 쉬어 가며 말해도 되도록 '다 말했어요'를 누를 때까지 계속 이어서 들음
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var finishing = false
    private val heard = StringBuilder()
    private var partial = ""
    private var lastSpoken = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mode = prefs.mode
        readIntent(intent)
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
        build()
    }

    private fun readIntent(i: Intent) {
        room = i.getStringExtra(EXTRA_ROOM).orEmpty()
        fromKakao = i.getBooleanExtra(EXTRA_FROM_KAKAO, false)
        screenText = if (fromKakao) pendingScreenText else null
        pendingScreenText = null
        heard.clear(); partial = ""; lastSpoken = ""
    }

    private fun context(): List<ChatLine> = if (room.isBlank()) emptyList() else Store.lines(room).takeLast(40)

    // ───────── 화면 ─────────

    private fun build() {
        val ctx = this
        val panel = Ui.vbox(ctx, 18).apply { background = Ui.rounded(ctx, Ui.BG, 24) }

        // 머리
        val head = Ui.hbox(ctx)
        head.add(Ui.text(ctx, if (room.isBlank()) "카톡 도우미" else room, 24f, bold = true), weight = 3f)
        head.add(Ui.button(ctx, "닫기", Ui.CARD, sp = 16f) { finish() }, weight = 1f)
        panel.add(head, 0)

        // 최근 받은 카톡
        val recent = context().takeLast(4)
        if (recent.isNotEmpty()) {
            val box = Ui.vbox(ctx, 12, Ui.CARD)
            box.add(Ui.text(ctx, "최근 대화", 15f, color = Ui.MUTED), 0)
            recent.forEach { box.add(Ui.bubble(ctx, if (it.fromMe) "나" else it.sender, it.text, it.fromMe), 6) }
            panel.add(box)
        }

        // 요약
        panel.add(Ui.button(ctx, "📢  카톡 요약해서 들려주기") { summarize() })
        summaryBox = Ui.text(ctx, "", 21f).apply {
            background = Ui.rounded(ctx, Ui.CARD, 16)
            val p = Ui.dp(ctx, 14); setPadding(p, p, p, p)
            visibility = View.GONE
            setOnClickListener { Speaker.speak(ctx, text.toString()) }
        }
        panel.add(summaryBox)

        // 답장
        panel.add(Ui.text(ctx, "답장하기 — 말투를 고르고 편하게 말씀하세요", 17f, bold = true, color = Ui.MUTED), 22)
        val grid = Ui.vbox(ctx)
        val buttons = LinkedHashMap<Mode, Button>()
        Mode.values().toList().chunked(2).forEach { pair ->
            val row = Ui.hbox(ctx)
            pair.forEach { m -> buttons[m] = row.add(Ui.button(ctx, "${m.emoji} ${m.label}", Ui.CARD, sp = 18f) { selectMode(m) }) as Button }
            grid.add(row, 8)
        }
        modeButtons = buttons
        panel.add(grid, 4)
        paintModes()

        micButton = Ui.button(ctx, "🎤  눌러서 말하기", Ui.BROWN, Ui.YELLOW, sp = 24f) { toggleMic() }.apply { minHeight = Ui.dp(ctx, 88) }
        panel.add(micButton, 14)
        heardView = Ui.text(ctx, "", 19f, color = Ui.MUTED).apply { visibility = View.GONE }
        panel.add(heardView)

        typedInput = Ui.input(ctx, "말 대신 글로 쓰기 (대충 써도 됩니다)", multiline = true).apply { visibility = View.GONE }
        panel.add(typedInput)
        val typeRow = Ui.hbox(ctx)
        typeRow.add(Ui.button(ctx, "⌨️ 글로 쓰기", Ui.CARD, sp = 16f) {
            typedInput.visibility = View.VISIBLE
            typedInput.requestFocus()
        })
        typeRow.add(Ui.button(ctx, "✨ 다듬기", Ui.CARD, sp = 16f) {
            val t = typedInput.text.toString().trim()
            if (t.isNotEmpty()) rewrite(t) else typedInput.visibility = View.VISIBLE
        })
        panel.add(typeRow, 8)

        status = Ui.text(ctx, "", 17f, color = Ui.MUTED).apply { visibility = View.GONE }
        panel.add(status)

        // 다듬은 결과
        resultBox = Ui.vbox(ctx, 12, Ui.MINE).apply { visibility = View.GONE }
        resultBox.add(Ui.text(ctx, "이렇게 보낼까요? (눌러서 고칠 수 있어요)", 15f, color = Ui.MUTED), 0)
        resultEdit = Ui.input(ctx, "", multiline = true).apply { textSize = 22f * prefs.textScale }
        resultBox.add(resultEdit, 6)
        sendButton = Ui.button(ctx, "📤  보내기", Ui.BROWN, Ui.YELLOW, sp = 24f) { send() }.apply { minHeight = Ui.dp(ctx, 80) }
        resultBox.add(sendButton, 10)
        val again = Ui.hbox(ctx)
        again.add(Ui.button(ctx, "🔊 다시 듣기", Ui.CARD, sp = 16f) { Speaker.speak(ctx, resultEdit.text.toString()) })
        again.add(Ui.button(ctx, "🔄 다시 다듬기", Ui.CARD, sp = 16f) { if (lastSpoken.isNotBlank()) rewrite(lastSpoken) })
        resultBox.add(again, 8)
        panel.add(resultBox, 14)

        if (prefs.apiKey.isBlank()) {
            panel.add(Ui.text(ctx, "⚠️ 설정에서 Claude API 키를 먼저 넣어 주세요.", 17f, color = Ui.RED))
            panel.add(Ui.button(ctx, "설정 열기", Ui.CARD) { startActivity(Intent(ctx, SettingsActivity::class.java)) })
        }

        val scroll = ScrollView(ctx).apply { isFillViewport = false; addView(panel) }
        val root = FrameLayout(ctx).apply {
            setOnClickListener { finish() } // 바깥을 누르면 닫힘
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            lp.topMargin = Ui.dp(ctx, 40)
            addView(scroll, lp)
        }
        panel.setOnClickListener { } // 패널 안쪽은 닫히지 않게
        setContentView(root)
    }

    private fun selectMode(m: Mode) {
        mode = m
        prefs.mode = m
        paintModes()
        // 이미 다듬은 문장이 있으면 새 말투로 다시
        if (resultBox.visibility == View.VISIBLE && lastSpoken.isNotBlank()) rewrite(lastSpoken)
    }

    private fun paintModes() = modeButtons.forEach { (m, b) ->
        b.background = Ui.rounded(this, if (m == mode) Ui.YELLOW else Ui.CARD, 18, if (m == mode) Ui.BROWN else Ui.LINE)
    }

    private fun showStatus(s: String?, color: Int = Ui.MUTED) {
        status.text = s ?: ""
        status.setTextColor(color)
        status.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // ───────── 요약 ─────────

    private fun summarize() {
        val claude = ClaudeProvider.get(this) ?: return showStatus("설정에서 Claude API 키를 먼저 넣어 주세요.", Ui.RED)
        val lines = if (room.isBlank()) Store.allUnread() else context()
        if (lines.isEmpty() && screenText.isNullOrBlank()) {
            summaryBox.text = "아직 받은 카톡이 없습니다."
            summaryBox.visibility = View.VISIBLE
            Speaker.speak(this, summaryBox.text.toString())
            return
        }
        summaryBox.text = "읽고 있어요… 잠시만요"
        summaryBox.visibility = View.VISIBLE
        Speaker.speak(this, "잠시만 기다려 주세요.")
        Bg.run({ claude.summarize(room, lines, screenText, withRoom = room.isBlank()) }) { r ->
            if (isFinishing) return@run
            r.onSuccess {
                summaryBox.text = it
                Speaker.speak(this, it)
                if (room.isNotBlank()) Store.markRead(room) else Store.markAllRead()
            }.onFailure { summaryBox.text = errorText(it) }
        }
    }

    // ───────── 음성 입력 ─────────

    private fun toggleMic() {
        if (listening) { stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus("이 휴대폰에서 음성 인식을 쓸 수 없습니다. '글로 쓰기'를 이용해 주세요.", Ui.RED)
            return
        }
        Speaker.stop()
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
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleMic()
        else if (requestCode == REQ_MIC) showStatus("마이크를 허용해야 말로 답장할 수 있어요.", Ui.RED)
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
            heardView.text = heard.toString().trim()
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
        val claude = ClaudeProvider.get(this) ?: return showStatus("설정에서 Claude API 키를 먼저 넣어 주세요.", Ui.RED)
        lastSpoken = spoken
        showStatus("'${mode.label}' 말투로 다듬는 중… 잠시만요")
        resultBox.visibility = View.GONE
        val m = mode
        Bg.run({ claude.rewrite(m, spoken, room, context(), screenText, prefs.myName, prefs.myRole) }) { r ->
            if (isFinishing) return@run
            r.onSuccess { text ->
                showStatus(null)
                resultEdit.setText(text)
                resultBox.visibility = View.VISIBLE
                resultBox.post { (resultBox.parent?.parent as? ScrollView)?.smoothScrollTo(0, resultBox.bottom) }
                if (prefs.readBack) Speaker.speak(this, "이렇게 보낼까요? $text")
            }.onFailure { showStatus(errorText(it), Ui.RED) }
        }
    }

    // ───────── 보내기 ─────────

    private fun send() {
        val text = resultEdit.text.toString().trim()
        if (text.isEmpty()) return
        Speaker.stop()
        val app = applicationContext
        val r = room
        val record = { if (r.isNotBlank()) Store.add(ChatLine(r, "나", text, System.currentTimeMillis(), fromMe = true)) }
        val svc = KakaoAccessibilityService.instance

        if (fromKakao && svc != null) {
            // 이 화면을 닫아 카톡이 다시 앞에 오면 입력창에 넣고 전송
            finish()
            svc.typeAndSend(text, prefs.autoPressSend, { ok ->
                if (ok) { record(); toast(app, if (prefs.autoPressSend) "보냈습니다" else "입력창에 넣었어요. 전송을 눌러 주세요") }
                else if (KakaoNotificationListener.replyViaNotification(app, r, text)) { record(); toast(app, "보냈습니다") }
                else copyFallback(app, text)
            })
            return
        }
        if (r.isNotBlank() && KakaoNotificationListener.replyViaNotification(app, r, text)) {
            record(); toast(app, "보냈습니다"); finish(); return
        }
        copyFallback(app, text)
        packageManager.getLaunchIntentForPackage(KAKAO)?.let { startActivity(it) }
        finish()
    }

    private fun copyFallback(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("답장", text))
        toast(ctx, "글을 복사했어요. 카톡 입력창을 길게 눌러 '붙여넣기' 해 주세요")
        Speaker.speak(ctx, "글을 복사했어요. 카톡 입력창을 길게 눌러 붙여넣기 해 주세요.")
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    private fun errorText(t: Throwable) = (t as? HelperException)?.message ?: "문제가 생겼어요: ${t.message}"

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
