package ai.meeos.tago.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 앱 내장 서버가 허브 응답에 붙이는 헤더(RFC 9457 `application/problem+json`, 429 `Retry-After`) — PC 서버 response_headers 와 같다(B-67) */
class LinkHubHeadersTest {
    @Test fun problemErrorIsProblemJson() {
        val o = LinkHub.problem(400, JSONObject().put("error", "schema").put("field", "t").put("rule", "number"), "POST", "/api/road/state")
        val h = LinkHub.responseHeaders(o)
        assertEquals(LinkHub.PROBLEM_MEDIA, h["Content-Type"])
        assertFalse("429 가 아니면 Retry-After 없음", h.containsKey("Retry-After"))
    }

    @Test fun lockedAddsRetryAfter() {
        val o = LinkHub.problem(429, JSONObject().put("error", "locked").put("retryAfter", 30), "POST", "/api/link/join")
        val h = LinkHub.responseHeaders(o)
        assertEquals(LinkHub.PROBLEM_MEDIA, h["Content-Type"])
        assertEquals("30", h["Retry-After"])
    }

    @Test fun successStaysPlainJson() {
        val h = LinkHub.responseHeaders(JSONObject().put("ok", true))
        assertEquals("application/json; charset=utf-8", h["Content-Type"])
        assertFalse(h.containsKey("Retry-After"))
    }
}
