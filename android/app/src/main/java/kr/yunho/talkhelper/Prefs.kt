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
        get() = sp.getString("myRole", "본당 주임신부") ?: ""
        set(v) = sp.edit().putString("myRole", v).apply()

    /** 새 카톡이 오면 소리로 읽어주기 */
    var autoRead: Boolean
        get() = sp.getBoolean("autoRead", false)
        set(v) = sp.edit().putBoolean("autoRead", v).apply()
    /** 다듬은 답장을 보내기 전에 소리로 읽어주기 */
    var readBack: Boolean
        get() = sp.getBoolean("readBack", true)
        set(v) = sp.edit().putBoolean("readBack", v).apply()
    /** 카톡 입력창에 넣은 뒤 전송 버튼까지 누르기 */
    var autoPressSend: Boolean
        get() = sp.getBoolean("autoPressSend", true)
        set(v) = sp.edit().putBoolean("autoPressSend", v).apply()
    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubbleEnabled", true)
        set(v) = sp.edit().putBoolean("bubbleEnabled", v).apply()

    var speechRate: Float
        get() = sp.getFloat("speechRate", 0.9f)
        set(v) = sp.edit().putFloat("speechRate", v).apply()
    var textScale: Float
        get() = sp.getFloat("textScale", 1.3f)
        set(v) = sp.edit().putFloat("textScale", v).apply()

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.POLITE.name)!!) }.getOrDefault(Mode.POLITE)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()
}
