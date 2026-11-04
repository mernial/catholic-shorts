package kr.yunho.talkhelper

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 고급 받아쓰기: Whisper large-v3-turbo 를 휴대폰 안에서 돌림 (무료, 음성이 밖으로 나가지 않음).
 * 모델(약 550MB)은 처음 한 번만 받아 앱 저장공간에 둠.
 */
object WhisperEngine {
    private const val MODEL_NAME = "ggml-large-v3-turbo-q5_0.bin"
    private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_NAME"
    /** 받은 파일이 이보다 작으면 잘못 받은 것으로 봄 */
    private const val MIN_MODEL_BYTES = 400_000_000L
    /** 한국어 카톡 말투에 맞게 받아쓰도록 주는 힌트 */
    private const val PROMPT = "다음은 카카오톡으로 보낼 말을 한국어로 편하게 말한 것입니다."

    private external fun nativeInit(path: String): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeTranscribe(ptr: Long, samples: FloatArray, threads: Int, prompt: String?): ByteArray?

    private var libLoaded = false
    private var ctx = 0L

    /** 받는 중이면 0~100, 아니면 -1 */
    @Volatile var downloadPercent = -1
        private set

    fun modelFile(c: Context) = File(c.filesDir, MODEL_NAME)
    fun isDownloaded(c: Context) = modelFile(c).let { it.exists() && it.length() >= MIN_MODEL_BYTES }
    fun modelMb(c: Context) = modelFile(c).length() / 1_000_000

    /** 64비트 ARM + dotprod/fp16 명령을 지원하는 CPU 인지 (2018년 이후 대부분의 폰) */
    fun cpuSupported(): Boolean {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
        val features = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
            .filter { it.startsWith("Features") }.joinToString(" ")
        return "asimddp" in features && ("asimdhp" in features || "fphp" in features)
    }

    fun totalRamGb(c: Context): Double {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** 모델 받기 (블로킹, 이어 받기 지원). [progress] 는 0~100 */
    fun download(c: Context, progress: (Int) -> Unit) {
        if (downloadPercent >= 0) return
        downloadPercent = 0
        try {
            val target = modelFile(c)
            val part = File(target.path + ".part")
            var have = if (part.exists()) part.length() else 0L

            var url = URL(MODEL_URL)
            var conn: HttpURLConnection? = null
            for (i in 0 until 8) {
                val cn = url.openConnection() as HttpURLConnection
                cn.instanceFollowRedirects = false
                cn.connectTimeout = 20_000
                cn.readTimeout = 60_000
                if (have > 0) cn.setRequestProperty("Range", "bytes=$have-")
                val code = cn.responseCode
                if (code in 300..399) {
                    url = URL(url, cn.getHeaderField("Location")); cn.disconnect(); continue
                }
                conn = cn; break
            }
            val cn = conn ?: throw IllegalStateException("주소 이동이 너무 많음")
            when (cn.responseCode) {
                206 -> {}
                200 -> have = 0
                416 -> {} // 이미 다 받음
                else -> throw IllegalStateException("HTTP ${cn.responseCode}")
            }
            if (cn.responseCode != 416) {
                val total = have + cn.contentLengthLong.coerceAtLeast(0)
                cn.inputStream.use { input ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            val pct = if (total > 0) (have * 100 / total).toInt().coerceIn(0, 99) else 0
                            if (pct != last) { last = pct; downloadPercent = pct; progress(pct) }
                        }
                    }
                }
            }
            if (part.length() < MIN_MODEL_BYTES) { part.delete(); throw IllegalStateException("파일이 완전하지 않음") }
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("파일 저장 실패")
            progress(100)
        } finally {
            downloadPercent = -1
        }
    }

    @Synchronized
    fun delete(c: Context) {
        release()
        modelFile(c).delete()
        File(modelFile(c).path + ".part").delete()
    }

    /** 16kHz 모노 음성 → 한국어 글 (블로킹: 백그라운드에서 부를 것) */
    @Synchronized
    fun transcribe(c: Context, samples: FloatArray): String {
        if (!libLoaded) { System.loadLibrary("whisperjni"); libLoaded = true }
        if (ctx == 0L) {
            ctx = nativeInit(modelFile(c).absolutePath)
            if (ctx == 0L) throw IllegalStateException("받아쓰기 모델을 불러오지 못했어요. 설정에서 다시 받아 주세요.")
        }
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val bytes = nativeTranscribe(ctx, samples, threads, PROMPT)
            ?: throw IllegalStateException("받아쓰기에 실패했어요.")
        return String(bytes, Charsets.UTF_8).trim()
    }

    @Synchronized
    fun release() {
        if (ctx != 0L) { nativeFree(ctx); ctx = 0L }
    }
}
