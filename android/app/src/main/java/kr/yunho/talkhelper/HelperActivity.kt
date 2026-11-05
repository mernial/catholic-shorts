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
import android.view.MotionEvent
import android.view.WindowManager
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
import kr.yunho.talkhelper.core.EndSignal
import kr.yunho.talkhelper.core.HelperException
import kr.yunho.talkhelper.core.Mode

/**
 * 카톡 위 '도우미' 버튼을 누르면 뜨는 화면.
 * 위쪽에서 말투 모드(정중하게 / 친구처럼 / 업무 연락)를 고름. 마지막에 고른 모드를 기억.
 * 버튼 한 번: 바로 듣기 → 말이 끝나면 Claude 가 정중하게 다듬기 → 3초 뒤 자동 전송 (그 사이 취소 가능).
 * 설정에서 자동 전송을 끄면 다듬은 문장을 확인하고 '보내기'를 눌러야 전송.
 */
class HelperActivity : Activity() {

    companion object {
        /** 버튼을 누른 순간 카톡 화면에 보이던 글 (대화 맥락) */
        @Volatile var pendingScreenText: String? = null
        /** true 면 '대화에서 찾기'로 바로 듣기 시작 (도우미 버튼을 꾹 눌러 🔎 고른 경우) */
        const val EXTRA_ASK = "ask"
        private const val REQ_MIC = 7
        private const val COUNTDOWN = 3
        private const val MAX_RETRY = 4
        /** 신호 말 없이 이만큼 조용하면 다 말한 것으로 봄 */
        private const val SILENCE_END_MS = 6000L

    }

    private lateinit var prefs: Prefs
    private var mode = Mode.POLITE
    private lateinit var modeButtons: Map<Mode, Button>
    private var screenText: String? = null

