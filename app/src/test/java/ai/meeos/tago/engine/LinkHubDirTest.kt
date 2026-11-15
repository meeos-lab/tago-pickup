package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 방향 구분(11-14, 사용자 "고속도로는 상행과 하행이 있으니까 … 경고 마커 구분"): 차의 방향 = 경로 위 s 의 증감(또는 방위),
 * 고속도로 구간의 맞은편 위험은 보기에서 빼고, 일반 도로 구간은 낮은 단계("맞은편"), 방향이 애매하면 낮은 단계("방향 불확실").
 * PC 허브의 test_hub_dir.py 와 짝(같은 장면·같은 기대).
 */
class LinkHubDirTest {
    private val base = MeeosEngineTest.server("link/gyeongbu_route.json")
    private fun motorway() = LinkHub(base)
    /** 같은 노선을 전 구간 '일반 도로'로 표시한 경로 파일 */
    private fun ordinary(): LinkHub {
        val f = JSONObject(base)
        return LinkHub(f.put("classes", JSONArray().put(JSONObject().put("s0", 0.0).put("s1", 1e9).put("cls", "road"))).toString())
    }

    private fun post(hub: LinkHub, act: String, b: JSONObject) = hub.handle("POST", listOf("api", "road", act), emptyMap(), b)
    private fun view(hub: LinkHub, me: String): JSONObject = hub.handle("GET", listOf("api", "road"), mapOf("me" to me), JSONObject()).second
    private fun world(hub: LinkHub, me: String): JSONObject = hub.handle("GET", listOf("api", "road", "world"), mapOf("me" to me), JSONObject()).second
    private fun hazards(v: JSONObject): List<JSONObject> = v.getJSONArray("hazards").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** 차 하나: t0 에 from, t0+1 에 to 자리(경로 위 거리) — 30 m 움직여 방향이 정해진다 */
    private fun car(hub: LinkHub, name: String, from: Double, to: Double, t0: Double, speed: Double = 25.0): String {
        val me = post(hub, "join", JSONObject().put("name", name).put("t", t0)).second.getString("me")
        post(hub, "state", JSONObject().put("me", me).put("s", from).put("speed", speed).put("t", t0))
        post(hub, "state", JSONObject().put("me", me).put("s", to).put("speed", speed).put("t", t0 + 1))
        return me
    }
    private fun brake(hub: LinkHub, me: String, s: Double, t: Double) =
        post(hub, "event", JSONObject().put("me", me).put("type", "HARD_BRAKE").put("value", "25.0->8.0").put("s", s).put("speed", 8.0).put("t", t))

    /** 내 차는 10 km 자리에서 경로 방향으로, 3 km 앞에서 두 대가 급제동 — 그 두 대의 방향만 다르다 */
    private fun scene(hub: LinkHub, opposite: Boolean): String {
        val me = car(hub, "나", 9970.0, 10000.0, 100.0)
        val (a0, a1) = if (opposite) 13030.0 to 13000.0 else 12970.0 to 13000.0
        val x = car(hub, "X", a0, a1, 100.0)
        val y = car(hub, "Y", a0 + 40, a1 + 40, 100.0)
        brake(hub, x, 13000.0, 102.0); brake(hub, y, 13040.0, 102.0)
        post(hub, "state", JSONObject().put("me", me).put("s", 10030.0).put("speed", 25.0).put("t", 102.0))
        return me
    }

    @Test fun sameDirectionHazardAheadIsAnnouncedAsBefore() {
        val hub = motorway()
        val me = scene(hub, opposite = false)
        val hz = hazards(view(hub, me)).filter { it.getString("type") == "HARD_BRAKE" }
        assertEquals(1, hz.size)
        assertTrue("같은 방향 앞 위험은 말한다", hz[0].has("say"))
        assertFalse(hz[0].has("side"))
        assertEquals(1, world(hub, me).getJSONArray("hazards").length())
        assertEquals(2, world(hub, me).getJSONArray("pulses").length())
    }

    @Test fun oncomingHazardOnMotorwayIsNotShownAtAll() {       // 하행 주행 중 상행 사고 = 경고 0
        val hub = motorway()
        val me = scene(hub, opposite = true)
        val v = view(hub, me)
        assertEquals("고속도로 맞은편 위험은 보기에 없다", 0, hazards(v).size)
        assertTrue("위험도 계산에도 없다", v.isNull("risk") || v.getJSONObject("risk").optInt("level", 0) == 0)
        assertEquals("지도 표지도 없다", 0, world(hub, me).getJSONArray("hazards").length())
        assertEquals("고리도 없다", 0, world(hub, me).getJSONArray("pulses").length())
        assertEquals("맞은편 차는 주변 차 수에 세지 않는다", 0, v.getInt("nearby"))
    }

    @Test fun switchOffKeepsTheOldBehaviour() {
        val hub = motorway().also { it.dirAware = false }
        val me = scene(hub, opposite = true)
        val hz = hazards(view(hub, me))
        assertEquals(1, hz.size)
        assertTrue("스위치를 끄면 예전처럼 말한다", hz[0].has("say"))
        assertFalse(hz[0].has("side"))
        assertEquals(2, view(hub, me).getInt("nearby"))
    }

