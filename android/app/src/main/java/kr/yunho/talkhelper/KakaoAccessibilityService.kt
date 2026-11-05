package kr.yunho.talkhelper

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import kr.yunho.talkhelper.core.Mode

/**
 * 1) 카카오톡이 화면에 있을 때만 '도우미' 버튼을 띄움
 * 2) 버튼을 누르면 카톡 화면에 보이는 글(대화 맥락)을 모아 도우미 화면(HelperActivity)을 엶
 *    꾹 누르면 버튼 둘레에 말투·찾기 동그라미 메뉴가 펼쳐짐
 * 3) 정중하게 다듬은 답장을 카톡 입력창에 넣고 전송 버튼을 누름
 * 카카오톡 이외의 앱 화면은 읽지 않음.
 */
class KakaoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KakaoAccessibilityService? = null
            private set

        private val SEND_IDS = listOf("send", "send_button", "send_btn", "btn_send", "sendButton", "send_button_layout", "chatroom_send").map { "$KAKAO:id/$it" }
        /** 입력창 옆에 있지만 전송이 아닌 버튼 */
        private val NOT_SEND = listOf("이모티콘", "샵", "검색", "#", "더보기", "메뉴", "첨부", "음성", "Emoticon", "Search", "More")
        // 떠 있는 버튼 색
        private val CHARCOAL_TOP = android.graphics.Color.parseColor("#3A3A40")
        private val CHARCOAL_BOTTOM = android.graphics.Color.parseColor("#17171A")
        private val GOLD = android.graphics.Color.parseColor("#C9A96E")
        /** 이만큼 꾹 누르면 동그라미 메뉴 */
        private const val LONG_PRESS_MS = 450L
        private val SEND_WORDS = listOf("전송", "보내기", "메시지 전송", "Send")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val refresh = Runnable { updateBubble() }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        diag("서비스 연결됨")
        // 예전 '고급 받아쓰기'로 받아 둔 큰 파일(약 550MB)이 남아 있으면 지움
        Thread { filesDir.listFiles { f -> f.name.startsWith("ggml-") }?.forEach { it.delete() } }.start()
        updateBubble()
    }

    // ───────── 진단 기록 (설정 화면 '도우미 버튼 상태'에 보임) ─────────

    private var lastDiag = ""
    /** 마지막으로 앞에 나온 앱 (창 목록을 못 받는 기기를 위한 보조 정보) */
    private var lastPkg = ""

    private fun diag(s: String) {
        if (s == lastDiag) return
        lastDiag = s
        val t = java.text.SimpleDateFormat("M/d HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        Prefs(this).bubbleDiag = "$t  $s"
    }

    /** 위치 초기화 등으로 버튼을 다시 그림 */
    fun redrawBubble() {
        removeBubble()
        handler.postDelayed({ updateBubble() }, 200)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            // 키보드·상단바는 '앞에 나온 앱'으로 치지 않음
            val ignore = pkg.isEmpty() || pkg == packageName || "inputmethod" in pkg || "honeyboard" in pkg || pkg == "com.android.systemui"
            if (!ignore) lastPkg = pkg
        }
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 250)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeCover()
        removeBubble()
        instance = null
        super.onDestroy()
    }

    // ───────── 카톡 화면 읽기 ─────────

    private fun appWindows() = runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } }.getOrDefault(emptyList())

    fun kakaoRoot(): AccessibilityNodeInfo? =
        appWindows().firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == KAKAO } }
            ?: rootInActiveWindow?.takeIf { it.packageName == KAKAO }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (visit(node)) return node
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, visit)?.let { return it }
        }
        return null
    }

    /** 카톡 화면에 보이는 글자들 (입력창 제외) */
    /**
     * 카톡 화면에 보이는 글. 오른쪽에 붙은 말풍선(내가 보낸 말)은 앞에 "나: " 를 붙여
     * AI 가 누가 한 말인지 헷갈리지 않게 함 (상대가 나를 부르는 '신부님' 같은 호칭을 상대에게 쓰는 일 방지)
     */
    fun screenTexts(): List<String> {
        val root = kakaoRoot() ?: return emptyList()
        return textsIn(root, bounds(root))
    }

    /** [node] 안의 글자들. 창 [win] 오른쪽에 붙은 말풍선은 "나: " 표시 */
    private fun textsIn(node: AccessibilityNodeInfo, win: Rect): List<String> {
        val w = win.width().coerceAtLeast(1)
        val out = ArrayList<String>()
        walk(node) { n ->
            if (!n.isEditable) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    val b = bounds(n)
                    val mine = b.left - win.left > w * 0.3 && win.right - b.right < w * 0.12 && b.width() < w * 0.8
                    val line = if (mine) "나: $t" else t
                    if (out.lastOrNull() != line) out += line
                }
            }
            out.size > 400
        }
        return out
    }

    // ───────── 예전 대화 모으기 (위로 넘기며) ─────────

    /** 대화 목록 (위아래로 넘길 수 있는 가장 큰 칸) */
    private fun chatList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val lists = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n -> if (n.isScrollable && n.isVisibleToUser) lists += n; false }
        return lists.maxByOrNull { bounds(it).let { b -> b.width().toLong() * b.height() } }
    }

    @Volatile private var collecting = false
    @Volatile private var stopCollect = false

    // 넘기는 동안 화면을 덮는 안내 (대화가 휙휙 넘어가는 모습을 보이지 않게)
    private var cover: android.widget.LinearLayout? = null
    private var coverText: TextView? = null

    private fun showCover(title: String = "예전 대화에서 찾고 있어요…", stopLabel: String = "그만 찾고 지금까지 본 데서 찾기") {
        if (cover != null) return
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#F21C1C1F"))
            isClickable = true   // 덮개 뒤 카톡이 눌리지 않게
            val p = Ui.dp(this@KakaoAccessibilityService, 32); setPadding(p, p, p, p)
        }
        box.addView(TextView(this).apply {
            text = "🔎"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
        })
        coverText = TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@KakaoAccessibilityService, 16), 0, Ui.dp(this@KakaoAccessibilityService, 32))
        }
        box.addView(coverText)
        box.addView(TextView(this).apply {
            text = stopLabel
            setTextColor(CHARCOAL_BOTTOM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            val p = Ui.dp(this@KakaoAccessibilityService, 14); setPadding(p * 2, p, p * 2, p)
            background = Ui.rounded(this@KakaoAccessibilityService, android.graphics.Color.WHITE, 24)
            setOnClickListener { stopCollect = true; coverText?.text = "지금까지 본 대화로 찾을게요…" }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(box, lp); cover = box }
    }

    private fun removeCover() {
        cover?.let { runCatching { wm.removeView(it) } }
        cover = null
        coverText = null
    }

    /**
     * 사용자가 '찾기'를 했을 때, 지금 대화방을 위로 넘기며 예전 대화 글을 모음.
     * [maxPages] 쪽 또는 [maxMs] 가 지나거나 대화 처음에 닿으면 멈추고, 원래 자리(맨 아래)로 돌아간 뒤 [done].
     * 모은 글은 휴대폰 안에 있고, 질문과 관련된 부분만 AI 에 보냄.
     */
    fun collectHistory(maxPages: Int = 40, maxMs: Long = 15_000, progress: (Int) -> Unit, done: (List<String>) -> Unit) {
        if (collecting) return
        collecting = true
        stopCollect = false
        showCover()
        val start = System.currentTimeMillis()
        var acc: List<String> = emptyList()
        var pages = 0
        var scrolled = 0

        fun finish() {
            // 넘긴 만큼 다시 아래로 (맨 아래 최신 대화로 돌아감)
            fun back(left: Int) {
                val list = chatList(kakaoRoot())
                if (left <= 0 || list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    collecting = false
                    removeCover()
                    done(acc)
                    return
                }
                handler.postDelayed({ back(left - 1) }, 120)
            }
            back(scrolled + 3)
        }

        fun step() {
            val root = kakaoRoot()
            val list = chatList(root)
            if (root == null || list == null) { finish(); return }
            val page = textsIn(list, bounds(root))
            val top = acc.isNotEmpty() && kr.yunho.talkhelper.core.History.reachedTop(acc, page)
            acc = kr.yunho.talkhelper.core.History.prepend(acc, page)
            pages++
            progress(pages)
            coverText?.text = "예전 대화에서 찾고 있어요…\n(${pages}쪽 살펴봄)"
            if (stopCollect) coverText?.text = "지금까지 본 대화로 찾을게요…"
            if (top || stopCollect || pages >= maxPages || System.currentTimeMillis() - start > maxMs) { finish(); return }
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { finish(); return }
            scrolled++
            handler.postDelayed({ step() }, 450)   // 예전 대화가 그려질 때까지 잠깐 기다림
        }
        step()
    }

    // ───────── 대화 내보내기 (사용자가 허락했을 때 카톡 메뉴를 대신 눌러 줌) ─────────

    /** 화면에 떠 있는 모든 창(카톡, 공유 창 등)의 뿌리 */
    private fun allRoots(): List<AccessibilityNodeInfo> =
        (runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName != packageName }

    /** [iconOnly] 면 아이콘 버튼 설명(contentDescription)만 봄 — 대화 글에 같은 낱말이 있어도 누르지 않게 */
    private fun findAny(match: (String) -> Boolean, iconOnly: Boolean = false): AccessibilityNodeInfo? {
        for (r in allRoots()) {
            walk(r) { n ->
                val l = if (iconOnly) n.contentDescription?.toString()?.trim().orEmpty() else label(n)
                n.isVisibleToUser && l.isNotEmpty() && match(l)
            }?.let { return it }
        }
        return null
    }

    /** [match] 에 맞는 것을 찾아 누름. 안 보이면 [scroll] 이면 목록을 넘겨 가며 [tries] 번 다시 봄 */
    private fun tapWhenShown(match: (String) -> Boolean, tries: Int, scroll: Boolean, iconOnly: Boolean = false, then: (Boolean) -> Unit) {
        if (stopCollect) { then(false); return }
        val n = findAny(match, iconOnly)
        val target = n?.let { clickable(it) }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed({ then(true) }, 900)
            return
        }
        if (tries <= 0) { then(false); return }
        if (scroll) {
            allRoots().forEach { r ->
                walk(r) { x -> x.isScrollable && x.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
        handler.postDelayed({ tapWhenShown(match, tries - 1, scroll, iconOnly, then) }, 450)
    }

    /**
     * 지금 카톡 대화방의 '대화 내용 내보내기 → 텍스트만 보내기'를 눌러 이 앱('대화에서 찾기')으로 보냄.
     * 카톡 화면 구성이 바뀌어 못 찾으면 [done](false, 막힌 단계)
     */
    fun exportChat(done: (Boolean, String) -> Unit) {
        if (collecting) return
        collecting = true
        stopCollect = false
        showCover("이 대화방의 대화를 내보내는 중이에요…\n내보낸 대화는 이 휴대폰 안에만 보관돼요", "그만두기")
        val appName = getString(R.string.app_name)
        fun end(ok: Boolean, step: String) {
            collecting = false
            removeCover()
            done(ok, step)
        }
        // 1) 오른쪽 위 ☰ 메뉴
        tapWhenShown({ it == "메뉴" || it.contains("서랍") || it == "Menu" }, 8, false, iconOnly = true, then = { ok1 ->
            if (!ok1) return@tapWhenShown end(false, "오른쪽 위 ☰ 메뉴")
            // 2) 서랍 아래 ⚙ 설정
            tapWhenShown({ it == "설정" || it == "채팅방 설정" || it.contains("Settings") }, 8, false, iconOnly = true, then = { ok2 ->
                if (!ok2) return@tapWhenShown end(false, "아래쪽 ⚙ 설정")
                // 3) 대화 내용 내보내기
                tapWhenShown({ it.contains("대화 내용 내보내기") || it.contains("Export") }, 10, true) { ok3 ->
                    if (!ok3) return@tapWhenShown end(false, "'대화 내용 내보내기'")
                    // 4) 텍스트만 보내기
                    tapWhenShown({ it.contains("텍스트") && it.contains("보내기") || it.contains("Send text") }, 8, false) { ok4 ->
                        if (!ok4) return@tapWhenShown end(false, "'텍스트만 보내기'")
                        // 5) 공유 창에서 이 앱('대화에서 찾기') 고르기
                        tapWhenShown({ it == "대화에서 찾기" || it == appName || it.startsWith(appName) }, 12, true) { ok5 ->
                            end(ok5, if (ok5) "" else "공유 창에서 '대화에서 찾기'")
                        }
                    }
                }
            })
        })
    }

    private fun findEditable(root: AccessibilityNodeInfo?) = walk(root) { it.isEditable && it.isVisibleToUser }

    private fun label(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    /** 누를 수 있는 가장 가까운 조상 (자기 자신 포함) */
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    /**
     * 전송 버튼 찾기.
     * 1) 알려진 버튼 id  2) '전송'/'보내기' 글자  3) 입력창과 같은 줄에서 가장 오른쪽에 있는 누를 수 있는 버튼
     */
    private fun findSend(root: AccessibilityNodeInfo?, edit: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in SEND_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }?.let { n -> clickable(n)?.let { return it } }
        }
        walk(root) { n -> n.isVisibleToUser && SEND_WORDS.any { w -> label(n) == w || label(n).startsWith("$w ") } }
            ?.let { n -> clickable(n)?.let { return it } }
        if (edit == null) return null
        val e = bounds(edit)
        val candidates = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n ->
            val l = label(n)
            val other = NOT_SEND.any { l.contains(it) }
            if (n.isClickable && n.isVisibleToUser && !n.isEditable && !other) {
                val r = bounds(n)
                val sameRow = r.centerY() in (e.top - e.height())..(e.bottom + e.height())
                if (sameRow && r.left >= e.right - 4 && r.width() < e.width()) candidates += n
            }
            false
        }
        return candidates.maxByOrNull { bounds(it).right }
    }

    /** 실패했을 때 원인을 알 수 있도록 입력창 근처의 버튼 정보를 기록 */
    private fun describeInputRow(root: AccessibilityNodeInfo?, edit: AccessibilityNodeInfo?): String {
        if (root == null) return "카톡 화면을 찾지 못함"
        val e = edit?.let { bounds(it) }
        val sb = StringBuilder()
        if (edit != null) sb.append("입력창: ${edit.viewIdResourceName} ${e}\n")
        walk(root) { n ->
            val r = bounds(n)
            val near = e == null || r.centerY() in (e.top - e.height())..(e.bottom + e.height())
            if (near && n.isVisibleToUser && (n.isClickable || label(n).isNotEmpty())) {
                sb.append("- ${n.className?.toString()?.substringAfterLast('.')} id=${n.viewIdResourceName?.substringAfter('/')} " +
                    "글=\"${label(n).take(20)}\" 누름=${n.isClickable} 위치=${r.left},${r.top}~${r.right},${r.bottom}\n")
            }
            sb.length > 3000
        }
        return sb.toString()
    }

    /**
     * 카톡 대화방의 🔍(대화 검색)을 눌러 [keyword] 를 넣고 검색 → 그 대화 위치로 이동.
     * 카톡 화면 구조에 따라 실패할 수 있음 (그때는 false)
     */
    fun searchInChat(keyword: String, done: (Boolean) -> Unit, attempt: Int = 0) {
        val root = kakaoRoot()
        val searchBtn = root?.let { r ->
            walk(r) { n -> n.isVisibleToUser && label(n).let { it == "검색" || it == "대화 검색" || it == "Search" } }
        }?.let { clickable(it) }
        if (searchBtn == null) {
            if (attempt < 8) handler.postDelayed({ searchInChat(keyword, done, attempt + 1) }, 300) else done(false)
            return
        }
        searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            // 검색칸은 화면 위쪽에 열림 → 가장 위에 있는 입력칸
            val r = kakaoRoot()
            val edits = ArrayList<AccessibilityNodeInfo>()
            walk(r) { n -> if (n.isEditable && n.isVisibleToUser) edits += n; false }
            val field = edits.minByOrNull { bounds(it).top }
            if (field == null) { done(false); return@postDelayed }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword) }
            val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && Build.VERSION.SDK_INT >= 30) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            done(ok)
        }, 700)
    }

    enum class SendResult { SENT, TYPED_ONLY, FAILED }

    /**
     * 카톡 입력창에 [text] 를 넣고, 설정에 따라 전송 버튼도 누름.
     * 도우미 화면이 닫히고 카톡이 다시 앞에 올 때까지 잠깐 기다렸다가 시도함.
     */
    fun typeAndSend(text: String, pressSend: Boolean, done: (SendResult) -> Unit, attempt: Int = 0) {
        val root = kakaoRoot()
        val edit = findEditable(root)
        if (edit == null) {
            if (attempt < 10) handler.postDelayed({ typeAndSend(text, pressSend, done, attempt + 1) }, 300)
            else { Prefs(this).lastSendLog = "입력창을 찾지 못함\n" + describeInputRow(root, null); done(SendResult.FAILED) }
            return
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Prefs(this).lastSendLog = "입력창에 글을 넣지 못함\n" + describeInputRow(root, edit)
            done(SendResult.FAILED); return
        }
        if (!pressSend) { done(SendResult.TYPED_ONLY); return }
        // 글자가 들어간 뒤에야 전송 버튼이 나타나므로 기다렸다가 몇 번 시도
        pressSendButton(0, done)
    }

    private fun pressSendButton(attempt: Int, done: (SendResult) -> Unit) {
        handler.postDelayed({
            val root = kakaoRoot()
            val edit = findEditable(root)
            val send = findSend(root, edit)
            if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                // 전송되면 입력창이 비워짐 → 확인
                handler.postDelayed({
                    val left = findEditable(kakaoRoot())?.text?.toString().orEmpty()
                    val hint = findEditable(kakaoRoot())?.hintText?.toString().orEmpty()
                    if (left.isBlank() || left == hint) { Prefs(this).lastSendLog = "성공"; done(SendResult.SENT) }
                    else { Prefs(this).lastSendLog = "전송 버튼을 눌렀지만 글이 남아 있음\n" + describeInputRow(kakaoRoot(), findEditable(kakaoRoot())); done(SendResult.TYPED_ONLY) }
                }, 600)
            } else if (attempt < 2) {
                pressSendButton(attempt + 1, done)
            } else {
                Prefs(this).lastSendLog = "전송 버튼을 찾지 못함\n" + describeInputRow(root, edit)
                done(SendResult.TYPED_ONLY)
            }
        }, 600)
    }

    // ───────── 떠 있는 버튼 ─────────

    private fun updateBubble() {
        val wins = appWindows()
        val apps = wins.mapNotNull { it.root?.packageName?.toString() }
        // 창 목록을 못 받는 기기에서는 마지막으로 앞에 나온 앱으로 판단
        val kakao = KAKAO in apps || (apps.isEmpty() && lastPkg == KAKAO)
        val show = kakao && packageName !in apps && Prefs(this).enabled
        if (show) showBubble() else removeBubble()
        diag("앱 창 ${wins.size}개 [${apps.joinToString().ifBlank { "없음" }}] · 최근 앱 ${lastPkg.ifBlank { "-" }} · 카톡 ${if (kakao) "인식" else "못 찾음"} · 버튼 ${if (bubble != null) "표시 중" else "숨김"}")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = Ui.dp(this, 62)
        // 차콜 원 + 얇은 금색 테두리 + 흰 마이크 하나 (글자 없이 간결하게)
        val v = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(CHARCOAL_TOP, CHARCOAL_BOTTOM),
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(Ui.dp(this@KakaoAccessibilityService, 2), GOLD)
            }
            elevation = Ui.dp(this@KakaoAccessibilityService, 10).toFloat()
            contentDescription = getString(R.string.app_name) + " 열기. 길게 누르면 말투와 찾기 메뉴"
        }
        // 지금 화면 크기 (폴드폰은 펼침·접힘에 따라 바뀜)
        val (sw, sh) = screenSize()
        val prefs = Prefs(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = Ui.dp(this@KakaoAccessibilityService, 12)
            // 저장된 위치가 지금 화면 밖이면(큰 화면에서 옮겨 둔 위치 등) 화면 안으로 끌어옴
            x = (prefs.bubbleX.takeIf { it >= 0 } ?: (sw - size - margin)).coerceIn(0, maxOf(0, sw - size))
            y = (prefs.bubbleY.takeIf { it >= 0 } ?: (sh * 0.55).toInt()).coerceIn(0, maxOf(0, sh - size))
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        var longPressed = false
        val slop = Ui.dp(this, 10)
        val longPress = Runnable {
            if (!dragged) {
                longPressed = true
                showMenu()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false; longPressed = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    // 메뉴가 펼쳐진 뒤에는 손가락이 지나는 동그라미를 표시 (떼면 그것을 고름)
                    if (longPressed) { hoverMenu(e.rawX, e.rawY, Math.hypot(dx.toDouble(), dy.toDouble()) > size); return@setOnTouchListener true }
                    if (dragged || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        if (!dragged) handler.removeCallbacks(longPress)
                        dragged = true
                        lp.x = (startX + dx).toInt().coerceIn(0, sw - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, sh - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        longPressed -> releaseMenu(e.rawX, e.rawY, Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > size)
                        dragged -> { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y }
                        else -> openHelper()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); true }
                else -> false
            }
        }
        runCatching { wm.addView(v, lp); bubble = v; bubbleParams = lp }
            .onFailure { diag("버튼 그리기 실패: ${it.javaClass.simpleName} ${it.message}") }
    }

    /** 지금 화면의 가로·세로 픽셀 */
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** 폴드폰을 펼치거나 접는 등 화면이 바뀌면 버튼을 새 화면 안에 다시 그림 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble != null) {
            removeBubble()
            handler.postDelayed({ updateBubble() }, 300)
        }
    }

    private fun removeBubble() {
        closeMenu()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    /** 도우미 카드 열기. [ask] 면 말투 다듬기 대신 '대화에서 찾기'로 바로 듣기 시작 */
    private fun openHelper(ask: Boolean = false) {
        closeMenu()
        HelperActivity.pendingScreenText = screenTexts().joinToString("\n")
        val intent = Intent(this, HelperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(HelperActivity.EXTRA_ASK, ask)
        startActivity(intent)
    }

    private fun openQuickSettings() {
        closeMenu()
        startActivity(Intent(this, QuickSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ───────── 꾹 누르면 버튼 둘레에 펼쳐지는 동그라미 메뉴 ─────────

    private var menu: FrameLayout? = null
    private var menuItems: List<Pair<View, () -> Unit>> = emptyList()
    private var menuHot: View? = null

    private fun oval(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(fill)
        setStroke(Ui.dp(this@KakaoAccessibilityService, 2), stroke)
    }

    private fun showMenu() {
        val b = bubble ?: return
        closeMenu()
        val prefs = Prefs(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66101012)
            setOnClickListener { closeMenu() }   // 빈 곳을 누르면 닫힘
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // 말투 4가지 + 찾기 + 설정. 지금 고른 말투는 노란색
        val entries: List<Triple<String, Boolean, () -> Unit>> =
            Mode.values().map { m -> Triple("${m.emoji}\n${m.label}", m == prefs.mode) { prefs.mode = m; openHelper() } } +
                Triple("🔎\n찾기", false) { openHelper(ask = true) } +
                Triple("⚙️\n설정", false) { openQuickSettings() }
        val size = Ui.dp(this, 78)
        val items = entries.map { (label, on, act) ->
            val item = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 0.95f)
                if (on) { setTextColor(0xFFFFFFFF.toInt()); background = oval(CHARCOAL_BOTTOM, GOLD) }
                else { setTextColor(CHARCOAL_BOTTOM); background = oval(0xFFFFFFFF.toInt(), 0xFFE4DED3.toInt()) }
                elevation = Ui.dp(this@KakaoAccessibilityService, 8).toFloat()
                contentDescription = label.replace("\n", " ")
                alpha = 0f; scaleX = 0.3f; scaleY = 0.3f
                setOnClickListener { act() }
            }
            root.addView(item, FrameLayout.LayoutParams(size, size))
            item to act
        }
        runCatching { wm.addView(root, lp); menu = root; menuItems = items }
            .onFailure { diag("메뉴 그리기 실패: ${it.javaClass.simpleName} ${it.message}"); return }
        buzz()

        // 버튼 자리에서 화면 가운데 쪽으로 부채꼴로 하나씩 펼침
        root.post {
            val o = IntArray(2).also { root.getLocationOnScreen(it) }
            val bl = IntArray(2).also { b.getLocationOnScreen(it) }
            val cx = bl[0] + b.width / 2f - o[0]
            val cy = bl[1] + b.height / 2f - o[1]
            val w = root.width; val h = root.height
            // 버튼이 오른쪽에 있으면 왼쪽으로, 왼쪽에 있으면 오른쪽으로 반원(위→아래)으로 펼침.
            // 버튼이 화면 위·아래 끝에 있어도 반원이 화면 안에 다 들어오게 중심을 옮김
            val r = Ui.dp(this, if (items.size > 6) 162 else 140)   // 동그라미가 많으면 반원을 크게
            val toward = if (cx > w / 2f) Math.PI else 0.0
            val spread = Math.PI
            val half = r + size / 2f
            val acy = if (h > half * 2) cy.coerceIn(half, h - half) else h / 2f
            items.forEachIndexed { i, (v, _) ->
                // 위에서부터 차례로 (오른쪽 버튼이면 시계 반대 방향)
                val t = i.toDouble() / (items.size - 1)
                val a = if (toward == 0.0) -spread / 2 + spread * t else Math.PI + spread / 2 - spread * t
                val x = (cx + (r * Math.cos(a)).toFloat() - size / 2f).coerceIn(0f, maxOf(0, w - size).toFloat())
                val y = (acy + (r * Math.sin(a)).toFloat() - size / 2f).coerceIn(0f, maxOf(0, h - size).toFloat())
                v.translationX = cx - size / 2f; v.translationY = cy - size / 2f
                v.animate().translationX(x).translationY(y).alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 50L).setDuration(220).start()
            }
        }
    }

    /** 메뉴가 펼쳐질 때 '드르륵' 한 번 (휴대폰 '터치 진동' 설정이 꺼져 있어도 울리도록 진동기를 직접 씀) */
    private fun buzz() = runCatching {
        val vib = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
        vib.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun itemAt(rawX: Float, rawY: Float): Pair<View, () -> Unit>? = menuItems.firstOrNull { (v, _) ->
        val l = IntArray(2).also { v.getLocationOnScreen(it) }
        rawX >= l[0] && rawX <= l[0] + v.width && rawY >= l[1] && rawY <= l[1] + v.height
    }

    /** 꾹 누른 채로 손가락을 옮기면 지나는 동그라미를 크게 */
    private fun hoverMenu(rawX: Float, rawY: Float, movedAway: Boolean) {
        // 버튼 위에 그대로 있으면(펼쳐지는 중인 동그라미가 손 밑을 지나가도) 아무것도 고르지 않음
        val hot = if (movedAway) itemAt(rawX, rawY)?.first else null
        if (hot === menuHot) return
        menuHot?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(0)?.setDuration(100)?.start()
        hot?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setStartDelay(0)?.setDuration(100)?.start()
        menuHot = hot
    }

    /**
     * 꾹 누른 자리(버튼 위)에서 손을 떼면 메뉴를 펼친 채로 두어 동그라미를 눌러 고르게 함.
     * 손가락을 동그라미까지 끌고 가서 뗐을 때만 바로 고름
     */
    private fun releaseMenu(rawX: Float, rawY: Float, movedAway: Boolean) {
        menuHot?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(0)?.setDuration(100)?.start()
        menuHot = null
        if (movedAway) itemAt(rawX, rawY)?.second?.invoke()
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        menuItems = emptyList()
        menuHot = null
    }
}
