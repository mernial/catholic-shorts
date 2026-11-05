package kr.yunho.talkhelper.core

/**
 * 말 끝의 "보내 줘", "끝" 같은 마무리 신호.
 * 말하는 중간에 '보내', '끝', '이상' 같은 낱말이 문장 끝에 와도(예: "사진 보내…", "백 명 이상") 끊기지 않도록,
 *  - "보내 줘", "보내 주세요", "전송해 줘", "끝이야", "여기까지" 처럼 분명한 말이 끝에 오거나
 *  - 잠깐 쉬었다가 "보내" / "끝" / "전송" 만 따로 말했을 때만 마무리로 봄
 */
object EndSignal {
    /** 말 끝에 오면 마무리 (분명한 신호) */
    private val STRONG = Regex("(보내\\s*줘|보내\\s*주세요|보내\\s*줄래|보내\\s*줘요|전송\\s*해\\s*줘|전송해|끝이야|끝입니다|여기까지|이상입니다|다\\s*했어)[\\s.!?~]*$")

    /** 잠깐 쉬었다가 따로 말한 한 마디만 있을 때 마무리 */
    private val ALONE = Regex("^\\s*(보내|보내기|전송|끝|이상|오케이 보내|그래 보내)[\\s.!?~]*$")

    /** 메시지에서 지울 신호 말 ("보내 줘"는 AI 가 알아서 빼므로 그대로 둠) */
    private val STRIP = Regex("\\s*(전송해 줘|전송해|전송|끝이야|끝입니다|끝내|끝|이상입니다|여기까지)[\\s.!?]*$")

    /** 지금까지 들은 전체 글이 분명한 마무리 말로 끝나는지 */
    fun isEnd(spoken: String) = STRONG.containsMatchIn(spoken.trim())

    /** 방금 들은 한 마디(잠깐 쉰 뒤 따로 말한 것)가 마무리 신호인지 */
    fun isEndSegment(segment: String) = ALONE.matches(segment) || isEnd(segment)

    fun strip(spoken: String) = spoken.trim().replace(STRIP, "").trim()
}