    @Test fun oncomingHazardOnOrdinaryRoadIsLowTier() {         // 일반 도로 맞은편 = 낮은 단계
        val hub = ordinary()
        val me = scene(hub, opposite = true)
        val hz = hazards(view(hub, me))
        assertEquals(1, hz.size)
        assertEquals("oncoming", hz[0].getString("side"))
        assertFalse("낮은 단계는 말하지 않는다", hz[0].has("say"))
        assertEquals(0, hz[0].getInt("level"))
        assertTrue(hz[0].getString("note").startsWith("맞은편 · 급제동"))
        val wh = world(hub, me).getJSONArray("hazards")
        assertEquals(1, wh.length()); assertEquals("oncoming", wh.getJSONObject(0).getString("side"))
        assertEquals("낮은 단계 보고는 고리를 그리지 않는다", 0, world(hub, me).getJSONArray("pulses").length())
    }

    @Test fun headingDecidesForStoppedCars() {
        val hub = motorway()
        val me = car(hub, "나", 9970.0, 10000.0, 100.0)
        val bearing = hub.bearingAt(13000.0)
        // 한 번도 안 움직인 두 대: 방위가 맞은편(180°) → 고속도로라 안 보임
        for ((n, s) in listOf("X" to 13000.0, "Y" to 13040.0)) {
            val c = post(hub, "join", JSONObject().put("name", n).put("t", 100.0)).second.getString("me")
            post(hub, "state", JSONObject().put("me", c).put("s", s).put("speed", 0.0).put("hdg", (bearing + 180.0) % 360.0).put("t", 101.0))
            brake(hub, c, s, 102.0)
        }
        assertEquals(0, hazards(view(hub, me)).size)
        // 방위가 90° 어긋난 두 대(교차로·램프): 애매 → 낮은 단계 '방향 불확실'
        for ((n, s) in listOf("P" to 15000.0, "Q" to 15040.0)) {
            val c = post(hub, "join", JSONObject().put("name", n).put("t", 100.0)).second.getString("me")
            post(hub, "state", JSONObject().put("me", c).put("s", s).put("speed", 0.0).put("hdg", (bearing + 90.0) % 360.0).put("t", 101.0))
            brake(hub, c, s, 102.0)
        }
        val hz = hazards(view(hub, me))
        assertEquals(1, hz.size)
        assertEquals("unsure", hz[0].getString("side"))
        assertFalse(hz[0].has("say"))
        assertTrue(hz[0].getString("note").startsWith("방향 불확실"))
    }

    @Test fun unknownDirectionIsTreatedAsBefore() {             // 한 번도 안 움직이고 방위도 없는 차: 근거가 없으니 지금 그대로(빼지 않는다)
        val hub = motorway()
        val me = car(hub, "나", 9970.0, 10000.0, 100.0)
        for ((n, s) in listOf("X" to 13000.0, "Y" to 13040.0)) {
            val c = post(hub, "join", JSONObject().put("name", n).put("t", 100.0)).second.getString("me")
            post(hub, "state", JSONObject().put("me", c).put("s", s).put("speed", 0.0).put("t", 101.0))
            brake(hub, c, s, 102.0)
        }
        val hz = hazards(view(hub, me))
        assertEquals(1, hz.size)
        assertTrue(hz[0].has("say")); assertFalse(hz[0].has("side"))
    }

    @Test fun reportsOfDifferentDirectionsAreNotMerged() {
        val hub = motorway()
        val me = car(hub, "나", 9970.0, 10000.0, 100.0)
        val same = car(hub, "S", 12970.0, 13000.0, 100.0)
        val opp = car(hub, "O", 13230.0, 13200.0, 100.0)
        brake(hub, same, 13000.0, 102.0); brake(hub, opp, 13200.0, 102.0)     // 200 m 떨어진 두 보고(합치는 거리 1 km 안)
        val wh = world(hub, me).getJSONArray("hazards")
        assertEquals("내 방향 것만 보인다", 1, wh.length())
        assertEquals("맞은편 보고가 내 쪽 위험의 차 수에 더해지지 않는다", 1, wh.getJSONObject(0).getInt("cars"))
        // 맞은편을 달리는 차에게는 그쪽(자기 방향) 것만
        val wo = world(hub, opp).getJSONArray("hazards")
        assertEquals(1, wo.length())
        assertEquals(1, wo.getJSONObject(0).getInt("cars"))
    }

    @Test fun weatherAndTurnbackStayDirectionFree() {           // 비·되돌아감은 양쪽 모두의 일 — 방향으로 거르지 않는다
        val hub = motorway()
        val me = car(hub, "나", 9970.0, 10000.0, 100.0)
        val x = car(hub, "X", 13030.0, 13000.0, 100.0)
        val y = car(hub, "Y", 13070.0, 13040.0, 100.0)
        for (c in listOf(x, y)) post(hub, "event", JSONObject().put("me", c).put("type", "TURNBACK").put("value", "u").put("s", 13000.0).put("speed", 3.0).put("t", 102.0))
        val hz = hazards(view(hub, me)).filter { it.getString("type") == "BLOCKED" }
        assertEquals(1, hz.size)
        assertNull(hz[0].opt("side"))
    }
}
