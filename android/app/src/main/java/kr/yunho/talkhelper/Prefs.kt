package kr.yunho.talkhelper

import android.content.Context
import kr.yunho.talkhelper.core.Mode

/** 보내는 방식 */
enum class SendMode(val label: String, val desc: String) {
    INSTANT("바로", "다듬자마자 바로 보냅니다"),
    COUNTDOWN("3초 뒤", "3초 동안 보여 주고 보냅니다 (그 사이 취소 가능)"),
    CONFIRM("확인 후", "다듬은 문장을 보고 '보내기'를 눌러야 보냅니다"),
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()
    var myName: String
        get() = sp.getString("myName", "") ?: ""
        set(v) = sp.edit().putString("myName", v).apply()
    var myRole: String
        get() = sp.getString("myRole", "") ?: ""
        set(v) = sp.edit().putString("myRole", v).apply()
    /** 카톡 입력창에 넣은 뒤 전송 버튼까지 누르기 */
    var autoPressSend: Boolean
        get() = sp.getBoolean("autoPressSend", true)
        set(v) = sp.edit().putBoolean("autoPressSend", v).apply()

    /** 버튼 한 번으로 말하기→다듬기→전송까지 자동 (끄면 보내기 전에 확인) */
    /** 다듬은 문장을 언제 보낼지 (예전 '자동 전송' 체크 설정을 이어받음) */
    var sendMode: SendMode
        get() = sp.getString("sendMode", null)?.let { runCatching { SendMode.valueOf(it) }.getOrNull() }
            ?: if (sp.getBoolean("autoSend", true)) SendMode.COUNTDOWN else SendMode.CONFIRM
        set(v) = sp.edit().putString("sendMode", v.name).apply()

    /** 답장 다듬기를 더 섬세한(조금 비싼) 모델로 */
    var carefulRewrite: Boolean
        get() = sp.getBoolean("carefulRewrite", false)
        set(v) = sp.edit().putBoolean("carefulRewrite", v).apply()

    /** 고급 받아쓰기(Whisper, 휴대폰 안에서) 사용 */
    var whisperOn: Boolean
        get() = sp.getBoolean("whisperOn", false)
        set(v) = sp.edit().putBoolean("whisperOn", v).apply()

    /** 마지막으로 고른 말투 모드 */
    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.POLITE.name)!!) }.getOrDefault(Mode.POLITE)
        set(v) = sp.edit().putString("mode", v.name).apply()

    /** 도우미 버튼 진단 기록 */
    var bubbleDiag: String
        get() = sp.getString("bubbleDiag", "") ?: ""
        set(v) = sp.edit().putString("bubbleDiag", v).apply()

    /** 마지막 다듬기 요청의 토큰 사용량 (캐시 확인용) */
    var lastUsage: String
        get() = sp.getString("lastUsage", "") ?: ""
        set(v) = sp.edit().putString("lastUsage", v).apply()

    /** 마지막 카톡 보내기 시도 기록 (문제 진단용) */
    var lastSendLog: String
        get() = sp.getString("lastSendLog", "") ?: ""
        set(v) = sp.edit().putString("lastSendLog", v).apply()

    val textScale: Float get() = 1.3f

    /** 도우미 카드의 화면 위치 (위에서부터) */
    var panelY: Int
        get() = sp.getInt("panelY", -1)
        set(v) = sp.edit().putInt("panelY", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
}
