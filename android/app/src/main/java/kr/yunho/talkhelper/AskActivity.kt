package kr.yunho.talkhelper

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.ChatSearch
import kr.yunho.talkhelper.core.HelperException
import java.io.File
import java.util.Locale

/**
 * 대화에서 찾기.
 *  - 카톡 '대화 내용 내보내기'에서 이 앱으로 공유한 대화를 저장해 두고
 *  - 또는 카톡 위 도우미 카드에서 넘겨준 '지금 화면의 대화'에서
 *  말이나 글로 물어보면 Claude 가 찾아 큰 글씨로 보여 주고 읽어 줌.
 */
class AskActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_SCREEN = "screenText"
        /** 카톡 위 카드에서 '내보내고 찾기'를 고른 질문 (내보낸 대화가 들어오면 이어서 답함) */
        @Volatile var pendingQuestion: Pair<String, Long>? = null
        /** 내보낸 카톡 방의 화면 이름 (저장된 대화와 짝지어 기억) */
        @Volatile var pendingTitle: String? = null
        private const val REQ_SPEECH = 11
        private const val REQ_FOLDER = 12
        private const val SCREEN = "\u0000screen"   // '지금 화면' 을 나타내는 내부 이름

        fun chatsDir(c: Context) = ChatStore.dir(c)
        fun savedRooms(c: Context): List<String> =
            chatsDir(c).listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
                .sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(".txt") }
    }

    private lateinit var body: LinearLayout
    private var screenText: String? = null
    private var selected: String? = null
    private lateinit var question: android.widget.EditText
    private lateinit var answer: TextView
    private lateinit var status: TextView
    private lateinit var photoBox: LinearLayout
    // 저장하거나 찾는 동안 화면 가운데에 크게 보이는 '진행 중' 표시
    private lateinit var busy: LinearLayout
    private lateinit var busyText: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        body = Ui.vbox(this, 18)
        busyText = Ui.text(this, "", 22f, bold = true).apply { gravity = android.view.Gravity.CENTER }
        busy = Ui.vbox(this, 28).apply {
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xEEF7F4EE.toInt())
            isClickable = true   // 진행 중에는 뒤 화면이 눌리지 않게
            visibility = View.GONE
            addView(android.widget.ProgressBar(this@AskActivity).apply {
                scaleX = 1.6f; scaleY = 1.6f
            })
            add(busyText, 28)
        }
        setContentView(android.widget.FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(ScrollView(this@AskActivity).apply { addView(body) })
            addView(busy, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) { tts?.language = Locale.KOREAN; ttsReady = true }
    }

    // 화면을 떠나면 읽기를 멈춤
    override fun onStop() {
        tts?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ───────── 받은 것 처리 ─────────

    private fun handle(i: Intent) {
        i.getStringExtra(EXTRA_SCREEN)?.takeIf { it.isNotBlank() }?.let { screenText = it; selected = SCREEN }
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val uri: Uri? = if (i.action == Intent.ACTION_SEND_MULTIPLE) {
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.firstOrNull()
            } else if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            Prefs(this).logExport("받음: ${i.action?.substringAfterLast('.')} ${i.type} 파일=${uri != null} 글=${text?.length ?: 0}자 · 기다리는 질문=${pendingQuestion != null}")
            showBusy("💬 대화를 저장하는 중이에요…\n\n내보낸 대화는 이 휴대폰 안에만 보관돼요")
            importChat {
                if (uri != null) ChatStore.importStream(this) { contentResolver.openInputStream(uri) }
                else text?.let { ChatStore.importText(this, it) }
            }
        }
        if (selected == null) selected = savedRooms(this).firstOrNull()
        render()
    }

    /** 저장은 시간이 걸릴 수 있어(사진이 많으면) 뒤에서 하고, 끝나면 그 방을 고름 */
    private fun importChat(work: () -> ChatStore.Saved?) {
        Bg.run(work) { r ->
            if (isFinishing) return@run
            hideBusy()
            val saved = r.getOrNull()
            if (saved == null) {
                Prefs(this).logExport("저장 실패: ${r.exceptionOrNull()?.let { it.javaClass.simpleName + " " + it.message } ?: "대화 글이 비어 있음"}")
                AlertDialog.Builder(this).setMessage("카톡 대화 파일만 받을 수 있어요.\n카톡 대화방 ☰ → ⚙ → '대화 내용 내보내기' → '텍스트 메시지만'으로 보내 주세요.")
                    .setPositiveButton("확인", null).show()
                return@run
            }
            Prefs(this).logExport("저장: '${saved.room}' 사진 ${saved.photos}장")
            // 이 방을 다음부터 바로 알아보도록 화면 이름과 짝지어 둠
            pendingTitle?.let { t ->
                Prefs(this).addRoomAlias(kr.yunho.talkhelper.core.RoomMatch.norm(t), saved.room)
                Prefs(this).logExport("방 이름 기억: '$t' = '${saved.room}'")
            }
            pendingTitle = null
            selected = saved.room
            val photos = if (saved.photos > 0) " 사진 ${saved.photos}장도 함께 저장했어요." else ""
            Toast.makeText(this, "'${saved.room}' 대화를 저장했어요.$photos 이제 물어보세요.", Toast.LENGTH_LONG).show()
            render()
            // 카드에서 물어본 질문이 기다리고 있으면 바로 찾아서 정리해 드림 (5분 안)
            pendingQuestion?.takeIf { System.currentTimeMillis() - it.second < 5 * 60_000 }?.let { (q, _) ->
                pendingQuestion = null
                Prefs(this).logExport("기다리던 질문으로 찾기 시작")
                question.setText(q)
                search()
                return@run
            }
            askWhoIsMe(saved.room)
        }
    }

    /** '내 말투'를 배우려면 이 대화에서 누가 '나'인지 알아야 함. 짐작이 안 되면 한 번 물어봄 */
    private fun askWhoIsMe(room: String, force: Boolean = false) {
        Bg.run({
            val chat = ChatStore.chatText(this, room).orEmpty()
            (if (force) null else ChatStore.meFor(this, room, chat)) to kr.yunho.talkhelper.core.MyStyle.senders(chat).take(8)
        }) { r ->
            val (me, senders) = r.getOrNull() ?: return@run
            if (isFinishing || me != null || senders.isEmpty()) return@run
            AlertDialog.Builder(this)
                .setTitle("이 대화에서 '나'는 누구예요?")
                .setItems(senders.toTypedArray()) { _, i ->
                    Prefs(this).setKakaoMe(room, senders[i])
                    Toast.makeText(this, "이 방에서의 내 말투를 배웠어요. 🪞 내 말투로 다듬을 수 있어요.", Toast.LENGTH_LONG).show()
                    render()
                }
                .setNegativeButton("나중에", null).show()
        }
    }

    private fun chatOf(name: String?): String? = when (name) {
        null -> null
        SCREEN -> screenText
        else -> File(chatsDir(this), "$name.txt").takeIf { it.exists() }?.readText()
    }

    // ───────── 화면 ─────────

    private fun render() {
        body.removeAllViews()
        body.add(Ui.text(this, "🔎 대화에서 찾기", 28f, bold = true), 0)
        body.add(Ui.text(this, "궁금한 것을 말하거나 쓰면, 대화에서 찾아 알려 드려요.", 17f, color = Ui.MUTED), 6)

        // 어느 대화에서 찾을지
        body.add(Ui.text(this, "어느 대화에서 찾을까요?", 18f, bold = true), 20)
        val sources = buildList {
            if (screenText != null) add(SCREEN)
            addAll(savedRooms(this@AskActivity))
        }
        if (sources.isEmpty()) {
            body.add(Ui.text(this,
                "아직 저장된 대화가 없어요.\n\n카톡 대화방 → 오른쪽 위 ☰ → ⚙ 설정 → '대화 내용 내보내기' → '텍스트만 보내기' → 앱 목록에서 '${getString(R.string.app_name)}'을 고르세요.",
                17f), 8)
        }
        for (s in sources) {
            val label = if (s == SCREEN) "📱 지금 카톡 화면의 대화" else "💬 $s"
            val on = s == selected
            body.add(Ui.button(this, label, if (on) Ui.YELLOW else Ui.CARD, sp = 18f) { selected = s; render() }
                .apply { background = Ui.rounded(this@AskActivity, if (on) Ui.YELLOW else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE) }, 8)
        }
        if (selected != null && selected != SCREEN) {
            body.add(Ui.button(this, "이 대화 지우기", Ui.CARD, Ui.RED, sp = 14f) { confirmDelete(selected!!) }, 6)
        }
        if (selected != null && selected != SCREEN) {
            val room = selected!!
            val me = Prefs(this).kakaoMe(room)
            body.add(Ui.button(this, if (me != null) "🪞 이 방에서 나: $me (바꾸기)" else "🪞 내 말투 배우기: 내 이름 고르기", Ui.CARD, sp = 14f) {
                askWhoIsMe(room, force = true)
            }, 8)
            body.add(Ui.text(this,
                if (ChatStore.hasPhotos(this, selected)) "📷 이 대화는 사진도 함께 저장돼 있어요. \"…할 때 보낸 사진 찾아 줘\"처럼 물어보세요."
                else "📷 사진도 찾으려면: 카톡 대화방 ☰ → ⚙ → '대화 내용 내보내기' → '모든 메시지 내부 저장소에 저장' 후 아래 버튼으로 그 폴더를 고르세요.",
                14f, color = Ui.MUTED), 8)
        }
        body.add(Ui.button(this, "📁 사진까지 가져오기 (내보낸 폴더 고르기)", Ui.CARD, sp = 15f) { pickFolder() }, 6)
        body.add(Ui.text(this, "대화를 새로 내보내면 같은 방은 새 내용으로 바뀝니다. 저장은 무료이고, 물어볼 때만 요금이 듭니다.", 13f, color = Ui.MUTED), 6)

        // 질문
        body.add(Ui.text(this, "무엇을 찾을까요?", 18f, bold = true), 22)
        question = Ui.input(this, "예) 성가대 연습 날짜가 언제였지?", multiline = true)
        body.add(question, 8)
        val row = Ui.hbox(this)
        row.add(Ui.button(this, "🎤 말로 묻기", Ui.BROWN, Ui.YELLOW, sp = 20f) { listen() })
        row.add(Ui.button(this, "🔎 찾기", sp = 20f) { search() })
        body.add(row, 10)

        status = Ui.text(this, "", 16f, color = Ui.MUTED).apply { visibility = View.GONE }
        body.add(status, 10)
        answer = Ui.text(this, "", 21f).apply {
            background = Ui.rounded(this@AskActivity, Ui.CARD, 18)
            val p = Ui.dp(this@AskActivity, 16); setPadding(p, p, p, p)
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        body.add(answer, 12)
        photoBox = Ui.vbox(this).apply { visibility = View.GONE }
        body.add(photoBox, 8)
        val listenRow = Ui.hbox(this).apply { visibility = View.GONE; tag = "replay" }
        listenRow.add(Ui.button(this, "🔊 다시 듣기", Ui.CARD, sp = 17f) { speak(answer.text.toString()) })
        listenRow.add(Ui.button(this, "⏹ 그만 읽기", Ui.CARD, sp = 17f) { tts?.stop() })
        body.add(listenRow, 8)
        body.add(View(this), 40)
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this).setMessage("'$name' 대화를 이 앱에서 지울까요? (카톡 대화는 지워지지 않습니다)")
            .setPositiveButton("지우기") { _, _ ->
                ChatStore.delete(this, name)
                selected = savedRooms(this).firstOrNull() ?: screenText?.let { SCREEN }
                render()
            }.setNegativeButton("취소", null).show()
    }

    // ───────── 묻기 ─────────

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "무엇을 찾을까요?")
        runCatching { startActivityForResult(i, REQ_SPEECH) }
            .onFailure { Toast.makeText(this, "이 휴대폰에서 음성 인식을 쓸 수 없어요. 글로 써 주세요.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Activity 기본 결과 받기")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val tree = data?.data ?: return
            showBusy("📁 대화와 사진을 가져오는 중이에요…\n사진이 많으면 조금 걸려요")
            importChat { ChatStore.importTree(this, tree) }
            return
        }
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        question.setText(said)
        search()
    }

    private fun pickFolder() {
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER) }
            .onFailure { Toast.makeText(this, "폴더를 고를 수 없는 휴대폰이에요.", Toast.LENGTH_LONG).show() }
    }

    private fun search() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) { Toast.makeText(this, "무엇을 찾을지 말하거나 써 주세요.", Toast.LENGTH_SHORT).show(); return }
        val chat = chatOf(selected)
        if (chat.isNullOrBlank()) { Toast.makeText(this, "먼저 찾을 대화를 골라 주세요.", Toast.LENGTH_SHORT).show(); return }
        val claude = ClaudeProvider.get(this)
        if (claude == null) { Toast.makeText(this, "먼저 앱 설정을 마쳐 주세요.", Toast.LENGTH_LONG).show(); return }
        val room = selected?.takeIf { it != SCREEN }
        showBusy("🔎 ${if (room != null) "'$room' 대화에서" else "대화에서"} 찾고 있어요…\n\n물어본 것: $q\n\n어떻게 진행되고 무엇이 정해졌는지 정리하는 중이에요")
        answer.visibility = View.GONE
        photoBox.visibility = View.GONE
        Bg.run({ claude.answer(q, chat, room) }) { r ->
            if (isFinishing) return@run
            hideBusy()
            Prefs(this).logExport(if (r.isSuccess) "찾기 완료" else "찾기 실패: ${r.exceptionOrNull()?.message}")
            val text = r.map { ChatSearch.splitAnswer(it).first }
                .getOrElse { (it as? HelperException)?.message ?: "문제가 생겼어요: ${it.message}" }
            showPhotos(room, r.getOrNull())
            answer.text = text
            answer.visibility = View.VISIBLE
            body.findViewWithTag<View>("replay")?.visibility = View.VISIBLE
            if (r.isSuccess) speak(text)
            (body.parent as? ScrollView)?.post { (body.parent as ScrollView).smoothScrollTo(0, answer.top) }
        }
    }

    /** 답에 사진 시각이 있으면 함께 저장된 사진 중 그 무렵 것을 보여 줌 */
    private fun showPhotos(room: String?, full: String?) {
        photoBox.removeAllViews()
        val at = full?.let { kr.yunho.talkhelper.core.PhotoFind.photoTime(it) } ?: return
        val files = ChatStore.photosAt(this, room, at)
        if (files.isNotEmpty()) {
            photoBox.add(Ui.text(this, "📷 그 무렵 올라온 사진 (누르면 크게)", 16f, bold = true), 0)
            photoBox.add(PhotoStrip.view(this, files), 6)
        } else {
            photoBox.add(Ui.text(this,
                if (ChatStore.hasPhotos(this, room)) "그 시각의 사진 파일은 찾지 못했어요. 카톡에서 그 날짜 대화를 직접 확인해 주세요."
                else "사진 파일은 저장돼 있지 않아요. '📁 사진까지 가져오기'로 사진도 함께 저장하면 바로 보여 드려요.",
                15f, color = Ui.MUTED), 0)
        }
        photoBox.visibility = View.VISIBLE
    }

    private fun showBusy(msg: String) {
        busyText.text = msg
        busy.visibility = View.VISIBLE
    }

    private fun hideBusy() { busy.visibility = View.GONE }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.setSpeechRate(0.9f)
        tts?.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 10), TextToSpeech.QUEUE_FLUSH, null, "ask")
    }
}
