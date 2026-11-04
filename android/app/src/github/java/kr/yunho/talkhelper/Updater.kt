package kr.yunho.talkhelper

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 안에서 새 버전 확인 → 받기 → 설치.
 * GitHub Releases 의 version.json 과 talk-helper.apk 를 씀.
 * 기존 앱 위에 덮어 설치되려면 두 버전이 같은 서명 키로 만들어져 있어야 함.
 */
object Updater {
    private const val BASE = "https://github.com/mernial/catholic-shorts/releases/download/talk-helper"
    private const val VERSION_URL = "$BASE/version.json"
    private const val APK_URL = "$BASE/talk-helper.apk"

    data class Info(val versionCode: Int, val versionName: String, val notes: String)

    fun installedVersion(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    /** 새 버전이 있으면 정보, 없으면 null (블로킹: 백그라운드에서 부를 것) */
    fun check(ctx: Context): Info? {
        val o = JSONObject(get(VERSION_URL).readBytes().decodeToString())
        val info = Info(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"))
        return info.takeIf { it.versionCode > installedVersion(ctx) }
    }

    private fun get(url: String): java.io.InputStream {
        var u = URL(url)
        // GitHub 은 실제 파일 주소로 넘겨주므로 따라감
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Cache-Control", "no-cache")
            when (c.responseCode) {
                in 300..399 -> { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
                200 -> return c.inputStream
                else -> throw IllegalStateException("HTTP ${c.responseCode}")
            }
        }
        throw IllegalStateException("too many redirects")
    }

    /** 이 앱이 다른 앱(자기 자신 포함)을 설치해도 되는지. 아니면 허용 화면을 엶 */
    fun ensureInstallAllowed(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(ctx, "'이 출처 허용'을 켜고 돌아와서 다시 눌러 주세요", Toast.LENGTH_LONG).show()
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        return false
    }

    /** 새 APK 를 받아 설치를 시작 (블로킹: 백그라운드에서 부를 것). [progress] 는 0~100 */
    fun downloadAndInstall(ctx: Context, progress: (Int) -> Unit) {
        val file = File(ctx.cacheDir, "update.apk")
        val conn = get(APK_URL)
        conn.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    // 크기를 미리 알 수 없어 대략 15MB 기준으로 표시
                    val pct = (total * 100 / 15_000_000L).toInt().coerceAtMost(99)
                    if (pct != last) { last = pct; progress(pct) }
                }
            }
        }
        progress(100)

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("talk-helper.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}

/** 설치 진행 결과. 사용자 확인이 필요하면 시스템 확인 창을 띄움 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(ctx, "업데이트했습니다", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(ctx, "이 버전은 덮어 설치할 수 없어요. 앱을 지우고 새로 설치해 주세요.", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> Toast.makeText(ctx, "업데이트하지 못했어요: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
