package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 실제 경로로 경로 파일 만들기(2026-11-14, 백로그 B-107) — `server/link/route_graft.py` 와 같은 규칙·같은 결과(단위 시험 RouteGraftTest 가 견준다).
 *
 * 허브(LinkHub)는 경로 파일 하나(route·restAreas·zones·junctions·erExits·evStations)로 만들어진다. 길 찾기(TMAP)로 받은 실제 경로 좌표로
 * 같은 형식의 파일을 만들면 차대차 연결·위험 공유·일행·시연 각본이 그 경로 위에서 돈다.
 * 경로에 딸린 자료는 **지어내지 않는다**: 기준 파일(경부고속도로 하행)의 휴게소·구역·나들목·응급실 출구·충전소 가운데 새 경로 위에 있고
 * (가로 거리 한계 안) 진행 방향이 같은 것만 새 자리(s)로 옮겨 싣는다. 없으면 빈 목록이다.
 */
object RouteGraft {
    const val ON_ROUTE_M = 60.0        // 구역 끝·응급실 출구·나들목: 새 경로에서 이 안에 있어야 '경로 위'
    const val REST_EXTRA_M = 120.0     // 휴게소·충전소: 기준 경로에서 떨어진 거리(offset) + 이만큼 안
    const val SAME_DIR_COS = 0.5       // 진행 방향이 같다 = 사이각 60° 안
    const val CLASS_MIN_M = 300.0      // 도로 구분: 이보다 짧은 토막(고가·램프가 기준 경로를 스친 것)은 앞 토막의 구분을 따른다
    const val ZONE_LEN_TOL = 0.25      // 구역 길이가 기준과 이 비율 넘게 다르면 싣지 않는다(경로가 구역 중간에서 갈라진 것)

    /** 허브와 같은 평면 근사(위도 평균으로 경도 축척)와 같은 투영 */
    private class Geo(pts: List<Pair<Double, Double>>) {
        val lat0 = pts.sumOf { it.first } / pts.size
        val kx = 111320.0 * cos(Math.toRadians(lat0))
        val ky = 110540.0
        val xy = pts.map { it.second * kx to it.first * ky }
        val cum = DoubleArray(xy.size).also { c -> for (i in 1 until xy.size) c[i] = c[i - 1] + hypot(xy[i].first - xy[i - 1].first, xy[i].second - xy[i - 1].second) }
        val length = cum.last()

