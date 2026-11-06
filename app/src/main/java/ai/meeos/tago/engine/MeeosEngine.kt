package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 만남 지점 엔진 — server/meeos_server.py 를 그대로 옮긴 앱 내장판.
 * APK 하나로 시연할 수 있게 지도(daechi_map.json)와 규칙을 앱 안에서 계산한다. 규칙을 바꿀 때는 두 파일을 같이 고친다.
 */
class MeeosEngine(mapJson: String, cctvJson: String?, profileJson: String) {

    /** 지역 프로필(server/regions/<id>.json): 정차 규칙, 시간 한도, 언어, 시연 시나리오 */
    val profile = JSONObject(profileJson)
    val region: String = profile.getString("id")
    val lang: String = profile.getString("lang")
    private fun L(ko: String, en: String) = if (lang == "ko") ko else en

    data class P(val x: Double, val y: Double)

    class Cand(
        val id: String, val name: String, val icon: String, val kind: String, val covered: Boolean,
        val lat: Double, val lon: Double, val curb: JSONArray, val road: String, val onRoute: Boolean,
        val s: Double, val off: Double, val blocked: List<String>, val poi: P, val fine: Int,
        val curbXy: P, val cams: List<Pair<Int, Int>>,   // 100m 안 단속 CCTV (거리 m, cctvRaw 인덱스), 가까운 순
        val pickup: List<String>,                          // 주차만 금지(잠깐 태우기는 됨)인 규칙에 걸린 사유
        val safe: Int, val toilet: Double?,                // 반경 30m 방범 시설 수, 가장 가까운 화장실까지(m)
        val wsnap: Snap, val dsnap: Snap,                  // 도로망 위치: 걷기(POI) / 차(길가 정차점)
    )

    /** 도로 구간 위 한 점: 점까지 거리, 구간 번호, 구간 안 비율 t, 투영점 */
    data class Snap(val d: Double, val seg: Int, val t: Double, val q: P)
    private class Seg(val a: Int, val b: Int, val len: Double, val v: Double, val fwd: Boolean, val bwd: Boolean)
    private data class Reach(val cost: Double, val len: Double, val pts: List<P>)

    private class Rule(val layer: String, val radius: Double, val reason: String, val ban: String)

    private val NEARBY_KINDS = setOf("parking", "toilet", "drivethru", "takeout")   // 근처 장소 종류(소속 검사만 한다)
    // 가는 길 포장 주문(서버 TAKEOUT_PREP·TAKEOUT_STOP과 같음)
    private val TAKEOUT_PREP = mapOf("cafe" to 300, "fast_food" to 420, "convenience" to 60)
    private val TAKEOUT_STOP = 90.0   // 매장 앞에 세우고 받아 오는 시간(초)

    companion object {
        const val CAR_CRUISE = 8.3                                  // m/s, 정차 중 ETA 계산용(30km/h)
        val WALK_SPEED = mapOf("adult" to 1.2, "child" to 1.0, "senior" to 0.7)
        const val MAX_SESSIONS = 100   // 세션 저장 상한 — 넘치면 끝난·오래 조용한 세션부터, 없을 때만 가장 오래된 것부터 지운다(무제한 생성으로 메모리를 채우지 못하게)
        const val IDLE_EVICT_SEC = 30 * 60.0   // 이만큼 조회·조작이 없으면 '오래 조용한' 세션(서버 IDLE_EVICT_SEC 와 같다)
        const val MSG_MAX = 80                 // 기다리는 사람 메시지 길이 상한(코드 포인트, 서버 MSG_MAX 와 같다)
        private val MSG_CTRL = Regex("""[\u0000-\u001f\u007f-\u009f\u2028\u2029]""")              // 원시 문자열: \u 는 정규식이 푼다
        private val MSG_INVIS = Regex("""[\u200b\u200c\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]""")
        private val MSG_SPACES = Regex(" {2,}")
        private val HHMM = Regex("[0-9]{2}:[0-9]{2}")                                      // 시연 시각 형식(서버 HHMM 과 같다)
        /** "hh:mm"(00:00–23:59)인지 — 세션 생성·시각 바꾸기 공용(서버 valid_clock 과 같다) */
        private fun validClock(hhmm: String) = HHMM.matches(hhmm) && hhmm.substring(0, 2).toInt() < 24 && hhmm.substring(3, 5).toInt() < 60
        private val RIDER_DELAY = Regex("""([0-9]+) *분만|give me ([0-9]+) min""", RegexOption.IGNORE_CASE)   // "2분만" → 늦게 나온다

        /** 차에서 소리 내어 읽는 메시지: 제어 문자·줄바꿈은 빈칸으로, 보이지 않는 방향·폭 없는 문자는 빼고 80자까지 (서버 clean_message 와 같다) */
        fun cleanMessage(raw: String): String {
            val t = MSG_SPACES.replace(MSG_INVIS.replace(MSG_CTRL.replace(raw, " "), ""), " ").trim(' ')
            val cut = if (t.codePointCount(0, t.length) > MSG_MAX) t.substring(0, t.offsetByCodePoints(0, MSG_MAX)) else t
            return cut.trimEnd(' ')
        }
        val WALK_WEIGHT = mapOf("adult" to 1.0, "child" to 1.3, "senior" to 2.5)
        const val WALK_DETOUR = 1.25
        const val CROSS_WAIT = 60.0
        const val UTURN_PENALTY = 120.0
        const val LOOP_DETOUR = 100.0
        const val CAM_RANGE = 100.0                          // 고정형 CCTV 촬영 반경 "100m 내외"(강남구)
        const val CAM_STOP = 240                             // 운영 중인 카메라 앞: 5분 단속 전 1분 여유
        const val CAR_HALF = 2.5
        const val SEARCH_RADIUS = 450.0
        // 후보·평가 규칙의 고정값(서버 meeos_server.py 의 같은 이름과 같은 값 — tools/check_engine_consts.py 가 대조한다)
        const val CURB_MAX_D = 40.0                  // POI 에서 가장 가까운 도로가 이보다 멀면 후보에서 뺀다(m)
        const val CURB_SHIFT_MAIN = 9.0              // 길가 정차점: 경로 도로에서 POI 쪽으로 옮기는 거리(m)
        const val CURB_SHIFT_SIDE = 3.0              //   골목
        const val RULE_CAR_HALF_BELOW = 100.0        // 반경이 이보다 작은 규칙에만 차 길이 절반을 더한다(m)
        const val SIGNAL_ON_ROUTE = 30.0             // 경로에서 이 안의 신호를 경로 위 신호로 본다(m)
        const val SIDE_SPEED = 5.6                   // 이면도로·못 갈 때 직선 속도(m/s)
        const val PASSED_BACK = 120.0                // 지나친 곳으로 되돌아오는 거리(m)
        const val TURN_RIGHT_WAIT = 20               // 회전 신호 대기(초): 우회전
        const val TURN_LEFT_WAIT = 45                //   좌회전
        val CAM_HOURS_ARTERIAL = 7.0 to 22.0         // 주정차 단속 CCTV 운영 시간(시): 간선
        val CAM_HOURS_SIDE = 8.0 to 22.0             //   이면
        const val SCHOOL_FINE = 12                   // 어린이 보호구역 과태료(만원) — 가중 시간 밖에는 SCHOOL_FINE_OFF(대치동 값)
        val SCHOOL_FINE_HOURS = 8.0 to 20.0
        const val SCHOOL_FINE_OFF = 4
        const val CROSS_NEAR = 200.0                 // 큰길을 건너는지 보는 폭(m)
        const val NIGHT_SAFE_PTS = 5                 // 밤 방범 시설 가점: 개당
        const val NIGHT_SAFE_MAX = 3                 //   최대 개수
        const val RAIN_WALK = 1.5                    // 비 올 때 걷기 가중
        const val MISMATCH_W = 0.5                   // 비용: 서로 기다림 가중
        const val COVERED_BONUS = 30                 //   지붕 있는 곳 가점(초)
        const val OVER_W = 2.0                       //   정차 한도 초과 가중
        const val SENIOR_NEAR_M = 40.0               // 어르신이 '거의 걷지 않는' 거리(m)
        const val WALK_ONE_MIN_S = 45                // '도보 1분 이내'(초)
        const val NEARBY_TOP = 3                     // 근처 장소·포장 매장 개수
        const val DEFAULT_HHMM = "22:00"             // 시연 시계 기본 시각
        const val ARRIVE_M = 30.0                    // 확정 지점 이 거리 안·
        const val ARRIVE_MS = 0.5                    //   이 속도 아래면 도착
        const val PLACE_NEAR_M = 150.0               // 차 위치를 'OO 근처'로 부르는 거리(m)
    }

