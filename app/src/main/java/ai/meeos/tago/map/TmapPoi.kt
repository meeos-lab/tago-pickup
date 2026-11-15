package ai.meeos.tago.map

import ai.meeos.tago.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 티맵 장소(POI) 통합 검색: 검색어 → 이름·좌표 목록. 검색 결과는 실제 값만 쓴다(지어내지 않는다).
 * 부르기(fetch, 네트워크 — io 스레드에서만)와 응답 해석(parse, 단위 시험)을 나눈다. 앱키는 머리글로만 보낸다(주소·로그에 남기지 않는다).
 */
object TmapPoi {
    private const val URL = "https://apis.openapi.sk.com/tmap/pois"
    private const val CONNECT_MS = 5000
    private const val READ_MS = 8000
    /** 한 번에 받는 결과 수(검색 API 최대 200 — 화면에는 걸러서 몇 개만) */
    const val COUNT = 30

    data class Place(val name: String, val lat: Double, val lon: Double, val addr: String)

    /** 검색어와 기준점. 정렬은 정확도순(searchtypCd=A — 거리순은 이름만 비슷한 가까운 곳이 먼저 왔다, 11-14 "천안" 실측). 반경 0 = 전국 */
    fun url(keyword: String, centerLat: Double, centerLon: Double): String =
        "$URL?version=1&searchKeyword=${URLEncoder.encode(keyword, "UTF-8")}&searchType=all&page=1&count=$COUNT" +
            "&resCoordType=WGS84GEO&reqCoordType=WGS84GEO&multiPoint=N&searchtypCd=A&radius=0" +
            "&centerLat=$centerLat&centerLon=$centerLon"

    fun fetch(appKey: String, keyword: String, centerLat: Double, centerLon: Double): List<Place> =
        parse(Http.text(url(keyword, centerLat, centerLon), headers = mapOf("appKey" to appKey, "accept" to "application/json"),
            connectMs = CONNECT_MS, readMs = READ_MS, emptyOn204 = true).ifBlank { "{}" })   // 결과가 없으면 204(본문 없음) — 실패가 아니라 0건(11-14 "검색하지 못했습니다"로 잘못 보임)

    /** searchPoiInfo.pois.poi[] → Place. 진입 좌표(front)가 있으면 그것, 없으면 중심(noor) 좌표 */
    fun parse(text: String): List<Place> {
        val pois = JSONObject(text).optJSONObject("searchPoiInfo")?.optJSONObject("pois")?.optJSONArray("poi") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until pois.length()) {
            val p = pois.optJSONObject(i) ?: continue
            val lat = p.optString("frontLat").toDoubleOrNull()?.takeIf { it != 0.0 } ?: p.optString("noorLat").toDoubleOrNull() ?: continue
            val lon = p.optString("frontLon").toDoubleOrNull()?.takeIf { it != 0.0 } ?: p.optString("noorLon").toDoubleOrNull() ?: continue
            val name = p.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
            val addr = listOf("upperAddrName", "middleAddrName", "lowerAddrName").mapNotNull { k -> p.optString(k).trim().takeIf { it.isNotEmpty() } }.joinToString(" ")
            out += Place(name, lat, lon, addr)
        }
        return out
    }
}
