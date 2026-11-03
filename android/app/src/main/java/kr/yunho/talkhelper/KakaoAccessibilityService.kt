package kr.yunho.talkhelper

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
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
 * 2) 버튼을 누르면 카톡 화면에 보이는 글을 모아 도우미 화면(HelperActivity)을 엶
 * 3) 다듬은 답장을 카톡 입력창에 넣고 전송 버튼을 누름
 * 카카오톡 이외의 앱 화면은 읽지 않음.
 */
class KakaoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KakaoAccessibilityService? = null
            private set

        private val SEND_IDS = listOf("send", "send_button", "send_btn", "btn_send", "sendButton").map { "$KAKAO:id/$it" }
        private val SEND_WORDS = listOf("전송", "보내기", "Send")
        private val TITLE_IDS = listOf("title", "toolbar_title", "chat_room_title", "chatroom_title").map { "$KAKAO:id/$it" }
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

    fun roomTitle(texts: List<String>): String {
        Store.findRoomIn(texts)?.let { return it }
        val root = kakaoRoot() ?: return ""
        for (id in TITLE_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { !it.text.isNullOrBlank() }?.let { return it.text.toString() }
        }
        return ""
    }

    private fun findEditable(root: AccessibilityNodeInfo?) = walk(root) { it.isEditable && it.isVisibleToUser }

    private fun findSend(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in SEND_IDS) root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }?.let { return it }
        return walk(root) { n ->
            val label = (n.contentDescription ?: n.text)?.toString()?.trim() ?: ""
            n.isVisibleToUser && SEND_WORDS.any { label == it }
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /**
     * 카톡 입력창에 [text] 를 넣고, 설정에 따라 전송 버튼도 누름.
     * 도우미 화면이 닫히고 카톡이 다시 앞에 올 때까지 잠깐 기다렸다가 시도함.
     */
    fun typeAndSend(text: String, pressSend: Boolean, done: (Boolean) -> Unit, attempt: Int = 0) {
        val edit = findEditable(kakaoRoot())
        if (edit == null) {
            if (attempt < 8) handler.postDelayed({ typeAndSend(text, pressSend, done, attempt + 1) }, 300) else done(false)
            return
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { done(false); return }
        if (!pressSend) { done(true); return }
        // 글자가 들어간 뒤에야 전송 버튼이 활성화되므로 잠깐 기다림
        handler.postDelayed({
            val send = findSend(kakaoRoot())
            done(send != null && click(send))
        }, 500)
    }

    // ───────── 떠 있는 버튼 ─────────

    private fun updateBubble() {
        val apps = appWindows().mapNotNull { it.root?.packageName?.toString() }
        val show = Prefs(this).bubbleEnabled && KAKAO in apps && packageName !in apps
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
        val texts = screenTexts()
        HelperActivity.pendingScreenText = texts.joinToString("\n")
        val intent = Intent(this, HelperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(HelperActivity.EXTRA_ROOM, roomTitle(texts))
            .putExtra(HelperActivity.EXTRA_FROM_KAKAO, true)
        startActivity(intent)
    }
}
