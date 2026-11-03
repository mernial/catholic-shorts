package kr.yunho.talkhelper.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** 가짜 API 서버로 요청 내용과 오류 처리를 확인 (실제 API 는 호출하지 않음) */
class ClaudeTest {
    private var body = ""
    private var beta = ""
    private var status = 200
    private var reply = ok("\"토요일 저녁 7시에 뵙겠습니다.\"", "end_turn")

    private fun ok(text: String, stop: String) =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":${quote(text)}}],"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun server(): HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/") { ex ->
            body = ex.requestBody.readBytes().decodeToString()
            beta = ex.requestHeaders.getFirst("anthropic-beta") ?: ""
            val b = reply.toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(status, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        start()
    }

    private val ctx = listOf(ChatLine("김미카엘", "김미카엘", "토요일 미사 몇 시예요?", 1761600000000))

    @Test
    fun rewriteRequestAndResult() {
        val srv = server()
        try {
            val c = Claude("sk-test", "http://localhost:${srv.address.port}")
            val out = c.rewrite(Mode.POLITE, "어 그거 일곱시라고 해 저녁", "김미카엘", ctx, null, "윤호요셉", "본당 주임신부")
            assertEquals("토요일 저녁 7시에 뵙겠습니다.", out)
            assertTrue(body.contains("\"model\":\"claude-opus-5-5\""))
            assertTrue(body.contains("\"fallbacks\":\"default\""))
            assertTrue(body.contains("\"effort\":\"low\""))
            assertTrue(body.contains("어 그거 일곱시라고 해 저녁"))
            assertTrue(body.contains("토요일 미사 몇 시예요?"))
            assertTrue(beta.contains("server-side-fallback-2026-07-01"))
        } finally { srv.stop(0) }
    }

    @Test
    fun errorsBecomeKoreanMessages() {
        val srv = server()
        try {
            val c = Claude("sk-test", "http://localhost:${srv.address.port}")
            reply = ok("", "refusal")
            assertThrows(HelperException::class.java) { c.summarize("방", ctx, null) }
            status = 401
            reply = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
            val e = assertThrows(HelperException::class.java) { c.summarize("방", ctx, null) }
            assertTrue(e.message!!.contains("API 키"))
        } finally { srv.stop(0) }
    }
}
