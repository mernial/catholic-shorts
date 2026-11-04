package kr.yunho.talkhelper

import android.content.Context
import kr.yunho.talkhelper.core.Mode

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
    var autoSend: Boolean
        get() = sp.getBoolean("autoSend", true)
        set(v) = sp.edit().putBoolean("autoSend", v).apply()

    /** 고급 받아쓰기(Whisper, 휴대폰 안에서) 사용 */
    var whisperOn: Boolean
        get() = sp.getBoolean("whisperOn", false)
        set(v) = sp.edit().putBoolean("whisperOn", v).apply()

    /** 마지막으로 고른 말투 모드 */
    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.POLITE.name)!!) }.getOrDefault(Mode.POLITE)
        set(v) = sp.edit().putString("mode", v.name).apply()

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
