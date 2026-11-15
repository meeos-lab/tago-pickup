package ai.meeos.tago.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 비컨 차종(구급차·소방차·경찰차, 2026-11-13): 문구만 다르고 진로 양보 판단·우선순위는 같다. 기본·생략·모르는 값은 ambulance, 기존 문장 그대로.
 *  PC 허브의 test_hub_vtype.py 와 짝(두 엔진 대조는 check_link_parity.py 의 run_vtype). */
class LinkHubVtypeTest {
    private lateinit var hub: LinkHub

    @Before fun load() { hub = LinkHub(MeeosEngineTest.server("link/gyeongbu_route.json")) }

    private fun post(act: String, b: JSONObject) = hub.handle("POST", listOf("api", "road", act), emptyMap(), b)

    /** 내 차 앞쪽 1 km 에서 비컨 차가 다가오는 장면: (SIREN 위험, 전체 보기) */
    private fun scenario(vtype: String?, lang: String): Pair<JSONObject, JSONObject> {
        hub = LinkHub(MeeosEngineTest.server("link/gyeongbu_route.json"))
        val me = post("join", JSONObject().put("name", "A").put("t", 1).put("lang", lang)).second.getString("me")
        val jb = JSONObject().put("kind", "ev").put("token", hub.evToken).put("t", 1).put("lang", lang)
        if (vtype != null) jb.put("vtype", vtype)
        val ev = post("join", jb).second.getString("me")
        var es = 19000.0; var ms = 20000.0
        for (k in 1..5) {
            es += 30; ms += 20
            post("state", JSONObject().put("me", me).put("t", 1 + k).put("s", ms).put("speed", 20))
            post("state", JSONObject().put("me", ev).put("t", 1 + k).put("s", es).put("speed", 30).put("on", true).put("dest_s", 24000))
        }
        val view = hub.handle("GET", listOf("api", "road"), mapOf("me" to me, "t" to "6"), JSONObject()).second
        val hz = view.getJSONArray("hazards")
        val siren = (0 until hz.length()).map { hz.getJSONObject(it) }.first { it.getString("type") == "SIREN" }
        return siren to view
    }

    /** 차종·문구·무작위 차 id 를 뺀 보기 문자열 — 판단·우선순위가 같으면 차종과 무관하게 같다 */
    private fun stripped(view: JSONObject): String {
        val v = JSONObject(view.toString()); v.remove("me")
        val hz = v.getJSONArray("hazards")
        for (i in 0 until hz.length()) hz.getJSONObject(i).apply { remove("vtype"); remove("label"); remove("say") }
        return v.toString()
    }

    @Test fun wordingPerTypeAndLanguage() {
        val words = mapOf("ambulance" to ("구급차" to "Ambulance"), "fire" to ("소방차" to "Fire truck"), "police" to ("경찰차" to "Police car"))
        for ((vt, w) in words) {
            val ko = scenario(vt, "ko").first
            assertEquals(vt, ko.getString("vtype")); assertTrue(ko.getString("say").contains(w.first)); assertEquals("${w.first}(위치 신호)", ko.getString("label"))
            val en = scenario(vt, "en").first
            assertTrue(en.getString("say").startsWith(w.second)); assertEquals("${w.second} (position beacon)", en.getString("label"))
        }
    }

    @Test fun defaultMissingAndUnknownAreAmbulanceWithTheOldSentence() {
        val old = "후방 1.0km 구급차 접근 중(구급차 위치 신호). 약 2분 후 통과 예상. 진로를 양보하세요."
        for (vt in listOf(null, "ambulance", "bogus", "FIRE", "")) {
            val h = scenario(vt, "ko").first
            assertEquals(vt.toString(), "ambulance" to old, h.getString("vtype") to h.getString("say"))
        }
    }

    @Test fun decisionsAndPrioritiesDoNotDependOnType() {
        val base = stripped(scenario("ambulance", "ko").second)
        for (vt in listOf("fire", "police")) assertEquals(vt, base, stripped(scenario(vt, "ko").second))
        assertEquals(stripped(scenario("ambulance", "en").second), stripped(scenario("police", "en").second))
    }
}
