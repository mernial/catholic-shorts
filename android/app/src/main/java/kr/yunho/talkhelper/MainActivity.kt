package kr.yunho.talkhelper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kr.yunho.talkhelper.Ui.add
import kr.yunho.talkhelper.core.SetupCode

/**
 * 어르신도 따라 할 수 있게, 한 화면에 한 단계만 크게 보여 주는 설정 화면.
 *  1단계: 카톡 위 버튼 켜기 (접근성)
 *  2단계: 마이크 허용
 *  3단계: 설정 코드 붙여넣기 (자녀가 카톡으로 보내 준 것) 또는 API 키 직접 입력
 *  완료: 사용법 + 가족에게 설정 보내기 + 자세한 설정
 */
class MainActivity : Activity() {

    companion object {
        /** 접근성 API 사용에 대한 눈에 띄는 공개 (Play 정책) */
        const val DISCLOSURE = """이 앱은 '접근성' 권한을 다음 용도로만 사용합니다.

• 카카오톡 화면 위에 '도우미' 버튼을 띄웁니다.
• 도우미 버튼을 누를 때, 그 순간 카카오톡 화면에 보이는 대화 글을 읽습니다. 무엇에 대한 답장인지 알기 위해서입니다.
• 다듬은 답장을 카카오톡 입력창에 넣고 전송 버튼을 누릅니다. 보내기 전에 화면에 보여 드리고 취소할 수 있습니다.

읽은 대화 글과 말씀하신 내용은 답장을 다듬기 위해 Anthropic(Claude AI)으로 전송되며, 이 앱의 서버나 휴대폰에 저장하지 않습니다.
카카오톡 이외의 앱 화면은 읽지 않으며, 버튼을 누르지 않았을 때는 아무것도 읽거나 보내지 않습니다."""
    }

