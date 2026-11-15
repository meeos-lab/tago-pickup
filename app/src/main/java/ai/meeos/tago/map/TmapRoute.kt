package ai.meeos.tago.map

import ai.meeos.tago.map.TmapCarMap.LL
import ai.meeos.tago.net.Http
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 티맵 자동차 경로 API(교통 포함): 요청 본문 만들기 → 부르기 → 응답을 혼잡도가 같은 구간으로 자르기.
 * 혼잡도 0 정보없음 · 1 원활 · 2 서행 · 3 지체 · 4 정체 — SDK TrafficLine 은 0~3 이라 4 는 3 으로(공식 샘플과 같다).
 * 지도 그리기(TmapCarMap)와 떼어, 응답 자르기(parse)를 단위 시험한다. fetch 는 네트워크라 io 스레드에서만.
 */
object TmapRoute {
    private const val URL = "https://apis.openapi.sk.com/tmap/routes?version=1"
    private const val CONNECT_MS = 5000
    private const val READ_MS = 8000

    /** 요청 본문: 출발·도착(·경유) WGS84, 교통 포함, 차가 보는 방향 우선(유턴 경로 방지) */
    fun request(from: LL, to: LL, via: LL?, heading: Double): JSONObject = JSONObject().apply {
        put("startX", from.lon); put("startY", from.lat); put("endX", to.lon); put("endY", to.lat)
        put("reqCoordType", "WGS84GEO"); put("resCoordType", "WGS84GEO"); put("trafficInfo", "Y"); put("searchOption", 0)
        put("angle", ((heading % 360 + 360) % 360).roundToInt()); put("directionOption", 1)
        if (via != null) put("passList", "${via.lon},${via.lat}")
    }

    /** 부르고 잘라서 (혼잡도, 점들) 목록. 200 이 아니면 IllegalStateException("HTTP n"), 경로가 없으면 IllegalStateException */
    fun fetch(appKey: String, from: LL, to: LL, via: LL?, heading: Double): List<Pair<Int, List<LL>>> =
        parse(Http.text(URL, "POST", request(from, to, via, heading).toString().toByteArray(),
            mapOf("appKey" to appKey, "Content-Type" to "application/json", "accept" to "application/json"), CONNECT_MS, READ_MS, requireOk = true))

    /** 첫 화면(11-14): 실제 위치 → 목적지 경로 한 줄과 요약(거리 m·시간 s). 방향 조건 없이(서 있는 차), 교통 반영 */
    data class Plain(val points: List<LL>, val distM: Int, val timeS: Int)

    fun fetchPlain(appKey: String, from: LL, to: LL): Plain {
        val body = JSONObject().apply {
            put("startX", from.lon); put("startY", from.lat); put("endX", to.lon); put("endY", to.lat)
            put("reqCoordType", "WGS84GEO"); put("resCoordType", "WGS84GEO"); put("trafficInfo", "Y"); put("searchOption", 0)
        }
        return parsePlain(Http.text(URL, "POST", body.toString().toByteArray(),
            mapOf("appKey" to appKey, "Content-Type" to "application/json", "accept" to "application/json"), CONNECT_MS, READ_MS, requireOk = true))
    }

    /** 응답 → 점들(구간을 이어 붙임, 겹치는 끝점 하나 뺌) + 첫 지물의 totalDistance·totalTime. 없으면 IllegalStateException */
    fun parsePlain(text: String): Plain {
        val segs = parse(text)
        val pts = ArrayList<LL>()
        for ((_, cs) in segs) { if (pts.isNotEmpty() && pts.last() == cs.first()) pts.addAll(cs.drop(1)) else pts.addAll(cs) }
        val props = JSONObject(text).optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
        val d = props?.optInt("totalDistance", -1) ?: -1; val t = props?.optInt("totalTime", -1) ?: -1
        if (d < 0 || t < 0) error("요약 없음")
        return Plain(pts, d, t)
    }

    /** 응답 features 의 LineString 마다 traffic [시작 점, 끝 점, 혼잡도, …] 로 점 사이 칸에 혼잡도를 칠하고, 같은 혼잡도가 이어진 칸끼리 한 구간으로 */
    fun parse(text: String): List<Pair<Int, List<LL>>> {
        val feats = JSONObject(text).optJSONArray("features") ?: error("빈 응답")
        val out = ArrayList<Pair<Int, List<LL>>>()
        for (i in 0 until feats.length()) {
            val g = feats.getJSONObject(i).optJSONObject("geometry") ?: continue
            if (g.optString("type") != "LineString") continue
            val cs = g.getJSONArray("coordinates").let { a -> (0 until a.length()).map { LL(a.getJSONArray(it).getDouble(1), a.getJSONArray(it).getDouble(0)) } }
            if (cs.size < 2) continue
            val code = IntArray(cs.size - 1)
            g.optJSONArray("traffic")?.let { tr -> for (k in 0 until tr.length()) { val r = tr.getJSONArray(k); for (x in r.getInt(0) until minOf(r.getInt(1), code.size)) code[x] = minOf(r.getInt(2), 3) } }
            var start = 0
            for (x in 1..code.size) if (x == code.size || code[x] != code[start]) { out += code[start] to cs.subList(start, x + 1); start = x }
        }
        if (out.isEmpty()) error("경로 없음")
        return out
    }
}
