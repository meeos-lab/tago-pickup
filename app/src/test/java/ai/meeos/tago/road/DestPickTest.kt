package ai.meeos.tago.road

import ai.meeos.tago.map.TmapPoi
import org.junit.Assert.assertEquals
import org.junit.Test

class DestPickTest {
    // 가짜 경로: 위도 37.0 에서 남쪽으로 내려갈수록 s 가 커지고, 경도 127.0 에서 떨어진 만큼 횡거리(1° ≈ 88 km 로 단순화)
    private val geo = { lat: Double, lon: Double -> (37.0 - lat) * 111_000.0 to kotlin.math.abs(lon - 127.0) * 88_000.0 }

    @Test fun keepsOnlyNearRouteAndAhead() {
        val places = listOf(
            TmapPoi.Place("뒤", 37.0, 127.0, ""),            // s 0 — 출발 자리 뒤
            TmapPoi.Place("가까운 앞", 36.9, 127.01, ""),     // s 11.1 km, 횡 0.9 km
            TmapPoi.Place("먼 옆", 36.8, 127.1, ""),          // 횡 8.8 km — 빠짐
            TmapPoi.Place("더 앞", 36.5, 127.02, ""),         // s 55.5 km
            TmapPoi.Place("가까운 앞", 36.89, 127.0, ""),     // 같은 이름 — 하나만
            TmapPoi.Place("경로 밖 끝", 35.0, 127.0, ""))     // routeLen 넘음
        val r = DestPick.filter(places, geo, fromS = 500.0, routeLen = 100_000.0)
        assertEquals(listOf("가까운 앞", "더 앞"), r.map { it.name })   // 입력(정확도) 순서 유지
        assertEquals(11_100.0, r[0].s, 1.0)
    }

    @Test fun skipsTooCloseToStart() {
        val r = DestPick.filter(listOf(TmapPoi.Place("바로 앞", 36.996, 127.0, "")), geo, fromS = 0.0, routeLen = 100_000.0)
        assertEquals(0, r.size)   // 444 m — MIN_AHEAD_M 안
    }

    @Test fun classifyKeepsOffRouteResultsGrayedAfterOnRoute() {   // 11-14: 결과를 숨기지 않는다 — 경로 위 먼저, 경로 밖은 뒤에 onRoute = false
        val places = listOf(
            TmapPoi.Place("먼 옆", 36.8, 127.1, ""),          // 횡 8.8 km — 경로 밖
            TmapPoi.Place("가까운 앞", 36.9, 127.01, ""),     // 경로 위
            TmapPoi.Place("뒤", 37.0, 127.0, ""),            // 출발 뒤 — 경로 밖
            TmapPoi.Place("가까운 앞", 36.89, 127.0, ""))     // 같은 이름 — 하나만
        val r = DestPick.classify(places, geo, fromS = 500.0, routeLen = 100_000.0)
        assertEquals(listOf("가까운 앞", "먼 옆", "뒤"), r.map { it.name })
        assertEquals(listOf(true, false, false), r.map { it.onRoute })
    }

    @Test fun classifyWithNothingOnRouteStillShowsResults() {
        val r = DestPick.classify(listOf(TmapPoi.Place("서울역", 37.55, 126.97, "")), geo, fromS = 0.0, routeLen = 100_000.0)
        assertEquals(1, r.size); assertEquals(false, r[0].onRoute)
    }
}
