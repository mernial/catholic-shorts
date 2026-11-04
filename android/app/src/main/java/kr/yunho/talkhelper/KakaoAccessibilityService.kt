package kr.yunho.talkhelper

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.TextView

/**
 * 1) 카카오톡이 화면에 있을 때만 '도우미' 버튼을 띄움
 * 2) 버튼을 누르면 카톡 화면에 보이는 글(대화 맥락)을 모아 도우미 화면(HelperActivity)을 엶
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
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
    fun screenTexts(): List<String> {
        val root = kakaoRoot() ?: return emptyList()
        val out = ArrayList<String>()
        walk(root) { n ->
            if (!n.isEditable) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && out.lastOrNull() != t) out += t
            }
            out.size > 400
        }
        return out
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
        val apps = appWindows().mapNotNull { it.root?.packageName?.toString() }
        val show = KAKAO in apps && packageName !in apps
        if (show) showBubble() else removeBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = Ui.dp(this, 76)
        val v = TextView(this).apply {
            text = "💬\n도우미"
            gravity = Gravity.CENTER
            setTextColor(Ui.BROWN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Ui.YELLOW)
                setStroke(Ui.dp(this@KakaoAccessibilityService, 3), Ui.BROWN)
            }
            elevation = Ui.dp(this@KakaoAccessibilityService, 6).toFloat()
            contentDescription = "카톡 도우미 열기"
        }
        val dm = resources.displayMetrics
        val prefs = Prefs(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX.takeIf { it >= 0 } ?: (dm.widthPixels - size - Ui.dp(this@KakaoAccessibilityService, 12))
            y = prefs.bubbleY.takeIf { it >= 0 } ?: (dm.heightPixels * 0.55).toInt()
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dragged || Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                        dragged = true
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y } else openHelper()
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(v, lp); bubble = v; bubbleParams = lp }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun openHelper() {
        HelperActivity.pendingScreenText = screenTexts().joinToString("\n")
        val intent = Intent(this, HelperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }
}
