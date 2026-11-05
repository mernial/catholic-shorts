package kr.yunho.talkhelper.core

import java.time.Duration
import java.time.LocalDateTime

/**
 * 대화에서 사진 찾기.
 * 내보낸 카톡 대화에는 사진 자리에 '사진' 글자만 남으므로, AI 가 대화 흐름으로 사진이 올라온 시각을 찾고
 * ("사진시각: 2024-03-05 15:12"), 앱은 함께 내보낸 사진 파일 중 그 시각에 가까운 것을 보여 줌.
 * 사진은 휴대폰 밖으로 보내지 않음.
 */
object PhotoFind {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val ANSWER_LINE = Regex("""(?m)^\s*사진시각\s*[:：]\s*(\d{4})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})\s*$""")
    private val NAME_TIME = Regex("""(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})[-_ T]?(\d{2})[-_.:]?(\d{2})[-_.:]?(\d{2})?""")

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    /** AI 답의 '사진시각:' 줄 → 시각 */
    fun photoTime(answer: String): LocalDateTime? {
        val m = ANSWER_LINE.find(answer) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) }.getOrNull()
    }

    /** 답에서 '사진시각:' 줄 지우기 (보여 주거나 읽어 줄 때) */
    fun strip(answer: String) = answer.replace(ANSWER_LINE, "").replace(Regex("\n{3,}"), "\n\n").trim()

    /** 파일 이름에 찍힌 시각 (예: KakaoTalk_20240305_151203123.jpg, 20240305_151203.jpg) */
    fun nameTime(name: String): LocalDateTime? {
        val m = NAME_TIME.find(name) ?: return null
        val g = m.groupValues
        return runCatching {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }.getOrNull()
    }

    /** [at] 앞뒤 [minutes]분 안의 사진을 가까운 순서로 (많아야 [max]장) */
    fun <T> pick(files: List<Pair<T, LocalDateTime>>, at: LocalDateTime, minutes: Long = 3, max: Int = 10): List<T> =
        files.map { (f, t) -> f to Duration.between(at, t).abs() }
            .filter { it.second <= Duration.ofMinutes(minutes).plusSeconds(59) }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
}
