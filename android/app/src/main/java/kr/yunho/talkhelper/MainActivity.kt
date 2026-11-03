package kr.yunho.talkhelper

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add

/** 처음 설정 화면: 권한 켜기 + API 키·이름 넣기 */
class MainActivity : Activity() {

    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = Ui.vbox(this, 18)
        setContentView(ScrollView(this).apply { addView(body) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, KakaoAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val ctx = this
        val p = Prefs(ctx)
        body.removeAllViews()
        body.add(Ui.text(ctx, "카톡 도우미", 28f, bold = true), 0)
        body.add(Ui.text(ctx, "카톡 대화방에 뜨는 노란 💬 버튼을 누르고 편하게 말하면, 정중한 말로 바꿔서 보내 드립니다.", 17f, color = Ui.MUTED), 6)

        // 1. 권한
        val steps = Ui.vbox(ctx, 14, Ui.CARD)
        steps.add(Ui.text(ctx, "처음 한 번만 켜 주세요", 19f, bold = true), 0)
        val acc = accessibilityOn()
        steps.add(Ui.button(ctx, if (acc) "✅  카톡 위 버튼 켜짐" else "1.  카톡 위 버튼 켜기 (접근성)", if (acc) Ui.THEM else Ui.YELLOW, sp = 18f) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (!acc) steps.add(Ui.text(ctx, "설정 → 접근성 → 설치된 앱 → '카톡 도우미' 켜기", 15f, color = Ui.MUTED), 4)
        val mic = micOn()
        steps.add(Ui.button(ctx, if (mic) "✅  마이크 허용됨" else "2.  마이크 허용", if (mic) Ui.THEM else Ui.YELLOW, sp = 18f) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        if (!acc) {
            steps.add(Ui.text(ctx,
                "'제한된 설정'이라며 켜지지 않으면: 아래 '앱 정보'를 열고 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜 주세요.",
                15f, color = Ui.MUTED))
            steps.add(Ui.button(ctx, "앱 정보 열기", Ui.CARD, sp = 16f) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        body.add(steps, 16)

        // 2. 설정
        body.add(Ui.text(ctx, "3. Claude API 키", 17f, bold = true), 22)
        val key = Ui.input(ctx, "sk-ant-...", password = true).apply { setText(p.apiKey) }
        body.add(key, 6)
        body.add(Ui.text(ctx, "platform.claude.com 에서 발급합니다. 답장을 다듬을 때만 말한 내용과 카톡 화면 글이 Anthropic 으로 전송되며, 사용한 만큼 요금이 나갑니다.", 14f, color = Ui.MUTED), 4)

        body.add(Ui.text(ctx, "내 이름", 17f, bold = true), 18)
        val name = Ui.input(ctx, "예: 윤호요셉").apply { setText(p.myName) }
        body.add(name, 6)
        body.add(Ui.text(ctx, "나는 어떤 사람인가요? (말투에 반영)", 17f, bold = true), 14)
        val role = Ui.input(ctx, "예: 본당 주임신부").apply { setText(p.myRole) }
        body.add(role, 6)
        val pressSend = CheckBox(ctx).apply {
            text = "입력창에 넣은 뒤 전송 버튼까지 누르기"
            isChecked = p.autoPressSend
            textSize = 18f * p.textScale
            setTextColor(Ui.BROWN)
        }
        body.add(pressSend, 12)

        body.add(Ui.button(ctx, "저장", sp = 22f) {
            p.apiKey = key.text.toString().trim()
            p.myName = name.text.toString().trim()
            p.myRole = role.text.toString().trim()
            p.autoPressSend = pressSend.isChecked
            Toast.makeText(ctx, "저장했습니다", Toast.LENGTH_SHORT).show()
            render()
        }, 20)

        body.add(Ui.button(ctx, "🎤  여기서 시험해 보기", Ui.CARD, sp = 18f) {
            startActivity(Intent(ctx, HelperActivity::class.java))
        }, 12)
        body.add(Ui.text(ctx, "시험 화면에서 '보내기'를 누르면 카톡이 열려 있지 않아 글이 복사만 됩니다.", 14f, color = Ui.MUTED), 4)
    }
}
