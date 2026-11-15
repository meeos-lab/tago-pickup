package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 실제 경로로 경로 파일 만들기(B-107): 고정 자료(TMAP 응답에서 뽑은 판교 → 롯데백화점 동탄점 좌표 377점)로 앱 규칙(RouteGraft)이
 * PC 규칙(server/link/route_graft.py)의 출력(real_route_grafted.json)과 같은지, 그리고 그 파일로 허브가 서는지 본다.
 */
class RouteGraftTest {
    private fun res(name: String) = File("src/test/resources/$name").readText(Charsets.UTF_8)
    private val base get() = JSONObject(MeeosEngineTest.server("link/gyeongbu_route.json"))
    private val pts get() = JSONObject(res("real_route_pangyo_dongtan.json")).getJSONArray("pts").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } }
    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).optString("name") }
    private fun nums(a: JSONArray, key: String) = (0 until a.length()).map { a.getJSONObject(it).getDouble(key) }

    @Test fun sameAsThePythonGraft() {
        val want = JSONObject(res("real_route_grafted.json"))
        val got = RouteGraft.graft(base, pts, "현재 위치 → 롯데백화점 동탄점", "Current location → 롯데백화점 동탄점")
        assertEquals(want.getLong("length"), got.getLong("length"))
        assertEquals(want.getJSONArray("route").length(), got.getJSONArray("route").length())
        for (k in listOf("restAreas", "junctions", "erExits", "evStations")) {
            assertEquals(k, names(want.getJSONArray(k)), names(got.getJSONArray(k)))
            assertEquals(k, nums(want.getJSONArray(k), "s"), nums(got.getJSONArray(k), "s"))
        }
        assertEquals(names(want.getJSONArray("zones")), names(got.getJSONArray("zones")))
        assertEquals(nums(want.getJSONArray("zones"), "s0"), nums(got.getJSONArray("zones"), "s0"))
        assertEquals(nums(want.getJSONArray("zones"), "s1"), nums(got.getJSONArray("zones"), "s1"))
        assertEquals(nums(want.getJSONArray("evStations"), "rest"), nums(got.getJSONArray("evStations"), "rest"))
    }

    @Test fun onlyWhatIsOnTheNewRouteIsCarriedOver() {   // 지어내지 않는다: 경부 하행 자료 가운데 이 경로 위·같은 방향인 것만
        val got = RouteGraft.graft(base, pts, "a", "a")
        assertEquals(listOf("기흥휴게소"), names(got.getJSONArray("restAreas")).map { it.substringBefore(" (") })
        assertTrue(got.getJSONArray("zones").length() in 1..base.getJSONArray("zones").length())
        assertTrue(got.getJSONArray("junctions").length() < base.getJSONArray("junctions").length())
    }

    @Test fun camerasOnTheNewRouteAreCarriedWithPairedSections() {   // 판교 → 동탄 경로는 경부 하행을 타다 빠진다: 앞 고정식은 옮기고, 끝이 안 옮겨진 구간단속은 싣지 않는다
        val got = RouteGraft.graft(base, pts, "a", "a").getJSONArray("cameras")
        val cams = (0 until got.length()).map { got.getJSONObject(it) }
        assertTrue(cams.isNotEmpty())
        assertTrue(cams.all { it.getDouble("s") >= 0 })
        val roles = cams.filter { it.optString("kind") == "99" }.map { it.optString("role") }
        assertEquals(roles.count { it == "start" }, roles.count { it == "end" })
        assertEquals(0, RouteGraft.graft(base, pts.reversed(), "a", "a").getJSONArray("cameras").length())   // 거꾸로 가면 하나도 없다
    }

    @Test fun oppositeDirectionCarriesNothing() {        // 같은 길을 거꾸로 가면(상행) 하행 자료는 하나도 싣지 않는다
        val got = RouteGraft.graft(base, pts.reversed(), "a", "a")
        for (k in listOf("restAreas", "zones", "junctions", "erExits", "evStations")) assertEquals(k, 0, got.getJSONArray(k).length())
    }

    @Test fun farAwayRouteCarriesNothing() {
        val got = RouteGraft.graft(base, listOf(35.10 to 129.03, 35.15 to 129.06, 35.20 to 129.08), "a", "a")
        for (k in listOf("restAreas", "zones", "junctions", "erExits", "evStations")) assertEquals(k, 0, got.getJSONArray(k).length())
    }

    @Test fun tooFewPointsIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { RouteGraft.graft(base, listOf(37.4 to 127.1), "a", "a") }
    }

    @Test fun theHubStandsOnTheGraftedRoute() {           // 그 파일로 허브가 서고, 경로 위 자리·좌표가 맞는다
        val file = RouteGraft.graft(base, pts, "현재 위치 → 롯데백화점 동탄점", "Current location → Lotte")
        val hub = LinkHub(file.toString())
        assertEquals(file.getLong("length").toDouble(), hub.length, 1.0)
        assertEquals(1, hub.rest.size); assertEquals(2, hub.junctions.size)
        val first = pts.first()
        assertEquals(0.0, hub.sAt(first.first, first.second), 1.0)
        assertEquals("현재 위치 → 롯데백화점 동탄점", hub.routeInfo().getString("name"))
    }
}