        /** (경로 위 거리 s, 경로까지 거리 d, 그 구간 번호) */
        fun project(lat: Double, lon: Double): Triple<Double, Double, Int> {
            val px = lon * kx; val py = lat * ky
            var best = Double.POSITIVE_INFINITY; var bs = 0.0; var bi = 0
            for (i in 0 until xy.size - 1) {
                val a = xy[i]; val b = xy[i + 1]
                val dx = b.first - a.first; val dy = b.second - a.second
                val l2 = (dx * dx + dy * dy).takeIf { it != 0.0 } ?: 1e-9
                val t = (((px - a.first) * dx + (py - a.second) * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(px - a.first - t * dx, py - a.second - t * dy)
                if (d < best) { best = d; bs = cum[i] + t * sqrt(l2); bi = i }
            }
            return Triple(bs, best, bi)
        }

        fun segAt(s0: Double): Int {
            val s = s0.coerceIn(0.0, length)
            for (i in 0 until cum.size - 1) if (cum[i + 1] >= s) return i
            return cum.size - 2
        }

        fun pointAt(s0: Double): Pair<Double, Double> {
            val s = s0.coerceIn(0.0, length)
            val i = segAt(s)
            val seg = (cum[i + 1] - cum[i]).takeIf { it != 0.0 } ?: 1e-9
            val t = (s - cum[i]) / seg
            val a = xy[i]; val b = xy[i + 1]
            return (a.second + t * (b.second - a.second)) / ky to (a.first + t * (b.first - a.first)) / kx
        }

        fun dirOf(i: Int): Pair<Double, Double> {
            val a = xy[i]; val b = xy[i + 1]
            val dx = b.first - a.first; val dy = b.second - a.second
            val n = hypot(dx, dy).takeIf { it != 0.0 } ?: 1e-9
            return dx / n to dy / n
        }
    }

    private fun r1(x: Double) = floor(x * 10 + 0.5) / 10
    private fun pts(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }
    private fun copy(o: JSONObject) = JSONObject(o.toString())

    /** 기준 경로 파일 base + 새 좌표(위도, 경도) → 새 경로 파일. 좌표가 둘 미만이면 IllegalArgumentException */
    fun graft(base: JSONObject, points: List<Pair<Double, Double>>, name: String, nameEn: String): JSONObject {
        require(points.size >= 2) { "좌표가 모자란다" }
        val old = Geo(pts(base.getJSONArray("route"))); val new = Geo(points)
        fun sameDir(oldS: Double, newI: Int): Boolean {
            val o = old.dirOf(old.segAt(oldS)); val n = new.dirOf(newI)
            return o.first * n.first + o.second * n.second >= SAME_DIR_COS
        }
        fun movePoint(lat: Double, lon: Double, oldS: Double, limit: Double): Pair<Double, Double>? {
            val (s, d, i) = new.project(lat, lon)
            return if (d <= limit && sameDir(oldS, i)) s to d else null
        }
        fun list(key: String): List<JSONObject> = base.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

        val restMap = HashMap<Double, Double>()
        val rests = list("restAreas").mapNotNull { r ->
            movePoint(r.getDouble("lat"), r.getDouble("lon"), r.getDouble("s"), r.optDouble("offset", 0.0) + REST_EXTRA_M)?.let { (s, d) ->
                restMap[r.getDouble("s")] = r1(s); copy(r).put("s", r1(s)).put("offset", r1(d))
            }
        }.sortedBy { it.getDouble("s") }

        val zones = list("zones").mapNotNull { z ->
            val s0 = z.getDouble("s0"); val s1 = z.getDouble("s1")
            val a = old.pointAt(s0).let { movePoint(it.first, it.second, s0, ON_ROUTE_M) }
            val b = old.pointAt(s1).let { movePoint(it.first, it.second, s1, ON_ROUTE_M) }
            if (a == null || b == null || b.first <= a.first) return@mapNotNull null
            val was = s1 - s0
            if (was > 0 && abs((b.first - a.first) - was) > ZONE_LEN_TOL * was) return@mapNotNull null
            copy(z).put("s0", r1(a.first)).put("s1", r1(b.first))
        }.sortedBy { it.getDouble("s0") }

        val juncs = list("junctions").mapNotNull { j ->
            movePoint(j.getDouble("lat"), j.getDouble("lon"), j.getDouble("s"), ON_ROUTE_M + j.optDouble("offset", 0.0))?.let { (s, d) -> copy(j).put("s", r1(s)).put("offset", r1(d)) }
        }.sortedBy { it.getDouble("s") }
        val juncIds = juncs.map { it.getString("id") }.toSet()

        val exits = list("erExits").mapNotNull { e ->
            if (e.has("junction") && e.getString("junction") !in juncIds) return@mapNotNull null
            val s = e.getDouble("s")
            old.pointAt(s).let { movePoint(it.first, it.second, s, ON_ROUTE_M) }?.let { copy(e).put("s", r1(it.first)) }
        }.sortedBy { it.getDouble("s") }

        val evs = list("evStations").mapNotNull { e ->
            val rest = restMap[e.getDouble("rest")] ?: return@mapNotNull null
            movePoint(e.getDouble("lat"), e.getDouble("lon"), e.getDouble("s"), e.optDouble("offset", 0.0) + REST_EXTRA_M)?.let { (s, d) ->
                copy(e).put("rest", rest).put("s", r1(s)).put("offset", r1(d))
            }
        }.sortedBy { it.getDouble("s") }

        // 단속카메라(11-15): 새 경로 위·같은 방향만. 구간단속(kind 99)은 시작·끝 짝이 모두 옮겨졌을 때만 남긴다(한쪽만 있으면 '끝까지 몇 km'를 지어내게 된다)
        val camsAll = list("cameras").mapNotNull { c ->
            if (!c.has("lat") || !c.has("lon")) return@mapNotNull null
            movePoint(c.getDouble("lat"), c.getDouble("lon"), c.getDouble("s"), ON_ROUTE_M)?.let { (s, _) -> copy(c).put("s", r1(s)) }
        }.sortedBy { it.getDouble("s") }
        val cams = ArrayList<JSONObject>()
        var open: JSONObject? = null
        for (c in camsAll) {
            val role = if (c.optString("kind") == "99") c.optString("role") else ""
            when (role) {
                "start" -> open = c
                "end" -> { open?.let { cams.add(it); cams.add(c) }; open = null }
                else -> cams.add(c)
            }
        }
        cams.sortBy { it.getDouble("s") }

        // 도로 구분(방향 구분이 쓴다 — 고속도로는 맞은편 위험을 빼고 일반 도로는 낮은 단계로): 구간 가운데 점이 기준 경로(고속도로) 위·같은 방향이면
        // motorway, 아니면 road. 이어진 같은 구분은 한 줄로 묶는다. 길 찾기 응답의 도로 종류 값은 쓰지 않는다(값이 오는지 확인 전 — 어림)
        class Run(var s0: Double, var s1: Double, var cls: String)
        var runs = ArrayList<Run>()
        for (i in 0 until new.xy.size - 1) {
            val m = new.pointAt((new.cum[i] + new.cum[i + 1]) / 2)
            val (_, od, oi) = old.project(m.first, m.second)
            val o = old.dirOf(oi); val n = new.dirOf(i)
            val cls = if (od <= ON_ROUTE_M && o.first * n.first + o.second * n.second >= SAME_DIR_COS) "motorway" else "road"
            if (runs.isNotEmpty() && runs.last().cls == cls) runs.last().s1 = new.cum[i + 1] else runs.add(Run(new.cum[i], new.cum[i + 1], cls))
        }
        while (true) {                                     // 짧은 토막을 앞 토막(맨 앞이면 뒤 토막)에 붙인다 — 앞에서부터 하나씩
            val k = runs.indexOfFirst { it.s1 - it.s0 < CLASS_MIN_M }
            if (k < 0 || runs.size < 2) break
            runs[k].cls = if (k > 0) runs[k - 1].cls else runs[k + 1].cls
            val merged = ArrayList<Run>()
            for (r in runs) if (merged.isNotEmpty() && merged.last().cls == r.cls) merged.last().s1 = r.s1 else merged.add(r)
            runs = merged
        }
        val classes = JSONArray()
        for (r in runs) classes.put(JSONObject().put("s0", r1(r.s0)).put("s1", r1(r.s1)).put("cls", r.cls))

        val sources = JSONArray()
        base.optJSONArray("sources")?.let { a -> for (i in 0 until a.length()) sources.put(a.get(i)) }
        sources.put("경로 좌표: TMAP 자동차 경로 안내 API(SK 텔레콤) — 실제 위치에서 목적지까지")
        return JSONObject().put("name", name).put("nameEn", nameEn)
            .put("route", JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.first).put(it.second)) } })
            .put("length", floor(new.length + 0.5).toLong())
            .put("restAreas", JSONArray(rests)).put("zones", JSONArray(zones)).put("junctions", JSONArray(juncs))
            .put("erExits", JSONArray(exits)).put("evStations", JSONArray(evs)).put("cameras", JSONArray(cams)).put("classes", classes).put("sources", sources)
            .put("graft", JSONObject().put("from", base.optString("name")).put("rule", "기준 경로 자료 가운데 새 경로 위·같은 방향인 것만 옮김"))
    }
}
