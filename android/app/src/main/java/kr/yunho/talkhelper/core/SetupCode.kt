package kr.yunho.talkhelper.core

import java.util.Base64

/**
 * 가족에게 보내는 '설정 코드'. API 키를 카톡으로 넘겨 받는 사람이 직접 입력하지 않아도 되게 함.
 * (암호화가 아니라 단순 포장이므로, 받은 뒤에는 카톡 메시지를 지우도록 안내)
 */
object SetupCode {
    private const val PREFIX = "TALKHELPER1:"
    private val CODE = Regex("TALKHELPER1:([A-Za-z0-9_-]+)")

    fun encode(apiKey: String): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(apiKey.trim().toByteArray(Charsets.UTF_8))

    /** 붙여 넣은 글 어딘가에 설정 코드가 있으면 API 키를 꺼냄 */
    fun decode(text: String?): String? {
        val m = CODE.find(text ?: return null) ?: return null
        return runCatching { String(Base64.getUrlDecoder().decode(m.groupValues[1]), Charsets.UTF_8) }
            .getOrNull()?.trim()?.takeIf { it.startsWith("sk-ant-") }
    }

    /** 카톡으로 보낼 안내문 */
    fun message(apiKey: String) = """
[카톡 도우미 설정]
아래 글을 길게 눌러 '복사'한 뒤 카톡 도우미 앱을 열어 주세요. 설정이 자동으로 됩니다.
설정이 끝나면 이 메시지는 지워 주세요.

${encode(apiKey)}
""".trim()
}
