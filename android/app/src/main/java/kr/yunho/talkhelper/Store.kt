package kr.yunho.talkhelper

import android.app.Notification
import android.content.Context
import kr.yunho.talkhelper.core.ChatLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class RoomInfo(val name: String, val last: ChatLine, val unread: Int)

/** 카톡 알림으로 받은 메시지를 이 휴대폰 안에만 저장 */
object Store {
    private const val PER_ROOM = 300
    private const val TOTAL = 4000

    private lateinit var file: File
    private lateinit var readFile: File
    private val lines = ArrayList<ChatLine>()
    private val lastRead = HashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 방 이름 → 카톡 알림의 '답장' 버튼 (앱이 켜져 있는 동안만 기억) */
    val replyActions = ConcurrentHashMap<String, Notification.Action>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "messages.json")
        readFile = File(ctx.filesDir, "read.json")
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lines += ChatLine(o.getString("r"), o.getString("s"), o.getString("t"), o.getLong("w"), o.optBoolean("m"))
            }
        }
        runCatching {
            val o = JSONObject(readFile.readText())
            for (k in o.keys()) lastRead[k] = o.getLong(k)
        }
    }

    /** 새 메시지면 저장하고 true. 이미 있던 것이면 false */
    @Synchronized
    fun add(line: ChatLine): Boolean {
        val dup = lines.asReversed().asSequence().take(500).any {
            it.room == line.room && it.text == line.text && it.sender == line.sender && Math.abs(it.time - line.time) < 120_000
        }
        if (dup) return false
        lines += line
        lines.sortBy { it.time }
        trim()
        save()
        notifyChanged()
        return true
    }

    @Synchronized
    fun lines(room: String): List<ChatLine> = lines.filter { it.room == room }

    @Synchronized
    fun unread(room: String): List<ChatLine> {
        val r = lastRead[room] ?: 0L
        return lines.filter { it.room == room && !it.fromMe && it.time > r }
    }

    @Synchronized
    fun allUnread(): List<ChatLine> = lines.filter { !it.fromMe && it.time > (lastRead[it.room] ?: 0L) }

    @Synchronized
    fun rooms(): List<RoomInfo> = lines.groupBy { it.room }
        .map { (name, ls) -> RoomInfo(name, ls.last(), ls.count { !it.fromMe && it.time > (lastRead[name] ?: 0L) }) }
        .sortedByDescending { it.last.time }

    @Synchronized
    fun markRead(room: String) {
        lastRead[room] = System.currentTimeMillis()
        saveRead()
        notifyChanged()
    }

    @Synchronized
    fun markAllRead() {
        val now = System.currentTimeMillis()
        for (r in lines.map { it.room }.toSet()) lastRead[r] = now
        saveRead()
        notifyChanged()
    }

    @Synchronized
    fun clear() {
        lines.clear(); lastRead.clear(); replyActions.clear()
        save(); saveRead(); notifyChanged()
    }

    /** 화면에 보이는 글자들 가운데 저장된 방 이름과 같은 것을 찾음 */
    @Synchronized
    fun findRoomIn(texts: Collection<String>): String? {
        val names = lines.map { it.room }.toSet()
        return texts.map { it.trim() }.firstOrNull { it in names }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = Bg.main.post { listeners.forEach { it() } }

    private fun trim() {
        val counts = HashMap<String, Int>()
        val keep = ArrayList<ChatLine>()
        for (l in lines.asReversed()) {
            val c = (counts[l.room] ?: 0) + 1
            counts[l.room] = c
            if (c <= PER_ROOM && keep.size < TOTAL) keep += l
        }
        keep.reverse()
        lines.clear(); lines += keep
    }

    private fun save() = runCatching {
        val arr = JSONArray()
        for (l in lines) arr.put(JSONObject().put("r", l.room).put("s", l.sender).put("t", l.text).put("w", l.time).put("m", l.fromMe))
        file.writeText(arr.toString())
    }

    private fun saveRead() = runCatching {
        val o = JSONObject()
        for ((k, v) in lastRead) o.put(k, v)
        readFile.writeText(o.toString())
    }
}
