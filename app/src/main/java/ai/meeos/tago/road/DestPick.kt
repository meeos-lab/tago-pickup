package ai.meeos.tago.road

import ai.meeos.tago.map.TmapPoi

/**
 * 목적지 고르기(11-14 사용자 흐름 "목적지 검색 > 목적지 지정 > 출발"): 실제 장소 검색 결과 가운데 시연 경로(경부고속도로 하행) 가까이에 있고
 * 출발 자리보다 앞에 있는 곳만 남긴다 — 경로 위 위험·일행 기능이 그 경로에서만 돌기 때문이다. 결과는 실제 값만(지어내지 않는다).
 * geo: 위경도 → (경로 위 자리 m, 경로에서 떨어진 거리 m)
 */
object DestPick {
    /** 경로에서 이만큼 안의 장소만(나들목·휴게소·고속도로 옆 도시 중심) */
    const val MAX_LATERAL_M = 3000.0
    /** 출발 자리보다 이만큼 앞부터(바로 옆은 목적지로 의미가 없다) */
    const val MIN_AHEAD_M = 1000.0
    const val LIMIT = 6

    /** onRoute = 시연 경로 3 km 안·출발 자리보다 앞(고를 수 있다). false = 검색 결과이지만 경로 밖(회색으로 보이고 고를 수 없다, 11-14) */
    data class Cand(val name: String, val addr: String, val lat: Double, val lon: Double, val s: Double, val lateralM: Double, val onRoute: Boolean = true,
                    /** 시연 경로(경부 하행) 가까이·앞인가 — 실제 위치 모드에서 onRoute 를 모두 true 로 바꿔도 이것으로 시연 경로 위 도착 안내를 가른다 */
                    val nearDemo: Boolean = onRoute)

    fun filter(places: List<TmapPoi.Place>, geo: (Double, Double) -> Pair<Double, Double>, fromS: Double, routeLen: Double): List<Cand> =
        places.mapNotNull { p ->
            val (s, lat) = geo(p.lat, p.lon)
            if (!s.isFinite() || !lat.isFinite() || lat > MAX_LATERAL_M || s < fromS + MIN_AHEAD_M || s > routeLen) null
            else Cand(p.name, p.addr, p.lat, p.lon, s, lat)
        }.distinctBy { it.name }.take(LIMIT)   // 검색 API 의 정확도 순서 그대로(경로 위 자리 순으로 다시 정렬하지 않는다)

    /**
     * 결과를 숨기지 않는다(11-14 사용자 "목적지 검색도 만들어주고" — 경로 밖 결과를 지우면 아무 곳이나 쳐서 '결과 없음'만 보였다): 경로 위(고를 수 있음) LIMIT 곳을
     * 먼저, 그다음 경로 밖(onRoute = false) LIMIT 곳을 검색 API 순서대로. 같은 이름은 한 번만
     */
    fun classify(places: List<TmapPoi.Place>, geo: (Double, Double) -> Pair<Double, Double>, fromS: Double, routeLen: Double): List<Cand> {
        val all = places.map { p ->
            val (s, lat) = geo(p.lat, p.lon)
            val on = s.isFinite() && lat.isFinite() && lat <= MAX_LATERAL_M && s >= fromS + MIN_AHEAD_M && s <= routeLen
            Cand(p.name, p.addr, p.lat, p.lon, if (s.isFinite()) s else 0.0, if (lat.isFinite()) lat else Double.MAX_VALUE, on)
        }.distinctBy { it.name }
        return all.filter { it.onRoute }.take(LIMIT) + all.filter { !it.onRoute }.take(LIMIT)
    }
}
