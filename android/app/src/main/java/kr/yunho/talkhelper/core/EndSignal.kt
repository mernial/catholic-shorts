package kr.yunho.talkhelper.core

/**
 * 말 끝의 마무리 신호. 사용자 요청: "~라고 보내"처럼 전하는 말(라고·다고·자고·냐고) 뒤에
 * '보내 / 전송'이 올 때만 마무리함. ("자료 보내 줘"처럼 상대에게 하는 말에 '보내'가 들어가도 끊기지 않게)
 */
object EndSignal {
    /** "~라고 보내 줘", "~하자고 보내", "~간다고 전송해" 가 맨 끝에 올 때 */
    private val QUOTED_SEND = Regex("(라고|이라고|다고|자고|냐고|라구|다구|자구)\\s*(보내|전송)[가-힣\\s]{0,6}[.!?~]*$")

    /** 메시지에서 지울 신호 말 ("~라고 보내 줘"는 AI 가 알아서 상대에게 하는 말로 바꿈) */
    private val STRIP = Regex("\\s*(전송해 줘|전송해|전송)[\\s.!?]*$")

    /** 지금까지 들은 글이 "~라고 보내"로 끝나는지 */
    fun isEnd(spoken: String) = QUOTED_SEND.containsMatchIn(spoken.trim())

    /** 방금 들은 한 마디가 마무리 신호인지 (전체와 같은 규칙) */
    fun isEndSegment(segment: String) = isEnd(segment)

    fun strip(spoken: String) = spoken.trim().replace(STRIP, "").trim()
}
