package ai.meeos.tago.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 주차 기둥 사진 우편함(새 기능 H): 가족 휴대폰이 올리고 Pleos 차가 한 번 가져간다. PC 허브 test_hub_parkphoto.py 와 짝(두 엔진 대조는 check_link_parity.py 의 run_park) */
class LinkHubParkPhotoTest {
    private lateinit var hub: LinkHub
    private lateinit var gid: String; private lateinit var car: String; private lateinit var ph: String; private lateinit var stranger: String

    private fun post(path: List<String>, b: JSONObject) = hub.handle("POST", path, emptyMap(), b)
    private fun put(b: JSONObject) = post(listOf("api", "link", gid, "parkphoto"), b)
    private fun take(me: String, t: Double = 100.0) = post(listOf("api", "link", gid, "parkphoto", "take"), JSONObject().put("me", me).put("t", t))
    private fun photo(r: Pair<Int, JSONObject>): String? = r.second.opt("photo").let { if (it is JSONObject) it.getString("img") else null }

    @Before fun setup() {
        hub = LinkHub(MeeosEngineTest.server("link/gyeongbu_route.json"))
        post(listOf("api", "road", "reset"), JSONObject().put("clockGuard", false))   // 논리 시각 대본(시계 오차 가드 끔)
        car = post(listOf("api", "road", "join"), JSONObject().put("name", "A").put("t", 100).put("lang", "ko")).second.getString("me")
        val g = post(listOf("api", "link", "group"), JSONObject().put("me", car).put("t", 100).put("name", "A")).second
        gid = g.getString("group")
        ph = post(listOf("api", "link", "join"), JSONObject().put("code", g.getString("code")).put("name", "폰").put("kind", "web").put("t", 100)).second.getString("me")
        stranger = post(listOf("api", "road", "join"), JSONObject().put("name", "X").put("t", 100)).second.getString("me")
    }

    @Test fun roundtripOnce() {
        assertEquals(200, put(JSONObject().put("me", ph).put("img", "QUJD")).first)
        val r = take(car); assertEquals(200, r.first); assertEquals("QUJD", photo(r))
        assertEquals(null, photo(take(car)))                       // 한 번뿐
    }

    @Test fun onlyCarsMayTakeAndOnlyMembers() {
        put(JSONObject().put("me", ph).put("img", "QUJD"))
        assertEquals(403, take(ph).first); assertEquals(404, take(stranger).first)
        assertEquals(404, put(JSONObject().put("me", stranger).put("img", "QUJD")).first)
        assertEquals("QUJD", photo(take(car)))                      // 거부들이 사진을 지우지 않았다
    }

    @Test fun validation() {
        val bad = listOf(JSONObject().put("me", ph), JSONObject().put("me", ph).put("img", 5), JSONObject().put("me", ph).put("img", ""),
            JSONObject().put("me", ph).put("img", "@@@@"), JSONObject().put("me", ph).put("img", "A".repeat(60_001)),
            JSONObject().put("me", ph).put("img", "data:image/jpeg;base64,AAAA"), JSONObject().put("me", ph).put("img", "AAAA\n"))
        for (b in bad) assertEquals(b.toString().take(60), 400, put(b).first)
        assertEquals(null, photo(take(car)))
        assertEquals(200, put(JSONObject().put("me", ph).put("img", "A".repeat(60_000))).first)
    }

    private fun parkOf(viewer: String): Any? {
        val v = hub.handle("GET", listOf("api", "link", gid), mapOf("me" to viewer), JSONObject()).second
        val ms = v.getJSONObject("group").getJSONArray("members")
        for (i in 0 until ms.length()) ms.getJSONObject(i).let { if (it.getString("name") == "A") return it.opt("park") }
        return "missing"
    }
    private fun state(b: JSONObject) = post(listOf("api", "road", "state"), b.put("me", car).put("t", 101).put("s", 1000))

    /** 차가 1 s 상태에 실은 주차 글이 그룹 화면(휴대폰)에 보이고 null 이면 지워진다(PC 허브 ParkText 와 짝) */
    @Test fun parkTextShownThenCleared() {
        state(JSONObject().put("park", "B2 · D-14")); assertEquals("B2 · D-14", parkOf(ph))
        state(JSONObject().put("park", JSONObject.NULL)); assertEquals(JSONObject.NULL, parkOf(ph))
        state(JSONObject().put("park", "B1")); state(JSONObject()); assertEquals("B1", parkOf(ph))   // 키가 없으면 그대로
    }

    @Test fun parkTextSanitized() {
        state(JSONObject().put("park", "  B2~B3 <b>x</b> 37.5,127.1 " + "가".repeat(60)))
        val p = parkOf(ph) as String
        assertTrue(p.length <= LinkHub.PARK_TEXT_MAX); assertFalse('<' in p); assertFalse(',' in p)
        for (bad in listOf<Any>(5, "", "   ", JSONObject().put("lat", 1))) { state(JSONObject().put("park", bad)); assertEquals(bad.toString(), JSONObject.NULL, parkOf(ph)) }
    }

    @Test fun latestWinsAndExpiry() {
        put(JSONObject().put("me", ph).put("img", "QUJD")); put(JSONObject().put("me", ph).put("img", "REVG"))
        assertEquals("REVG", photo(take(car)))
        put(JSONObject().put("me", ph).put("img", "QUJD"))
        assertEquals(null, photo(take(car, 401.0)))                // 5분(300 s) 지나면 없는 것
        put(JSONObject().put("me", ph).put("img", "QUJD").put("t", 500))
        assertEquals("QUJD", photo(take(car, 799.0)))
    }

    @Test fun viewNeverCarriesThePhoto() {
        put(JSONObject().put("me", ph).put("img", "QUJD"))
        for (me in listOf(car, ph)) {
            val v = hub.handle("GET", listOf("api", "link", gid), mapOf("me" to me), JSONObject()).second
            assertFalse(v.toString().contains("QUJD")); assertTrue(v.has("group") || v.length() > 0)
        }
    }
}