    private fun objMap(o: JSONObject?): Map<String, JSONObject> = o?.keys()?.asSequence()?.associateWith { o.getJSONObject(it) } ?: emptyMap()
    private val AVOID: Map<String, Map<String, String>> = objMap(profile.optJSONObject("avoid")).mapValues { (_, v) -> v.keys().asSequence().associateWith { v.getString(it) } }
    private val PREFER: Map<String, Map<String, Pair<Int, String>>> = objMap(profile.optJSONObject("prefer")).mapValues { (_, v) ->
        v.keys().asSequence().associateWith { k -> v.getJSONArray(k).let { it.getInt(0) to it.getString(1) } }
    }
    private val rules = profile.getJSONArray("rules").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Rule(it.getString("layer"), it.getDouble("radius"), it.getString("reason"), it.optString("ban", "stop")) }
    }
    private val FINE = profile.getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associate { it.getString("layer") to it.optInt("fine", 0) } }
    private val BUS_LANES: Map<String, Pair<String, List<Pair<Double, Double>>>> = profile.optJSONObject("busLanes")?.let { o ->
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONArray(k); val sp = v.getJSONArray(1)
            v.getString(0) to (0 until sp.length()).map { sp.getJSONArray(it).let { x -> x.getDouble(0) to x.getDouble(1) } }
        }
    } ?: emptyMap()
    private val STOP_LIMIT = profile.getInt("stopLimit")
    private val PEAK: Pair<Double, Double>? = profile.optJSONArray("peak")?.let { it.getDouble(0) to it.getDouble(1) }
    private val PEAK_STOP = profile.getInt("peakStop")
    private val PICKUP_ONLY = profile.getInt("pickupOnly")
    private val SC = profile.getJSONObject("scenario")
    private val RIDER_S = SC.getDouble("riderS")
    private val RIDER_OFF = SC.getDouble("riderOff")
    private val CAR_BACK = SC.getDouble("carBack")
    private val routeCurbBan: String? = profile.optString("routeCurbBan").takeIf { it.isNotEmpty() && it != "null" }

    val map = JSONObject(mapJson)
    private val lat0 = map.getJSONArray("center").getDouble(0)
    private val lon0 = map.getJSONArray("center").getDouble(1)
    private val kx = cos(Math.toRadians(lat0)) * 111320.0
    val routeName: String = map.getString("routeName")

    fun xy(lat: Double, lon: Double) = P((lon - lon0) * kx, (lat - lat0) * 110540.0)
    fun latlon(p: P) = doubleArrayOf(lat0 + p.y / 110540.0, lon0 + p.x / kx)
    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)
    /** JSON 숫자(불리언·글 아님)이고 유한하면 Double, 아니면 null — 입력 검사용(서버 finite_num 과 같다) */
    private fun finiteNum(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun pts(key: String): List<P> {
        val a = map.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> xy(o.getDouble("lat"), o.getDouble("lon")) } }
    }

    val cctvRaw: JSONArray = cctvJson?.let { JSONArray(it) } ?: JSONArray()
    private val cctv = (0 until cctvRaw.length()).map { cctvRaw.getJSONObject(it).let { o -> xy(o.getDouble("lat"), o.getDouble("lon")) } }
    private val route: List<P> = map.getJSONArray("route").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { c -> xy(c.getDouble(0), c.getDouble(1)) } } }
    private val cum = DoubleArray(route.size).also { for (i in 1 until route.size) it[i] = it[i - 1] + dist(route[i - 1], route[i]) }
    val routeLen = cum.last()
    private val layers = rules.associate { it.layer to pts(it.layer) } + ("signals" to pts("signals"))

    /** 경로 투영: (경로상 거리 s, 부호 있는 수직 거리 off(+왼쪽)) */
    fun project(p: P): Pair<Double, Double> {
        var best = Triple(1e18, 0.0, 0.0)
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y
            val l2 = (dx * dx + dy * dy).takeIf { it != 0.0 } ?: 1e-9
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
            val q = P(a.x + t * dx, a.y + t * dy)
            val d = dist(p, q)
            if (d < best.first) {
                val cross = dx * (p.y - a.y) - dy * (p.x - a.x)
                best = Triple(d, cum[i] + t * sqrt(l2), if (cross > 0) d else -d)
            }
        }
        return best.second to best.third
    }

    fun pointAt(s0: Double): P {
        val s = s0.coerceIn(0.0, routeLen)
        for (i in 0 until cum.size - 1) {
            if (cum[i] <= s && s <= cum[i + 1]) {
                val t = (s - cum[i]) / ((cum[i + 1] - cum[i]).takeIf { it != 0.0 } ?: 1e-9)
                val a = route[i]; val b = route[i + 1]
                return P(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
            }
        }
        return route.last()
    }

    private val signalS = layers.getValue("signals").map { project(it) }.filter { abs(it.second) < SIGNAL_ON_ROUTE }.map { it.first }.sorted()
    private val roads: List<Pair<String, List<P>>> = map.getJSONArray("roads").let { a ->
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i); val c = r.getJSONArray("coords")
            r.optString("name") to (0 until c.length()).map { j -> c.getJSONArray(j).let { xy(it.getDouble(0), it.getDouble(1)) } }
        }
    }

    private fun nearestRoad(p: P): Triple<Double, P?, String?> {
        var best = Triple<Double, P?, String?>(1e18, null, null)
        for ((name, line) in roads) for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y
            val l2 = (dx * dx + dy * dy).takeIf { it != 0.0 } ?: 1e-9
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
            val q = P(a.x + t * dx, a.y + t * dy)
            val d = dist(p, q)
            if (d < best.first) best = Triple(d, q, name)
        }
        return best
    }

    // ------------------------------------------------------------ 도로망 경로 (직선 대신 실제 길, 서버와 같음)
    private val ROAD_SPEED = mapOf("primary" to 11.1, "secondary" to 11.1, "tertiary" to 8.3)
    private val nodeId = HashMap<Pair<Double, Double>, Int>()
    private val nodes = ArrayList<P>()
    private val adjW = ArrayList<MutableList<Pair<Int, Double>>>()
    private val adjD = ArrayList<MutableList<Pair<Int, Double>>>()
    private val segs = ArrayList<Seg>()

    private fun node(lat: Double, lon: Double): Int = nodeId.getOrPut(lat to lon) {
        nodes.add(xy(lat, lon)); adjW.add(mutableListOf()); adjD.add(mutableListOf()); nodes.size - 1
    }

    init {
        val rs = map.getJSONArray("roads")
        for (i in 0 until rs.length()) {
            val r = rs.getJSONObject(i)
            val ow = r.optString("oneway"); val v = ROAD_SPEED[r.getString("highway")] ?: SIDE_SPEED
            val cs = r.getJSONArray("coords")
            val ids = (0 until cs.length()).map { j -> cs.getJSONArray(j).let { node(it.getDouble(0), it.getDouble(1)) } }
            for (k in 0 until ids.size - 1) {
                val a = ids[k]; val b = ids[k + 1]
                if (a == b) continue
                val len = dist(nodes[a], nodes[b])
                val fwd = ow != "-1"; val bwd = ow !in setOf("yes", "1", "true")
                adjW[a].add(b to len); adjW[b].add(a to len)
                if (fwd) adjD[a].add(b to len / v)
                if (bwd) adjD[b].add(a to len / v)
                segs.add(Seg(a, b, len, v, fwd, bwd))
            }
        }
    }

    fun snap(p: P): Snap {
        var best: Snap? = null
        for ((i, sg) in segs.withIndex()) {
            val a = nodes[sg.a]; val b = nodes[sg.b]
            val dx = b.x - a.x; val dy = b.y - a.y
            val l2 = (dx * dx + dy * dy).takeIf { it != 0.0 } ?: 1e-9
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
            val q = P(a.x + t * dx, a.y + t * dy)
            val d = dist(p, q)
            if (best == null || d < best.d) best = Snap(d, i, t, q)
        }
        return best!!
    }

    private fun w(i: Int, drive: Boolean) = segs[i].let { if (drive) it.len / it.v else it.len }

    private fun dijkstra(sn: Snap, drive: Boolean): Pair<DoubleArray, IntArray> {
        val sg = segs[sn.seg]; val ww = w(sn.seg, drive); val t = sn.t
        val adj = if (drive) adjD else adjW
        val dd = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }; val prev = IntArray(nodes.size) { -1 }
        val h = java.util.PriorityQueue<Pair<Double, Int>>(compareBy<Pair<Double, Int>>({ it.first }, { it.second }))
        for ((nd, c, ok) in listOf(Triple(sg.a, t * ww, sg.bwd || !drive), Triple(sg.b, (1 - t) * ww, sg.fwd || !drive))) {
            if (ok && c < dd[nd]) { dd[nd] = c; h.add(c to nd) }
        }
        while (h.isNotEmpty()) {
            val (c, u) = h.poll()!!
            if (c > dd[u]) continue
            for ((x, wx) in adj[u]) {
                val nc = c + wx
                if (nc < dd[x]) { dd[x] = nc; prev[x] = u; h.add(nc to x) }
            }
        }
        return dd to prev
    }

    /** 출발 snap → 도착 snap 비용과 마지막 노드(-2 = 같은 구간에서 바로) */
    private fun arrive(dd: DoubleArray, src: Snap, dst: Snap, drive: Boolean): Pair<Double, Int> {
        val sg = segs[dst.seg]; val ww = w(dst.seg, drive); val t = dst.t
        var best = Double.POSITIVE_INFINITY; var end = -1
        if ((sg.fwd || !drive) && dd[sg.a] + t * ww < best) { best = dd[sg.a] + t * ww; end = sg.a }
        if ((sg.bwd || !drive) && dd[sg.b] + (1 - t) * ww < best) { best = dd[sg.b] + (1 - t) * ww; end = sg.b }
        if (src.seg == dst.seg) {
            val dt = dst.t - src.t
            if ((!drive || (dt >= 0 && sg.fwd) || (dt <= 0 && sg.bwd)) && abs(dt) * ww < best) { best = abs(dt) * ww; end = -2 }
        }
        return best to end
    }

    private fun reach(dd: DoubleArray, prev: IntArray, p0: P, p1: P, sn0: Snap, sn1: Snap, drive: Boolean): Reach {
        val (cost, end0) = arrive(dd, sn0, sn1, drive)
        if (cost == Double.POSITIVE_INFINITY) {
            val l = dist(p0, p1); return Reach(if (drive) l / SIDE_SPEED else l, l, listOf(p0, p1))
        }
        val ns = ArrayList<Int>(); var end = end0
        while (end >= 0) { ns.add(end); end = prev[end] }
        val pts = ArrayList<P>()
        if (!drive) pts.add(p0)
        pts.add(sn0.q); ns.reversed().forEach { pts.add(nodes[it]) }; pts.add(sn1.q)
        if (!drive) pts.add(p1)
        var len = 0.0
        for (k in 0 until pts.size - 1) len += dist(pts[k], pts[k + 1])
        return Reach(cost, len, pts)
    }

    private fun route(p0: P, p1: P, drive: Boolean, sn1: Snap? = null): Reach {
        val sn0 = snap(p0); val s1 = sn1 ?: snap(p1)
        val (dd, prev) = dijkstra(sn0, drive)
        return reach(dd, prev, p0, p1, sn0, s1, drive)
    }

    private val WALK_PATH_MAX = 1.6   // 도로망 걷는 길이 직선의 1.6배를 넘으면 지도에는 직선으로

    /** 아이 위치 → 후보까지 걷는 거리(m): 직선 × 1.25 (서버와 같음) */
    private fun walkMTo(rp: P, c: Cand): Double = dist(rp, c.poi) * WALK_DETOUR

    private fun llArr(ps: List<P>) = JSONArray(ps.map { latlon(it).let { ll -> JSONArray().put(ll[0]).put(ll[1]) } })

    // 근처 장소·방범 레이어. 대치동: 공영주차장·역 화장실·안심귀갓길 시설물 / 해외: OSM 화장실·가로등·감시카메라
    private fun objs(key: String): List<JSONObject> = map.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private val publicParking = objs("publicParking")
    private val toilets = objs("toilets")
    private val driveThru = objs("driveThru")   // 드라이브스루(OSM drive_through=yes) — 음성 주문 목업
    private val safety: List<P> = (if (map.has("safety")) objs("safety") else objs("lamps") + objs("cameras")).map { xy(it.getDouble("lat"), it.getDouble("lon")) }
    private fun ptSn(o: JSONObject) = xy(o.getDouble("lat"), o.getDouble("lon")).let { it to snap(it) }
    private val toiletSn by lazy { toilets.map { ptSn(it) } }
    private val driveThruSn by lazy { driveThru.map { ptSn(it) } }
    private val parkingSn by lazy { publicParking.map { ptSn(it) } }
    private val SAFE_RANGE = 30.0
    private val TOILET_RANGE = 150.0
    private val TOILET_BONUS = 90
    private val RESTROOM_KINDS = setOf("cafe", "fast_food", "fuel")   // 손님 화장실을 쓸 수 있는 곳
    private val NIGHT = listOf(19.0 to 24.0, 0.0 to 6.0)

    // 후보의 길가 정차점과 금지 사유는 세션과 무관하므로 미리 계산
    val cands: List<Cand> = map.getJSONArray("candidates").let { a ->
        (0 until a.length()).mapNotNull { i ->
            val c = a.getJSONObject(i)
            val p = xy(c.getDouble("lat"), c.getDouble("lon"))
            val (d, q, road) = nearestRoad(p)
            if (q == null || d > CURB_MAX_D) return@mapNotNull null
            val k = min(d, if (road == routeName) CURB_SHIFT_MAIN else CURB_SHIFT_SIDE) / (if (d == 0.0) 1.0 else d)
            val curb = if (d > 0) P(q.x + (p.x - q.x) * k, q.y + (p.y - q.y) * k) else q
            val (s, off) = project(curb)
            val blocked = mutableListOf<String>()
            val pickup = mutableListOf<String>()
            var fine = 0
            for (r in rules) {
                val near = layers.getValue(r.layer).minOfOrNull { dist(curb, it) } ?: 1e9
                if (near <= r.radius + (if (r.radius < RULE_CAR_HALF_BELOW) CAR_HALF else 0.0)) {
                    if (r.ban == "stop") {
                        blocked += "${r.reason} (${rnd(near)}m)"
                        fine = max(fine, FINE.getValue(r.layer))
                    } else pickup += "${r.reason} (${rnd(near)}m)"
                }
            }
            val camsNear = cctv.mapIndexedNotNull { j, o -> dist(curb, o).takeIf { it <= CAM_RANGE }?.let { it.roundToInt() to j } }
                .sortedWith(compareBy({ it.first }, { it.second }))
            if (routeCurbBan != null && road == routeName) blocked += routeCurbBan
            if (c.getString("kind") == "academy" && profile.optBoolean("academyRule")) blocked += "학원 정문 앞 — 하원 시간 픽업 차량 밀집"
            val ll = latlon(curb)
            Cand(
                "c$i", c.getString("name"), c.getString("icon"), c.getString("kind"), c.optBoolean("covered"),
                c.getDouble("lat"), c.getDouble("lon"), JSONArray().put(ll[0]).put(ll[1]), road?.ifEmpty { L("골목", "side street") } ?: L("골목", "side street"),
                road == routeName, r1(s), r1(off), blocked, p, fine, curb, camsNear, pickup,
                safety.count { dist(p, it) <= SAFE_RANGE }, toilets.minOfOrNull { dist(p, xy(it.getDouble("lat"), it.getDouble("lon"))) },
                snap(p), snap(curb),
            )
        }
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    /** 정수 반올림(서버 rnd 와 같은 0.5 올림) — Math.floor(x + 0.5).toInt() 를 한 이름으로 */
    private fun rnd(x: Double) = Math.floor(x + 0.5).toInt()
    private fun josa(word: String, withFinal: String, without: String): String {
        val last = word.lastOrNull() ?: return without
        return if (last in '가'..'힣' && (last - '가') % 28 != 0) withFinal else without
    }

    // ------------------------------------------------------------ 세션

    val sessions = LinkedHashMap<String, JSONObject>()
    // 세션 id → 마지막으로 조회·조작된 시각(초). 공개 상태에는 넣지 않는다 (서버 touched 와 같다)
    private val touched = HashMap<String, Double>()

    private fun touch(sid: String) { touched[sid] = System.currentTimeMillis() / 1000.0 }

    /** 세션 하나를 지운다: 끝난 세션(탑승·하차) → 오래 조용한 세션 → 가장 오래 만든 세션 순 — 진행 중인 픽업은 마지막까지 남긴다 (서버 evict_one 과 같다) */
    private fun evictOne() {
        val now = System.currentTimeMillis() / 1000.0
        val victim = sessions.entries.firstOrNull { (sid, s) ->
            val st = s.optString("status")
            st == "boarded" || st == "dropped" || now - (touched[sid] ?: s.optDouble("created", now)) > IDLE_EVICT_SEC
        }?.key ?: sessions.keys.firstOrNull() ?: return
        sessions.remove(victim); touched.remove(victim); evalCache.remove(victim); pathCache.remove(victim)
    }

    // 같은 입력이면 다시 계산하지 않는다(차량 앱·차 지도·휴대폰이 각자 1초마다 묻기 때문). 결과는 매번 계산한 것과 같다
    private val evalCache = HashMap<String, Pair<String, JSONObject?>>()

    private fun evalKey(s: JSONObject): String {
        val r = s.getJSONObject("rider"); val car = s.getJSONObject("car")
        return listOf(r.opt("lat"), r.opt("lon"), carS(s), car.optString("gear"), car.optDouble("speed", 0.0), s.optBoolean("needToilet"),   // 반올림하지 않는다: 반올림 칸 안의 변화로 옛 제안이 남으면 캐시 없는 PC 서버와 갈린다
            car.optString("wiper"), s.optJSONObject("clock")?.toString(), s.getJSONObject("member").optString("type"), s.getJSONObject("member").optString("name"),
            s.optJSONArray("passed")?.toString()).joinToString("|")
    }

    private fun carS(s: JSONObject) = min(routeLen, s.getJSONObject("scenario").getDouble("carStartS") + s.getJSONObject("car").getDouble("progress"))

    private data class CarT(val sec: Double, val uturn: Boolean, val how: String)

    private fun carTime(sess: JSONObject, c: Cand): CarT {
        val cs = carS(sess)
        val car = sess.getJSONObject("car")
        val v = if (car.getString("gear") == "D") max(car.getDouble("speed"), CAR_CRUISE) else CAR_CRUISE
        val ahead = c.s - cs
        val right = c.off < 0
        if (c.onRoute) {
            if (right && ahead >= 0) return CarT(ahead / v, false, L("직진 후 오른쪽 길가 정차", "Straight ahead, stop at the right curb"))
            if (!right && ahead >= 0) {
                val loop = ahead / v + LOOP_DETOUR
                val nxt = signalS.firstOrNull { it > c.s } ?: routeLen
                val uturn = (nxt - cs) / v + UTURN_PENALTY + (nxt - c.s) / v
                return if (loop <= uturn) CarT(loop, false, L("블록을 돌아 반대편 정차(유턴 없음)", "Go around the block to the far side (no U-turn)")) else CarT(uturn, true, L("신호 교차로에서 유턴", "U-turn at the signal"))
            }
            return CarT((-ahead + PASSED_BACK) / v + UTURN_PENALTY * 2, true, L("지나쳐서 되돌아와야 함", "Passed it — must come back around"))
        }
        val turn = if (right) TURN_RIGHT_WAIT else TURN_LEFT_WAIT
        if (ahead >= 0) return CarT(ahead / v + abs(c.off) / v + turn, false, if (right) L("우회전 후 골목 정차", "Turn right into the side street") else L("좌회전 후 골목 정차", "Turn left into the side street"))
        return CarT((-ahead + PASSED_BACK) / v + abs(c.off) / v + UTURN_PENALTY, true, L("지나쳐서 되돌아와야 함", "Passed it — must come back around"))
    }

    private fun clock(sess: JSONObject): Pair<Double, Boolean> {
        val c = sess.optJSONObject("clock") ?: JSONObject().put("hhmm", DEFAULT_HHMM).put("weekday", true)
        val (h, m) = c.getString("hhmm").split(":").map { it.toInt() }
        return h + m / 60.0 to c.optBoolean("weekday", true)
    }

    private fun inHours(h: Double, spans: List<Pair<Double, Double>>) = spans.any { (a, b) -> a <= h && h < b }

    /** 강남구 고정형 CCTV 운영 시간: 간선 07–22시, 이면 08–22시, 심야 운영 카메라는 종일[추정], 주말은 토·공휴일 운영 카메라만 */
    private fun camActive(cam: JSONObject, h: Double, weekday: Boolean): Boolean {
        if (!weekday && !cam.optBoolean("weekend")) return false
        return cam.optBoolean("night") || inHours(h, if (cam.optBoolean("arterial")) listOf(CAM_HOURS_ARTERIAL) else listOf(CAM_HOURS_SIDE))
    }

    private fun stopLimit(sess: JSONObject, c: Cand): Pair<Int, String> {
        val (h, weekday) = clock(sess)
        if (PEAK != null && inHours(h, listOf(PEAK))) return PEAK_STOP to "학원가 하원 집중 단속 시간(21–23시)"
        if (c.pickup.isNotEmpty()) return PICKUP_ONLY to L("잠깐 태우기만 — ${c.pickup[0]}", "Pickup only, no waiting — ${c.pickup[0]}")
        if (cctv.isEmpty()) return STOP_LIMIT to profile.optString("stopWhyDefault", "정차 5분 초과 시 주차(학원가 CCTV 24시간)")
        val on = c.cams.firstOrNull { camActive(cctvRaw.getJSONObject(it.second), h, weekday) }
        if (on != null) return CAM_STOP to "단속 CCTV ${on.first}m 운영 중(${cctvRaw.getJSONObject(on.second).optString("name")}) — 5분 넘으면 단속"
        if (c.cams.isNotEmpty()) return STOP_LIMIT to "단속 CCTV ${c.cams[0].first}m 있지만 지금은 운영 시간 아님 — 정차는 5분까지"
        return STOP_LIMIT to "주변 단속 CCTV 없음 — 정차는 5분까지"
    }

    private fun exObj(c: Cand, straight: Double, reason: String, fine: Int? = null) = JSONObject()
        .put("id", c.id).put("name", c.name).put("icon", c.icon).put("curb", c.curb)
        .put("dist", straight.roundToInt()).put("reason", reason).apply { if (fine != null) put("fine", fine) }

    fun evaluate(sess: JSONObject): JSONObject? {
        val r = sess.getJSONObject("rider")
        if (r.isNull("lat")) return null
        val kind = sess.getJSONObject("member").getString("type")
        val rp = xy(r.getDouble("lat"), r.getDouble("lon"))
        val rOff = project(rp).second
        val wiper = sess.getJSONObject("car").optString("wiper", "OFF")
        val raining = wiper !in setOf("OFF", "OTHER", "", "null")
        val (hour, weekday) = clock(sess)
        val scored = mutableListOf<JSONObject>()
        val excluded = mutableListOf<JSONObject>()
        val passed = passedIds(sess); val cs0 = carS(sess)   // 평가 중 세션은 바뀌지 않는다 — 후보마다 다시 만들지 않는다
        for (c in cands) {
            val straight = dist(rp, c.poi)
            if (straight > SEARCH_RADIUS) continue
            if (passed.contains(c.id)) { excluded += exObj(c, straight, L("방금 서지 못하고 지나침", "Just drove past it — couldn't stop")); continue }
            // 한 번 지나친 뒤에는 차보다 앞에 있는 곳만 고른다(되돌아가려면 블록을 돌아야 하고, 그사이 또 막힐 수 있다)
            if (passed.isNotEmpty() && c.s < cs0) { excluded += exObj(c, straight, L("이미 지나온 곳", "Already behind the car")); continue }
            if (c.blocked.isNotEmpty()) {
                excluded += exObj(c, straight, c.blocked[0], if (c.fine != SCHOOL_FINE || inHours(hour, listOf(SCHOOL_FINE_HOURS))) c.fine else SCHOOL_FINE_OFF)
                continue
            }
            val lane = BUS_LANES[c.road]
            if (lane != null && weekday && inHours(hour, lane.second)) { excluded += exObj(c, straight, lane.first, 4); continue }
            var crossing = c.onRoute && abs(rOff) < CROSS_NEAR && (rOff > 0) != (c.off > 0)
            crossing = crossing || (!c.onRoute && abs(rOff) < CROSS_NEAR && abs(c.off) < CROSS_NEAR && (rOff > 0) != (c.off > 0))
            if (crossing && kind in setOf("child", "senior")) {
                excluded += exObj(c, straight, L("${if (kind == "child") "아이가" else "어르신이"} 큰길($routeName)을 건너야 함",
                    "${if (kind == "child") "Child" else "Senior"} would have to cross $routeName")); continue
            }
            val avoid = AVOID[kind]?.get(c.kind)
            if (avoid != null) { excluded += exObj(c, straight, avoid); continue }
            run {
                // 걷기는 직선 × 1.25 (서버와 같음: 보도·횡단보도·지하통로 데이터가 없어 도로망 걷기는 과대)
                val walk = straight * WALK_DETOUR / WALK_SPEED.getValue(kind) + (if (crossing) CROSS_WAIT else 0.0)
                var (bonus, why) = PREFER[kind]?.get(c.kind) ?: (0 to "")
                if (sess.optBoolean("needToilet")) {
                    if (c.toilet != null && c.toilet <= TOILET_RANGE) { bonus += TOILET_BONUS; why = L("화장실에 들르기 좋은", "next to a restroom") }
                    else if (c.kind in RESTROOM_KINDS) { bonus += TOILET_BONUS; why = L("화장실을 쓸 수 있는", "with a customer restroom") }
                }
                if (inHours(hour, NIGHT)) bonus += NIGHT_SAFE_PTS * min(c.safe, NIGHT_SAFE_MAX)   // 밤에는 방범 시설 있는 곳을 조금 더
                val ct = carTime(sess, c)
                val w = WALK_WEIGHT.getValue(kind) * (if (raining) RAIN_WALK else 1.0)
                val carWait = max(0.0, walk - ct.sec)
                val (limit, limitWhy) = stopLimit(sess, c)
                if (carWait > STOP_LIMIT) {
                    excluded += exObj(c, straight, L("차가 ${(carWait / 60).roundToInt()}분 넘게 서 있어야 함 — 주차로 보고 CCTV 단속",
                        "Car would wait over ${(carWait / 60).roundToInt()} min — that counts as parking"), FINE["busStops"] ?: 0)
                } else {
                    val over = max(0.0, carWait - limit)
                    val cost = ct.sec + w * walk + MISMATCH_W * abs(ct.sec - walk) - (if (raining && c.covered) COVERED_BONUS else 0) - bonus + OVER_W * over
                    scored += JSONObject().put("id", c.id).put("name", c.name).put("icon", c.icon).put("curb", c.curb).put("road", c.road)
                        .put("cost", cost.roundToInt()).put("carSec", ct.sec.roundToInt()).put("walkSec", walk.roundToInt())
                        .put("uturn", ct.uturn).put("crossing", crossing).put("route", ct.how).put("covered", c.covered).put("s", c.s)
                        .put("prefer", why).put("carWait", carWait.roundToInt()).put("stopLimit", limit).put("stopWhy", limitWhy).put("safety", c.safe)
                }
            }
        }
        scored.sortBy { it.getInt("cost") }
        val exArr = JSONArray(excluded)
        if (scored.isEmpty()) return JSONObject().put("best", JSONObject.NULL).put("alt", JSONObject.NULL).put("excluded", exArr)
            .put("reason", L("근처에 정차 가능한 곳이 없습니다", "No legal place to stop nearby"))
        val best = scored[0]
        val (reason, speech) = if (lang == "ko") explain(sess, best, excluded, raining, scored) else explainEn(sess, best, excluded, raining, scored)
        return JSONObject().put("best", best).put("alt", scored.getOrNull(1) ?: JSONObject.NULL).put("excluded", exArr)
            .put("considered", scored.size + excluded.size).put("reason", reason).put("speech", speech).put("raining", raining)
    }

    private fun explain(sess: JSONObject, best: JSONObject, excluded: List<JSONObject>, raining: Boolean, scored: List<JSONObject>): Pair<String, String> {
        val name = sess.getJSONObject("member").getString("name")
        val kind = sess.getJSONObject("member").getString("type")
        val parts = mutableListOf<String>()
        val banned = excluded.filter { e -> cand(e.getString("id"))?.blocked?.firstOrNull() == e.getString("reason") }   // 정차 금지 규칙(blocked)으로 뺀 곳만 센다(서버와 같다) — 문구로 세면 어바인 소화전(§22514)처럼 금지 문구가 없는 규칙을 놓친다
        var lead = ""
        val words = linkedMapOf("소화전" to "소화전과", "횡단보도" to "횡단보도와", "버스정류장" to "버스정류장과", "교차로" to "교차로와")
        val legal = banned.filter { e -> e.getInt("dist") in 20 until 150 && words.keys.any { e.getString("reason").startsWith(it) } }
        legal.minByOrNull { it.getInt("dist") }?.let { e ->
            val k = words.keys.first { e.getString("reason").startsWith(it) }
            val rs = e.getString("reason")
            val gap = if ("(" in rs) rs.substringAfterLast("(").trimEnd(')') else ""
            val en = e.getString("name")
            lead = "${if (e.getInt("dist") < 60) "바로 옆" else "근처"} $en${josa(en, "은", "는")} ${words[k]} $gap 거리라 제외했습니다. "
        }
        if (banned.isNotEmpty()) lead += "정차 금지 ${banned.size}곳 제외. "
        val who = "$name${josa(name, "이가", "가")}"
        val walkM = best.getInt("walkSec") * WALK_SPEED.getValue(kind)
        val closest = scored.minBy { it.getInt("walkSec") }
        val saved = closest.getInt("carSec") - best.getInt("carSec")
        val route = best.getString("route")
        if (kind == "senior" && walkM < SENIOR_NEAR_M) parts += "$who 거의 걷지 않도록 차가 ${if ("블록" in route) "블록을 돌아 " else ""}바로 앞에 서는"
        else if (kind in setOf("child", "senior") && !best.getBoolean("crossing")) parts += "$who 큰길을 건너지 않는"
        else if (best.getBoolean("crossing")) parts += "$who 횡단보도로 건너오는"
        if (raining && best.getBoolean("covered")) parts += "비를 피할 수 있는"
        val prefer = best.optString("prefer")
        if (prefer.isNotEmpty() && !(kind == "senior" && walkM < SENIOR_NEAR_M)) parts += prefer
        if (sess.optBoolean("needToilet") && prefer in setOf("화장실에 들르기 좋은", "화장실을 쓸 수 있는")) { parts.remove(prefer); parts.add(0, prefer) }
        if (kind == "adult" && best !== closest && saved >= 20) parts.add(0, "조금 걷지만 차가 ${if (saved >= 60) "${(saved / 60.0).roundToInt()}분" else "${saved}초"} 빨리 도착하는")
        if (!best.getBoolean("uturn") && "블록" !in route) parts += "유턴 없이 가는"
        val walkTxt = if (best.getInt("walkSec") < WALK_ONE_MIN_S) "도보 1분 이내" else "도보 ${(best.getInt("walkSec") / 60.0).roundToInt()}분"
        excluded.firstOrNull { "버스전용차로" in it.getString("reason") }?.let { lead += "버스전용차로 운영 시간이라 ${it.getString("name")} 쪽 큰길은 제외했습니다. " }
        val tail = when {
            best.getInt("stopLimit") == PEAK_STOP && kind != "senior" -> " 하원 단속 시간이라 ${name}에게 1분 먼저 나오도록 알립니다."
            best.getInt("stopLimit") == PEAK_STOP -> " 야간이라 $name${josa(name, "이", "가")} 밖에서 기다리지 않도록, 차량 도착 직전에 나오시도록 알립니다."
            else -> ""
        }
        val bn = best.getString("name")
        val screen = "$lead${if (parts.isNotEmpty()) parts.take(2).joinToString(", ") + " " else ""}$bn 앞을 제안합니다. " +
            "$name${josa(name, "이는", "는")} $walkTxt, 차량은 ${max(1, (best.getInt("carSec") / 60.0).roundToInt())}분 후 도착합니다.$tail"
        val hook = if (banned.isNotEmpty()) "정차 금지 ${banned.size}곳 제외. " else ""
        var key = parts.firstOrNull() ?: ""
        if (kind == "senior" && walkM < SENIOR_NEAR_M) key = "$who 거의 걷지 않는"
        val speech = "$hook${if (key.isNotEmpty()) "$key " else ""}$bn 앞을 제안합니다."
        return screen to speech
    }

    private fun minsEn(sec: Int) = if (sec < WALK_ONE_MIN_S) "under a minute" else "${(sec / 60.0).roundToInt()} min"

    /** 영어 설명문 (explain()과 같은 판단, Python explain_en과 같은 문장) */
    private fun explainEn(sess: JSONObject, best: JSONObject, excluded: List<JSONObject>, raining: Boolean, scored: List<JSONObject>): Pair<String, String> {
        val name = sess.getJSONObject("member").getString("name")
        val kind = sess.getJSONObject("member").getString("type")
        val banned = excluded.filter { e -> cand(e.getString("id"))?.blocked?.firstOrNull() == e.getString("reason") }   // 정차 금지 규칙(blocked)으로 뺀 곳만 센다(서버와 같다) — 문구로 세면 어바인 소화전(§22514)처럼 금지 문구가 없는 규칙을 놓친다
        val spots = "${banned.size} no-stopping spot${if (banned.size != 1) "s" else ""}"
        val lead = if (banned.isNotEmpty()) "Skipped $spots. " else ""
        val parts = mutableListOf<String>()
        val walkM = best.getInt("walkSec") * WALK_SPEED.getValue(kind)
        val closest = scored.minBy { it.getInt("walkSec") }
        val saved = closest.getInt("carSec") - best.getInt("carSec")
        if (kind == "senior" && walkM < SENIOR_NEAR_M) parts += "$name barely has to walk"
        else if (kind in setOf("child", "senior") && !best.getBoolean("crossing")) parts += "$name doesn't cross $routeName"
        else if (best.getBoolean("crossing")) parts += "$name crosses at the crosswalk"
        if (raining && best.getBoolean("covered")) parts += "out of the rain"
        val prefer = best.optString("prefer")
        if (prefer.isNotEmpty() && !(kind == "senior" && walkM < SENIOR_NEAR_M)) parts += prefer
        if (sess.optBoolean("needToilet") && prefer in setOf("next to a restroom", "with a customer restroom")) { parts.remove(prefer); parts.add(0, prefer) }
        if (kind == "adult" && best !== closest && saved >= 20) parts.add(0, "a short walk saves the car ${minsEn(saved)}")
        if (!best.getBoolean("uturn") && "block" !in best.getString("route")) parts += "no U-turn"
        if (best.getInt("stopLimit") == PICKUP_ONLY) parts += "pickup only — be ready when the car arrives"
        val bn = best.getString("name")
        val screen = "${lead}Meet at $bn: ${parts.take(2).joinToString(", ")}. " +
            "$name walks ${minsEn(best.getInt("walkSec"))}, the car arrives in ${max(1, (best.getInt("carSec") / 60.0).roundToInt())} min."
        val hook = if (banned.isNotEmpty()) "Avoiding $spots, " else ""
        val key = if (parts.isNotEmpty()) " — ${parts[0]}" else ""
        val speech = "${hook}let's meet at $bn$key."
        return screen to speech.replaceFirstChar { it.uppercase() }
    }

    private fun cand(id: String) = cands.firstOrNull { it.id == id }

    private fun logEvent(s: JSONObject, text: String) {
        val ev = s.getJSONArray("events")
        ev.put(JSONObject().put("t", System.currentTimeMillis() / 1000.0).put("text", text))
        while (ev.length() > 30) ev.remove(0)
    }

    private fun confirmedId(s: JSONObject) = if (s.isNull("confirmed")) null else s.getString("confirmed")

    /** 차가 서지 못하고 지나친 만남 지점들 — 이 픽업에서는 다시 고르지 않는다 */
    private fun passedIds(s: JSONObject): Set<String> = s.optJSONArray("passed")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()

    fun refresh(s: JSONObject) {
        val status = s.getString("status")
        if (status == "boarded" || status == "dropped") return
        val conf = confirmedId(s)
        if (conf == null) {
            val key = evalKey(s)
            val sid = s.getString("id")
            val cached = evalCache[sid]
            val prop = if (cached != null && cached.first == key && !s.isNull("proposal")) cached.second else evaluate(s).also { evalCache[sid] = key to it }
            val old = s.optJSONObject("proposal")?.optJSONObject("best")?.optString("id")
            s.put("proposal", prop ?: JSONObject.NULL)
            val newId = prop?.optJSONObject("best")?.optString("id")
            if (newId != null && newId != old) {
                s.put("proposalVersion", s.getInt("proposalVersion") + 1)
                if (status == "waiting_rider") s.put("status", "proposed")
                val alt = prop.optJSONObject("alt")?.optString("name") ?: "-"
                logEvent(s, L("제안: ${prop.getJSONObject("best").getString("name")} (대안: $alt, 제외 ${prop.getJSONArray("excluded").length()}곳)",
                    "Suggested: ${prop.getJSONObject("best").getString("name")} (alt: $alt, ${prop.getJSONArray("excluded").length()} ruled out)"))
            }
            return
        }
        val c = cand(conf) ?: return
        val r = s.getJSONObject("rider")
        if (r.isNull("lat")) return
        var ct = carTime(s, c)
        val walk = walkMTo(xy(r.getDouble("lat"), r.getDouble("lon")), c) / WALK_SPEED.getValue(s.getJSONObject("member").getString("type"))
        if (status == "arrived") ct = ct.copy(sec = 0.0)
        val peak = PEAK != null && inHours(clock(s).first, listOf(PEAK))
        val margin = if (peak) (if (s.getJSONObject("member").getString("type") != "senior") 60 else 0) else 30
        s.put("eta", JSONObject().put("carSec", ct.sec.roundToInt()).put("walkSec", walk.roundToInt()).put("route", ct.how).put("margin", margin))
        if (!s.getBoolean("leaveNow") && ct.sec <= walk + margin) {
            s.put("leaveNow", true)
            logEvent(s, L("${s.getJSONObject("member").getString("name")}에게 '지금 나오세요' 알림", "Told ${s.getJSONObject("member").getString("name")} to head out now"))
        }
        if (status == "confirmed" && abs(carS(s) - c.s) < ARRIVE_M && s.getJSONObject("car").getDouble("speed") < ARRIVE_MS) {
            s.put("status", "arrived")
            logEvent(s, L("차가 ${c.name} 앞에 도착", "Car arrived at ${c.name}"))
        }
    }

    fun scenario(): JSONObject {
        val p = pointAt(RIDER_S); val q = pointAt(RIDER_S + 5)
        val dx = q.x - p.x; val dy = q.y - p.y
        val n = hypot(dx, dy).takeIf { it != 0.0 } ?: 1.0
        val ll = latlon(P(p.x - dy / n * RIDER_OFF, p.y + dx / n * RIDER_OFF))
        return JSONObject()
            .put("riderDefault", JSONObject().put("lat", ll[0]).put("lon", ll[1]).put("name", SC.getString("riderName")))
            .put("carStartS", max(0.0, RIDER_S - CAR_BACK))
    }

    fun presets(): JSONObject {
        val out = JSONObject().put("academy", scenario().getJSONObject("riderDefault"))
        val ps = profile.optJSONArray("presets") ?: JSONArray()
        for (i in 0 until ps.length()) {
            val pr = ps.getJSONObject(i)
            cands.firstOrNull { it.name == pr.getString("cand") }?.let {
                out.put(pr.getString("key"), JSONObject().put("lat", it.lat + pr.getDouble("dlat")).put("lon", it.lon + pr.getDouble("dlon")).put("name", pr.getString("name")))
            }
        }
        return out
    }

    fun public(s: JSONObject): JSONObject {
        val cs = carS(s)
        val ll = latlon(pointAt(cs))
        val a = pointAt(cs + 5); val b = pointAt(cs - 5)
        val out = JSONObject(s.toString())
        out.put("carPos", JSONObject().put("lat", ll[0]).put("lon", ll[1]).put("s", cs).put("heading", Math.toDegrees(atan2(a.x - b.x, a.y - b.y))))
        out.put("maxProgress", routeLen - s.getJSONObject("scenario").getDouble("carStartS"))
        val cp = pointAt(cs)
        val near = cands.minBy { dist(it.curbXy, cp) }
        out.put("carPlace", if (dist(near.curbXy, cp) < PLACE_NEAR_M) L("$routeName ${near.name} 근처", "$routeName near ${near.name}")
            else L("$routeName (대치동)", "$routeName, ${profile.getString("area")}"))
        out.put("region", regionInfo())
        val conf = confirmedId(s)?.let { cand(it) }
        out.put("target", conf?.let {
            JSONObject().put("id", it.id).put("name", it.name).put("curb", it.curb).put("x", it.s - s.getJSONObject("scenario").getDouble("carStartS"))
        } ?: JSONObject.NULL)
        s.optJSONObject("via")?.let { v ->           // 경유지(매장)의 경로상 위치 (서버 viaX와 같음)
            out.put("viaX", r1(project(xy(v.getDouble("lat"), v.getDouble("lon"))).first - s.getJSONObject("scenario").getDouble("carStartS")))
        }
        val bid = s.optJSONObject("proposal")?.optJSONObject("best")?.optString("id")
        val goal = conf ?: bid?.let { cand(it) }
        if (goal != null && s.getString("status") !in setOf("boarded", "dropped")) {
            val ps = paths(s, goal)
            for (k in ps.keys()) out.put(k, ps.get(k))
        }
        return out
    }

    fun mapLayers(): JSONObject {
        val out = JSONObject()
        for (k in listOf("route", "busStops", "crossings", "signals", "hydrants", "schools", "stops", "sources", "center", "routeName")) out.put(k, map.opt(k) ?: JSONArray())
        out.put("rules", profile.getJSONArray("rules"))
        out.put("candidates", JSONArray(cands.map {
            JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon).put("curb", it.curb).put("blocked", JSONArray(it.blocked)).put("kind", it.kind)
        }))
        val roadsArr = map.getJSONArray("roads")
        out.put("busLanes", JSONArray((0 until roadsArr.length()).map { roadsArr.getJSONObject(it) }.filter { BUS_LANES.containsKey(it.optString("name")) }.map { r ->
            val lane = BUS_LANES.getValue(r.getString("name"))
            JSONObject().put("name", r.getString("name")).put("coords", r.getJSONArray("coords")).put("label", lane.first)
                .put("spans", JSONArray(lane.second.map { JSONArray().put(it.first).put(it.second) }))
        }))
        out.put("cctv", cctvRaw)
        out.put("roads", JSONArray((0 until roadsArr.length()).map { roadsArr.getJSONObject(it) }.map { r ->   // 오프라인 바탕 지도용
            JSONObject().put("name", r.optString("name")).put("highway", r.optString("highway")).put("coords", r.getJSONArray("coords"))
        }))
        out.put("peak", PEAK?.let { JSONArray().put(it.first).put(it.second) } ?: JSONObject.NULL)
        out.put("region", regionInfo())
        return out
    }

    fun regionInfo(): JSONObject = JSONObject().put("id", region).put("lang", lang).put("country", profile.getString("country")).put("label", profile.getString("label"))

    // ------------------------------------------------------------ API 동작 (서버와 같은 경로)

    @Synchronized
    fun createSession(body: JSONObject): JSONObject {
        // 요청 본문은 믿지 않는다: member 는 이름(20자)·대상 종류만, clock 은 hhmm·weekday 만, carDesc 는 40자까지 — 그대로 저장하면 세션마다 수십 KB 가 남는다
        val rawMember = body.optJSONObject("member") ?: profile.getJSONObject("member")
        val member = JSONObject()
            .put("name", rawMember.optString("name").take(20).ifEmpty { profile.getJSONObject("member").getString("name") })
            .put("type", rawMember.optString("type").takeIf { it in WALK_SPEED.keys } ?: profile.getJSONObject("member").getString("type"))
        val rawClock = body.optJSONObject("clock")
        val clock = JSONObject().put("hhmm", rawClock?.optString("hhmm")?.takeIf { validClock(it) } ?: DEFAULT_HHMM).put("weekday", rawClock?.optBoolean("weekday", true) ?: true)
        // 세션은 100개까지만 둔다(시연 세션은 몇 개면 된다) — 넘치면 끝난·조용한 세션부터 캐시와 함께 지운다(evictOne)
        while (sessions.size >= MAX_SESSIONS) evictOne()
        // 세션 id 는 링크(/m/<id>)의 열쇠다 — 짐작할 수 없게 64비트 난수(16자리 16진수). PC 서버(meeos_server.py)와 같은 길이
        val sid = java.math.BigInteger(64, java.security.SecureRandom()).toString(16).padStart(16, '0')
        val s = JSONObject()
            .put("id", sid).put("created", System.currentTimeMillis() / 1000.0).put("member", member).put("scenario", scenario())
            .put("clock", clock)
            .put("car", JSONObject().put("progress", 0.0).put("speed", 0.0).put("gear", "P").put("wiper", "OFF").put("desc", body.optString("carDesc", profile.getString("carDesc")).take(40)))
            .put("rider", JSONObject().put("lat", JSONObject.NULL).put("lon", JSONObject.NULL).put("name", member.getString("name")))
            .put("proposal", JSONObject.NULL).put("proposalVersion", 0).put("confirmed", JSONObject.NULL).put("confirmedBy", JSONObject.NULL)
            .put("leaveNow", false).put("eta", JSONObject.NULL).put("status", "waiting_rider").put("boarding", JSONObject.NULL).put("events", JSONArray()).put("messages", JSONArray()).put("emergency", JSONObject.NULL)
            .put("needToilet", false).put("via", JSONObject.NULL).put("passed", JSONArray()).put("replan", JSONObject.NULL)
        sessions[sid] = s
        touch(sid)
        val wait = s.getJSONObject("scenario").getJSONObject("riderDefault").getString("name")
        logEvent(s, L("픽업 시작: ${member.getString("name")}(${member.getString("type")}) · 대기 위치 $wait",
            "Pickup started: ${member.getString("name")} (${member.getString("type")}) · waiting at $wait"))
        return JSONObject().put("id", sid).put("link", "/m/$sid")
    }

    @Synchronized
    fun get(sid: String): JSONObject? = sessions[sid]?.let { touch(sid); refresh(it); public(it) }

    /** OSM에 이름이 없는 주차장('주차장 입구')은 길 이름으로 부른다 (서버 place_name과 같음) */
    private fun placeName(c: Cand) = when {
        c.name == "주차장 입구" || c.name == "주차장" -> if (c.road == "골목") "골목 안 주차장" else "${c.road} 주차장"
        c.name == "Parking lot" -> "Parking lot on ${c.road}"
        else -> c.name
    }

    /** 운영 시간 [시작, 끝] 안인지. 00:00–00:00은 운영 안 함 (서버 open_now와 같음) */
    private fun openNow(span: JSONArray?, h: Double): Boolean {
        val a = span?.optString(0)?.takeIf { it.length == 5 } ?: return false
        val b = span.optString(1).takeIf { it.length == 5 } ?: return false
        if (a == b) return false
        val ha = a.take(2).toInt() + a.takeLast(2).toInt() / 60.0
        val hb = b.take(2).toInt() + b.takeLast(2).toInt() / 60.0
        return if (ha < hb) h in ha..hb else (h >= ha || h <= hb)
    }

    /** 근처 장소(서버 nearby()와 같은 규칙) — 거리·시간은 실제 길 */
    @Synchronized
    fun nearby(sid: String, kind: String, from: String? = null): JSONObject? {
        val s = sessions[sid] ?: return null
        if (kind !in NEARBY_KINDS) throw org.json.JSONException("unknown kind")   // 서버와 같은 400(전에는 404)
        touch(sid)
        val conf = confirmedId(s)?.let { cand(it) }
        val bestId = s.optJSONObject("proposal")?.optJSONObject("best")?.optString("id")?.takeIf { cand(it) != null }
        val car = pointAt(carS(s))
        val r = s.getJSONObject("rider")
        val rider = if (r.isNull("lat")) null else xy(r.getDouble("lat"), r.getDouble("lon"))
        var origin = from ?: if (kind == "toilet" || kind == "drivethru") "car" else "target"
        val ref = when {
            origin == "rider" && rider != null -> rider
            origin == "car" || (conf == null && bestId == null) -> { origin = "car"; car }
            conf != null -> { origin = "target"; conf.curbXy }
            else -> { origin = "proposal"; cand(bestId!!)!!.curbXy }
        }
        val drive = origin != "rider"
        val refSn = snap(ref)
        val (dd, prev) = if (drive) dijkstra(refSn, true) else (DoubleArray(0) to IntArray(0))
        // 차는 도로망, 걷기는 직선 × 1.25 (서버 net과 같음)
        fun net(pt: P, sn: Snap): Reach = if (!drive) (dist(ref, pt) * WALK_DETOUR).let { Reach(it, it, emptyList()) } else reach(dd, prev, ref, pt, refSn, sn, true)
        val (h, weekday) = clock(s)
        val items = JSONArray()
        when {
            kind == "toilet" -> toilets.indices.map { i -> net(toiletSn[i].first, toiletSn[i].second).len to toilets[i] }.sortedBy { it.first }.take(NEARBY_TOP).forEach { (len, t) ->
                items.put(JSONObject().put("name", t.getString("name")).put("lat", t.getDouble("lat")).put("lon", t.getDouble("lon"))
                    .put("curb", JSONArray().put(t.getDouble("lat")).put(t.getDouble("lon"))).put("dist", rnd(len))
                    .put("inside", t.optBoolean("inside")).put("hours", t.optString("hours")).put("exit", t.optString("exit")).put("detail", t.optString("detail")))
            }
            kind == "takeout" -> {
                val tgt = conf ?: bestId?.let { cand(it) }
                if (tgt != null) {
                    origin = if (conf != null) "target" else "proposal"
                    val carSn = snap(car)
                    val (cd, cp) = dijkstra(carSn, true)
                    val direct = arrive(cd, carSn, tgt.dsnap, true).first
                    val walk = if (rider != null) walkMTo(rider, tgt) / WALK_SPEED.getValue(s.getJSONObject("member").getString("type")) else 0.0
                    val slack = max(0.0, walk + s.optInt("riderDelay", 0) - direct)
                    val cs = carS(s)
                    data class Opt(val late: Int, val extra: Int, val c: Cand, val toStore: Double, val wait: Double)
                    val opts = ArrayList<Opt>()
                    for (c in cands) {
                        if (c.kind !in TAKEOUT_PREP || c.blocked.isNotEmpty() || c.id == tgt.id || c.s < cs - 5) continue
                        val toStore = arrive(cd, carSn, c.dsnap, true).first
                        val (sd, _) = dijkstra(c.dsnap, true)
                        val toTarget = arrive(sd, c.dsnap, tgt.dsnap, true).first
                        if (toStore == Double.POSITIVE_INFINITY || toTarget == Double.POSITIVE_INFINITY) continue
                        val extra = max(0.0, toStore + TAKEOUT_STOP + toTarget - direct)
                        val wait = max(0.0, TAKEOUT_PREP.getValue(c.kind) - toStore)
                        val late = max(0.0, extra + wait - slack)
                        opts += Opt(rnd(late), rnd(extra), c, toStore, wait)
                    }
                    opts.sortedWith(compareBy({ it.late }, { it.extra }, { it.c.id })).take(NEARBY_TOP).forEach { o ->
                        items.put(JSONObject().put("id", o.c.id).put("name", o.c.name).put("kind", o.c.kind).put("lat", o.c.lat).put("lon", o.c.lon).put("curb", o.c.curb)
                            .put("dist", rnd(reach(cd, cp, car, o.c.curbXy, carSn, o.c.dsnap, true).len)).put("extraSec", o.extra).put("lateSec", o.late)
                            .put("slackSec", rnd(slack)).put("carToStoreSec", rnd(o.toStore))
                            .put("prepSec", TAKEOUT_PREP.getValue(o.c.kind)).put("waitSec", rnd(o.wait)))
                    }
                }
            }
            kind == "drivethru" -> {
                val cs = carS(s)
                driveThru.indices.map { i -> Triple(net(driveThruSn[i].first, driveThruSn[i].second).len, driveThru[i], driveThruSn[i].first) }
                    .sortedBy { it.first }.take(NEARBY_TOP).forEach { (len, t, pt) ->
                        items.put(JSONObject().put("name", t.getString("name")).put("brand", t.optString("brand")).put("lat", t.getDouble("lat")).put("lon", t.getDouble("lon"))
                            .put("curb", JSONArray().put(t.getDouble("lat")).put(t.getDouble("lon"))).put("dist", rnd(len))
                            .put("ahead", project(pt).first >= cs).put("hours", t.optString("hours")))
                    }
            }
            else -> {
                val pool = mutableListOf<Pair<Double, JSONObject>>()
                for ((i, q) in publicParking.withIndex()) {
                    val span = q.getJSONArray(if (weekday) "weekday" else "saturday")
                    if (!openNow(span, h)) continue
                    pool += net(parkingSn[i].first, parkingSn[i].second).len to JSONObject().put("name", q.getString("name")).put("lat", q.getDouble("lat")).put("lon", q.getDouble("lon"))
                        .put("curb", JSONArray().put(q.getDouble("lat")).put(q.getDouble("lon"))).put("fee", q.getString("fee")).put("type", q.getString("type"))
                        .put("spaces", q.getInt("spaces")).put("hours", "${span.optString(0)}–${span.optString(1)}").put("public", true)
                }
                for (c in cands) if (c.kind == "parking")
                    pool += net(c.curbXy, c.dsnap).len to JSONObject().put("id", c.id).put("name", placeName(c)).put("lat", c.lat).put("lon", c.lon).put("curb", c.curb).put("public", false)
                pool.sortedBy { it.first }.take(NEARBY_TOP).forEach { (dd2, o) -> items.put(o.put("dist", rnd(dd2))) }
            }
        }
        return JSONObject().put("kind", kind).put("from", origin).put("items", items)
    }

    private val pathCache = HashMap<String, Pair<String, JSONObject>>()

    /** 지도용 실제 경로선: 차 → (경유지) → 만남 지점, 아이 → 만남 지점 (서버 paths와 같음) */
    private fun paths(s: JSONObject, c: Cand): JSONObject {
        val via = s.optJSONObject("via")
        val r = s.getJSONObject("rider")
        val key = listOf(carS(s), r.opt("lat"), r.opt("lon"), c.id, via?.let { "${it.getDouble("lat")},${it.getDouble("lon")}" }).joinToString("|")   // 차 위치는 반올림하지 않는다(evalKey 와 같은 이유)
        pathCache[s.getString("id")]?.let { if (it.first == key) return it.second }
        val car = pointAt(carS(s))
        val out = JSONObject()
        if (via != null) {
            val vp = xy(via.getDouble("lat"), via.getDouble("lon"))
            val a = route(car, vp, true); val b = route(vp, c.curbXy, true, c.dsnap)
            out.put("carPath", llArr(a.pts + b.pts.drop(1))).put("carPathM", rnd(a.len + b.len)).put("carPathSec", rnd(a.cost + b.cost))
        } else {
            val a = route(car, c.curbXy, true, c.dsnap)
            out.put("carPath", llArr(a.pts)).put("carPathM", rnd(a.len)).put("carPathSec", rnd(a.cost))
        }
        if (!r.isNull("lat")) {
            val rp = xy(r.getDouble("lat"), r.getDouble("lon"))
            val wr = route(rp, c.poi, false, c.wsnap)
            val straight = dist(rp, c.poi)
            if (wr.len > straight * WALK_PATH_MAX) out.put("walkPath", llArr(listOf(rp, c.poi))).put("walkPathM", rnd(straight * WALK_DETOUR))
            else out.put("walkPath", llArr(wr.pts)).put("walkPathM", rnd(wr.len))
        }
        pathCache[s.getString("id")] = key to out
        return out
    }

    @Synchronized
    fun latestCreated(): Pair<String, Double>? = sessions.values.maxByOrNull { it.getDouble("created") }?.let { it.getString("id") to it.getDouble("created") }

    @Synchronized
    fun action(sid: String, action: String, body: JSONObject): JSONObject? {
        val s = sessions[sid] ?: return null
        touch(sid)
        when (action) {
            "car" -> {
                // 진행 보고: 숫자 칸은 유한한 숫자, 글 칸은 글만(잘못된 값을 저장하면 그 세션 평가가 실패한다) — 하나라도 어긋나면 아무것도 바꾸지 않고 400(서버와 같다)
                for (k in listOf("progress", "speed")) if (body.has(k) && finiteNum(body.get(k)) == null) throw org.json.JSONException(k)
                for (k in listOf("gear", "wiper")) if (body.has(k) && body.get(k) !is String) throw org.json.JSONException(k)
                val car = s.getJSONObject("car"); for (k in listOf("progress", "speed", "gear", "wiper")) if (body.has(k)) car.put(k, body.get(k))
            }
            "rider" -> {
                val r = s.getJSONObject("rider"); val first = r.isNull("lat")
                // 위도·경도는 유한한 숫자이고 범위 안이어야 한다(NaN·글·범위 밖은 400, 서버와 같다) — 둘 다 검사한 뒤에 기록
                val lat = finiteNum(body.opt("lat"))?.takeIf { it in -90.0..90.0 } ?: throw org.json.JSONException("lat")
                val lon = finiteNum(body.opt("lon"))?.takeIf { it in -180.0..180.0 } ?: throw org.json.JSONException("lon")
                r.put("lat", lat).put("lon", lon)
                if (first) logEvent(s, L("${s.getJSONObject("member").getString("name")} 위치 공유 시작", "${s.getJSONObject("member").getString("name")} shared location"))
            }
            "choose", "confirm" -> {
                val cid = body.optString("pointId").ifEmpty { s.optJSONObject("proposal")?.optJSONObject("best")?.optString("id") ?: "" }
                if (cid.isNotEmpty() && cand(cid) != null && !s.getJSONObject("rider").isNull("lat")) {
                    val by = if (action == "choose") body.optString("by", "rider") else "driver"
                    s.put("confirmed", cid).put("confirmedBy", by).put("status", "confirmed")
                    logEvent(s, L("${if (by == "driver") "운전자" else s.getJSONObject("member").getString("name")} 확정: ${cand(cid)?.name}",
                        "${if (by == "driver") "Driver" else s.getJSONObject("member").getString("name")} confirmed: ${cand(cid)?.name}"))
                }
            }
            "clock" -> {
                // 잘못된 시각을 저장하면 그 세션의 모든 평가가 실패한다 — 검사하고 어긋나면 세션을 바꾸지 않고 400(서버와 같다)
                val hhmm = if (body.has("hhmm")) body.get("hhmm") as? String ?: throw org.json.JSONException("hhmm") else DEFAULT_HHMM
                val weekday = if (body.has("weekday")) body.get("weekday") as? Boolean ?: throw org.json.JSONException("weekday") else true
                if (!validClock(hhmm)) throw org.json.JSONException("hhmm")
                s.put("clock", JSONObject().put("hhmm", hhmm).put("weekday", weekday))
                s.put("proposal", JSONObject.NULL)
                logEvent(s, L("시연 시각 $hhmm (${if (weekday) "평일" else "주말"})", "Demo clock $hhmm (${if (weekday) "weekday" else "weekend"})"))
            }
            "emergency" -> {
                // 차 안 응급 상황: 기다리는 사람 링크를 '픽업이 늦어져요'로 바꾼다 (상세 의료 정보는 보내지 않음)
                val on = body.optBoolean("on")
                s.put("emergency", if (on) JSONObject().put("on", true).put("t", System.currentTimeMillis() / 1000.0).put("card", body.optJSONObject("card") ?: JSONObject.NULL) else JSONObject.NULL)
                logEvent(s, if (on) L("응급 상황 카드 시작 — 픽업 지연 안내", "Emergency card started — pickup delay notice sent") else L("응급 상황 종료", "Emergency ended"))
            }
            "message" -> {
                // 기다리는 사람의 원탭 메시지 → 차에서 음성으로 읽어 준다
                val text = cleanMessage(if (body.isNull("text")) "" else body.optString("text"))
                if (text.isNotEmpty()) {
                    val ms = s.getJSONArray("messages")
                    ms.put(JSONObject().put("t", System.currentTimeMillis() / 1000.0).put("from", body.optString("from", "rider")).put("text", text))
                    RIDER_DELAY.find(text)?.let { mm ->   // "2분만 기다려 주세요" → 2분 늦게 나온다
                        // 1~60분만 받는다: 긴 숫자로 늦춤이 터무니없이 커지거나 변환이 실패해 메시지만 들어간 채 남지 않게(서버와 같다)
                        val d = mm.groupValues[1].ifEmpty { mm.groupValues[2] }
                        val n = if (d.length <= 3) d.toInt() else 0
                        if (n in 1..60) s.put("riderDelay", max(s.optInt("riderDelay", 0), n * 60))
                    }
                    while (ms.length() > 10) ms.remove(0)
                    logEvent(s, "${s.getJSONObject("member").getString("name")}: $text")
                }
            }
            "via" -> {
                if (body.has("lat") && !body.isNull("lat")) {
                    // 경유지 위치도 유한한 숫자·범위 안만(서버와 같다). lat 이 없거나 null 이면 경유지 해제
                    val lat = finiteNum(body.get("lat"))?.takeIf { it in -90.0..90.0 } ?: throw org.json.JSONException("lat")
                    val lon = finiteNum(body.opt("lon"))?.takeIf { it in -180.0..180.0 } ?: throw org.json.JSONException("lon")
                    s.put("via", JSONObject().put("lat", lat).put("lon", lon).put("name", body.optString("name").take(40)))
                    logEvent(s, L("경유지: ${body.optString("name").take(40)}", "Stop on the way: ${body.optString("name").take(40)}"))
                } else s.put("via", JSONObject.NULL)
            }
            "toilet" -> {
                // 기다리는 사람: 화장실 급해요 → 만남 지점을 화장실 가까운 곳으로 다시 정한다(운전자에게 다시 물음)
                val st = s.getString("status")
                if (st != "boarded" && st != "dropped" && !s.optBoolean("needToilet")) {
                    s.put("needToilet", true)
                    val ms = s.getJSONArray("messages")
                    ms.put(JSONObject().put("t", System.currentTimeMillis() / 1000.0).put("from", "rider").put("text", L("화장실이 급해요", "I need a restroom")))
                    while (ms.length() > 10) ms.remove(0)
                    s.put("confirmed", JSONObject.NULL).put("confirmedBy", JSONObject.NULL).put("leaveNow", false)
                    if (st == "confirmed" || st == "arrived") s.put("status", "proposed")
                    s.put("proposal", JSONObject.NULL)
                    val nm = s.getJSONObject("member").getString("name")
                    logEvent(s, L("$nm: 화장실이 급해요 — 화장실 가까운 곳으로 다시 정함", "$nm needs a restroom — re-planning near one"))
                }
            }
            "passed" -> {
                // 차가 확정 지점에서 서지 못하고 지나감(자리 막힘 등) → 그 지점을 빼고 지금 차 위치에서 다시 골라 바로 확정한다.
                // 운전 중이라 다시 묻지 않는다. 기다리는 사람 화면도 새 지점으로 바뀐다(replan)
                val prev = confirmedId(s)?.let { cand(it) }
                if (prev != null && s.getString("status") == "confirmed") {
                    s.getJSONArray("passed").put(prev.id)
                    s.put("confirmed", JSONObject.NULL).put("confirmedBy", JSONObject.NULL).put("leaveNow", false).put("status", "proposed").put("proposal", JSONObject.NULL)
                    logEvent(s, L("${prev.name}에 서지 못하고 지나감 — 다시 정함", "Couldn't stop at ${prev.name} — re-planning"))
                    refresh(s)
                    val nb = s.optJSONObject("proposal")?.optJSONObject("best")
                    if (nb != null) {
                        val nxt = cand(nb.getString("id"))
                        s.put("confirmed", nb.getString("id")).put("confirmedBy", "auto").put("status", "confirmed")
                        s.put("replan", JSONObject().put("t", System.currentTimeMillis() / 1000.0).put("why", "passed").put("from", prev.name).put("to", nxt?.name ?: "").put("ack", false))
                        logEvent(s, L("자동 확정: ${nxt?.name} (지나친 ${prev.name} 대신)", "Auto-confirmed: ${nxt?.name} (instead of ${prev.name})"))
                    }
                }
            }
            "ack" -> {
                // 기다리는 사람: 차가 다시 정한 만남 지점을 봤다고 확인(묻지 않고 바꾼 변경을 상대가 알았는지 남긴다)
                val rp = s.optJSONObject("replan")
                if (rp != null && !rp.optBoolean("ack")) {
                    rp.put("ack", true).put("ackT", System.currentTimeMillis() / 1000.0)
                    logEvent(s, L("${s.getJSONObject("member").getString("name")}: 새 만남 지점 확인 (${rp.optString("to")})", "${s.getJSONObject("member").getString("name")} saw the new spot (${rp.optString("to")})"))
                }
            }
            "aboard" -> {
                // 기다리는 사람: "제가 탔어요" — 좌석·문은 누가 탔는지 모르므로 탑승 감지와 본인 확인을 나눈다
                val b = s.optJSONObject("boarding")
                if (s.getString("status") == "boarded" && b != null && !b.optBoolean("riderConfirmed")) {
                    b.put("riderConfirmed", true).put("riderT", System.currentTimeMillis() / 1000.0)
                    logEvent(s, L("${s.getJSONObject("member").getString("name")}: 탔어요 (본인 확인)", "${s.getJSONObject("member").getString("name")}: I'm in (confirmed)"))
                }
            }
            "boarding" -> {
                // 탑승·하차 보고: 상태는 boarded·dropped 만, 저장은 좌석·표시·벨트만(본문을 통째로 대기자 화면에 내보내지 않는다 — 서버와 같다)
                val state = body.opt("state") as? String
                if (state != "boarded" && state != "dropped") throw org.json.JSONException("state")
                val b = JSONObject().put("state", state)
                if (body.has("seat")) b.put("seat", body.optString("seat").take(40))
                if (body.has("seatLabel")) b.put("seatLabel", body.optString("seatLabel").take(40))
                if (body.has("belt")) b.put("belt", body.opt("belt") as? Boolean ?: JSONObject.NULL)
                b.put("riderConfirmed", false)
                s.put("boarding", b).put("status", state)
                val boarded = state == "boarded"
                logEvent(s, L("${b.optString("seatLabel")} ${if (boarded) "탑승" else "하차"} 감지", "${b.optString("seatLabel")}: ${if (boarded) "someone got in" else "someone got out"}"))
            }
            else -> return null
        }
        refresh(s)
        return public(s)
    }
}
