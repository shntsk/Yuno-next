package com.yuno

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveChatTest {
    @Test fun allMessagesFlipsTopChatToEveryMessage() {
        val bytes = byteArrayOf(0x0A, 0x82.toByte(), 0x01, 0x02, 0x08, 0x04)
        val key = Base64.getEncoder().encodeToString(bytes)
        val flipped = Base64.getDecoder().decode(liveChatAllMessages(key).replace("%3D", "="))
        assertEquals(0x01.toByte(), flipped[5])
    }

    @Test fun allMessagesLeavesAnUnknownKey() {
        assertEquals("not-a-chat-key", liveChatAllMessages("not-a-chat-key"))
    }

    @Test fun watchPageCarriesTheChatKeyAndWatchingCount() {
        val page = JSONObject("""{"contents":{"twoColumnWatchNextResults":{
            "conversationBar":{"liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"CHATKEY"}}]}},
            "results":{"results":{"contents":[{"videoPrimaryInfoRenderer":{"viewCount":{"videoViewCountRenderer":{"viewCount":{"simpleText":"12,400 watching"}}}}}]}}
        }}}""")
        assertEquals("CHATKEY", page.liveChatKey())
        assertEquals("12,400 watching", page.watchingLabel())
        assertNull(JSONObject("{}").liveChatKey())
        assertTrue(liveChatSendParams("UCxxxxxxxxxxxxxxxxxxxxxx", "dQw4w9WgXcQ").isNotBlank())
    }
}
