package kr.yunho.talkhelper

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import kr.yunho.talkhelper.Ui.add

/** 첫 화면: 설정 안내 + 새 카톡 모두 듣기 + 대화방 목록 */
class MainActivity : Activity() {

    private lateinit var body: LinearLayout
    private val onChange: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = Ui.vbox(this, 18)
        setContentView(ScrollView(this).apply { addView(body) })
    }

    override fun onResume() {
        super.onResume()
        Store.addListener(onChange)
        render()
    }

    override fun onPause() {
        Store.removeListener(onChange)
        super.onPause()
    }

    private fun notificationsOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, KakaoAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val ctx = this
        body.removeAllViews()
        val head = Ui.hbox(ctx)
        head.add(Ui.text(ctx, "카톡 도우미", 28f, bold = true), weight = 3f)
        head.add(Ui.button(ctx, "⚙️ 설정", Ui.CARD, sp = 16f) { startActivity(Intent(ctx, SettingsActivity::class.java)) }, weight = 1.3f)
        body.add(head, 0)

        // 설정 안내
        val steps = listOf(
            Triple("카톡 알림 읽기 허용", notificationsOn()) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            Triple("카톡 위 도우미 버튼 켜기", accessibilityOn()) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            Triple("마이크 허용", micOn()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
            Triple("Claude API 키 넣기", Prefs(ctx).apiKey.isNotBlank()) { startActivity(Intent(ctx, SettingsActivity::class.java)) },
        )
        if (steps.any { !it.second }) {
            val box = Ui.vbox(ctx, 14, Ui.CARD)
            box.add(Ui.text(ctx, "처음 한 번만 설정해 주세요", 19f, bold = true), 0)
            steps.forEachIndexed { i, (label, done, open) ->
                box.add(Ui.button(ctx, "${if (done) "✅" else "${i + 1}."}  $label", if (done) Ui.THEM else Ui.YELLOW, sp = 18f) { open() }.apply { isEnabled = !done })
            }
            box.add(Ui.text(ctx,
                "'제한된 설정'이라며 켜지지 않으면: 아래 '앱 정보'를 열고 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜 주세요.",
                15f, color = Ui.MUTED))
            box.add(Ui.button(ctx, "앱 정보 열기", Ui.CARD, sp = 16f) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            body.add(box, 16)
        }

        // 새 카톡 모두 듣기
        val unread = Store.allUnread()
        body.add(Ui.button(ctx, if (unread.isEmpty()) "📢  새 카톡 없음" else "📢  새 카톡 ${unread.size}개 요약해서 듣기", sp = 22f) {
            startActivity(Intent(ctx, HelperActivity::class.java))
        }.apply { minHeight = Ui.dp(ctx, 84); isEnabled = unread.isNotEmpty() }, 18)

        // 대화방 목록
        val rooms = Store.rooms()
        body.add(Ui.text(ctx, "받은 카톡", 17f, bold = true, color = Ui.MUTED), 22)
        if (rooms.isEmpty()) {
            body.add(Ui.text(ctx, "아직 받은 카톡이 없습니다.\n'카톡 알림 읽기'를 허용하면 이제부터 오는 카톡이 여기에 모입니다.", 17f, color = Ui.MUTED))
        }
        for (r in rooms) {
            val card = Ui.vbox(ctx, 14, Ui.CARD)
            val top = Ui.hbox(ctx)
            top.add(Ui.text(ctx, r.name, 20f, bold = true), weight = 3f)
            val badge: TextView = Ui.text(ctx, if (r.unread > 0) "새 ${r.unread}" else "", 15f, bold = true, color = Ui.RED)
            top.add(badge, weight = 1f)
            card.add(top, 0)
            val who = if (r.last.fromMe) "나" else r.last.sender
            card.add(Ui.text(ctx, "$who: ${r.last.text}".take(80), 17f).apply { maxLines = 2 }, 4)
            card.add(Ui.text(ctx, DateUtils.getRelativeTimeSpanString(r.last.time).toString(), 14f, color = Ui.MUTED), 2)
            card.setOnClickListener {
                startActivity(Intent(ctx, HelperActivity::class.java).putExtra(HelperActivity.EXTRA_ROOM, r.name))
            }
            body.add(card, 10)
        }
        body.add(View(ctx), 40)
    }
}
