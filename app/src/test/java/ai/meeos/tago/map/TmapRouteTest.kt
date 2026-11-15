package ai.meeos.tago.map

import ai.meeos.tago.map.TmapCarMap.LL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 티맵 경로 응답 자르기: 혼잡도가 같은 칸끼리 한 구간, 4(정체)는 3 으로, 점이 없는 구간·Point 는 건너뛴다 */
class TmapRouteTest {
    @Test fun splitsByTrafficCode() {
        // 점 5개(칸 4개): 칸 0~1 원활(1), 칸 2 정체(4 → 3), 칸 3 정보 없음(0)
        val text = """{"features":[
            {"geometry":{"type":"Point","coordinates":[127.0,37.0]}},
            {"geometry":{"type":"LineString","coordinates":[[127.00,37.00],[127.01,37.00],[127.02,37.00],[127.03,37.00],[127.04,37.00]],
                         "traffic":[[0,2,1,0],[2,3,4,0]]}}]}"""
        val out = TmapRoute.parse(text)
        assertEquals(listOf(1, 3, 0), out.map { it.first })
        assertEquals(listOf(LL(37.00, 127.00), LL(37.00, 127.01), LL(37.00, 127.02)), out[0].second)   // 구간 끝점은 다음 구간 시작과 겹친다
        assertEquals(listOf(LL(37.00, 127.02), LL(37.00, 127.03)), out[1].second)
        assertEquals(listOf(LL(37.00, 127.03), LL(37.00, 127.04)), out[2].second)
    }

    @Test fun noRouteIsAnError() {
        assertThrows(IllegalStateException::class.java) { TmapRoute.parse("""{"features":[{"geometry":{"type":"LineString","coordinates":[[127.0,37.0]]}}]}""") }
        assertThrows(IllegalStateException::class.java) { TmapRoute.parse("""{"type":"error"}""") }
    }

    @Test fun requestFacesTheCarHeading() {
        val r = TmapRoute.request(LL(37.5, 127.0), LL(37.6, 127.1), LL(37.55, 127.05), heading = -90.0)
        assertEquals(270, r.getInt("angle"))           // 음수 방위는 0~359 로
        assertEquals("127.05,37.55", r.getString("passList"))
        assertEquals("Y", r.getString("trafficInfo"))
    }

    // 11-14 오후: 첫 화면 실제 경로 — 구간을 이어 붙이고(겹치는 끝점 하나), 첫 지물의 totalDistance·totalTime 을 요약으로
    @Test fun plainJoinsSegmentsAndReadsSummary() {
        val text = """{"features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[127.1,37.4]},"properties":{"totalDistance":24012,"totalTime":2130}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.1,37.4],[127.1,37.3]],"traffic":[]},"properties":{}},
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.1,37.3],[127.0,37.2]],"traffic":[]},"properties":{}}]}"""
        val p = TmapRoute.parsePlain(text)
        assertEquals(listOf(LL(37.4, 127.1), LL(37.3, 127.1), LL(37.2, 127.0)), p.points)
        assertEquals(24012, p.distM); assertEquals(2130, p.timeS)
    }

    @Test fun plainWithoutSummaryIsAnError() {
        val text = """{"features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.1,37.4],[127.1,37.3]]},"properties":{}}]}"""
        assertThrows(IllegalStateException::class.java) { TmapRoute.parsePlain(text) }
    }
}
