package kr.yunho.talkhelper

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val p = Prefs(ctx)
        val body = Ui.vbox(ctx, 18)

        body.add(Ui.text(ctx, "설정", 28f, bold = true), 0)

        body.add(Ui.text(ctx, "Claude API 키", 17f, bold = true), 20)
        val key = Ui.input(ctx, "sk-ant-...", password = true).apply { setText(p.apiKey) }
        body.add(key, 6)
        body.add(Ui.text(ctx, "platform.claude.com 에서 발급합니다. 요약·답장 다듬기를 할 때만 카톡 내용이 Anthropic 으로 전송되며, 사용한 만큼 요금이 나갑니다.", 14f, color = Ui.MUTED), 4)

        body.add(Ui.text(ctx, "내 이름", 17f, bold = true), 20)
        val name = Ui.input(ctx, "예: 윤호요셉").apply { setText(p.myName) }
        body.add(name, 6)
        body.add(Ui.text(ctx, "나는 어떤 사람인가요? (답장 말투에 반영)", 17f, bold = true), 16)
        val role = Ui.input(ctx, "예: 본당 주임신부").apply { setText(p.myRole) }
        body.add(role, 6)

        fun check(label: String, on: Boolean) = CheckBox(ctx).apply {
            text = label; isChecked = on; textSize = 18f * p.textScale; setTextColor(Ui.BROWN)
        }
        val autoRead = check("새 카톡이 오면 소리로 읽어주기", p.autoRead)
        val readBack = check("다듬은 답장을 보내기 전에 읽어주기", p.readBack)
        val pressSend = check("카톡 입력창에 넣은 뒤 전송까지 누르기", p.autoPressSend)
        val bubble = check("카톡 위에 '도우미' 버튼 띄우기", p.bubbleEnabled)
        listOf(autoRead, readBack, pressSend, bubble).forEach { body.add(it, 10) }

        fun radios(title: String, options: List<Pair<String, Float>>, current: Float): RadioGroup {
            body.add(Ui.text(ctx, title, 17f, bold = true), 20)
            val g = RadioGroup(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEachIndexed { i, (label, v) ->
                g.addView(RadioButton(ctx).apply {
                    id = 1000 + i; text = label; textSize = 17f * p.textScale; setTextColor(Ui.BROWN); tag = v
                    isChecked = Math.abs(v - current) < 0.01f
                })
            }
            body.add(g, 6)
            return g
        }
        val rate = radios("읽어주는 빠르기", listOf("느리게" to 0.75f, "보통" to 0.9f, "빠르게" to 1.1f), p.speechRate)
        val size = radios("글씨 크기", listOf("크게" to 1.15f, "더 크게" to 1.3f, "아주 크게" to 1.5f), p.textScale)

        body.add(Ui.button(ctx, "저장", sp = 22f) {
            p.apiKey = key.text.toString().trim()
            p.myName = name.text.toString().trim()
            p.myRole = role.text.toString().trim()
            p.autoRead = autoRead.isChecked
            p.readBack = readBack.isChecked
            p.autoPressSend = pressSend.isChecked
            p.bubbleEnabled = bubble.isChecked
            (rate.findViewById<RadioButton>(rate.checkedRadioButtonId)?.tag as? Float)?.let { p.speechRate = it }
            (size.findViewById<RadioButton>(size.checkedRadioButtonId)?.tag as? Float)?.let { p.textScale = it }
            Toast.makeText(ctx, "저장했습니다", Toast.LENGTH_SHORT).show()
            finish()
        }, 26)
        body.add(Ui.button(ctx, "🔊 목소리 들어보기", Ui.CARD, sp = 17f) {
            Speaker.speak(ctx, "안녕하세요. 카톡 도우미입니다. 이 빠르기로 읽어 드릴게요.")
        })
        body.add(Ui.button(ctx, "저장된 카톡 모두 지우기", Ui.CARD, Ui.RED, sp = 17f) {
            AlertDialog.Builder(ctx).setMessage("이 앱에 모아 둔 카톡을 모두 지울까요? (카카오톡 대화는 지워지지 않습니다)")
                .setPositiveButton("지우기") { _, _ -> Store.clear(); Toast.makeText(ctx, "지웠습니다", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("취소", null).show()
        }, 30)

        setContentView(ScrollView(ctx).apply { addView(body) })
    }
}
