package kr.yunho.talkhelper

import android.app.Application
import android.os.Handler
import android.os.Looper
import kr.yunho.talkhelper.core.Claude
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Speaker.init(this)
    }
}

/** 백그라운드에서 일하고 결과는 메인 스레드로 */
object Bg {
    private val exec = Executors.newCachedThreadPool()
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        exec.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }
}

/** 설정의 API 키로 Claude 클라이언트를 만들어 재사용 */
object ClaudeProvider {
    private var key: String? = null
    private var client: Claude? = null

    @Synchronized
    fun get(ctx: android.content.Context): Claude? {
        val k = Prefs(ctx).apiKey.trim()
        if (k.isBlank()) return null
        if (k != key) {
            client = Claude(k)
            key = k
        }
        return client
    }
}
