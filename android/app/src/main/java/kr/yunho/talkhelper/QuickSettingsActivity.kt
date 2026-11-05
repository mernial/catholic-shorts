package kr.yunho.talkhelper

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add

/**
 * 카톡 위에서 바로 여는 간단한 설정 (도우미 버튼을 꾹 눌러 ⚙️).
 *  - 다듬은 문장 보내는 방식: 바로 / 3초 뒤 / 확인 후
 *  - 다듬기 AI: 기본(빠르고 저렴) / 섬세하게(조금 비쌈)
 * 누르는 즉시 저장됨.
 */
class QuickSettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var sendButtons: Map<SendMode, Button>
    private lateinit var aiButtons: Map<Boolean, Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val card = Ui.vbox(this, 18).apply { background = Ui.rounded(this@QuickSettingsActivity, Ui.BG, 22, Ui.BROWN) }
        card.add(Ui.text(this, "⚙️ 간단 설정", 22f, bold = true), 0)

        card.add(Ui.text(this, "다듬은 문장, 언제 보낼까요?", 17f, bold = true), 16)
        sendButtons = SendMode.values().associateWith { m ->
            choice(card, "${m.label} 보내기", m.desc) { prefs.sendMode = m; paint(); saved() }
        }

        card.add(Ui.text(this, "다듬는 AI", 17f, bold = true), 18)
        aiButtons = mapOf(
            false to choice(card, "기본", "빠르고 대부분 충분해요") { prefs.carefulRewrite = false; paint(); saved() },
            true to choice(card, "섬세하게", "말투를 더 섬세하게 다듬어요") { prefs.carefulRewrite = true; paint(); saved() },
        )

        card.add(Ui.button(this, "닫기", sp = 19f) { finish() }, 18)
        paint()

        val root = android.widget.FrameLayout(this).apply {
            val p = Ui.dp(this@QuickSettingsActivity, 16); setPadding(p, p, p, p)
            addView(ScrollView(this@QuickSettingsActivity).apply { addView(card) })
        }
        setContentView(root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { gravity = Gravity.CENTER }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.4f)
    }

    /** 고를 수 있는 큰 버튼 (제목 + 짧은 설명) */
    private fun choice(parent: LinearLayout, title: String, desc: String, onClick: () -> Unit): Button =
        Ui.button(this, "$title\n$desc", Ui.CARD, sp = 16f) { onClick() }.apply {
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@QuickSettingsActivity, 12); setPadding(p, p, p, p)
        }.also { parent.add(it, 8) }

    private fun paint() {
        fun mark(b: Button, on: Boolean) {
            b.background = Ui.rounded(this, if (on) Ui.YELLOW else Ui.CARD, 18, if (on) Ui.BROWN else Ui.LINE)
            b.text = b.text.toString().removePrefix("✅ ").let { if (on) "✅ $it" else it }
        }
        sendButtons.forEach { (m, b) -> mark(b, m == prefs.sendMode) }
        aiButtons.forEach { (careful, b) -> mark(b, careful == prefs.carefulRewrite) }
    }

    private fun saved() = Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
}
