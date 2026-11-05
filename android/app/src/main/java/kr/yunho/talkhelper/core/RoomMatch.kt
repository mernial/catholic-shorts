package kr.yunho.talkhelper.core

/**
 * 지금 보고 있는 카톡 방(화면 맨 위 이름)과 내보내 저장해 둔 대화를 짝지음.
 * 카톡 화면 이름과 내보낸 파일 속 이름은 띄어쓰기·인원수 표시 등이 조금씩 다를 수 있음.
 */
object RoomMatch {
    /** 비교용 이름: 띄어쓰기·끝의 인원수·'님과 카카오톡 대화' 등을 뺌 */
    fun norm(name: String): String = name
        .replace(Regex("님과\\s*카카오톡\\s*대화.*$"), "")
        .replace(Regex("카카오톡\\s*대화.*$"), "")
        .replace(Regex("\\s+\\d+$"), "")      // "청년부 12" → "청년부"
        .replace(Regex("[\\s_]+"), "")
        .lowercase()

    /**
     * [title] 방에 해당하는 저장된 방 이름. [aliases] 는 (정규화한 화면 이름 → 저장된 방) 기억.
     * 못 찾으면 null
     */
    fun find(title: String?, saved: List<String>, aliases: Map<String, String>): String? {
        val t = title?.let { norm(it) }?.takeIf { it.isNotEmpty() } ?: return null
        aliases[t]?.takeIf { it in saved }?.let { return it }
        saved.firstOrNull { norm(it) == t }?.let { return it }
        return saved.firstOrNull { val r = norm(it); r.length >= 2 && (r.contains(t) || t.contains(r)) }
    }
}
