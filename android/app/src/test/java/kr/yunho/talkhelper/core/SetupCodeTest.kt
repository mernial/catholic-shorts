package kr.yunho.talkhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupCodeTest {
    @Test
    fun roundTripInsideMessage() {
        val key = "sk-ant-api03-abcDEF_123-xyz"
        assertEquals(key, SetupCode.decode(SetupCode.message(key)))
        assertEquals(key, SetupCode.decode("앞글 " + SetupCode.encode(key) + " 뒷글"))
    }

    @Test
    fun ignoresOtherText() {
        assertNull(SetupCode.decode("안녕하세요"))
        assertNull(SetupCode.decode(null))
        assertNull(SetupCode.decode("TALKHELPER1:" + java.util.Base64.getUrlEncoder().encodeToString("not-a-key".toByteArray())))
    }
}
