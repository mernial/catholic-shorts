package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 가짜 API 서버로 요청 내용과 오류 처리를 확인 (실제 API 는 호출하지 않음) */
class ClaudeTest {
    private var body = ""
    private var beta = ""
    private var status = 200
    private var reply = ok("\"토요일 저녁 7시에 뵙겠습니다.\"", "end_turn")

    private fun ok(text: String, stop: String) =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":${quote(text)}}],"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 아주 단순한 가짜 HTTP 서버 (안드로이드 단위 테스트에서도 쓸 수 있는 java.net 만 사용) */
    private inner class FakeServer : AutoCloseable {
        val socket = ServerSocket(0)
        val url = "http://localhost:${socket.localPort}"
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val (k, v) = line.split(":", limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
                        if (k == "content-length") length = v.toInt()
                        if (k == "anthropic-beta") beta = v
                    }
                    // content-length 는 바이트 단위이므로 바이트로 읽음
                    val chars = CharArray(length)
                    var read = 0
                    val sb = StringBuilder()
                    while (sb.toString().toByteArray(Charsets.UTF_8).size < length) {
                        val n = input.read(chars, 0, 1)
                        if (n <= 0) break
                        sb.append(chars, 0, n); read += n
                    }
                    body = sb.toString()
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    val out = c.getOutputStream()
                    out.write(("HTTP/1.1 $status X\r\ncontent-type: application/json\r\ncontent-length: ${bytes.size}\r\nconnection: close\r\n\r\n").toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        override fun close() = socket.close()
    }

    private val screen = "김미카엘\n신부님 청년 모임 언제 할까요?"

    @Test
    fun rewriteRequestAndResult() {
        FakeServer().use { srv ->
            val c = Claude("sk-test", srv.url)
            val out = c.rewrite(Mode.BUSINESS, "야 이거 토요일에 하자고 해", screen, "윤호요셉", "본당 주임신부")
            assertEquals("토요일 저녁 7시에 뵙겠습니다.", out)
            assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""))
            assertTrue(!body.contains("fallbacks"))
            assertTrue(body.contains("\"effort\":\"medium\""))
            // 답장은 {"message": ...} 형식으로만 받음
            assertTrue(body.contains("\"json_schema\""))
            assertTrue(body.contains("\"required\":[\"message\"]"))
            assertTrue(body.contains("야 이거 토요일에 하자고 해"))
            assertTrue(body.contains("청년 모임 언제 할까요?"))
            assertTrue(body.contains("<보내는사람>윤호요셉, 본당 주임신부</보내는사람>"))
            // 시스템 지침에만 캐시 표시, 사람마다 다른 정보는 시스템 지침에 없어야 함
            assertTrue(body.contains("\"cache_control\":{\"type\":\"ephemeral\"}"))
            assertTrue(Prompts.system(Mode.BUSINESS) == Prompts.system(Mode.BUSINESS))
            assertTrue(!Prompts.system(Mode.BUSINESS).contains("윤호요셉"))
            assertEquals(c.lastUsage?.input, 10L)
            assertTrue(body.contains("업무 연락"))
            assertTrue(body.contains("해당 건은 토요일에 진행하겠습니다."))
        }
    }

    @Test
    fun rewriteDefaultsToHaikuWithoutFallback() {
        FakeServer().use { srv ->
            val c = Claude("sk-test", srv.url)
            c.rewrite(Mode.POLITE, "알았어", "가".repeat(5000), "", "")
            assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""))
            assertTrue(!body.contains("fallbacks"))
            // 카톡 화면 글은 마지막 1,200자만 보냄
            assertTrue(body.contains("가".repeat(Prompts.SCREEN_CHARS)))
            assertTrue(!body.contains("가".repeat(Prompts.SCREEN_CHARS + 1)))
        }
    }

    @Test
    fun searchUsesHaikuWithoutFallback() {
        FakeServer().use { srv ->
            val c = Claude("sk-test", srv.url)
            reply = ok("토요일입니다.", "end_turn")
            val out = c.answer("성가대 연습 언제?", "김미카엘: 성가대 연습은 토요일", "청년부")
            assertEquals("토요일입니다.", out)
            assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""))
            assertTrue(!body.contains("fallbacks"))
            assertTrue(body.contains("<질문>성가대 연습 언제?</질문>"))
        }
    }

    @Test
    fun errorsBecomeKoreanMessages() {
        FakeServer().use { srv ->
            val c = Claude("sk-test", srv.url)
            reply = ok("", "refusal")
            assertThrows(HelperException::class.java) { c.rewrite(Mode.POLITE, "응", null, "", "") }
            status = 401
            reply = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
            val e = assertThrows(HelperException::class.java) { c.rewrite(Mode.POLITE, "응", null, "", "") }
            assertTrue(e.message!!.contains("API 키"))
        }
    }

    @Test
    fun onlyTheMessageIsSent() {
        assertEquals("얘가 잘못 이해하고 보냈어요 ㅎㅎ", Claude.messageOnly("{\"message\":\"얘가 잘못 이해하고 보냈어요 ㅎㅎ\"}"))
        // 형식을 어겨 생각한 내용이 앞에 붙어 와도 마지막 문단만
        val leaked = "사용자가 말한 뜻: 잘못 이해하고 보냈다는 것.\n\nHmm, the user wants this. Better rewrite faithfully:\n\n얘가 잘못 이해하고 보냈어요 ㅎㅎ"
        assertEquals("얘가 잘못 이해하고 보냈어요 ㅎㅎ", Claude.messageOnly(leaked))
        assertEquals("네, 알겠어요.", Claude.messageOnly("네, 알겠어요."))
    }
}
