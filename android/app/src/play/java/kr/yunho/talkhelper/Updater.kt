package kr.yunho.talkhelper

import android.content.Context

/** 플레이 스토어판: 업데이트는 플레이 스토어가 하므로 앱 안 업데이트 기능 없음 */
object Updater {
    data class Info(val versionCode: Int, val versionName: String, val notes: String)

    fun check(ctx: Context): Info? = null
    fun ensureInstallAllowed(ctx: Context): Boolean = false
    fun downloadAndInstall(ctx: Context, progress: (Int) -> Unit) {}
}
