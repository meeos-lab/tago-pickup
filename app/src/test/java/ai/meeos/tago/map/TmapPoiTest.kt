package ai.meeos.tago.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmapPoiTest {
    @Test fun parsesFrontThenCenterCoords() {
        val text = """{"searchPoiInfo":{"pois":{"poi":[
            {"name":"기흥휴게소(부산방향)","frontLat":"37.2401","frontLon":"127.1123","noorLat":"37.2400","noorLon":"127.1120","upperAddrName":"경기","middleAddrName":"용인시 기흥구","lowerAddrName":"공세동"},
            {"name":"좌표만","frontLat":"0.0","frontLon":"0.0","noorLat":"36.5","noorLon":"127.2"},
            {"name":"","noorLat":"36.0","noorLon":"127.0"},
            {"name":"좌표없음"}]}}}"""
        val r = TmapPoi.parse(text)
        assertEquals(2, r.size)
        assertEquals("기흥휴게소(부산방향)", r[0].name)
        assertEquals(37.2401, r[0].lat, 1e-9); assertEquals(127.1123, r[0].lon, 1e-9)
        assertEquals("경기 용인시 기흥구 공세동", r[0].addr)
        assertEquals(36.5, r[1].lat, 1e-9)   // front 가 0 이면 중심 좌표
    }

    @Test fun emptyOrNoResults() {
        assertTrue(TmapPoi.parse("{}").isEmpty())
        assertTrue(TmapPoi.parse("""{"searchPoiInfo":{"pois":{}}}""").isEmpty())
    }

    @Test fun urlEncodesKeywordAndHasNoKey() {
        val u = TmapPoi.url("천안 아산", 37.0, 127.0)
        assertTrue(u.contains("searchKeyword=%EC%B2%9C%EC%95%88+%EC%95%84%EC%82%B0"))
        assertTrue(!u.contains("appKey"))   // 앱키는 머리글로만
    }
}
