package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 긴급차량 예상 경로 — 넓힌 안내(새 기능 I). 응급실 나들목은 시험용 가짜 한 곳(실제 기관 아님). PC 허브 test_hub_far.py 와 짝 */
class LinkHubFarTest {
    private lateinit var hub: LinkHub
    private val ids = mutableMapOf<String, String>()

    private fun make(exits: Boolean) {
        val route = JSONObject(MeeosEngineTest.server("link/gyeongbu_route.json"))
        route.remove("erExits")                                   // 실제 경로 파일의 응급실 나들목 대신 시험용 가짜 한 곳만(또는 없음)
        if (exits) route.put("erExits", JSONArray().put(JSONObject().put("name", "시험IC").put("nameEn", "Test IC").put("s", 40000.0)))   // 시험용 가짜
        hub = LinkHub(route.toString())
        post("reset", JSONObject().put("clockGuard", false))
        ids.clear()
        for ((name, s0) in listOf("Y1" to 30000.0, "Y2" to 30300.0, "Y3" to 30600.0, "Y4" to 30900.0, "Y5" to 31200.0, "Y6" to 31500.0,
            "FAR" to 36000.0, "NEAR" to 32300.0, "PAST" to 40500.0)) {
            val me = post("join", JSONObject().put("name", name).put("t", 1000).put("lang", "ko")).second.getString("me")
            post("state", JSONObject().put("me", me).put("t", 1000).put("s", s0 - 20).put("speed", 20))
            post("state", JSONObject().put("me", me).put("t", 1001).put("s", s0).put("speed", 20))
            ids[name] = me
        }
    }

    private fun post(act: String, b: JSONObject) = hub.handle("POST", listOf("api", "road", act), emptyMap(), b)
    private fun siren(name: String, t: Int): JSONObject? {
        val hz = hub.handle("GET", listOf("api", "road"), mapOf("me" to ids.getValue(name), "t" to "$t"), JSONObject()).second.getJSONArray("hazards")
        return (0 until hz.length()).map { hz.getJSONObject(it) }.firstOrNull { it.getString("type") == "SIREN" }
    }

    private fun wave(): List<JSONObject?> = (0 until 6).map { k ->
        val t = 1001 + 3 * k
        post("event", JSONObject().put("me", ids.getValue("Y${k + 1}")).put("t", t).put("s", 30000 + 300 * k).put("speed", 12).put("type", "YIELD").put("value", "right"))
        siren("FAR", t)
    }

    @Test fun farOnlyAfterTheWavePersists() {
        make(true)
        assertTrue(wave().all { it == null || !it.has("far") })
        val h = siren("FAR", 1030); assertNotNull(h)
        val far = h!!.getJSONObject("far")
        assertEquals("er", far.getString("kind")); assertEquals("시험IC", far.getString("exit")); assertTrue(far.getString("text").contains("추정"))
        assertFalse(h.has("say"))
    }

    @Test fun nearRangeIsUnchangedAndPastExitGetsNothing() {
        make(true); wave()
        val near = siren("NEAR", 1016); assertNotNull(near); assertTrue(near!!.has("say")); assertFalse(near.has("far"))
        val past = siren("PAST", 1030); assertTrue(past == null || !past.has("far"))
    }

    @Test fun noErExitMeansNoFarAndStoppedWaveWithdraws() {
        make(false); wave()
        val h = siren("FAR", 1030); assertTrue(h == null || !h.has("far"))
        make(true); wave()
        assertTrue(siren("FAR", 1030)!!.has("far"))
        val gone = siren("FAR", 1100); assertTrue(gone == null || !gone.has("far"))
    }

    @Test fun beaconDestinationWins() {
        make(true)
        val ev = post("join", JSONObject().put("kind", "ev").put("token", hub.evToken).put("t", 1000).put("lang", "ko")).second.getString("me")
        var es = 20000.0
        var h: JSONObject? = null
        for (k in 1..6) {
            es += 30
            post("state", JSONObject().put("me", ev).put("t", 1000 + k).put("s", es).put("speed", 30).put("on", true).put("dest_s", 38000))
            h = siren("Y1", 1000 + k)                 // 뒤 약 10 km(> 비컨 5 km, ≤ 15 km), 목적지 38 km 앞
        }
        assertNotNull(h); assertEquals("dest", h!!.getJSONObject("far").getString("kind")); assertFalse(h.has("say"))
    }
}
