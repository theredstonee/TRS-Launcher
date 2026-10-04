package dev.theredstonee.trs.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadTest {
    private val ok = """{"v":1,"id":"mfz2k1a3b4c.1842","type":"chat_message","category":"chat","title":"Alice‮",
        "body":"Neue\n  Nachricht","target":"/chat/c0123456789abcdef0123","collapse":"chat:c0123456789abcdef0123",
        "at":"2026-10-03T12:00:00.000Z","extra":true}"""

    @Test
    fun parsesAndCleansAPayload() {
        val p = Payload.parse(ok)!!
        assertEquals("Alice", p.title)
        assertEquals("Neue Nachricht", p.body)
        assertEquals("chat", p.category)
        assertEquals("chat:c0123456789abcdef0123", p.collapse)
        assertEquals("trs-launcher://notify/chat/c0123456789abcdef0123", p.link())
    }

    @Test
    fun rejectsUnknownVersionsAndBadRoutes() {
        assertNull(Payload.parse(ok.replace("\"v\":1", "\"v\":2")))
        assertNull(Payload.parse(ok.replace("/chat/c0123456789abcdef0123\"", "javascript:alert(1)\"")))
        assertNull(Payload.parse(ok.replace("/chat/c0123456789abcdef0123\"", "/chat/../../x\"")))
        assertNull(Payload.parse(ok.replace("\"category\":\"chat\"", "\"category\":\"Chat!\"")))
        assertNull(Payload.parse(ok.replace("\"title\":\"Alice‮\"", "\"title\":\"\"")))
        assertNull(Payload.parse("kein json"))
        assertNull(Payload.parse("{" + "\"x\":1,".repeat(2000) + "\"v\":1}"))
    }

    @Test
    fun dropsBadCollapseKeysAndShortensTexts() {
        val p = Payload.parse(ok.replace("chat:c0123456789abcdef0123\"", "x y\"").replace("Neue\\n  Nachricht", "a".repeat(500)))!!
        assertNull(p.collapse)
        assertEquals(200, p.body.length)
    }
}
