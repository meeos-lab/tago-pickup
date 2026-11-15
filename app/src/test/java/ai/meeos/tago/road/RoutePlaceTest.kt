package ai.meeos.tago.road

import ai.meeos.tago.engine.MeeosEngineTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 응급 카드의 도로명·방향·나들목·휴게소(C): 실제 시연 경로 파일과 작은 가짜 경로 */
class RoutePlaceTest {
    private fun fake() = RoutePlace(JSONObject().put("name", "A고속도로 하행").put("nameEn", "A Expwy southbound").put("length", 10000.0)
        .put("junctions", JSONArray().put(JSONObject().put("s", 5000).put("name", "중간IC").put("nameEn", "Mid IC")).put(JSONObject().put("s", 1000).put("name", "처음IC").put("nameEn", "First IC")))
        .put("restAreas", JSONArray().put(JSONObject().put("s", 7000).put("name", "큰휴게소").put("nameEn", "Big SA")).put(JSONObject().put("s", "x"))))

    private fun cams() = RoutePlace(JSONObject().put("name", "x").put("cameras", JSONArray()
        .put(JSONObject().put("s", 2000).put("kind", "1").put("limit", 110))
        .put(JSONObject().put("s", 5000).put("kind", "99").put("role", "start").put("limit", 100))
        .put(JSONObject().put("s", 9000).put("kind", "99").put("role", "end").put("limit", 100))
        .put(JSONObject().put("s", 9500).put("kind", "4").put("limit", 0))))   // 제한속도 없는 행은 뺀다

    @Test fun camAlertAheadSectionAndPassed() {
        val p = cams(); assertEquals(3, p.cameras.size)
        assertNull(p.camAlert(500.0))                                    // 1.5 km 앞 — 아직 안 뜸
        p.camAlert(1200.0)!!.let { assertEquals(RoutePlace.CamKind.SPEED, it.kind); assertEquals(110, it.limit); assertEquals(800.0, it.distM, 0.0) }
        assertNull(p.camAlert(2500.0))                                   // 고정식 지남, 구간 시작 2.5 km 앞
        p.camAlert(4300.0)!!.let { assertEquals(RoutePlace.CamKind.SECTION_START, it.kind); assertEquals(700.0, it.distM, 0.0) }
        p.camAlert(6000.0)!!.let { assertEquals(RoutePlace.CamKind.IN_SECTION, it.kind); assertEquals(100, it.limit); assertEquals(3000.0, it.distM, 0.0) }
        assertNull(p.camAlert(9100.0))                                   // 구간 끝 지남
    }

    @Test fun demoRouteFileHasCameras() {
        val f = java.io.File("../server/link/gyeongbu_route.json").takeIf { it.isFile } ?: java.io.File("server/link/gyeongbu_route.json")
        val p = RoutePlace(JSONObject(f.readText()))
        assertEquals(7, p.cameras.size)
        assertEquals(RoutePlace.CamKind.IN_SECTION, p.camAlert(40000.0)!!.kind)   // 376.9 → 365.1 km 구간단속 안
    }

    @Test fun roadNameBothLanguages() { val p = fake(); assertEquals("A고속도로 하행", p.road(false)); assertEquals("A Expwy southbound", p.road(true)) }

    @Test fun nearestJunctionIsSignedAndSorted() {
        val p = fake()
        val (a, d1) = p.nearestJunction(4000.0)!!; assertEquals("중간IC", a.name); assertEquals(1000.0, d1, 0.0)    // 앞 1 km
        val (b, d2) = p.nearestJunction(5600.0)!!; assertEquals("중간IC", b.name); assertEquals(-600.0, d2, 0.0)   // 뒤 600 m(지났다)
        assertEquals("처음IC", p.nearestJunction(-500.0)!!.first.name)
    }

    @Test fun badEntriesAreSkippedAndEmptyListIsNull() {
        val p = fake(); assertEquals(1, p.rests.size)      // s 가 숫자가 아닌 항목은 뺀다
        assertNull(RoutePlace(JSONObject().put("name", "x")).nearestJunction(0.0))
        assertNull(RoutePlace(JSONObject()).nearestRest(0.0))
    }

    @Test fun arrivedNearEndOnly() { val p = fake(); assertFalse(p.arrived(9000.0)); assertTrue(p.arrived(9700.0)); assertFalse(RoutePlace(JSONObject()).arrived(1e9)) }

    @Test fun realRouteFileHasTenJunctionsAndRests() {
        val p = RoutePlace(JSONObject(MeeosEngineTest.server("link/gyeongbu_route.json")))
        assertTrue(p.road(false).contains("경부고속도로")); assertEquals(10, p.junctions.size); assertTrue(p.rests.isNotEmpty())
        assertNotNull(p.nearestJunction(30000.0)); assertTrue(p.length > 50000)
    }
}
