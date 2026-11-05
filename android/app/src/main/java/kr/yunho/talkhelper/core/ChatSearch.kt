package kr.yunho.talkhelper.core

/**
 * 긴 카톡 대화에서 질문과 관련 있는 부분만 골라 AI 에 보내기 위한 검색.
 * (대화 전체를 매번 보내면 요금이 많이 나오므로)
 */
object ChatSearch {
    /** 이 글자 수 이하면 대화 전체를 보냄 */
    const val MAX_CHARS = 15_000
    private const val WINDOW = 4          // 찾은 줄 앞뒤로 함께 보낼 줄 수
    private const val RECENT_CHARS = 3_000 // 질문과 상관없이 항상 넣는 최근 대화

    private val JOSA = Regex("(으로|에서|에게|한테|까지|부터|처럼|보다|이랑|하고|이나|은|는|이|가|을|를|에|의|도|만|와|과|랑|로|요)$")
    private val STOP = setOf(
        "언제", "어디", "무엇", "뭐", "뭐였", "누가", "누구", "어떻게", "얼마", "몇", "했지", "했나", "했어", "했는지", "라고", "이라고",
        "그거", "이거", "저거", "그때", "얘기", "이야기", "말한", "말했", "알려", "알려줘", "찾아", "찾아줘", "있었", "있었지", "대화", "카톡",
    )

    /** 질문에서 검색어 뽑기 */
    fun keywords(question: String): List<String> =
        Regex("[가-힣A-Za-z0-9]{2,}").findAll(question).map { it.value }
            .map { w -> w.replace(JOSA, "").ifBlank { w } }
            .filter { it.length >= 2 && it !in STOP && !it.endsWith("했지") && !it.endsWith("했나") }
            .distinct().toList()

    /** 대화에서 질문과 관련 있는 부분을 [MAX_CHARS] 안으로 골라 냄 */
    fun excerpt(chat: String, question: String): String {
        if (chat.length <= MAX_CHARS) return chat
        val lines = chat.lines()
        val keys = keywords(question)
        // 줄마다 검색어가 몇 개 들어 있는지
        val scores = lines.map { line -> keys.count { k -> line.contains(k, ignoreCase = true) } }
        val picked = BooleanArray(lines.size)
        var budget = MAX_CHARS - RECENT_CHARS
        // 많이 맞는 줄부터, 앞뒤 몇 줄과 함께
        for (i in scores.indices.filter { scores[it] > 0 }.sortedWith(compareByDescending<Int> { scores[it] }.thenByDescending { it })) {
            for (j in maxOf(0, i - WINDOW)..minOf(lines.lastIndex, i + WINDOW)) {
                if (picked[j]) continue
                if (budget - lines[j].length - 1 < 0) break
                picked[j] = true
                budget -= lines[j].length + 1
            }
            if (budget <= 0) break
        }
        // 최근 대화도 조금 (질문이 최근 일에 관한 것일 수 있음)
        var recent = RECENT_CHARS
        for (j in lines.indices.reversed()) {
            if (recent - lines[j].length - 1 < 0) break
            picked[j] = true
            recent -= lines[j].length + 1
        }
        val out = StringBuilder()
        var gap = false
        for (j in lines.indices) {
            if (picked[j]) {
                if (gap && out.isNotEmpty()) out.append("…(중략)…\n")
                out.append(lines[j]).append('\n')
                gap = false
            } else gap = true
        }
        return out.toString()
    }

    /** 내보낸 카톡 파일 첫머리에서 대화방 이름 찾기 */
    fun roomName(chat: String): String? {
        val head = chat.lineSequence().take(3).joinToString("\n")
        Regex("^(.+?) 님과 카카오톡 대화", RegexOption.MULTILINE).find(head)?.let { return it.groupValues[1].trim() }
        Regex("^(.+?) 카카오톡 대화", RegexOption.MULTILINE).find(head)?.let { return it.groupValues[1].trim() }
        Regex("^KakaoTalk Chats with (.+)$", RegexOption.MULTILINE).find(head)?.let { return it.groupValues[1].trim() }
        return null
    }
}
