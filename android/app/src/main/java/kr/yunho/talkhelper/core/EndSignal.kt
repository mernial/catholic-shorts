package kr.yunho.talkhelper.core

/** 말 끝의 "보내 줘", "끝" 같은 신호 말 */
object EndSignal {
    /** 말 끝에 이 말이 나오면 다 말한 것으로 보고 바로 다듬어 보냄 */
    private val END = Regex("(보내|보내줘|보내 줘|보내주세요|보내 주세요|보내라|전송|전송해|전송해 줘|끝|끝내|끝이야|이상)[\\s.!?]*$")

    /** 메시지에서 지울 신호 말 ("보내 줘"는 Claude 가 알아서 빼므로 그대로 둠) */
    private val STRIP = Regex("\\s*(전송해 줘|전송해|전송|끝이야|끝내|끝|이상)[\\s.!?]*$")

    fun isEnd(spoken: String) = END.containsMatchIn(spoken.trim())

    fun strip(spoken: String) = spoken.trim().replace(STRIP, "").trim()
}
