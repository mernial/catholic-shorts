package kr.yunho.talkhelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import java.util.concurrent.TimeUnit

/**
 * 플레이 스토어판: 새 버전이 있는지 플레이 스토어에 물어보고(앱 안 업데이트 API),
 * 있으면 배너를 보여 주고 누르면 플레이 스토어의 이 앱 화면을 엶 (거기서 '업데이트')
 */
object Updater {
    data class Info(val versionCode: Int, val versionName: String, val notes: String)

    /** 업데이트는 플레이 스토어에서 */
    const val STORE = true

    /** 백그라운드에서 부를 것 */
    fun check(ctx: Context): Info? = runCatching {
        val info = Tasks.await(AppUpdateManagerFactory.create(ctx.applicationContext).appUpdateInfo, 15, TimeUnit.SECONDS)
        if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE)
            Info(info.availableVersionCode(), "1.${info.availableVersionCode()}", "")
        else null
    }.getOrNull()

    fun openStore(ctx: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}"))
            .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(market) }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun ensureInstallAllowed(ctx: Context): Boolean = false
    fun downloadAndInstall(ctx: Context, progress: (Int) -> Unit) {}
}
