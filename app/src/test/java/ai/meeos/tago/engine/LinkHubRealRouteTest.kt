package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 실제 경로 위의 허브(B-107): 고정 자료(판교 → 롯데백화점 동탄점 24.1 km)로 만든 경로 파일에 허브를 세우고,
 * 내 자리 0(출발점)에서 시연 각본 '핵심만'을 돌려 — 경로 밖(시작 전·끝 뒤)에 차가 놓이지 않고, 사고 의심·구급차 안내가 이 경로 위에서 나오는지 본다.
 */
class LinkHubRealRouteTest {
    private lateinit var hub: LinkHub

    @Before fun load() {
        val base = JSONObject(MeeosEngineTest.server("link/gyeongbu_route.json"))
        val pts = JSONObject(File("src/test/resources/real_route_pangyo_dongtan.json").readText(Charsets.UTF_8)).getJSONArray("pts")
            .let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } }
        hub = LinkHub(RouteGraft.graft(base, pts, "현재 위치 → 롯데백화점 동탄점", "Current location → Lotte").toString())
    }

    private fun post(act: String, b: JSONObject) = hub.handle("POST", listOf("api", "road", act), emptyMap(), b)
    private fun world(me: String): List<JSONObject> =
        hub.handle("GET", listOf("api", "road", "world"), mapOf("me" to me), JSONObject()).second.getJSONArray("cars").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun types(v: JSONObject): Set<String> = v.getJSONArray("hazards").let { a: JSONArray -> (0 until a.length()).map { a.getJSONObject(it).getString("type") }.toSet() }

    @Test fun routeIsTheRealOne() {
        val r = hub.handle("GET", listOf("api", "road", "route"), emptyMap(), JSONObject()).second
        assertEquals("현재 위치 → 롯데백화점 동탄점", r.getString("name"))
        assertEquals(377, r.getJSONArray("route").length())
        assertEquals(24.1, r.getDouble("length") / 1000, 0.2)
        assertEquals(1, r.getJSONArray("restAreas").length())
    }

    @Test fun demoRunsOnTheRealRouteAndKeepsCarsOnIt() {
        val t0 = 5000.0
        val me = post("join", JSONObject().put("name", "나").put("t", t0)).second.getString("me")
        post("state", JSONObject().put("me", me).put("s", 0.0).put("speed", 25.0).put("t", t0))
        post("demo", JSONObject().put("me", me).put("t", t0).put("core", true).put("ems", "beacon"))
        var s = 0.0
        var sawIncident = false; var sawSiren = false; var maxCars = 0
        for (k in 1..170) {
            s = (s + 25.0 * LinkHub.DEMO_SCALE * (if (sawIncident && s > 5000 && s < 6300) 0.2 else 1.0)).coerceAtMost(hub.length)   // 정체 구간에서는 느리게
            val v = post("state", JSONObject().put("me", me).put("s", s).put("speed", 25.0).put("t", t0 + k)).second
            val ty = types(v)
            if ("INCIDENT" in ty) sawIncident = true
            if ("SIREN" in ty) sawSiren = true
            val cars = world(me)
            maxCars = maxOf(maxCars, cars.size)
            for (c in cars) if (c.has("s") && !c.isNull("s")) {
                val cs = c.getDouble("s")
                assertTrue("경로 밖에 놓인 차 s=$cs (k=$k)", cs >= 0.0 && cs <= hub.length + 0.5)
            }
        }
        assertTrue("사고 의심 안내가 실제 경로 위에서 나온다", sawIncident)
        assertTrue("구급차 안내가 실제 경로 위에서 나온다", sawSiren)
        assertTrue("시연 차가 놓였다", maxCars >= 8)
    }

    @Test fun carsBehindTheStartAreNotCreated() {         // 출발점(s 0) 뒤 −2.5 km·−1.2 km 의 시연 차는 만들지 않는다 — 경부 시연(s0 18 km)에서는 14대 모두 놓인다
        val me = post("join", JSONObject().put("name", "나").put("t", 1.0)).second.getString("me")
        post("state", JSONObject().put("me", me).put("s", 0.0).put("speed", 25.0).put("t", 1.0))
        post("demo", JSONObject().put("me", me).put("t", 1.0).put("core", true).put("ems", "beacon"))
        val demoCars = world(me).count { !it.optBoolean("me") }
        assertEquals(LinkHub.DEMO_CARS.count { it.second >= 0 && it.second <= hub.length }, demoCars)
        assertEquals(12, demoCars)
    }
}
