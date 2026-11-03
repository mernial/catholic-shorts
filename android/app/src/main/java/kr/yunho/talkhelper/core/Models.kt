package kr.yunho.talkhelper.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 카톡 한 줄. [fromMe] 가 true 면 내가 보낸 말. */
data class ChatLine(
    val room: String,
    val sender: String,
    val text: String,
    val time: Long,
    val fromMe: Boolean = false,
) {
    fun format(withRoom: Boolean = false): String {
        val t = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(time))
        val where = if (withRoom && room != sender) "($room) " else ""
        return "[$t] $where${if (fromMe) "나" else sender}: $text"
    }
}

/** 말투 모드 */
enum class Mode(val label: String, val emoji: String, val instruction: String) {
    POLITE(
        "정중하게", "🙏",
        "본당 주임신부가 신자에게 보내는 정중하고 따뜻한 존댓말. 품위 있고 단정하게, 지나치게 딱딱하거나 사무적이지 않게. " +
            "필요하면 '형제님/자매님' 같은 호칭을 자연스럽게 쓰되, 상대 이름을 모르면 호칭을 지어내지 마세요."
    ),
    FRIENDLY(
        "친근하게", "😊",
        "오랜 친구에게 보내는 편안하고 다정한 말투. 상대가 반말을 쓰면 반말, 존댓말을 쓰면 부드러운 존댓말. 이모티콘은 많아야 하나."
    ),
    COMFORT(
        "위로·격려", "🤍",
        "상대의 마음을 먼저 알아주고 위로하거나 격려하는 따뜻한 말. 섣부른 충고나 훈계는 빼고, 진심이 느껴지게. " +
            "신앙적인 표현(기도하겠다 등)은 내가 말했거나 상대와의 대화에 맞을 때만 짧게."
    ),
    SHORT(
        "짧고 간단히", "✏️",
        "핵심만 한두 문장으로. 예의는 지키되 군더더기 없이."
    );
}
