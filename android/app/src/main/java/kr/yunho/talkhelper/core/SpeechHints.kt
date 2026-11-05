package kr.yunho.talkhelper.core

/**
 * 음성 인식기에 미리 알려 줄 낱말: 카톡 화면의 사람 이름·세례명·방 이름과 자주 쓰는 호칭·성당 말.
 * 이름이나 세례명은 인식기 사전에 없어 엉뚱한 낱말로 바뀌기 쉬우므로 미리 알려 줌.
 */
object SpeechHints {
    val COMMON = listOf(
        "자매님", "형제님", "신부님", "수녀님", "주교님", "부제님", "교우", "세례명", "본당", "성당", "미사", "성지순례",
        "소공동체", "구역", "레지오", "성가대", "교리", "고해성사", "보내 줘", "끝",
    )
    private val TIME = Regex("^(오전|오후)?\\s*\\d{1,2}:\\d{2}$")
    private val NAME_LINE = Regex("^[가-힣]{2,5}(\\s[가-힣]{2,6})?$")   // "신현주 테클라", "김미카엘", "청년부"

    fun words(screen: String?, title: String?, max: Int = 50): List<String> {
        val names = screen.orEmpty().lines().map { it.trim() }
            .filter { !it.startsWith("나:") && !TIME.matches(it) && NAME_LINE.matches(it) }
            .flatMap { listOf(it) + it.split(' ') }
        return (listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }) + names + COMMON)
            .map { it.trim() }.filter { it.length >= 2 }.distinct().take(max)
    }
}
