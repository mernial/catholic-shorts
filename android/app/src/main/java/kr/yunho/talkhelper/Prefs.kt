package kr.yunho.talkhelper

import android.content.Context

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

    /** 마지막 카톡 보내기 시도 기록 (문제 진단용) */
    var lastSendLog: String
        get() = sp.getString("lastSendLog", "") ?: ""
        set(v) = sp.edit().putString("lastSendLog", v).apply()

    val textScale: Float get() = 1.3f

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
}