    private lateinit var micButton: Button
    private lateinit var heardView: TextView
    private lateinit var typedInput: EditText
    private lateinit var typedRow: LinearLayout
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
            sendButton.text = "📤 지금 보내기"
            countdown--
            Bg.main.postDelayed(this, 1000)
        }
    }

    // 말이 끝나면(잠시 조용해지면) 자동으로 듣기를 마침. 말을 시작하기 전 침묵은 몇 번 기다려 줌
    private var retries = 0
    private var lastHeardAt = 0L
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
        start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopRecognizer()
        start()
    }

    private fun start() {
        screenText = pendingScreenText
        pendingScreenText = null
        heard.clear(); partial = ""; lastSpoken = ""
        mode = prefs.mode   // 동그라미 메뉴에서 고른 말투
        val ask = intent?.getBooleanExtra(EXTRA_ASK, false) == true
        build()
        // 버튼을 누르자마자 바로 듣기 시작
        if (prefs.apiKey.isBlank()) showStatus("⚠️ 아직 준비가 안 됐어요. '${getString(R.string.app_name)}' 앱을 열어 설정을 마쳐 주세요.", Ui.RED)
        else {
            askMode = ask
            toggleMic() // 마이크 권한이 없으면 여기서 허용을 물음
            if (ask && listening) heardView.text = "🔎 무엇을 찾을까요? 말씀하세요 (예: 연습이 언제였지?)"
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ───────── 화면 ─────────

    /** 작은 버튼 (떠 있는 카드용) */
    private fun small(text: String, bg: Int = Ui.CARD, fg: Int = Ui.BROWN, sp: Float = 15f, onClick: (View) -> Unit): Button =
        Ui.button(this, text, bg, fg, sp, onClick).apply {
            minHeight = Ui.dp(this@HelperActivity, 44); minimumHeight = minHeight
            minWidth = 0; minimumWidth = 0
            val p = Ui.dp(this@HelperActivity, 6)
            setPadding(p * 2, p, p * 2, p)
            background = Ui.rounded(this@HelperActivity, bg, 14, if (bg == Ui.CARD) Ui.LINE else null)
        }

    /** 카톡 위에 떠 있는 작은 카드 창. 뒤의 카톡 대화가 보이고 스크롤도 됨 */
    private fun setupWindow() {
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val lp = window.attributes
        lp.gravity = Gravity.TOP
        lp.y = prefs.panelY.takeIf { it >= 0 } ?: Ui.dp(this, 70)
        window.attributes = lp
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // 카드 바깥을 누르면 뒤의 카톡이 반응 (대화를 보며 스크롤 가능)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    /** 손잡이를 끌어 카드 위치를 옮김 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View) {
        var downY = 0f; var startY = 0
        handle.setOnTouchListener { _, e ->
            val lp = window.attributes
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    val max = resources.displayMetrics.heightPixels - window.decorView.height
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, maxOf(0, max))
                    window.attributes = lp
                }
                MotionEvent.ACTION_UP -> prefs.panelY = lp.y
            }
            true
        }
    }

    private fun build() {
        val ctx = this
        val card = Ui.vbox(ctx, 12).apply {
            background = Ui.rounded(ctx, Ui.BG, 22, Ui.BROWN)
            elevation = Ui.dp(ctx, 12).toFloat()
        }

        // 손잡이 + 닫기
        val head = Ui.hbox(ctx)
        // 지금 말투와 보내는 방식만 작게 (바꾸려면 도우미 버튼을 꾹 눌러 고름)
        val handle = Ui.text(ctx, "⠿  ${mode.emoji} ${mode.label} · ${prefs.sendMode.label}", 14f, bold = true, color = Ui.MUTED).apply {
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
        }
        head.add(handle, weight = 4f)
        head.add(small("✕", sp = 16f) { finish() }, weight = 1f)
        makeDraggable(handle)
        card.add(head, 0)

        // 말투 모드 (마지막에 고른 것을 기억)
        val buttons = LinkedHashMap<Mode, Button>()
        Mode.values().toList().chunked(2).forEachIndexed { i, pair ->
            val modeRow = Ui.hbox(ctx)
            for (m in pair) buttons[m] = modeRow.add(small("${m.emoji} ${m.label}", sp = 14f) { selectMode(m) }) as Button
            modeRow.visibility = View.GONE   // 카드는 간단히: 말투는 버튼을 꾹 눌러 고름
            card.add(modeRow, if (i == 0) 6 else 4)
        }
        modeButtons = buttons
        paintModes()

        // 보내는 방식 (바로 / 3초 뒤 / 확인 후)
        val sendModeRow = Ui.hbox(ctx)
        sendModeRow.add(Ui.text(ctx, "보내기", 14f, bold = true, color = Ui.MUTED), weight = 0.8f)
        val sendButtons = LinkedHashMap<SendMode, Button>()
        for (m in SendMode.values()) sendButtons[m] = sendModeRow.add(small(m.label, sp = 14f) { selectSendMode(m) }) as Button
        sendModeButtons = sendButtons
        sendModeRow.visibility = View.GONE   // 보내는 방식은 꾹 눌러 ⚙️ 설정에서
        card.add(sendModeRow, 6)
        paintSendModes()

        // 말하기 + 들은 말
        val talkRow = Ui.hbox(ctx)
        micButton = small("", Ui.BROWN, Ui.YELLOW, sp = 16f) { if (!listening) askMode = false; toggleMic() }
        talkRow.add(micButton, weight = 2f)
        // ⌨️·🔎 는 숨김 (찾기는 버튼을 꾹 눌러 🔎)
        talkRow.add(small("⌨️", sp = 15f) {
            stopRecognizer(); resetMic()
            typedRow.visibility = View.VISIBLE
            typedInput.requestFocus()
        }, weight = 0.7f).visibility = View.GONE
        // 지금 카톡 화면에 보이는 대화에서 묻기
        talkRow.add(small("🔎", sp = 15f) {
            stopRecognizer(); resetMic()
            askMode = true
            toggleMic()
            if (listening) heardView.text = "무엇을 찾을까요? 말씀하세요 (예: 연습이 언제였지?)"
        }, weight = 0.7f).visibility = View.GONE
        card.add(talkRow, 8)
        resetMic()
        heardView = Ui.text(ctx, "끝에 \"보내 줘\"라고 하면 바로 보냅니다", 15f, color = Ui.MUTED).apply { maxLines = 3 }
        card.add(heardView, 6)

        typedRow = Ui.hbox(ctx).apply { visibility = View.GONE }
        typedInput = Ui.input(ctx, "대충 써도 됩니다", multiline = true).apply { textSize = 16f; maxLines = 3 }
        typedRow.add(typedInput, weight = 3f)
        typedRow.add(small("✨ 다듬기", sp = 14f) {
            val t = typedInput.text.toString().trim()
            if (t.isNotEmpty()) rewrite(t)
        }, weight = 1.3f)
        card.add(typedRow, 6)

        status = Ui.text(ctx, "", 15f, color = Ui.MUTED).apply { visibility = View.GONE }
        card.add(status, 4)

        // 다듬은 결과
        resultBox = Ui.vbox(ctx, 10, Ui.MINE).apply { visibility = View.GONE }
        resultTitle = Ui.text(ctx, "", 14f, bold = true, color = Ui.BROWN)
        resultBox.add(resultTitle, 0)
        resultEdit = Ui.input(ctx, "", multiline = true).apply {
            textSize = 18f * prefs.textScale
            maxLines = 4
            // 고치려고 누르면 자동 전송을 멈춤
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) { stopConfirm(); stopCountdown() }; false }
        }
        resultBox.add(resultEdit, 4)
        val sendRow = Ui.hbox(ctx)
        cancelButton = small("✋ 취소", Ui.CARD, Ui.RED, sp = 17f) { stopCountdown() }
        sendRow.add(cancelButton, weight = 1f)
        sendButton = small("📤 보내기", Ui.BROWN, Ui.YELLOW, sp = 17f) { send() }
        sendRow.add(sendButton, weight = 1.4f)
        resultBox.add(sendRow, 8)
        againRow = Ui.hbox(ctx)
        againRow.add(small("🎤 다시 말하기", sp = 14f) { stopConfirm(); stopCountdown(); resultBox.visibility = View.GONE; toggleMic() })
        againRow.add(small("🔄 다시 다듬기", sp = 14f) { stopCountdown(); if (lastSpoken.isNotBlank()) rewrite(lastSpoken) })
        resultBox.add(againRow, 6)
        card.add(resultBox, 8)

        // 대화에서 찾은 답
        answerBox = Ui.vbox(ctx, 10, Ui.THEM).apply { visibility = View.GONE }
        answerText = Ui.text(ctx, "", 18f).apply { setTextIsSelectable(true) }
        answerBox.add(answerText, 0)
        answerPhotos = Ui.vbox(ctx).apply { visibility = View.GONE }
        answerBox.add(answerPhotos, 8)
        val answerRow = Ui.hbox(ctx)
        answerRow.add(small("📍 대화에서 보기", Ui.BROWN, Ui.YELLOW, sp = 15f) { goToMessage() }, weight = 1.4f)
        answerRow.add(small("🔊 듣기", sp = 15f) { speak(answerText.text.toString()) })
        answerBox.add(answerRow, 8)
        card.add(answerBox, 8)

        // 새 버전 알림 (누르면 앱에서 업데이트)
        val updateLine = Ui.text(ctx, "🔔 새 버전이 있어요. 눌러서 업데이트", 14f, bold = true, color = Ui.RED).apply {
            visibility = View.GONE
            setPadding(0, Ui.dp(ctx, 6), 0, 0)
            setOnClickListener {
                startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        card.add(updateLine, 4)
        Bg.run({ Updater.check(ctx) }) { r -> if (r.getOrNull() != null && !isFinishing) updateLine.visibility = View.VISIBLE }

        val root = FrameLayout(ctx).apply {
            val m = Ui.dp(ctx, 10)
            setPadding(m, 0, m, 0)
            addView(ScrollView(ctx).apply { addView(card) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        setupWindow()
    }

    private fun selectMode(m: Mode) {
        mode = m
        prefs.mode = m
        paintModes()
        // 이미 다듬은 문장이 있으면 새 말투로 다시 다듬음
        if (resultBox.visibility == View.VISIBLE && lastSpoken.isNotBlank()) { stopCountdown(); rewrite(lastSpoken) }
    }

    private lateinit var sendModeButtons: Map<SendMode, Button>

    private fun selectSendMode(m: SendMode) {
        prefs.sendMode = m
        paintSendModes()
        Toast.makeText(this, m.desc, Toast.LENGTH_SHORT).show()
        // 3초 카운트다운 중에 '확인 후'로 바꾸면 멈춤
        if (m == SendMode.CONFIRM || m == SendMode.VOICE) stopCountdown()
    }

    private fun paintSendModes() = sendModeButtons.forEach { (m, b) ->
        val on = m == prefs.sendMode
        b.background = Ui.rounded(this, if (on) Ui.YELLOW else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE)
    }

    private fun paintModes() = modeButtons.forEach { (m, b) ->
        val on = m == mode
        b.background = Ui.rounded(this, if (on) Ui.YELLOW else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE)
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
        heard.clear(); partial = ""; retries = 0; lastHeardAt = 0L
        if (::answerBox.isInitialized) answerBox.visibility = View.GONE
        resultBox.visibility = View.GONE
        listening = true; finishing = false
        micButton.text = "🎙️ 듣는 중 (누르면 끝)"
        micButton.background = Ui.rounded(this, Ui.GREEN, 18)
        micButton.setTextColor(Ui.CARD)
        heardView.text = "듣고 있어요… 끝에 \"보내 줘\"라고 하세요"
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
        val spoken = EndSignal.strip(heard.toString())
        if (spoken.isBlank()) { heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요."; return }
        heardView.text = (if (askMode) "물어본 것: " else "내가 한 말: ") + spoken
        if (askMode) askChat(spoken) else rewrite(spoken)
    }

    private fun resetMic() {
        listening = false
        micButton.isEnabled = true
        micButton.text = "🎤 눌러서 말하기"
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
            if (partial.isNotBlank()) lastHeardAt = System.currentTimeMillis()
            val now = (heard.toString() + " " + partial).trim()
            heardView.text = now.ifBlank { "듣고 있어요…" }
            // "보내 줘", "끝" 이 들리면 바로 마무리
            if (listening && EndSignal.isEnd(now)) stopListening()
        }

        override fun onResults(b: Bundle?) {
            val best = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (best.isNotBlank()) { heard.append(' ').append(best); lastHeardAt = System.currentTimeMillis() }
            partial = ""
            heardView.text = heard.toString().trim().ifBlank { "듣고 있어요…" }
            when {
                finishing -> finishSpeech()
                !listening -> {}
                // 묻기는 한 문장이면 충분 → 바로 찾기
                askMode && heard.isNotBlank() -> { listening = false; finishSpeech() }
                // 신호 말이 나왔으면 마무리
                EndSignal.isEnd(heard.toString()) -> { listening = false; finishSpeech() }
                else -> keepListening()
            }
        }

        override fun onError(error: Int) {
            when {
                finishing -> finishSpeech()
                !listening -> {}
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { resetMic(); showStatus("마이크 권한이 필요해요.", Ui.RED) }
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    { resetMic(); showStatus("인터넷 연결을 확인해 주세요.", Ui.RED) }
                else -> {
                    if (partial.isNotBlank()) { heard.append(' ').append(partial); partial = "" }
                    if ((askMode && heard.isNotBlank()) || EndSignal.isEnd(heard.toString())) { listening = false; finishSpeech() }
                    else keepListening()
                }
            }
        }
    }

    /**
     * 신호 말이 나올 때까지 이어서 들음. 말을 쉬어도 끊기지 않음.
     * 한참 조용하면(신호 말을 잊었으면) 그때까지 한 말로 마무리.
     */
    private fun keepListening() {
        val spoken = heard.isNotBlank()
        when {
            spoken && System.currentTimeMillis() - lastHeardAt >= SILENCE_END_MS -> { listening = false; finishSpeech() }
            spoken -> Bg.main.postDelayed({ if (listening) listen() }, 150)
            retries++ < MAX_RETRY -> Bg.main.postDelayed({ if (listening) listen() }, 150)
            else -> { resetMic(); heardView.text = "말씀을 듣지 못했어요. 다시 눌러 주세요." }
        }
    }

    // ───────── 다듬기 ─────────

    // ───────── 대화에서 찾기 (카드 안에서 바로) ─────────

    private var askMode = false
    private lateinit var answerBox: LinearLayout
    private lateinit var answerText: TextView
    private lateinit var answerPhotos: LinearLayout
    private var answerKeyword: String? = null
    private var tts: android.speech.tts.TextToSpeech? = null

    /** 이 대화방을 미리 '내보내기'로 저장해 뒀으면 그 전체 대화 + 지금 화면 */
    private fun chatForThisRoom(): Pair<String, String?> {
        val screen = screenText.orEmpty()
        val top = screen.lines().take(8).map { it.trim() }
        val room = AskActivity.savedRooms(this).firstOrNull { name -> top.any { it == name || it.startsWith(name) } }
        val saved = room?.let { java.io.File(AskActivity.chatsDir(this), "$it.txt").readText() }.orEmpty()
        val chat = if (saved.isNotBlank()) "$saved\n\n[지금 카톡 화면]\n$screen" else screen
        return chat to room
    }

    private fun askChat(question: String) {
        val claude = ClaudeProvider.get(this) ?: return showStatus("⚠️ 아직 준비가 안 됐어요. 앱을 열어 설정을 마쳐 주세요.", Ui.RED)
        val (chat, room) = chatForThisRoom()
        if (chat.isBlank()) return showStatus("화면에서 대화를 읽지 못했어요. 카톡 대화방에서 다시 눌러 주세요.", Ui.RED)
        resultBox.visibility = View.GONE
        answerBox.visibility = View.GONE
        showStatus(if (room != null) "🔎 '$room' 대화 전체에서 찾는 중…" else "🔎 화면의 대화에서 찾는 중…")
        Bg.run({ claude.answer(question, chat, room) }) { r ->
            if (isFinishing) return@run
            askMode = false
            r.onSuccess { full ->
                val (text, word) = kr.yunho.talkhelper.core.ChatSearch.splitAnswer(full)
                showStatus(null)
                answerKeyword = word
                answerText.text = text
                // 사진을 찾은 경우: 함께 저장해 둔 사진 중 그 무렵 것을 보여 줌 (사진은 휴대폰 밖으로 안 나감)
                answerPhotos.removeAllViews()
                val at = kr.yunho.talkhelper.core.PhotoFind.photoTime(full)
                val photos = at?.let { ChatStore.photosAt(this, room, it) }.orEmpty()
                if (photos.isNotEmpty()) answerPhotos.add(PhotoStrip.view(this, photos), 0)
                else if (at != null) answerPhotos.add(Ui.text(this,
                    if (room == null) "사진은 '대화에서 찾기'로 대화를 사진까지 저장해 두면 보여 드려요." else "그 무렵 사진 파일은 저장돼 있지 않아요. 📍로 그 위치에 가서 보세요.",
                    14f, color = Ui.MUTED), 0)
                answerPhotos.visibility = if (answerPhotos.childCount > 0) View.VISIBLE else View.GONE
                answerBox.visibility = View.VISIBLE
                speak(text)
            }.onFailure { showStatus((it as? HelperException)?.message ?: "문제가 생겼어요: ${it.message}", Ui.RED) }
        }
    }

    /** 카드를 닫고, 카톡 대화방 검색으로 그 말이 있는 곳으로 이동 */
    private fun goToMessage() {
        val word = answerKeyword
        val app = applicationContext
        tts?.stop()
        finish()
        val svc = KakaoAccessibilityService.instance
        if (word.isNullOrBlank() || svc == null) {
            Toast.makeText(app, "카톡 대화방에서 직접 찾아 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        svc.searchInChat(word, { ok ->
            if (!ok) {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("찾을말", word))
                Toast.makeText(app, "대화방 위쪽 🔍를 누르고 '$word'를 붙여 넣어 찾아 주세요.", Toast.LENGTH_LONG).show()
            }
        })
    }

    /** 읽어 주기. [then] 은 다 읽은 뒤 (읽지 못하면 바로) 실행 */
    private fun speak(text: String, then: (() -> Unit)? = null) {
        if (text.isBlank()) { then?.invoke(); return }
        fun say(t: android.speech.tts.TextToSpeech) {
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == "say") Bg.main.post { then?.invoke() } }
                @Deprecated("old") override fun onError(id: String?) { Bg.main.post { then?.invoke() } }
            })
            if (t.speak(text.take(3900), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "say") != android.speech.tts.TextToSpeech.SUCCESS) then?.invoke()
        }
        val t = tts ?: android.speech.tts.TextToSpeech(applicationContext) { st ->
            val ready = tts
            if (st == android.speech.tts.TextToSpeech.SUCCESS && ready != null) {
                ready.language = java.util.Locale.KOREAN
                ready.setSpeechRate(0.9f)
                say(ready)
            } else then?.invoke()
        }.also { tts = it; return }
        say(t)
    }

    // ───────── 듣고 확인 (읽어 드리고 말로 대답) ─────────

    private var confirmRec: SpeechRecognizer? = null
    /** 읽는 도중 직접 고치거나 버튼을 누르면 이어서 듣지 않도록 */
    private var confirmId = 0

    private fun readAndConfirm(text: String) {
        stopCountdown()
        resultTitle.text = "🔊 읽어 드릴게요. 듣고 \"보내\" 또는 \"다시\"라고 말씀하세요"
        val id = ++confirmId
        speak("이렇게 보낼까요? $text") {
            if (id == confirmId && !isFinishing && resultBox.visibility == View.VISIBLE) listenConfirm(0)
        }
    }

    private fun listenConfirm(attempt: Int) {
        if (!hasMic() || !SpeechRecognizer.isRecognitionAvailable(this)) return
        stopConfirm()
        resultTitle.text = "🎤 듣고 있어요…  \"보내\" · \"다시\" · \"취소\""
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        confirmRec = rec
        rec.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(error: Int) {
                if (confirmRec !== rec) return
                stopConfirm()
                if (attempt < 1) listenConfirm(attempt + 1)
                else resultTitle.text = "말씀을 듣지 못했어요. 아래 버튼으로 골라 주세요"
            }
            override fun onResults(b: Bundle?) {
                if (confirmRec !== rec) return
                stopConfirm()
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                when (kr.yunho.talkhelper.core.VoiceAnswer.of(said)) {
                    kr.yunho.talkhelper.core.VoiceAnswer.SEND -> send()
                    kr.yunho.talkhelper.core.VoiceAnswer.AGAIN -> { resultBox.visibility = View.GONE; askMode = false; toggleMic() }
                    kr.yunho.talkhelper.core.VoiceAnswer.CANCEL -> { stopCountdown(); resultTitle.text = "보내지 않았어요. 눌러서 고치거나 버튼을 골라 주세요" }
                    kr.yunho.talkhelper.core.VoiceAnswer.UNKNOWN ->
                        if (attempt < 1) listenConfirm(attempt + 1)
                        else resultTitle.text = "\"$said\" — 잘 모르겠어요. 아래 버튼으로 골라 주세요"
                }
            }
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
    }

    private fun stopConfirm() {
        confirmId++
        tts?.stop()
        confirmRec?.let { runCatching { it.cancel(); it.destroy() } }
        confirmRec = null
    }

    private fun rewrite(spoken: String) {
        val claude = ClaudeProvider.get(this) ?: return showStatus("⚠️ 아직 준비가 안 됐어요. '${getString(R.string.app_name)}' 앱을 열어 설정을 마쳐 주세요.", Ui.RED)
        lastSpoken = spoken
        showStatus("${mode.emoji} '${mode.label}' 말투로 다듬는 중… " + if (prefs.carefulRewrite) "섬세하게 다듬느라 조금 더 걸려요" else "잠시만요")
        resultBox.visibility = View.GONE
        val m = mode
        Bg.run({
            // '내 말투': 이 방을 내보내 저장해 뒀으면 그 방에서 내가 보낸 말, 아니면 화면의 내 말풍선을 예시로
            val style = if (m == Mode.MINE) ChatStore.myStyle(this, chatForThisRoom().second).ifEmpty { kr.yunho.talkhelper.core.MyStyle.fromScreen(screenText) } else emptyList()
            claude.rewrite(m, spoken, screenText, prefs.myName, prefs.myRole, prefs.carefulRewrite, style)
                .also { claude.lastUsage?.let { u -> prefs.lastUsage = u.describe() } }
        }) { r ->
            if (isFinishing) return@run
            r.onSuccess { text ->
                showStatus(null)
                resultEdit.setText(text)
                resultEdit.clearFocus()
                resultBox.visibility = View.VISIBLE
                when (prefs.sendMode) {
                    SendMode.INSTANT -> send()
                    SendMode.COUNTDOWN -> startCountdown()
                    SendMode.CONFIRM -> stopCountdown()
                    SendMode.VOICE -> readAndConfirm(text)
                }
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
        sendButton.text = "📤 보내기"
        cancelButton.visibility = View.GONE
        againRow.visibility = View.VISIBLE
    }

    private fun send() {
        Bg.main.removeCallbacks(tick)
        stopConfirm()
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
        stopConfirm()
        tts?.shutdown(); tts = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