    private lateinit var body: LinearLayout
    private var showAdvanced = false
    private var triedAccessibility = false
    private var update: Updater.Info? = null
    private var updating: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = Ui.vbox(this, 20)
        // 안드로이드 15 이상은 화면 끝까지 그리므로 상태 표시줄·내비게이션 바 자리를 비워 둠
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(body) })
    }

    override fun onResume() {
        super.onResume()
        render()
        // 새 버전 확인 (조용히, 실패해도 무시)
        Bg.run({ Updater.check(this) }) { r ->
            r.getOrNull().let { if (it != update) { update = it; render() } }
        }
    }

    /** 새 버전이 있으면 맨 위에 크게 알림 */
    private fun updateBanner() {
        val info = update ?: return
        val box = Ui.vbox(this, 16, Ui.MINE)
        box.add(Ui.text(this, "🔔 새 버전이 나왔어요", 22f, bold = true), 0)
        if (info.notes.isNotBlank()) box.add(Ui.text(this, info.notes, 14f, color = Ui.MUTED), 4)
        val btn = Ui.button(this, updating ?: "⬇️  지금 업데이트", Ui.BROWN, Ui.YELLOW, sp = 22f) {
            if (updating != null || !Updater.ensureInstallAllowed(this)) return@button
            updating = "받는 중… 0%"
            render()
            Bg.run({
                Updater.downloadAndInstall(this) { pct -> Bg.main.post { updating = "받는 중… $pct%"; render() } }
            }) { r ->
                updating = null
                r.exceptionOrNull()?.let { Toast.makeText(this, "업데이트 파일을 받지 못했어요. 인터넷을 확인해 주세요.", Toast.LENGTH_LONG).show() }
                render()
            }
        }.apply { minHeight = Ui.dp(this@MainActivity, 80) }
        box.add(btn, 10)
        body.add(box, 12)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 클립보드는 창이 앞에 있을 때만 읽을 수 있음. 설정 코드를 복사해 두었으면 자동으로 가져옴
        if (hasFocus && Prefs(this).apiKey.isBlank()) importFromClipboard(silent = true)
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, KakaoAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val p = Prefs(this)
        body.removeAllViews()
        body.add(Ui.text(this, getString(R.string.app_name), 30f, bold = true), 0)
        updateBanner()

        val steps = listOf(accessibilityOn(), micOn(), p.apiKey.isNotBlank())
        val current = steps.indexOfFirst { !it }
        if (current >= 0 && !showAdvanced) {
            body.add(Ui.text(this, "준비 ${current + 1} / 3", 18f, bold = true, color = Ui.MUTED), 8)
            when (current) {
                0 -> stepAccessibility()
                1 -> stepMic()
                2 -> stepSetupCode()
            }
            body.add(Ui.button(this, "자세한 설정 보기", Ui.CARD, sp = 15f) { showAdvanced = true; render() }, 40)
            return
        }
        if (!showAdvanced) done() else advanced()
    }

    private fun bigCard(title: String, desc: String): LinearLayout {
        val box = Ui.vbox(this, 18, Ui.CARD)
        box.add(Ui.text(this, title, 26f, bold = true), 0)
        box.add(Ui.text(this, desc, 19f), 12)
        body.add(box, 14)
        return box
    }

    // ───────── 1단계: 접근성 ─────────

    private fun stepAccessibility() {
        val box = bigCard(
            "카톡 위 버튼 켜기",
            "아래 노란 버튼을 누르면 설정 화면이 열립니다.\n\n'${getString(R.string.app_name)}'를 눌러 켜고, '허용'을 누른 뒤\n뒤로 가기(◁)로 돌아오세요."
        )
        box.add(Ui.button(this, "👉  설정 열기", sp = 24f) {
            // 구글 플레이 정책: 접근성 권한을 켜기 전에 무엇을 왜 쓰는지 크게 알리고 동의를 받아야 함
            confirmDisclosure {
                triedAccessibility = true
                openAccessibilitySettings()
            }
        }.apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)

        if (triedAccessibility) {
            val help = Ui.vbox(this, 16, Ui.MINE)
            help.add(Ui.text(this, "회색으로 막혀서 안 켜지나요?", 20f, bold = true), 0)
            help.add(Ui.text(this,
                "1. 아래 '앱 정보 열기'를 누르세요.\n2. 오른쪽 위 ⋮ (점 세 개)를 누르세요.\n3. '제한된 설정 허용'을 누르세요.\n4. 다시 위의 '설정 열기'를 누르세요.",
                18f), 8)
            help.add(Ui.button(this, "앱 정보 열기", Ui.CARD, sp = 20f) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, 10)
            body.add(help, 16)
        }
    }

    /** 접근성 권한 사용 안내와 동의 (처음 한 번) */
    private fun confirmDisclosure(onAgree: () -> Unit) {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("disclosureAccepted", false)) { onAgree(); return }
        AlertDialog.Builder(this)
            .setTitle("접근성 권한 사용 안내")
            .setMessage(DISCLOSURE)
            .setPositiveButton("동의하고 켜기") { _, _ ->
                sp.edit().putBoolean("disclosureAccepted", true).apply()
                onAgree()
            }
            .setNegativeButton("동의 안 함", null)
            .show()
    }

    /** 가능하면 '카톡 도우미' 접근성 상세 화면으로 바로 이동 */
    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= 33) {
            // 공개 SDK 에 상수가 없어 문자열로 지정 (안드로이드 13 이상에서 동작)
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, KakaoAccessibilityService::class.java).flattenToString())
            if (runCatching { startActivity(detail) }.isSuccess) return
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ───────── 2단계: 마이크 ─────────

    private fun stepMic() {
        val box = bigCard("마이크 허용", "말로 답장하려면 마이크가 필요합니다.\n\n아래 버튼을 누르고 '앱 사용 중에만 허용'을 누르세요.")
        box.add(Ui.button(this, "👉  마이크 허용하기", sp = 24f) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !micAskedBefore()) {
                markMicAsked()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else {
                // 두 번 거절하면 더 이상 묻지 않으므로 앱 정보에서 직접 켜야 함
                Toast.makeText(this, "'권한' → '마이크' → '앱 사용 중에만 허용'을 눌러 주세요", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }.apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)
    }

    private fun micAskedBefore() = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("micAsked", false)
    private fun markMicAsked() = getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("micAsked", true).apply()

    // ───────── 3단계: 설정 코드 ─────────

    private fun stepSetupCode() {
        val box = bigCard(
            "설정 받기",
            "자녀가 카톡으로 보내 준 '카톡 도우미 설정' 메시지를\n길게 눌러 '복사'한 뒤, 이 앱으로 돌아와\n아래 버튼을 누르세요."
        )
        box.add(Ui.button(this, "👉  복사한 설정 넣기", sp = 24f) { importFromClipboard(silent = false) }
            .apply { minHeight = Ui.dp(this@MainActivity, 88) }, 18)

        body.add(Ui.text(this, "API 키를 직접 넣으려면 (자녀용)", 15f, bold = true, color = Ui.MUTED), 30)
        val key = Ui.input(this, "API 키 또는 설정 메시지 붙여넣기", password = true)
        body.add(key, 6)
        body.add(Ui.button(this, "저장", Ui.CARD, sp = 18f) {
            val k = keyFrom(key.text.toString())
            if (!k.startsWith("sk-ant-")) {
                Toast.makeText(this, "sk-ant- 로 시작하는 키를 넣어 주세요", Toast.LENGTH_LONG).show()
            } else { Prefs(this).apiKey = k; render() }
        }, 8)
    }

    /** 키 칸에 API 키를 넣든, 설정 코드(안내문 포함)를 통째로 붙여 넣든 키로 바꿔 줌 */
    private fun keyFrom(text: String): String = SetupCode.decode(text) ?: text.trim()

    private fun importFromClipboard(silent: Boolean) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val key = SetupCode.decode(text)
        if (key == null) {
            if (!silent) AlertDialog.Builder(this)
                .setMessage("복사한 설정을 찾지 못했어요.\n\n카톡에서 '카톡 도우미 설정' 메시지를 길게 눌러 '복사'를 누른 뒤 다시 해 주세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        Prefs(this).apiKey = key
        render()
        AlertDialog.Builder(this)
            .setMessage("설정을 받았어요! 🎉\n\n이제 받은 카톡 메시지는 지워 주세요.")
            .setPositiveButton("확인", null).show()
    }

    // ───────── 완료 ─────────

    private fun done() {
        val box = bigCard(
            "준비 끝! 🎉",
            "카톡 대화방을 열면 노란 💬 버튼이 있어요.\n\n1. 💬 버튼을 누르고\n2. 하고 싶은 말을 편하게 한 뒤\n3. 끝에 \"보내 줘\"라고 말하세요.\n\n고른 말투로 바꿔서 보내 드려요.\n🙏 정중하게  😊 친구처럼  💼 업무 연락"
        )
        box.add(Ui.button(this, "🎤  여기서 연습해 보기", Ui.CARD, sp = 20f) {
            startActivity(Intent(this, HelperActivity::class.java))
        }, 16)
        packageManager.getLaunchIntentForPackage(KAKAO)?.let { launch ->
            box.add(Ui.button(this, "💬  카톡 열기", sp = 22f) { startActivity(launch) }, 10)
        }

        body.add(Ui.button(this, "👨‍👩‍👧  가족에게 설정 보내기", Ui.CARD, sp = 18f) { shareSetup() }, 26)
        body.add(Ui.text(this, "부모님 폰에 앱을 설치한 뒤, 이 버튼으로 카톡에 설정을 보내면 부모님은 복사만 하시면 됩니다.", 14f, color = Ui.MUTED), 4)
        body.add(Ui.button(this, "자세한 설정", Ui.CARD, sp = 16f) { showAdvanced = true; render() }, 16)
        sendLog()
    }

    private fun shareSetup() {
        val key = Prefs(this).apiKey
        if (key.isBlank()) return
        AlertDialog.Builder(this)
            .setMessage("설정 메시지에는 API 키가 들어 있습니다.\n가족에게만 보내고, 받은 쪽에서 설정이 끝나면 메시지를 지우도록 해 주세요.")
            .setPositiveButton("보내기") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SetupCode.message(key))
                packageManager.getLaunchIntentForPackage(KAKAO)?.let { send.setPackage(KAKAO) }
                runCatching { startActivity(send) }.onFailure { startActivity(Intent.createChooser(send.setPackage(null), "설정 보내기")) }
            }
            .setNegativeButton("취소", null).show()
    }

    // ───────── 자세한 설정 ─────────

    private fun advanced() {
        val p = Prefs(this)
        body.add(Ui.text(this, "자세한 설정", 22f, bold = true), 10)

        body.add(Ui.text(this, "현재 상태", 16f, bold = true, color = Ui.MUTED), 14)
        body.add(Ui.text(this,
            "${if (accessibilityOn()) "✅" else "❌"} 카톡 위 버튼   ${if (micOn()) "✅" else "❌"} 마이크   ${if (p.apiKey.isNotBlank()) "✅" else "❌"} API 키",
            16f), 4)
        val row = Ui.hbox(this)
        row.add(Ui.button(this, "접근성 설정", Ui.CARD, sp = 15f) { openAccessibilitySettings() })
        row.add(Ui.button(this, "앱 정보", Ui.CARD, sp = 15f) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        body.add(row, 8)

        body.add(Ui.text(this, "Claude API 키", 16f, bold = true), 18)
        val key = Ui.input(this, "sk-ant-...", password = true).apply { setText(p.apiKey) }
        body.add(key, 6)
        body.add(Ui.text(this, "답장을 다듬을 때만 말한 내용과 카톡 화면 글이 Anthropic 으로 전송되며, 사용한 만큼 요금이 나갑니다.", 13f, color = Ui.MUTED), 4)

        body.add(Ui.text(this, "내 이름 (선택)", 16f, bold = true), 14)
        val name = Ui.input(this, "예: 윤호요셉").apply { setText(p.myName) }
        body.add(name, 6)
        body.add(Ui.text(this, "나는 어떤 사람인가요? (선택, 말투에 반영)", 16f, bold = true), 12)
        val role = Ui.input(this, "예: 본당 주임신부 / 70대 어머니").apply { setText(p.myRole) }
        body.add(role, 6)

        fun check(label: String, on: Boolean) = CheckBox(this).apply {
            text = label; isChecked = on; textSize = 17f * p.textScale; setTextColor(Ui.BROWN)
        }
        val pressSend = check("입력창에 넣은 뒤 전송 버튼까지 누르기", p.autoPressSend)
        // 보내는 방식
        body.add(Ui.text(this, "다듬은 문장 보내는 방식", 16f, bold = true), 16)
        val sendGroup = android.widget.RadioGroup(this)
        SendMode.values().forEachIndexed { i, m ->
            sendGroup.addView(android.widget.RadioButton(this).apply {
                id = 2000 + i
                text = "${m.label} 보내기 — ${m.desc}"
                textSize = 16f * p.textScale
                setTextColor(Ui.BROWN)
                isChecked = m == p.sendMode
            })
        }
        body.add(sendGroup, 6)
        body.add(pressSend, 12)

        whisperSection(p)

        body.add(Ui.button(this, "저장", sp = 22f) {
            p.apiKey = keyFrom(key.text.toString())
            p.myName = name.text.toString().trim()
            p.myRole = role.text.toString().trim()
            SendMode.values().getOrNull(sendGroup.checkedRadioButtonId - 2000)?.let { p.sendMode = it }
            p.autoPressSend = pressSend.isChecked
            Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
            showAdvanced = false
            render()
        }, 18)
        body.add(Ui.button(this, "돌아가기", Ui.CARD, sp = 16f) { showAdvanced = false; render() }, 8)
        sendLog()
    }

    /** 고급 받아쓰기(Whisper) 받기·지우기·켜고 끄기 */
    private fun whisperSection(p: Prefs) {
        val box = Ui.vbox(this, 14, Ui.CARD)
        box.add(Ui.text(this, "고급 받아쓰기 (Whisper)", 18f, bold = true), 0)
        box.add(Ui.text(this,
            "휴대폰 안에서 더 정확하게 받아씁니다. 무료이고 음성이 밖으로 나가지 않습니다.\n" +
                "처음 한 번 약 550MB를 받습니다. 말을 마치고 3초 쉬면 받아쓰기를 시작합니다.",
            14f, color = Ui.MUTED), 6)
        body.add(box, 18)

        if (!WhisperEngine.cpuSupported()) {
            box.add(Ui.text(this, "이 휴대폰에서는 쓸 수 없습니다 (처리 성능 부족). 지금처럼 기본 받아쓰기를 씁니다.", 15f, color = Ui.RED), 8)
            return
        }
        val ram = WhisperEngine.totalRamGb(this)
        if (ram < 5.5) box.add(Ui.text(this, "메모리가 %.0fGB인 폰이라 느리거나 앱이 꺼질 수 있어요.".format(ram), 14f, color = Ui.RED), 6)

        val status = Ui.text(this, "", 16f, bold = true)
        box.add(status, 10)
        val use = CheckBox(this).apply {
            text = "고급 받아쓰기 사용"
            textSize = 17f * p.textScale
            setTextColor(Ui.BROWN)
            setOnCheckedChangeListener { _, on -> p.whisperOn = on }
        }
        val action = Ui.button(this, "", Ui.CARD, sp = 16f) {}

        fun refresh() {
            val downloading = WhisperEngine.downloadPercent >= 0
            val ready = WhisperEngine.isDownloaded(this)
            status.text = when {
                downloading -> "⬇️ 받는 중… ${WhisperEngine.downloadPercent}%"
                ready -> "✅ 받음 (${WhisperEngine.modelMb(this)}MB)"
                else -> "아직 받지 않음"
            }
            use.isEnabled = ready
            use.isChecked = ready && p.whisperOn
            action.isEnabled = !downloading
            action.text = if (ready) "🗑️ 받은 파일 지우기" else "⬇️ 받기 (약 550MB, 와이파이 권장)"
        }

        fun startDownload() {
            Bg.run({
                WhisperEngine.download(this) { pct -> Bg.main.post { status.text = "⬇️ 받는 중… $pct%" } }
            }) { r ->
                r.onSuccess { p.whisperOn = true; Toast.makeText(this, "고급 받아쓰기를 켰습니다", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "받지 못했어요 (${it.message}). 다시 누르면 이어서 받습니다.", Toast.LENGTH_LONG).show() }
                refresh()
            }
            Bg.main.postDelayed({ refresh() }, 300)
        }

        action.setOnClickListener {
            if (WhisperEngine.isDownloaded(this)) {
                AlertDialog.Builder(this).setMessage("받은 고급 받아쓰기 파일(약 550MB)을 지울까요?\n지우면 기본 받아쓰기를 씁니다.")
                    .setPositiveButton("지우기") { _, _ -> WhisperEngine.delete(this); p.whisperOn = false; refresh() }
                    .setNegativeButton("취소", null).show()
            } else if (!WhisperEngine.onWifi(this)) {
                AlertDialog.Builder(this).setMessage("와이파이가 아닙니다. 약 550MB를 받으면 데이터 요금이 나올 수 있어요. 그래도 받을까요?")
                    .setPositiveButton("받기") { _, _ -> startDownload() }
                    .setNegativeButton("취소", null).show()
            } else startDownload()
        }
        box.add(action, 8)
        box.add(use, 6)
        refresh()
    }

    /** 자동 전송이 안 될 때 원인을 볼 수 있는 기록 */
    private fun sendLog() {
        val log = Prefs(this).lastSendLog
        if (log.isBlank() || log == "성공") return
        body.add(Ui.text(this, "마지막 보내기 기록 (안 될 때 이 화면을 캡처해 주세요)", 14f, bold = true, color = Ui.MUTED), 28)
        body.add(Ui.text(this, log, 11f, color = Ui.MUTED).apply { setTextIsSelectable(true) }, 4)
        body.add(View(this), 30)
    }
}
