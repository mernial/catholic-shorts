package kr.yunho.talkhelper.core

/** '듣고 확인' 보내기에서, 다듬은 문장을 읽어 드린 뒤 하신 대답 */
enum class VoiceAnswer {
    SEND, AGAIN, CANCEL, UNKNOWN;

    companion object {
        private val CANCEL_WORDS = Regex("(보내지\\s*마|안\\s*보내|보내지\\s*말|아니|아뇨|취소|그만|하지\\s*마|됐어요?\\s*안)")
        private val AGAIN_WORDS = Regex("(다시|새로|고쳐)")
        private val SEND_WORDS = Regex("(보내|전송|응|어\\s*그래|그래|네|예|좋아|좋습니다|맞아|맞습니다|오케이|오케|ok|okay|됐어|그렇게\\s*해)", RegexOption.IGNORE_CASE)

        fun of(said: String): VoiceAnswer {
            val s = said.trim()
            return when {
                s.isEmpty() -> UNKNOWN
                CANCEL_WORDS.containsMatchIn(s) -> CANCEL
                AGAIN_WORDS.containsMatchIn(s) -> AGAIN
                SEND_WORDS.containsMatchIn(s) -> SEND
                else -> UNKNOWN
            }
        }
    }
}
