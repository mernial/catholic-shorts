package kr.yunho.talkhelper.core

/**
 * 찾은 내용(결론·진행·결정·미정·근거)을 읽기 좋게: 제목은 한 줄에 따로, 내용은 그 아래 들여 써서,
 * 진행·결정·미정은 쉼표로 나뉜 것을 한 줄씩. 칸 사이는 한 줄 띄움.
 */
object AnswerFormat {
    private val LABEL = Regex("""^\s*(결론|진행|결정|미정|근거|비슷한 것)\s*[:：]\s*(.*)$""")
    private val LIST_LABELS = setOf("진행", "결정", "미정")
    const val INDENT = "   "

    fun format(answer: String): String {
        val out = ArrayList<String>()
        for (raw in answer.lines()) {
            val m = LABEL.find(raw)
            if (m == null) {
                if (raw.isNotBlank()) out += INDENT + raw.trim()
                continue
            }
            val (label, body) = m.destructured
            if (out.isNotEmpty()) out += ""
            out += label
            val parts = if (label in LIST_LABELS) splitList(body) else listOf(body.trim())
            parts.filter { it.isNotBlank() }.forEach { out += "$INDENT$it" }
        }
        return out.joinToString("\n").trim('\n')
    }

    /** 쉼표로 나누되 괄호·따옴표 안의 쉼표는 나누지 않음 */
    private fun splitList(s: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote = false
        for (c in s) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> depth = maxOf(0, depth - 1)
                '\'', '"', '‘', '’', '“', '”' -> quote = !quote
            }
            if (c == ',' && depth == 0 && !quote) { parts += cur.toString().trim(); cur.clear() } else cur.append(c)
        }
        parts += cur.toString().trim()
        return parts.map { if (it.length > 1) "· $it" else it }
    }

    /** 제목 줄(결론·진행…)인지 — 화면에서 굵게 보여 줄 때 */
    fun isLabelLine(line: String) = line in setOf("결론", "진행", "결정", "미정", "근거", "비슷한 것")
}
