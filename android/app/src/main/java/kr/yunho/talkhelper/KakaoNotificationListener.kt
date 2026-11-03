package kr.yunho.talkhelper

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kr.yunho.talkhelper.core.ChatLine

const val KAKAO = "com.kakao.talk"

/** 카카오톡 알림을 읽어 Store 에 쌓고, 설정에 따라 소리로 알려줌 */
class KakaoNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // 이미 떠 있던 알림은 저장만 하고 읽어주지는 않음
        runCatching { activeNotifications?.forEach { handle(it, announce = false) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn, announce = true) }
    }

    private fun handle(sbn: StatusBarNotification, announce: Boolean) {
        if (sbn.packageName != KAKAO) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        // 단톡방은 방 이름, 1:1 은 보낸 사람 이름을 방 이름으로
        val room = style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: sub?.takeIf { it.isNotBlank() }
            ?: title
        if (room.isBlank()) return

        val added = ArrayList<ChatLine>()
        val msgs = style?.messages.orEmpty()
        if (msgs.isNotEmpty()) {
            for (m in msgs) {
                val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val person = m.person
                val line = ChatLine(room, person?.name?.toString() ?: "나", text, m.timestamp, fromMe = person == null)
                if (Store.add(line)) added += line
            }
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.takeIf { it.isNotBlank() } ?: return
            val line = ChatLine(room, title.ifBlank { room }, text, sbn.postTime)
            if (Store.add(line)) added += line
        }

        n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let { Store.replyActions[room] = it }

        val incoming = added.filter { !it.fromMe }
        if (announce && incoming.isNotEmpty() && Prefs(this).autoRead) Announcer.announce(this, room, incoming)
    }

    companion object {
        /** 카톡 알림의 '답장' 기능으로 보내기. 성공하면 true */
        fun replyViaNotification(ctx: Context, room: String, text: String): Boolean {
            val action = Store.replyActions[room] ?: return false
            val inputs = action.remoteInputs ?: return false
            return runCatching {
                val intent = Intent()
                val results = Bundle()
                for (ri in inputs) results.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(ctx, 0, intent)
                true
            }.getOrDefault(false)
        }
    }
}

/** 새 카톡을 소리로 알려줌. 길면 Claude 로 줄여서 */
object Announcer {
    fun announce(ctx: Context, room: String, lines: List<ChatLine>) {
        val who = lines.map { it.sender }.distinct().joinToString(", ")
        val head = if (room == who) "${who}님 카톡입니다." else "$room 방에서 ${who}님 카톡입니다."
        val joined = lines.joinToString(". ") { it.text }
        if (joined.length <= 80) {
            Speaker.speak(ctx, "$head $joined", interrupt = false)
            return
        }
        val claude = ClaudeProvider.get(ctx)
        if (claude == null) {
            Speaker.speak(ctx, "$head ${joined.take(150)}", interrupt = false)
            return
        }
        Bg.run({ claude.summarize(room, Store.lines(room).takeLast(30), null) }) { r ->
            Speaker.speak(ctx, "$head " + r.getOrElse { joined.take(150) }, interrupt = false)
        }
    }
}
