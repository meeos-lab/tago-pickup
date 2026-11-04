package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 도로 링크(차대차) 앱 내장 엔진. server/link/link_server.py 와 같은 규칙·같은 결과(check_link_parity.py로 확인).
 * 주행 중인 모든 Tago 차가 익명으로 연결돼 앞차의 급제동·정체·사고 의심·폭우를 뒤차에 알리고,
 * 앞길 미리보기(10km, 500m 칸)·정체를 녹이는 권장 속도·비 예보를 만든다. 가족 그룹은 선택(이름·위치·휴게소).
 * 시간은 요청의 시각 t(초)만 쓴다. 위치는 시연 경로(경부고속도로 하행) 위 거리 s(m).
 */
class LinkHub(routeJson: String) {
    private val file = JSONObject(routeJson)
    private val raw = file.getJSONArray("route").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } }
    private val lat0 = raw.sumOf { it.first } / raw.size
    private val kx = 111320.0 * cos(Math.toRadians(lat0))
    private val ky = 110540.0
    private fun xy(lat: Double, lon: Double) = lon * kx to lat * ky
    private fun r6(x: Double) = floor(x * 1e6 + 0.5) / 1e6
    private fun latlon(p: Pair<Double, Double>) = JSONArray().put(r6(p.second / ky)).put(r6(p.first / kx))
    private val route = raw.map { xy(it.first, it.second) }
    private val cum = DoubleArray(route.size).also { c -> for (i in 1 until route.size) c[i] = c[i - 1] + hypot(route[i].first - route[i - 1].first, route[i].second - route[i - 1].second) }
    val length = cum.last()

    fun project(p: Pair<Double, Double>): Double = projectD(p).first

    /** 점 → (경로 위 거리 s, 경로까지 거리 d) — 경로 밖(다른 도로)의 공식 돌발을 거를 때 d 를 쓴다 */
    fun projectD(p: Pair<Double, Double>): Pair<Double, Double> {
        var best = Double.POSITIVE_INFINITY; var bs = 0.0
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val dx = b.first - a.first; val dy = b.second - a.second
            val l2 = (dx * dx + dy * dy).takeIf { it != 0.0 } ?: 1e-9
            val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(p.first - a.first - t * dx, p.second - a.second - t * dy)
            if (d < best) { best = d; bs = cum[i] + t * sqrt(l2) }
        }
        return bs to best
    }

    fun pointAt(s0: Double): JSONArray {
        val s = s0.coerceIn(0.0, length)
        for (i in 0 until cum.size - 1) {
            if (cum[i + 1] >= s) {
                val seg = (cum[i + 1] - cum[i]).takeIf { it != 0.0 } ?: 1e-9
                val t = (s - cum[i]) / seg
                val a = route[i]; val b = route[i + 1]
                return latlon(a.first + t * (b.first - a.first) to a.second + t * (b.second - a.second))
            }
        }
        return latlon(route.last())
    }

    class Rest(val name: String, val nameEn: String, val kind: String, val s: Double, val lat: Double, val lon: Double)

    /** 구역(설계 10판 §8, build_zones.py): 사고 다발 구간·터널·교량 */
    class Zone(val id: String, val kind: String, val s0: Double, val s1: Double, val name: String, val nameEn: String, val count: Int, val deaths: Int, val years: String)

    val zones: List<Zone> = file.optJSONArray("zones")?.let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { z ->
            Zone(z.getString("id"), z.getString("kind"), z.getDouble("s0"), z.getDouble("s1"), z.getString("name"), z.getString("nameEn"),
                z.optInt("count"), z.optInt("deaths"), z.optString("years"))
        } }
    } ?: emptyList()

    val rest: List<Rest> = run {
        val out = mutableListOf<Rest>()
        val a = file.getJSONArray("restAreas")
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            if (out.isNotEmpty() && r.getDouble("s") - out.last().s < 300) continue   // OSM 중복 정리
            val name = r.getString("name").substringBefore("(").trim()
            val en = r.optString("nameEn").ifEmpty { name }.substringBefore("(").trim()
            out += Rest(name, en, r.getString("kind"), r.getDouble("s"), r.getDouble("lat"), r.getDouble("lon"))
        }
        out
    }

    // ---------------------------------------------------------------- 규칙 (서버와 같은 값)
    companion object {
        const val ALERT_RANGE = 10000.0
        const val MERGE_M = 1000.0
        const val ANON_CONFIRM = 2
        const val SLOW_MS = 8.3
        const val SLOW_HOLD = 20.0
        const val FAST_MS = 16.7
        const val STOP_MS = 1.4
        const val INCIDENT_HOLD = 30.0
        const val INCIDENT_NEAR = 300.0
        val TTL = mapOf("HARD_BRAKE" to 300.0, "CONGESTION" to 120.0, "INCIDENT" to 900.0, "HEAVY_RAIN" to 600.0, "ICING" to 900.0,
            "BLOCKED" to 1200.0, "DEBRIS" to 900.0, "SMOKE" to 600.0, "DOWNHILL" to 1800.0, "SIREN" to 30.0, "CHARGER_FAULT" to 3600.0, "CHARGER_WAIT" to 3600.0)
        val MERGE = mapOf("DEBRIS" to 300.0, "SIREN" to 3000.0, "CHARGER_FAULT" to 500.0, "CHARGER_WAIT" to 500.0)   // 종류별 묶는 거리(없으면 MERGE_M). 낙하물은 좁게, 긴급차량은 움직이니 넓게
        // 차가 보내는 신호 → 위험 종류. RECIRC_OFF는 해제하지 않는다(연기 보고는 TTL 동안 남는다)
        // SIREN: 마이크가 사이렌을 듣는 동안 5초마다 보낸다(해제 신호 없음 — 마지막 보고 30초 뒤 사라진다)
        // CHARGE_FAIL: 꽂았는데 충전이 안 됨(충전기 고장·결제 오류 의심), CHARGE_OK: 충전 시작(해제), CHARGE_START: 정차 뒤 오래 기다려 꽂음(충전 대기, 값 = 분)
        val EVENT_TYPE = mapOf("TURNBACK" to "BLOCKED", "SWERVE" to "DEBRIS", "RECIRC_ON" to "SMOKE", "RECIRC_OFF" to "", "BRAKE_HOLD" to "DOWNHILL",
            "CHARGE_FAIL" to "CHARGER_FAULT", "CHARGE_OK" to "CHARGER_FAULT_CLEARED", "CHARGE_START" to "CHARGER_WAIT")
        // 충전 위험은 휴게소(충전기가 있는 곳)에 붙는다: 차 위치에서 500m 안의 휴게소가 없으면 버린다
        val CHARGER = setOf("CHARGER_FAULT", "CHARGER_WAIT")
        const val CHARGER_NEAR = 500.0
        // 사고 판단(docs/incident-judgment-design.md 10판 10-1·10-2): 한 차의 60 s·300 m 사건 창에 근거를 더한다. PC 엔진과 같은 값
        val INC_POINTS = mapOf("HARD_BRAKE" to 1, "SWERVE" to 1, "IMPACT" to 3, "STOP" to 2, "AIRBAG" to 3, "COMM_LOSS" to 2, "NO_RESPONSE" to 2)
        val INC_ORDER = listOf("SWERVE", "HARD_BRAKE", "IMPACT", "STOP", "AIRBAG", "COMM_LOSS", "NO_RESPONSE")
        val INC_HARD = setOf("STOP", "IMPACT", "AIRBAG", "COMM_LOSS")
        const val INC_WINDOW = 60.0; const val INC_NEAR = 300.0
        const val INC_MIN = 3; const val INC_STRONG = 6
        const val COMM_LOSS = 8.0; const val COMM_AFTER = 15.0
        const val STRONG_SESSION = 120.0; const val STRONG_GAP = 1800.0
        const val CLEAR_PASS = 3
        // 공식 돌발 정보(설계 10판 §8·10-3): 순정 내비 TrafficStatus.trafficEventList 를 차가 올린다(/api/road/official). 서버와 같은 값
        // 구역(③): 서버와 같은 값
        const val ZONE_SAY = 1500.0; const val ZONE_NEAR = 300.0; const val TUNNEL_MARGIN = 200.0; const val ICE_TEMP = 4.0
        // 사고 위험성(④): 서버와 같은 값. 허브는 환경·도로 묶음만(운전 묶음은 차 안에서)
        val RISK_PTS = mapOf("AHEAD" to 3, "ICE" to 2, "ICE_HARD" to 3, "ZONE" to 2, "RAIN" to 2, "TUNNEL" to 1, "NIGHT" to 1, "DEBRIS" to 1)
        val RISK_ORDER = listOf("AHEAD", "ICE", "ZONE", "RAIN", "TUNNEL", "NIGHT", "DEBRIS")
        const val RISK_CAUTION = 3; const val RISK_HIGH = 6; const val RISK_AHEAD = 3000.0; const val RISK_NEAR = 1000.0; const val KST = 9 * 3600.0
        val OFFICIAL_TYPES = setOf("ACCIDENT", "ROAD_DAMAGE", "DISASTER")
        const val OFFICIAL_NEAR = 2000.0; const val OFFICIAL_MERGE = 300.0; const val OFFICIAL_OFFROUTE = 1000.0; const val OFFICIAL_TTL = 1800.0
        val OFFICIAL_KO = mapOf("ACCIDENT" to "사고", "ROAD_DAMAGE" to "도로 파손", "DISASTER" to "재난")
        val OFFICIAL_EN = mapOf("ACCIDENT" to "crash", "ROAD_DAMAGE" to "road damage", "DISASTER" to "disaster")
        val OFFICIAL_CAUSE = mapOf("ACCIDENT" to 2, "ROAD_DAMAGE" to 9, "DISASTER" to 99)
        const val DESC_MAX = 40
        const val IMPACT_A = 14.0; const val IMPACT_DV = 2.2; const val IMPACT_DT_MIN = 0.08; const val IMPACT_DT_MAX = 0.5; const val IMPACT_HOLD = 0.5
        val INC_KO = mapOf("SWERVE" to "급조향", "HARD_BRAKE" to "급제동", "IMPACT" to "충돌", "STOP" to "정차", "AIRBAG" to "에어백 전개", "COMM_LOSS" to "통신 끊김", "NO_RESPONSE" to "응답 없음")
        val INC_EN = mapOf("SWERVE" to "swerved", "HARD_BRAKE" to "braked hard", "IMPACT" to "crash-level deceleration", "STOP" to "stopped", "AIRBAG" to "airbag deployed",
            "COMM_LOSS" to "lost contact", "NO_RESPONSE" to "no response")
        val CAUSE_CODE = mapOf("INCIDENT" to 2, "CONGESTION" to 1, "SIREN" to 95, "BLOCKED" to 2)
        // 긴급차량(사이렌): 뒤에서 다가오는 움직이는 위험. 차마다 처음 들은 때·곳(onset)을 이어 위치·속도를 짐작한다
        const val SIREN_RANGE = 2000.0           // 내 뒤 2km 안에서 다가올 때만 알린다
        const val SIREN_NEAR = 500.0             // 500m 안이면 한 번 더(짧게) 말한다
        const val SIREN_TRACK = 60.0             // 최근 60초의 onset으로 속도를 잰다
        const val SIREN_MIN_DT = 3.0             // onset 두 개가 3초 이상 떨어져야 방향을 안다(반대 차로는 거꾸로 움직여 걸러진다)
        const val SIREN_EXTRAP = 15.0            // 마지막 onset 뒤 최대 15초까지 속도로 앞으로 민다
        const val SIREN_ETA_MAX = 90.0           // 통과 예상 시간은 90초 안일 때만 말한다
        const val RAIN_NEAR = 6000.0                       // 되돌아가는 차 지점 6km 안에 확인된 폭우가 있으면 '침수 의심'
        val STICKY = setOf("CONGESTION", "HEAVY_RAIN", "ICING", "INCIDENT", "CHARGER_FAULT")   // 보고 차가 해제할 때까지 남는 종류
        val LEVEL_UP = setOf("INCIDENT", "HARD_BRAKE", "BLOCKED")            // 차 3대 이상이면 한 번 더 말하는 종류
        val ONE_SHOT = setOf("HARD_BRAKE", "SMOKE", "DEBRIS", "BLOCKED", "DOWNHILL", "SIREN", "CHARGER_WAIT")   // 한 번 보고하고 끝나는 종류('활성'에 두지 않는다)
        const val CRUISE_MS = 25.0
        const val REST_AHEAD = 1000.0
        const val ACTIVE_SEC = 60.0
        const val BIN_M = 500.0
        const val BINS = 20
        const val JAM_KMH = 40
        const val ADVISE_KMH = 80                // 정체 꼬리 권장 속도는 거리와 무관하게 80 하나(docs/sim-report.md 3.10)
        const val PULSE_SEC = 8.0
        const val MAX_STEPS = 600
        const val GONE_SEC = 300.0               // 5분(ACTIVE_SEC의 5배) 넘게 소식 없는 차: 남아 있는 보고(정체·폭우 등)를 거둔다. 차가 꽉 차면 도로에서 뺀다
        const val MAX_CARS = 200                 // 도로 위 차는 200대까지(시연은 30대 안팎) — 넘치면 소식 없는 차부터 빼고, 그래도 꽉 차면 새 차를 받지 않는다
        const val GROUP_MAX = 50                 // 그룹은 50개까지 — 넘치면 가장 오래된 그룹과 코드를 지운다
        const val CODE_TTL = 1800.0              // 참가 코드는 만든 뒤 30분까지만 받는다(그룹은 그대로, 이미 합류한 사람은 그대로)
        const val JOIN_TRIES = 5                 // 같은 차가 코드를 5번 틀리면 60초 동안 받지 않는다(차 id 없는 휴대폰 참가는 한 칸을 같이 쓴다)
        const val JOIN_LOCK = 60.0
        const val CODE_WRONG_MAX = 20            // 코드가 살아 있는 동안 도로 전체에서 20번 틀리면 그 코드는 닫는다(4자리 코드를 차례로 넣어 맞히지 못하게)
        const val NAME_MAX = 20                  // 사람이 적는 이름·차 설명 글자 수(제어 문자는 뺀다)
        const val CAR_MAX = 40
        const val KIND_MAX = 12
        val PRIORITY = mapOf("OFFICIAL" to 0, "SIREN" to -1, "INCIDENT" to 0, "BLOCKED" to 1, "HARD_BRAKE" to 2, "DEBRIS" to 3, "ICING" to 4, "SMOKE" to 5, "DOWNHILL" to 6,
            "CONGESTION" to 7, "HEAVY_RAIN" to 8, "CHARGER_FAULT" to 9, "CHARGER_WAIT" to 10)   // 긴급차량 양보가 가장 먼저
        val TYPE_KO = mapOf("OFFICIAL" to "도로 정보", "HARD_BRAKE" to "급제동", "CONGESTION" to "정체", "INCIDENT" to "사고 의심", "HEAVY_RAIN" to "폭우", "ICING" to "결빙 위험",
            "BLOCKED" to "길 막힘 의심", "DEBRIS" to "낙하물 의심", "SMOKE" to "연기·매연", "DOWNHILL" to "긴 내리막", "SIREN" to "긴급차량",
            "CHARGER_FAULT" to "충전기 고장 의심", "CHARGER_WAIT" to "충전 대기")
        val TYPE_EN = mapOf("OFFICIAL" to "road info", "HARD_BRAKE" to "hard braking", "CONGESTION" to "slow traffic", "INCIDENT" to "a possible crash", "HEAVY_RAIN" to "heavy rain",
            "ICING" to "possible ice", "BLOCKED" to "a possible road block", "DEBRIS" to "possible debris", "SMOKE" to "smoke or fumes",
            "DOWNHILL" to "a long downhill", "SIREN" to "an emergency vehicle", "CHARGER_FAULT" to "a possible charger fault",
            "CHARGER_WAIT" to "a charging queue")

        // 시연 도로(서버와 같음): 사고 → 정체 → 비
        const val DEMO_SCALE = 4.0
        const val JAM_TAIL = 5000.0
        const val JAM_HEAD = 6500.0
        const val JAM_MS = 4.0
        const val JAM_END = 150
        const val CRASH_END = 110
        const val RAIN_FROM = 9000.0
        const val RAIN_TO = 22000.0
        const val DEBRIS_S = 3000.0              // 낙하물: 지나는 차가 핸들을 급히 꺾는다
        const val DOWNHILL_FROM = 7000.0         // 긴 내리막: 지나는 차가 브레이크를 오래 밟는다
        const val DOWNHILL_TO = 9500.0
        const val TUNNEL_FROM = 12000.0          // 터널: 지나는 차가 내기순환으로 바꾼다(연기)
        const val TUNNEL_TO = 12600.0
        const val TURNBACK_S = 14500.0           // 침수: 여기 닿은 차는 되돌아간다(비 구간 안)
        const val BRAKE_HOLD_SEC = 20
        const val BRAKE_ENTRIES = 0
        val DEMO_CARS = listOf(Triple("anon", -2500.0, 24.0), Triple("anon", -1200.0, 25.5), Triple("anon", 1500.0, 23.5), Triple("anon", 2600.0, 26.0),
            Triple("anon", 3700.0, 24.5), Triple("anon", 4600.0, 25.0), Triple("anon", 5300.0, 24.0), Triple("anon", 5800.0, 25.0), Triple("anon", 6200.0, 24.0),
            Triple("crash", 6400.0, 0.0), Triple("crash", 6420.0, 0.0), Triple("anon", 7600.0, 25.0), Triple("anon", 9600.0, 24.5), Triple("anon", 11500.0, 25.0))
        class Fam(val ko: String, val en: String, val car: String, val carEn: String, val off: Double, val free: Double, val soc: Int?)
        val DEMO_FAMILY = listOf(Fam("삼촌", "Uncle", "아반떼 · 흰색", "white Avante", 3000.0, 25.0, 41), Fam("이모", "Aunt", "쏘렌토 · 검정", "black Sorento", -1500.0, 24.0, null))
        // 구급차: AMB_K초에 정체 꼬리 뒤에서 나타나 사고 지점으로. 근처(HEAR_M) Tago 차의 마이크가 사이렌을 듣고 5초마다 보고한다.
        // 같이 나타나는 모르는 Tago 차 5대가 구급차 바로 앞을 달린다. 사고 지점에 닿으면 사이렌을 끈다
        const val AMB_K = 50
        const val AMB_OFF = 500.0
        const val AMB_MS = 33.0                  // 120km/h
        const val AMB_JAM_MS = 14.0              // 정체 안에서는 50km/h(차들이 비켜 준다)
        const val AMB_STOP = 6360.0              // 사고 차(6400) 바로 뒤에 선다
        val AMB_WITNESS = listOf(250.0 to 24.0, 700.0 to 24.0, 1150.0 to 24.0, 1600.0 to 24.0, 2050.0 to 24.0)   // (구급차 기준 위치, 속도)
        const val HEAR_M = 300.0                 // 차 안 마이크가 사이렌을 듣는 거리
        const val SIREN_EVERY = 5                // 듣는 동안 보고 간격(초)
        const val YIELD_M = 250.0                // 관제 화면: 구급차 앞 250m 안의 차는 비켜 선다
        // 여정(가족 그룹): 차량 관측·사람의 선택·장소 조건·서비스 판단을 나눠 담는다. 휴게소 제안과 다음 한 단계 카드는 같은 '할 일(act)' 형식:
        // 제안(proposed)·대기(pending) → 끝(done, 차 관측 vehicle / 사람 확인 person) 또는 취소(cancelled)
        // 다음 한 단계 카드: board·rest·arrive는 타고 있는 차의 관측으로 서버가 넘기고, task는 누군가 '했어요'를 눌러야 끝난다
        val STEP_KINDS = setOf("board", "rest", "arrive", "task")
        const val STEP_MAX = 6
        const val STEP_LABEL_MAX = 24
        const val NEAR_STOP = 800.0              // 휴게소 800m 안에서 멈추면 그곳에 섰다고 본다
        const val ARRIVE_NEAR = 500.0            // 경로 끝 500m 안에서 멈추면 도착
        const val MOVE_MS = 10 / 3.6             // 시속 10km를 넘으면 출발
        val BASIS = mapOf("lead_1km" to ("가장 앞선 일행도 1km 이상 남은 다음 휴게소" to "Next rest area at least 1 km past the lead car"),
            "agreed" to ("일행이 정한 휴게소" to "The rest area the group chose"),
            "next" to ("타고 있는 차 앞의 다음 휴게소" to "The next rest area ahead of the car"))

        fun r1(x: Double) = floor(x * 10 + 0.5) / 10
        fun rnd(x: Double) = floor(x + 0.5).toInt()
        fun L(lang: String, ko: String, en: String) = if (lang == "en") en else ko
        fun kmText(lang: String, m: Double): String =
            if (m < 950) L(lang, "${rnd(m / 100) * 100}m", "${rnd(m / 100) * 100} m") else L(lang, "${r1(m / 1000)}km", "${r1(m / 1000)} km")
        fun hasFinal(w: String): Boolean { val c = w.lastOrNull() ?: return false; return c in '가'..'힣' && (c - '가') % 28 != 0 }
        fun josa(w: String, a: String, b: String) = w + if (hasFinal(w)) a else b

        /** 사람이 적은 글: 제어 문자(줄바꿈·방향 바꾸기·짝 없는 대리 문자 등)를 빼고 앞뒤 공백을 지운 뒤 n자(코드 포인트)까지(서버와 같은 규칙) */
        fun clean(x: String, n: Int): String {
            val sb = StringBuilder()
            var i = 0
            while (i < x.length) {
                val cp = x.codePointAt(i)
                i += Character.charCount(cp)
                when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> {}
                    else -> sb.appendCodePoint(cp)
                }
            }
            val s = sb.toString().trim()
            return (if (s.codePointCount(0, s.length) > n) s.substring(0, s.offsetByCodePoints(0, n)) else s).trim()
        }
        fun langOf(x: String) = if (x == "en") "en" else "ko"
    }

    class Demo(val kind: String, val free: Double)
    /** id: 그 차의 열쇠(본인에게만), pub: 남에게 보이는 이름표 */
    class Car(val id: String, val pub: String, var name: String, var car: String, val kind: String, val lang: String, val demo: Demo?) {
        var s: Double? = null; var speed = 0.0; var soc: Any? = null; var wiper = ""; var temp: Any? = null; var t = 0.0
        var slowSince: Double? = null; var stopSince: Double? = null; var brake: Pair<Double, Double>? = null
        val active = mutableSetOf<String>(); var group: String? = null
        var holdSince: Double? = null; var gone = false; var tunnel = false   // holdSince: null / 시작 시각 / -1.0(이미 보고)
        var st: Pair<Double, Double>? = null; var vs: Double? = null; var sirenAt: Double? = null   // st: 마지막 상태(t, s), vs: 경로 위 실제 이동 속도
        var inc: Inc? = null; var joinT = 0.0; var strongAt: Double? = null; var strongH: String? = null   // 사고 판단 창·세션 시작·강한 확정 기록
    }
    /** 사고 판단 창: 첫 근거의 자리·시각, 근거 태그 → 시각 */
    class Inc(val s0: Double, val t0: Double) { val ev = linkedMapOf<String, Double>(); var closed = false; var reported = false; var lossV: Double? = null }
    class Hazard(val id: String, val type: String, var s: Double, val first: Double) {
        val reporters = sortedMapOf<String, Double>()
        var last = first; var value = ""; var cleared: Double? = null
        val track = mutableListOf(first to s)          // 긴급차량: 차마다 처음 들은 (시각, 위치)
        var rest: Rest? = null                         // 충전 위험: 붙은 휴게소
        val ev = mutableMapOf<String, List<String>>(); val score = mutableMapOf<String, Int>(); val passed = mutableSetOf<String>()   // 사고 판단: 차별 근거·점수, 통과 차
    }
    /** 공식 돌발 정보(순정 내비가 준 것을 차가 올림) */
    class Official(val id: String, val type: String, val s: Double, var end: Double?, var desc: String, var t: Double, val first: Double, val by: String)
    class Amb(var s: Double) { var on = true }
    class Group(val id: String, val code: String) {
        var codeAt = 0.0                                 // 코드를 만든 시각(30분 뒤 닫는다)
        var wrong = 0                                    // 이 코드가 살아 있는 동안 도로 전체에서 틀린 횟수
        val members = mutableListOf<String>()
        var rest: JSONObject? = null
        var answers = linkedMapOf<String, Boolean>()
        val log = mutableListOf<Pair<String, String>>()
        var demo = false
        // 여정
        var ride = ""                                    // 동승자가 타고 있는 차(사람의 선택). 카드의 차 관측은 이 차를 따른다
        val acts = mutableListOf<Act>()
        var seq = 0
        var planned: Place? = null                       // 휴게소 카드가 처음 본 쉬어 갈 곳
        var changed: Pair<Place, Place>? = null          // 바뀐 쉬어 갈 곳(누군가 '알겠어요'를 누를 때까지)
        var stepsBy = ""
    }
    /** 장소 조건: 이름·경로 위 위치·근거(agreed/next) */
    class Place(val name: String, val nameEn: String, val s: Double, val basis: String)
    /** 할 일: 휴게소 제안(rest) 또는 다음 한 단계 카드(step) */
    class Act(val id: String, val type: String, val kind: String, val label: String, val by: String, val basis: String?, val place: Place?) {
        var state = if (type == "rest") "proposed" else "pending"
        var how: String? = null; var doneBy = ""; var at: Double? = null
    }
    class WorldDemo(val t0: Double, val s0: Double) { var k = 0; var entries = 0; var amb: Amb? = null }
    /** 코드 틀린 횟수(차 하나, 휴대폰 참가는 "" 한 칸) */
    class Try { var n = 0; var until = 0.0 }
    /** 코드로 합류한 결과: 그룹과 차, 또는 까닭("code"·"locked"·"full") */
    class Joined(val g: Group?, val c: Car?, val error: String = "")
    /** 보기용 위험 한 줄(JSON + 정렬·칸 계산에 쓰는 값) */
    private class Item(val o: JSONObject, val s: Int, val ahead: Int?, val id: String, val type: String, val confirmed: Boolean, val cars: Int)

    private val cars = mutableListOf<Car>()
    private var hazards = mutableListOf<Hazard>()
    private val official = mutableListOf<Official>()
    private val pulses = mutableListOf<JSONObject>()
    val groups = linkedMapOf<String, Group>()
    private val codes = mutableMapOf<String, String>()
    private val tries = mutableMapOf<String, Try>()  // 코드 틀린 횟수: 차 id(휴대폰 참가는 "") → 횟수·잠금 끝
    private var now = 0.0
    private var seq = 0
    private var demo: WorldDemo? = null
    private val rng = java.security.SecureRandom()
    private fun rid(n: Int, chars: String = "0123456789abcdef") = (1..n).map { chars[rng.nextInt(chars.length)] }.joinToString("")

    fun car(id: String?) = cars.firstOrNull { it.id == id }

    /** 도로를 비운다(시연을 처음부터 다시, 시험용) */
    fun reset() { cars.clear(); hazards.clear(); official.clear(); pulses.clear(); groups.clear(); codes.clear(); tries.clear(); now = 0.0; seq = 0; demo = null }

    /** 차 id는 그 차의 열쇠다(이 id로 위치·보고를 보낸다) — 맞힐 수 없게 만들고 본인에게만 준다. 남에게는 pub(보기용 이름표)만 보인다 */
    private fun newCar(name: String, desc: String, kind: String, lang: String, demo: Demo? = null): Car? {
        if (cars.size >= MAX_CARS) {
            purge()
            if (cars.size >= MAX_CARS) return null
        }
        var id: String; do { id = "c" + rid(12) } while (car(id) != null)
        var pub: String; do { pub = "v" + rid(8) } while (cars.any { it.pub == pub })
        return Car(id, pub, name, desc, kind, lang, demo).also { it.t = now; it.joinT = now; cars += it }
    }

    /** 밖으로 내보내는 차 id: 나에게는 내 id, 남의 차는 보기용 이름표(pub) — 남의 id로 위치·보고를 보낼 수 없게 */
    private fun pid(c: Car, mid: String?) = if (c.id == mid) c.id else c.pub

    /** 보고한 차가 아직 도로에 있다: 마지막 상태나 그 보고가 GONE_SEC 안 */
    private fun alive(cid: String, at: Double): Boolean {
        val c = car(cid) ?: return false
        return now - maxOf(c.t, at) < GONE_SEC
    }

    /** 차가 꽉 찼을 때만: GONE_SEC 넘게 소식 없는 차를 도로·그룹에서 뺀다. 아무도 남지 않은 그룹은 코드와 함께 지운다 */
    private fun purge() {
        val gone = cars.filter { now - it.t >= GONE_SEC }.map { it.id }.toSet()
        if (gone.isEmpty()) return
        cars.removeAll { it.id in gone }
        for (cid in gone) tries.remove(cid)
        for ((gid, g) in groups.entries.toList()) {
            g.members.removeAll { it in gone }
            for (m in gone) g.answers.remove(m)
            if (g.members.isEmpty()) dropGroup(gid)
            else if (g.ride in gone) g.ride = g.members[0]
        }
    }

    private fun dropGroup(gid: String) {
        val g = groups.remove(gid) ?: return
        if (codes[g.code] == gid) codes.remove(g.code)
        for (c in cars) if (c.group == gid) c.group = null
    }

    private fun clock(b: JSONObject) {
        if (b.has("t") && !b.isNull("t")) { val t = b.getDouble("t"); if (t > now) advance(t) }
    }

    private fun str(b: JSONObject, k: String) = if (b.has(k) && !b.isNull(k)) b.get(k).toString() else ""

    // ---- 도로 링크
    fun joinRoad(b: JSONObject): Car? {
        clock(b)
        return newCar(clean(str(b, "name"), NAME_MAX), clean(str(b, "car"), CAR_MAX), clean(str(b, "kind"), KIND_MAX).ifEmpty { "pleos" }, langOf(str(b, "lang")))
    }

    fun state(b: JSONObject) {
        clock(b)
        val c = car(str(b, "me")) ?: return
        if (b.has("s") && !b.isNull("s")) c.s = b.getDouble("s")
        else if (b.has("lat") && !b.isNull("lat") && b.has("lon") && !b.isNull("lon")) c.s = project(xy(b.getDouble("lat"), b.getDouble("lon")))
        if (b.has("speed") && !b.isNull("speed")) c.speed = b.getDouble("speed")
        if (b.has("soc") && !b.isNull("soc")) c.soc = b.get("soc")
        if (b.has("temp") && !b.isNull("temp")) c.temp = b.get("temp")
        if (b.has("wiper") && !b.isNull("wiper")) c.wiper = b.get("wiper").toString()
        c.s?.let { s ->                                   // 경로 위 실제 이동 속도(시연은 압축된 거리 기준) — 긴급차량과 가까워지는 속도에 쓴다
            c.st?.let { (t0, s0) -> if (now > t0) c.vs = (s - s0) / (now - t0) }
            c.st = now to s
        }
        val prevS = c.st?.second
        c.t = now
        c.inc?.let { if ("COMM_LOSS" in it.ev) it.ev.remove("COMM_LOSS") }   // 다시 보고한다 = 통신이 돌아왔다
        passing(c, prevS)
        derive(c)
        for (g in groups.values) if (c.id in g.members) journey(g)
    }

    // ---- 사고 판단: 근거 창
    private fun evidence(c: Car, tag: String) {
        val s = c.s ?: return
        var inc = c.inc
        if (inc != null && (inc.closed || now - inc.t0 > INC_WINDOW || abs(s - inc.s0) > INC_NEAR)) inc = null
        if (inc == null) { inc = Inc(s, now); c.inc = inc }
        inc.ev.putIfAbsent(tag, now)
        judge(c)
    }

    private fun judge(c: Car) {
        val inc = c.inc ?: return
        if (inc.closed) return
        val score = inc.ev.keys.sumOf { INC_POINTS.getValue(it) }
        if (score >= INC_MIN && inc.ev.keys.any { it in INC_HARD }) {
            var h: Hazard? = null
            if (!inc.reported) { inc.reported = true; h = report(c, "INCIDENT", "score", inc.s0) }
            h = h ?: hazards.firstOrNull { it.type == "INCIDENT" && c.id in it.reporters && abs(it.s - inc.s0) <= INC_NEAR }
            h?.let { it.ev[c.id] = INC_ORDER.filter { t -> t in inc.ev }; it.score[c.id] = score; it.passed.remove(c.id) }   // 지나가던 차가 보고 차가 되면 '통과'에서 뺀다
        }
    }

    /** 차가 보낸 10 Hz 표본으로 충돌을 같은 규칙으로 다시 본다. 표본이 없으면 믿는다 */
    private fun impactOk(b: JSONObject?): Boolean {
        val a = b?.optJSONArray("samples") ?: return true
        if (a.length() == 0) return true
        val pts = (0 until a.length()).map { a.getJSONArray(it).getDouble(0) to a.getJSONArray(it).getDouble(1) }
        for (i in 1 until pts.size) {
            val (ti, vi) = pts[i]
            for (k in i - 1 downTo 0) {
                val (tk, vk) = pts[k]
                val dt = ti - tk
                if (dt > IMPACT_DT_MAX) break
                if (dt >= IMPACT_DT_MIN && vk - vi >= IMPACT_DV && (vk - vi) / dt >= IMPACT_A) {
                    val tail = pts.filter { (t, _) -> t > ti && t <= ti + IMPACT_HOLD }.map { it.second }
                    if (tail.isNotEmpty() && tail.all { it < 0.5 * vk }) return true
                    break
                }
            }
        }
        return false
    }

    /** 충돌을 보고한 차가 그 뒤 15 s 안의 보고를 끝으로 8 s 넘게 조용하면 '통신 끊김'(사고로 인한) */
    private fun scanCommLoss() {
        for (c in cars) {
            val inc = c.inc ?: continue
            if (inc.closed || "IMPACT" !in inc.ev || "COMM_LOSS" in inc.ev) continue
            if (c.t <= inc.t0 + COMM_AFTER && now - c.t >= COMM_LOSS && !inTunnel(c.s)) { inc.ev["COMM_LOSS"] = now; inc.lossV = c.speed; judge(c) }
        }
    }

    /** 보고하지 않은 차가 사고 의심 지점을 60 km/h 넘게 지나가면 '통과' → 3대면 해제, 1~2대면 일부 차로 통행 */
    private fun passing(c: Car, prevS: Double?) {
        val s = c.s ?: return
        if (prevS == null || c.speed <= FAST_MS) return
        for (h in hazards) {
            if (h.type != "INCIDENT" || h.reporters.isEmpty() || c.id in h.reporters) continue
            if ((prevS < h.s && h.s <= s) || abs(s - h.s) <= INC_NEAR) {
                h.passed += c.id
                val reporterStopped = h.reporters.keys.any { r -> car(r)?.let { x -> x.speed < STOP_MS && abs((x.s ?: -9e9) - h.s) <= INC_NEAR } == true }
                if (h.passed.size >= CLEAR_PASS && !reporterStopped) { h.reporters.clear(); h.cleared = now }
            }
        }
    }

    /** 운전자 응답: ok=true → 사고 아님(창 닫고 내 보고 거둠), ok=false·timeout → 응답 없음 근거 */
    fun respond(b: JSONObject) {
        clock(b)
        val c = car(str(b, "me")) ?: return
        val inc = c.inc ?: return
        if (b.has("ok") && !b.isNull("ok") && b.getBoolean("ok")) {
            inc.closed = true
            if (inc.reported) clear(c, "INCIDENT")
        } else evidence(c, "NO_RESPONSE")
    }

    /** 차가 올린 순정 내비 돌발 정보(ACCIDENT·ROAD_DAMAGE·DISASTER만): 경로 위 s 로 투영해 두고(같은 종류 300 m 안은 하나), endTime 또는 30분 뒤 지운다 */
    fun official(b: JSONObject) {
        clock(b)
        val c = car(str(b, "me")) ?: return
        val evs = b.optJSONArray("events") ?: return
        for (i in 0 until evs.length()) {
            val e = evs.getJSONObject(i)
            val type = str(e, "type")
            if (type !in OFFICIAL_TYPES) continue
            val (s, d) = if (e.has("s") && !e.isNull("s")) e.getDouble("s") to 0.0
                else if (e.has("lat") && !e.isNull("lat") && e.has("lon") && !e.isNull("lon")) projectD(xy(e.getDouble("lat"), e.getDouble("lon")))
                else continue
            if (d > OFFICIAL_OFFROUTE) continue                   // 경로 밖(다른 도로)의 돌발은 버린다
            val end = if (e.has("end") && !e.isNull("end")) e.getDouble("end") else null
            val desc = clean(str(e, "desc"), DESC_MAX)
            val o = official.firstOrNull { it.type == type && abs(it.s - s) <= OFFICIAL_MERGE }
            if (o != null) { o.t = now; o.end = end; if (desc.isNotEmpty()) o.desc = desc }
            else { seq++; official += Official("o$seq", type, s, end, desc, now, now, c.id) }
        }
    }

    /** s 에서 2 km 안의 공식 돌발(가장 가까운 것) */
    private fun inTunnel(s: Double?): Boolean = s != null && zones.any { it.kind == "tunnel" && s >= it.s0 - TUNNEL_MARGIN && s <= it.s1 + TUNNEL_MARGIN }

    /** s 가 들어간 사고 다발 구간(±300 m) */
    private fun zoneAt(s: Double): Zone? = zones.firstOrNull { it.kind == "accident" && s >= it.s0 - ZONE_NEAR && s <= it.s1 + ZONE_NEAR }

    /** 내 앞(10 km 안)과 지금 안에 있는 구역. 말은 교량 결빙(외기 4 °C 이하, 1.5 km 앞)만 */
    private fun zonesView(lang: String, me: Car?, myS: Double?): JSONArray {
        val out = JSONArray()
        if (myS == null) return out
        val temp = me?.temp?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }
        for (z in zones) {
            if (z.s1 < myS || z.s0 - myS > ALERT_RANGE) continue
            val ahead = z.s0 - myS
            val name = if (lang == "ko") z.name else z.nameEn
            val o = JSONObject().put("id", z.id).put("kind", z.kind).put("s0", z.s0).put("s1", z.s1).put("ahead", rnd(ahead)).put("inside", z.s0 <= myS && myS <= z.s1)
                .put("label", if (z.kind == "accident") L(lang, "사고 다발 구간", "Accident-prone section") else name)
            if (z.kind == "accident") {                      // 다발 구간은 지도에만(말 줄이기 10-11)
                o.put("name", name).put("count", z.count).put("deaths", z.deaths).put("years", z.years)
            } else if (z.kind == "bridge" && ahead > 0 && ahead <= ZONE_SAY && temp != null && temp <= ICE_TEMP)
                o.put("say", L(lang, "전방 ${kmText(lang, ahead)} $name 결빙 주의. 서행하세요.", "${kmText(lang, ahead)} ahead, $name: watch for ice. Drive slowly."))
            out.put(o)
        }
        return out
    }

    /** 환경·도로 위험성(서버 _risk 와 같은 규칙) */
    private fun risk(me: Car?, myS: Double?, items: List<Item>, ahead: JSONObject?): JSONObject? {
        if (myS == null) return null
        val temp = me?.temp?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }
        fun near(i: Item, lo: Double, hi: Double) = i.confirmed && i.ahead != null && i.ahead > lo && i.ahead <= hi
        fun zoneNear(kind: String) = zones.any { it.kind == kind && it.s1 >= myS && it.s0 - myS <= RISK_NEAR }
        val hour = ((now + KST) % 86400) / 3600
        val rain = (me?.wiper?.startsWith("CONTINUOUS") == true) || items.any { it.type == "HEAVY_RAIN" && near(it, -500.0, RISK_AHEAD) }
        val out = mutableListOf<JSONObject>()
        val cands = items.filter { (it.type == "INCIDENT" || it.type == "CONGESTION") && near(it, 0.0, RISK_AHEAD) }
            .map { it.ahead!! to (if (it.type == "INCIDENT") "incident" else "jam") }.toMutableList()
        ahead?.optJSONObject("advice")?.let { adv -> if (adv.getInt("dist") <= RISK_AHEAD) cands += adv.getInt("dist") to "jam" }
        cands.minWithOrNull(compareBy<Pair<Int, String>>({ it.first }, { it.second }))?.let { (d, kind) ->
            out += JSONObject().put("tag", "AHEAD").put("pts", RISK_PTS.getValue("AHEAD")).put("ahead", d).put("kind", kind)
        }
        val icy = items.any { it.type == "ICING" && near(it, -500.0, RISK_AHEAD) }
        if (icy || (temp != null && temp <= ICE_TEMP && (rain || hour < 7 || zoneNear("bridge"))))
            out += JSONObject().put("tag", "ICE").put("pts", RISK_PTS.getValue(if (temp != null && temp <= 0) "ICE_HARD" else "ICE"))
        if (zoneNear("accident")) out += JSONObject().put("tag", "ZONE").put("pts", RISK_PTS.getValue("ZONE"))
        if (rain) out += JSONObject().put("tag", "RAIN").put("pts", RISK_PTS.getValue("RAIN"))
        if (zoneNear("tunnel")) out += JSONObject().put("tag", "TUNNEL").put("pts", RISK_PTS.getValue("TUNNEL"))
        if (hour >= 20 || hour < 6) out += JSONObject().put("tag", "NIGHT").put("pts", RISK_PTS.getValue("NIGHT"))
        if (items.any { (it.type == "DEBRIS" || it.type == "BLOCKED") && near(it, 0.0, RISK_NEAR) }) out += JSONObject().put("tag", "DEBRIS").put("pts", RISK_PTS.getValue("DEBRIS"))
        out.sortWith(compareBy<JSONObject>({ -it.getInt("pts") }, { RISK_ORDER.indexOf(it.getString("tag")) }))
        val score = minOf(10, out.sumOf { it.getInt("pts") })
        return JSONObject().put("score", score).put("level", if (score >= RISK_HIGH) 2 else if (score >= RISK_CAUTION) 1 else 0).put("items", JSONArray(out))
    }

    private fun officialNear(s: Double): Official? = official.filter { abs(it.s - s) <= OFFICIAL_NEAR }.minByOrNull { abs(it.s - s) }

    private fun officialItem(lang: String, o: Official): JSONObject = JSONObject().put("type", o.type).put("kind", L(lang, OFFICIAL_KO.getValue(o.type), OFFICIAL_EN.getValue(o.type)))
        .put("desc", o.desc).put("s", rnd(o.s)).put("end", n(o.end)).put("age", rnd(now - o.first))

    /** 모르는 차 1대 확정: 점수 ≥ 6 이고 (응답 없음·에어백) 또는 (충돌+통신 끊김이고 끊기기 전 정지), 세션 2분↑, 30분에 한 번 */
    private fun strong(cid: String, h: Hazard): Boolean {
        val c = car(cid) ?: return false
        val ev = h.ev[cid] ?: emptyList()
        val lossV = c.inc?.lossV
        val stoppedLoss = "IMPACT" in ev && "COMM_LOSS" in ev && lossV != null && lossV < STOP_MS
        if (!stoppedLoss && ((h.score[cid] ?: 0) < INC_STRONG || !("NO_RESPONSE" in ev || "AIRBAG" in ev))) return false
        if (now - c.joinT < STRONG_SESSION) return false
        if (c.strongH == h.id) return true
        c.strongAt?.let { if (now - it < STRONG_GAP) return false }
        c.strongAt = now; c.strongH = h.id
        return true
    }

    /** 근거 둘까지: '급제동 후 충돌', '충돌, 응답 없음' */
    private fun evidencePhrase(lang: String, tags: List<String>): String {
        val two = INC_ORDER.filter { it in tags }.take(2)
        if (two.isEmpty()) return L(lang, "급제동 후 정차", "stopped after hard braking")
        val names = two.map { L(lang, INC_KO.getValue(it), INC_EN.getValue(it)) }
        if (names.size == 1) return names[0]
        val seq = two[0] in setOf("SWERVE", "HARD_BRAKE", "IMPACT") && two[1] in setOf("IMPACT", "STOP", "AIRBAG")
        return L(lang, if (seq) "${names[0]} 후 ${names[1]}" else "${names[0]}, ${names[1]}", if (seq) "${names[0]} then ${names[1]}" else "${names[0]}, ${names[1]}")
    }

    fun event(b: JSONObject) {
        clock(b)
        car(str(b, "me"))?.let { c ->
            // 이벤트에 실린 그 순간의 위치·속도(차가 보내면)를 먼저 반영 — 마지막 상태 보고 자리에 찍히지 않게. PC 엔진과 같은 규칙
            if (b.has("s") && !b.isNull("s")) c.s = b.getDouble("s")
            if (b.has("speed") && !b.isNull("speed")) c.speed = b.getDouble("speed")
            ev(c, str(b, "type"), str(b, "value"), b)
        }
    }

    private fun derive(c: Car) {
        val s = c.s ?: return
        val v = c.speed
        if (v < STOP_MS && restNear(s) != null) {       // 휴게소에 세운 차(충전·휴식)는 정체·사고 의심의 근거가 아니다
            for (type in listOf("CONGESTION", "INCIDENT")) if (type in c.active) clear(c, type)
            c.slowSince = null; c.stopSince = null
            return
        }
        if (v < SLOW_MS) {
            if (c.slowSince == null) c.slowSince = now
            if (now - c.slowSince!! >= SLOW_HOLD && "CONGESTION" !in c.active) report(c, "CONGESTION", "${rnd(v * 3.6)}km/h")
        } else if (v > FAST_MS) {
            c.slowSince = null
            if ("CONGESTION" in c.active) clear(c, "CONGESTION")
        }
        if (v < STOP_MS) {
            if (c.stopSince == null) c.stopSince = now
            val inc = c.inc
            if (inc != null && !inc.closed && "STOP" !in inc.ev && now - c.stopSince!! >= INCIDENT_HOLD && abs(s - inc.s0) <= INC_NEAR)
                evidence(c, "STOP")                  // 사건(급제동·급조향·충돌) 뒤 300 m 안 30 s 정차
        } else {
            c.stopSince = null
            if (v > FAST_MS) {
                if ("INCIDENT" in c.active) clear(c, "INCIDENT")
                c.inc?.closed = true                  // 다시 달린다 = 창을 닫는다
            }
        }
    }

    private fun ev(c: Car, signal: String, value: String, b: JSONObject? = null) {
        val s = c.s ?: return
        if (signal.isEmpty()) return
        var raw = signal
        var type = EVENT_TYPE[signal] ?: signal
        if (type.isEmpty()) return
        if (type.endsWith("_CLEARED")) { clear(c, type.removeSuffix("_CLEARED")); return }
        if (type == "HARD_BRAKE") c.brake = now to s
        if (raw == "IMPACT" && !impactOk(b)) { raw = "HARD_BRAKE"; type = "HARD_BRAKE" }   // 허브 재검증 실패(한 표본 튐 등) → 급제동으로
        if (raw in INC_POINTS) evidence(c, raw)                                          // 급제동·급조향·충돌·에어백은 사고 판단의 근거
        if (raw == "IMPACT" || raw == "AIRBAG") return                                   // 그 자체는 위험 종류가 아니다
        if (type in CHARGER) { restNear(s)?.let { r -> report(c, type, value, r.s, r) }; return }
        if (type in TTL) report(c, type, value)
    }

    /** 차 위치 500m 안의 휴게소(충전기가 있는 곳) — 없으면 null */
    private fun restNear(s: Double): Rest? = rest.filter { it.kind == "services" && abs(it.s - s) <= CHARGER_NEAR }.minByOrNull { abs(it.s - s) }

    private fun report(c: Car, type: String, value: String, at: Double? = null, restAt: Rest? = null): Hazard? {
        val s = at ?: c.s ?: return null
        if (type !in ONE_SHOT) c.active += type
        pulses += JSONObject().put("t", r1(now)).put("s", rnd(s)).put("type", type).put("car", c.id).put("pub", c.pub)
        while (pulses.size > 40) pulses.removeAt(0)
        val merge = MERGE[type] ?: MERGE_M
        for (h in hazards) if (h.type == type && abs((if (type == "SIREN") siren(h).first else h.s) - s) <= merge) {
            if (type == "SIREN" && c.id !in h.reporters) { h.track += now to s; h.s = s }   // 이 차가 처음 들었다 = 긴급차량이 여기 가까이 왔다
            h.reporters[c.id] = now; h.last = now; h.value = value; return h
        }
        seq++
        val nh = Hazard("h$seq", type, s, now).also { it.reporters[c.id] = now; it.value = value; it.rest = restAt }
        hazards += nh
        c.group?.let { groups[it] }?.let { g -> if (c.kind != "anon") glog(g, "${c.name} 차: ${TYPE_KO[type]}", "${c.name}: ${TYPE_EN[type]}") }
        return nh
    }

    private fun clear(c: Car, type: String) {
        c.active -= type
        for (h in hazards) if (h.type == type && c.id in h.reporters) {
            h.reporters.remove(c.id)
            if (h.reporters.isEmpty()) h.cleared = now
        }
    }

    /** 긴급차량의 지금 위치(짐작)와 경로 위 속도. 차들이 처음 들은 곳이 앞으로 옮겨 가는 속도로 잰다(모르면 null) */
    private fun siren(h: Hazard): Pair<Double, Double?> {
        val tr = h.track.filter { now - it.first <= SIREN_TRACK }
        if (tr.size < 2 || tr.last().first - tr.first().first < SIREN_MIN_DT) return h.s to null
        val v = (tr.last().second - tr.first().second) / (tr.last().first - tr.first().first)
        return (if (v > 0) h.s + v * minOf(now - tr.last().first, SIREN_EXTRAP) else h.s) to v
    }

    // ---- 시연 도로: 1초 단위
    private fun worldSpeed(c: Car, k: Int): Double {
        val rel = c.s!! - demo!!.s0
        if (c.demo!!.kind == "crash" && k < CRASH_END) return 0.0
        if (rel >= JAM_TAIL && rel <= JAM_HEAD && k < JAM_END) return JAM_MS
        return c.demo.free
    }

    private fun step(k: Int) {
        val d = demo!!
        now = d.t0 + k
        for (c in cars.filter { it.demo != null && !it.gone }) {
            if (k == 1 && c.demo!!.kind == "crash") ev(c, "HARD_BRAKE", "crash")
            val prevRel = c.s!! - d.s0
            val v = worldSpeed(c, k)
            c.s = c.s!! + v * DEMO_SCALE
            c.speed = v; c.t = now
            val rel = c.s!! - d.s0
            val inJam = rel >= JAM_TAIL && rel <= JAM_HEAD && k < JAM_END
            val wasJam = prevRel >= JAM_TAIL && prevRel <= JAM_HEAD
            if (inJam && !wasJam && c.demo!!.kind != "crash") {
                c.speed = JAM_MS
                if (d.entries < BRAKE_ENTRIES) { d.entries++; ev(c, "HARD_BRAKE", "jam tail") }
            }
            val rain = rel >= RAIN_FROM && rel <= RAIN_TO
            if (rain != c.wiper.startsWith("CONTINUOUS")) {
                c.wiper = if (rain) "CONTINUOUS_3" else "OFF"
                ev(c, if (rain) "HEAVY_RAIN" else "HEAVY_RAIN_CLEARED", c.wiper)
            }
            if (c.demo!!.kind != "crash") {
                if (prevRel < DEBRIS_S && DEBRIS_S <= rel) ev(c, "SWERVE", "steer")         // 낙하물 지점을 지나며 핸들 급회피
                if (rel >= DOWNHILL_FROM && rel <= DOWNHILL_TO) {                          // 긴 내리막: 브레이크를 계속 밟음
                    if (c.holdSince == null) c.holdSince = now
                    if (now - c.holdSince!! >= BRAKE_HOLD_SEC && c.holdSince!! > 0) {
                        c.holdSince = -1.0                                                  // 한 번만 보고
                        ev(c, "BRAKE_HOLD", "${BRAKE_HOLD_SEC}s")
                    }
                } else c.holdSince = null
                val tunnel = rel >= TUNNEL_FROM && rel <= TUNNEL_TO
                if (tunnel != c.tunnel) {
                    c.tunnel = tunnel
                    ev(c, if (tunnel) "RECIRC_ON" else "RECIRC_OFF", "recirc")
                }
                if (prevRel < TURNBACK_S && TURNBACK_S <= rel) {                            // 침수: 되돌아간다(도로에서 빠진다)
                    ev(c, "TURNBACK", "reverse")
                    c.gone = true; c.speed = 0.0
                    continue
                }
            }
            derive(c)
        }
        ambulance(k)
    }

    /** 구급차: 정체 꼬리 뒤에서 나타나 사고 지점으로. 사이렌이 들리는 Tago 차는 5초마다 보고한다(차 안 마이크) */
    private fun ambulance(k: Int) {
        val d = demo!!
        if (k == AMB_K) {
            d.amb = Amb(d.s0 + AMB_OFF)
            for ((off, free) in AMB_WITNESS) newCar("", "", "anon", "ko", Demo("anon", free))?.also { it.s = d.s0 + AMB_OFF + off; it.speed = free }
            return
        }
        val a = d.amb ?: return
        if (!a.on) return
        val rel = a.s - d.s0
        a.s += (if (rel >= JAM_TAIL && rel <= JAM_HEAD && k < JAM_END) AMB_JAM_MS else AMB_MS) * DEMO_SCALE
        if (a.s - d.s0 >= AMB_STOP) { a.s = d.s0 + AMB_STOP; a.on = false; return }   // 사고 지점 도착: 사이렌을 끈다
        for (c in cars.toList()) {
            if (c.demo != null && !c.gone && c.demo.kind != "crash" && abs(c.s!! - a.s) <= HEAR_M && (c.sirenAt == null || now - c.sirenAt!! >= SIREN_EVERY)) {
                c.sirenAt = now
                ev(c, "SIREN", "mic")
            }
        }
    }

    private fun advance(t: Double) {
        demo?.let { d ->
            val kmax = floor(t - d.t0).toInt()
            if (kmax - d.k > MAX_STEPS) d.k = kmax - MAX_STEPS   // 시계가 크게 뛰면 건너뛴다(서버와 같음)
            while (d.k < kmax) { d.k++; step(d.k) }
        }
        now = t
        hazards = hazards.filter { h ->
            if (h.type in STICKY && h.cleared == null && h.reporters.isNotEmpty()) {
                // 해제 신호 없이 사라진 차(앱 종료·통신 끊김)의 보고는 거둔다 — 보고한 차가 모두 사라지면 위험도 지운다
                for (cid in h.reporters.filter { (id, at) -> !alive(id, at) }.keys) {
                    h.reporters.remove(cid)
                    car(cid)?.active?.remove(h.type)        // 다시 소식이 오면 새로 보고할 수 있게
                }
                if (h.reporters.isEmpty()) return@filter false
            }
            val end = h.cleared ?: h.last
            t - end < TTL.getValue(h.type) || (h.cleared == null && h.type in STICKY && h.reporters.isNotEmpty())
        }.toMutableList()
        for (o in official.filter { (it.end != null && t >= it.end!!) || t - it.t >= OFFICIAL_TTL }) {
            official.remove(o)
            if (o.end != null && t >= o.end!!) for (h in hazards) {   // 공식 해제(endTime): 교차 확인됐던 사고 의심도 거둔다
                if (h.type != "INCIDENT" || h.reporters.isEmpty() || abs(h.s - o.s) > OFFICIAL_NEAR) continue
                for (r in h.reporters.keys.toList()) car(r)?.let { x -> x.active.remove("INCIDENT"); x.inc?.closed = true }
                h.reporters.clear(); h.cleared = t
            }
        }
        codes.entries.removeAll { (_, gid) -> groups[gid]?.let { t - it.codeAt >= CODE_TTL } ?: true }   // 30분 지난 참가 코드는 닫는다
        for (g in groups.values) journey(g)              // 시연 차(가족)도 움직이므로 시계가 갈 때마다 여정을 맞춘다
    }

    fun demoRoad(b: JSONObject) {
        clock(b)
        if (demo != null) return
        val s0 = car(str(b, "me"))?.s ?: 18000.0
        demo = WorldDemo(now, s0)
        for ((kind, off, free) in DEMO_CARS) {
            val c = newCar("", "", "anon", "ko", Demo(kind, free)) ?: continue
            c.s = s0 + off; c.speed = free
        }
    }

    // ---- 가족 그룹(선택)
    private fun glog(g: Group, ko: String, en: String) { g.log += ko to en; while (g.log.size > 30) g.log.removeAt(0) }

    fun createGroup(b: JSONObject): Group? {
        clock(b)
        val c = car(str(b, "me")) ?: return null
        // 그룹은 GROUP_MAX개까지(시연은 한두 개면 된다) — 넘치면 가장 오래된 그룹과 코드를 지운다. 100번 뽑아도 비지 않으면 포기한다(무한 루프 방지)
        while (groups.size >= GROUP_MAX) { val old = groups.keys.firstOrNull() ?: break; dropGroup(old) }
        var gid: String; var n = 0; do { gid = rid(6) } while (gid in groups && ++n < 100)
        var code: String; n = 0; do { code = rid(4, "0123456789") } while (code in codes && ++n < 100)
        if (gid in groups || code in codes) return null
        val g = Group(gid, code).also { it.members += c.id; it.ride = c.id; it.codeAt = now }
        groups[gid] = g; codes[code] = gid
        c.group = gid
        clean(str(b, "name"), NAME_MAX).takeIf { it.isNotEmpty() }?.let { c.name = it }
        clean(str(b, "car"), CAR_MAX).takeIf { it.isNotEmpty() }?.let { c.car = it }
        glog(g, "${c.name} 님이 그룹을 만들었어요", "${c.name} started the group")
        return g
    }

    private fun codeOk(g: Group) = codes[g.code] == g.id && now - g.codeAt < CODE_TTL

    /** 코드로 합류. 결과: 그룹과 차, 또는 까닭("code" 틀림·만료, "locked" 잠시 잠김, "full" 도로가 꽉 참) */
    fun joinGroup(b: JSONObject): Joined {
        clock(b)
        val me = car(str(b, "me"))
        val key = me?.id ?: ""                           // 차 id 없는 참가(휴대폰 웹)는 한 칸을 같이 쓴다(아무 id나 지어내 칸을 늘리지 못하게)
        tries[key]?.let { if (now < it.until) return Joined(null, null, "locked") }
        val g = codes[str(b, "code")]?.let { groups[it] }
        if (g == null || !codeOk(g)) {
            val tr = tries.getOrPut(key) { Try() }
            tr.n++
            if (tr.n >= JOIN_TRIES) { tr.n = 0; tr.until = now + JOIN_LOCK }
            for (x in groups.values) if (codes[x.code] == x.id) {   // 틀린 시도는 살아 있는 모든 코드의 몫으로 센다 → 코드 하나에 쓸 수 있는 시도 수가 정해진다
                x.wrong++
                if (x.wrong >= CODE_WRONG_MAX) codes.remove(x.code)   // 너무 많이 틀렸다 → 이 코드는 닫는다(이미 합류한 사람은 그대로)
            }
            return Joined(null, null, "code")
        }
        val c = me ?: newCar(clean(str(b, "name"), NAME_MAX).ifEmpty { "?" }, clean(str(b, "car"), CAR_MAX), clean(str(b, "kind"), KIND_MAX).ifEmpty { "web" },
            langOf(str(b, "lang"))) ?: return Joined(null, null, "full")
        tries.remove(key)
        clean(str(b, "name"), NAME_MAX).takeIf { it.isNotEmpty() }?.let { c.name = it }
        c.group = g.id
        if (c.id !in g.members) g.members += c.id
        glog(g, "${c.name} 님이 합류했어요", "${c.name} joined")
        return Joined(g, c)
    }

    fun demoGroup(g: Group, b: JSONObject) {
        clock(b)
        if (g.demo) return
        if (demo == null) demoRoad(b)
        g.demo = true
        val d = demo!!
        val lang = car(str(b, "me"))?.lang ?: "ko"
        for (f in DEMO_FAMILY) {
            val c = newCar(if (lang == "ko") f.ko else f.en, if (lang == "ko") f.car else f.carEn, "pleos", lang, Demo("family", f.free)) ?: continue
            c.s = d.s0 + f.off + f.free * DEMO_SCALE * d.k; c.speed = f.free; c.soc = f.soc; c.group = g.id
            g.members += c.id
            glog(g, "${f.ko} 님이 합류했어요", "${f.en} joined")
        }
    }

    fun rest(g: Group, b: JSONObject) {
        clock(b)
        val c = car(str(b, "me"))
        val group = g.members.mapNotNull { car(it) }.filter { it.s != null }
        if (c == null || group.isEmpty()) return
        val front = group.maxOf { it.s!! }
        val nxt = rest.firstOrNull { it.kind == "services" && it.s >= front + REST_AHEAD }
        g.answers = linkedMapOf()
        for (a in g.acts) if (a.type == "rest" && a.state == "proposed") a.state = "cancelled"   // 새 제안이 앞 제안을 대신한다
        if (nxt == null) { g.rest = JSONObject().put("by", c.id).put("none", true); return }
        g.rest = JSONObject().put("by", c.id).put("name", nxt.name).put("nameEn", nxt.nameEn).put("s", nxt.s).put("lat", nxt.lat).put("lon", nxt.lon)
        g.answers[c.id] = true
        for (x in group) if (x.demo != null) g.answers[x.id] = true
        act(g, "rest", "rest", nxt.name, c.name, "lead_1km", Place(nxt.name, nxt.nameEn, nxt.s, ""))
        glog(g, "${c.name} 님 제안: 다같이 ${nxt.name}", "${c.name} suggests: everyone stop at ${nxt.nameEn}")
        journey(g)
    }

    fun restAnswer(g: Group, b: JSONObject) {
        clock(b)
        val r = g.rest ?: return
        if (!r.optBoolean("none") && member(g, b) != null) g.answers[str(b, "me")] = b.optBoolean("yes")
    }

    // ---- 여정: 할 일(act) 공통 처리
    private fun act(g: Group, type: String, kind: String, label: String, by: String, basis: String?, place: Place? = null): Act {
        g.seq++
        return Act("a${g.seq}", type, kind, label, by, basis, place).also { g.acts += it }
    }

    private fun done(a: Act, how: String, who: String) { a.state = "done"; a.how = how; a.doneBy = who; a.at = r1(now) }

    private fun steps(g: Group) = g.acts.filter { it.type == "step" && it.state != "cancelled" }

    /** 쉬어 갈 곳(장소 조건): 일행이 정한 휴게소, 없으면 타고 있는 차 앞의 다음 휴게소 */
    private fun restTarget(g: Group, c: Car?): Place? {
        val r = g.rest
        if (r != null && !r.optBoolean("none")) return Place(r.getString("name"), r.getString("nameEn"), r.getDouble("s"), "agreed")
        val s = c?.s ?: return null
        return rest.firstOrNull { it.kind == "services" && it.s > s }?.let { Place(it.name, it.nameEn, it.s, "next") }
    }

    /** 차 관측으로 끝나는 할 일만 끝낸다: 정한 휴게소에 일행 차가 섰다 / 타고 있는 차가 출발·휴게소 정차·도착했다. 사람이 하는 일은 건드리지 않는다 */
    private fun journey(g: Group) {
        for (a in g.acts) if (a.type == "rest" && a.state == "proposed") {
            val x = g.members.mapNotNull { car(it) }.firstOrNull { it.s != null && it.speed < STOP_MS && abs(it.s!! - a.place!!.s) <= NEAR_STOP }
            if (x != null) done(a, "vehicle", x.name)
        }
        val c = car(g.ride) ?: return
        val cs = c.s ?: return
        val target = restTarget(g, c)
        val st = steps(g)
        for (a in st) {
            if (a.state == "done") continue
            val ok = (a.kind == "board" && c.speed >= MOVE_MS) ||
                (a.kind == "rest" && target != null && c.speed < STOP_MS && abs(target.s - cs) <= NEAR_STOP) ||
                (a.kind == "arrive" && c.speed < STOP_MS && cs >= length - ARRIVE_NEAR)
            if (!ok) break
            done(a, "vehicle", c.name)
        }
        // 쉬어 갈 곳이 바뀌면 알린다(휴게소 카드가 남아 있을 때) — 누군가 '알겠어요'를 누를 때까지 남긴다
        if (target != null && st.any { it.kind == "rest" && it.state != "done" }) {
            val p = g.planned
            if (p == null) g.planned = target
            else if (p.name != target.name && (g.changed == null || g.changed!!.second.name != target.name)) g.changed = p to target
        }
    }

    private fun member(g: Group, b: JSONObject) = car(str(b, "me"))?.takeIf { it.id in g.members }

    /** 다음 한 단계 카드 정하기(보호자). 알 수 없는 종류는 버리고, 글자는 24자까지. 새로 정하면 앞 카드는 취소된다 */
    fun setSteps(g: Group, b: JSONObject) {
        clock(b)
        val c = member(g, b) ?: return
        val a = b.optJSONArray("steps") ?: return
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until minOf(a.length(), 50)) {         // 걸러 낸 뒤 6장까지(잘못된 카드가 앞의 자리를 차지하지 않게)
            val s = a.optJSONObject(i) ?: continue
            val kind = s.optString("kind")
            val label = (if (s.isNull("label")) "" else s.optString("label")).trim().take(STEP_LABEL_MAX)
            if (kind in STEP_KINDS && label.isNotEmpty()) out += kind to label
            if (out.size == STEP_MAX) break
        }
        for (x in steps(g)) x.state = "cancelled"
        for ((kind, label) in out) act(g, "step", kind, label, c.name, null)
        g.stepsBy = c.name; g.planned = null; g.changed = null
        glog(g, "${c.name} 님이 다음 단계 카드 ${out.size}장을 정했어요", "${c.name} set ${out.size} next-step cards")
        journey(g)
    }

    /** 카드 하나를 '했어요'(사람 확인) 또는 되돌리기 */
    fun stepDone(g: Group, b: JSONObject) {
        clock(b)
        val c = member(g, b) ?: return
        val i = b.opt("i") as? Int ?: return
        val st = steps(g)
        if (i !in st.indices) return
        val a = st[i]
        if (b.optBoolean("done", true)) {
            done(a, "person", c.name)
            glog(g, "${c.name}: ${a.label} 했어요", "${c.name}: done — ${a.label}")
        } else { a.state = "pending"; a.how = null; a.doneBy = ""; a.at = null }
    }

    /** 동승자가 타고 있는 차(사람의 선택). 카드의 차 관측은 이 차를 따른다 */
    fun journeyRide(g: Group, b: JSONObject) {
        clock(b)
        val c = member(g, b) ?: return
        val ride = str(b, "ride")                         // 보기에 나온 id(남의 차는 pub) 또는 내 id
        val r = g.members.mapNotNull { car(it) }.firstOrNull { ride == it.id || ride == it.pub } ?: return
        g.ride = r.id; g.planned = null; g.changed = null
        glog(g, "${c.name}: ${r.name} 차에 타요", "${c.name}: riding in ${r.name}'s car")
        journey(g)
    }

    /** '쉬는 곳이 바뀌었어요'를 확인했다 */
    fun journeyAck(g: Group, b: JSONObject) {
        clock(b)
        if (member(g, b) != null) g.changed?.let { g.planned = it.second; g.changed = null }
    }

    private fun n(x: Any?): Any = x ?: JSONObject.NULL

    // ---- 보기
    fun view(mid: String?): JSONObject {
        scanCommLoss()
        val me = car(mid)
        val lang = me?.lang ?: "ko"
        val myS = me?.s
        val myV = me?.speed ?: 0.0
        val g = me?.group?.let { groups[it] }
        val mine = g?.members?.toSet() ?: emptySet()
        val others = cars.filter { it.id != mid && it.s != null && now - it.t <= ACTIVE_SEC && !it.gone }
        val nearby = if (myS == null) 0 else others.count { abs(it.s!! - myS) <= ALERT_RANGE }
        val items = mutableListOf<Item>()
        for (h in hazards) {
            if (h.reporters.isEmpty() && h.cleared != null) continue
            val reps = h.reporters.keys.toList()
            if (reps == listOf(mid)) continue                     // 나만 보고한 것은 나에게 안 알린다
            val (hs, hv) = if (h.type == "SIREN") siren(h) else h.s to null
            val ahead = myS?.let { hs - it }
            val named = (g?.members ?: emptyList<String>()).filter { it in h.reporters && it != mid }.map { car(it)!!.name }   // 일행 합류 순서(차 id는 무작위라 id 순서는 뜻이 없다)
            val anon = reps.count { it !in mine && it != mid }
            val incScore = if (h.type == "INCIDENT") (h.score.values.maxOrNull() ?: 0) else 0
            val isStrong = h.type == "INCIDENT" && reps.any { strong(it, h) }
            val off = if (h.type == "INCIDENT") officialNear(h.s) else null   // 공식 돌발 ±2 km = 공식 확인
            val confirmed = named.isNotEmpty() || reps.size >= ANON_CONFIRM || isStrong || off != null
            val level = if ((reps.size >= 3 && h.type in LEVEL_UP) || incScore >= INC_STRONG || isStrong || off != null) 2 else 1
            val o = JSONObject().put("id", h.id).put("type", h.type).put("label", if (lang == "ko") TYPE_KO.getValue(h.type) else TYPE_EN.getValue(h.type)).put("s", rnd(hs)).put("pos", pointAt(hs))
                .put("ahead", n(ahead?.let { rnd(it) })).put("by", JSONArray(named)).put("anon", anon).put("cars", reps.size)
                .put("confirmed", confirmed).put("level", level).put("age", rnd(now - h.first))
            val tags = if (h.type == "INCIDENT") INC_ORDER.filter { t -> h.ev.values.any { t in it } } else emptyList()
            val partial = h.type == "INCIDENT" && h.passed.size in 1 until CLEAR_PASS
            if (h.type == "INCIDENT") o.put("evidence", JSONArray(tags)).put("score", incScore).put("partial", partial).put("queueWarn", true)
                .put("official", n(off?.let { officialItem(lang, it) })).put("zone", n(zoneAt(h.s)?.id))
            if (off != null) o.put("label", L(lang, "${OFFICIAL_KO.getValue(off.type)}(도로 정보 확인)", "${OFFICIAL_EN.getValue(off.type)} (road authority)"))   // 공식 확인이면 이름표도 '의심'을 뗀다
            CAUSE_CODE[h.type]?.let { o.put("causeCode", it) }
            o.put("informationQuality", if (off != null) 7 else minOf(7, incScore + reps.size))
            if (h.type == "SIREN") {
                // 뒤에서 다가오는 긴급차량만(반대 차로·지나간 차는 말하지 않는다). 방향을 알기 전에는 화면에만
                o.put("oncoming", hv != null && hv < 0)
                val behind = ahead?.let { -it }
                val closing = if (hv == null || me == null) null else hv - (me.vs ?: myV)
                val eta = if (behind != null && closing != null && closing > 1 && behind > 0) behind / closing else null
                o.put("eta", n(eta?.let { rnd(it) }))
                if (behind != null && behind > 0 && behind <= SIREN_RANGE) {
                    o.put("level", if (behind <= SIREN_NEAR) 2 else 1)
                    if (confirmed && hv != null && hv > 0) o.put("say", sirenSentence(lang, behind, named, anon, reps.size, eta))
                }
                items += Item(o, rnd(hs), ahead?.let { rnd(it) }, h.id, h.type, confirmed, reps.size)
                continue
            }
            // 폭우는 따로 말하지 않는다: 비 예보(ahead.rain)가 같은 내용을 미리 말한다
            if (ahead != null && ahead > 0 && ahead <= ALERT_RANGE && confirmed && (h.type != "HEAVY_RAIN" || named.isNotEmpty())) {
                // 되돌아간 차 근처(6km)에 확인된 폭우가 있으면 '침수 의심'
                val wet = h.type == "BLOCKED" && hazards.any { x -> x.type == "HEAVY_RAIN" && x.reporters.size >= ANON_CONFIRM && abs(x.s - h.s) <= RAIN_NEAR }
                o.put("say", sentence(lang, h.type, ahead, named, anon, reps.size, wet, h.rest, h.value, tags, level, partial, off,
                    if (h.type == "INCIDENT") zoneAt(h.s) else null))
                if (wet) o.put("flood", true)
            }
            h.rest?.let { o.put("rest", if (lang == "ko") it.name else it.nameEn) }
            items += Item(o, rnd(h.s), ahead?.let { rnd(it) }, h.id, h.type, confirmed, reps.size)
        }
        for (o in official) {                            // 사고 의심이 없는 공식 돌발: 화면에만(순정 내비가 이미 말한다)
            if (hazards.any { it.type == "INCIDENT" && it.reporters.isNotEmpty() && abs(it.s - o.s) <= OFFICIAL_NEAR }) continue
            val ahead = myS?.let { o.s - it }
            val kind = L(lang, OFFICIAL_KO.getValue(o.type), OFFICIAL_EN.getValue(o.type))
            val j = JSONObject().put("id", o.id).put("type", "OFFICIAL").put("label", L(lang, "도로 정보: $kind", "Road info: $kind")).put("s", rnd(o.s)).put("pos", pointAt(o.s))
                .put("ahead", n(ahead?.let { rnd(it) })).put("by", JSONArray()).put("anon", 0).put("cars", 0).put("confirmed", true).put("level", 1)
                .put("age", rnd(now - o.first)).put("official", officialItem(lang, o)).put("causeCode", OFFICIAL_CAUSE.getValue(o.type)).put("informationQuality", 7)
            items += Item(j, rnd(o.s), ahead?.let { rnd(it) }, o.id, "OFFICIAL", true, 0)
        }
        items.sortWith(compareBy<Item>({ it.ahead ?: Int.MAX_VALUE }, { it.id }))
        val spoken = mutableListOf<Item>()
        for (it in items.filter { i -> i.o.has("say") }.sortedWith(compareBy<Item>({ i -> PRIORITY.getValue(i.type) }, { i -> i.ahead }, { i -> i.id }))) {
            if (spoken.any { o -> abs(it.s - o.s) <= MERGE_M }) it.o.remove("say") else spoken += it
        }
        val ahead = if (myS != null) ahead(lang, myS, myV, others, items) else null
        var restNext: JSONObject? = null
        if (myS != null) rest.firstOrNull { it.s > myS }?.let { r ->
            restNext = JSONObject().put("name", if (lang == "ko") r.name else r.nameEn).put("kind", r.kind).put("dist", rnd(r.s - myS)).put("charger", n(chargerAt(r)))
        }
        val group = g?.let { groupView(it, mid, lang, myS) }
        val riskV = risk(me, myS, items, ahead)
        return JSONObject().put("me", n(mid)).put("lang", lang).put("now", r1(now)).put("s", n(myS?.let { rnd(it) })).put("routeLength", rnd(length)).put("risk", n(riskV))
            .put("nearby", nearby).put("hazards", JSONArray(items.map { it.o })).put("zones", zonesView(lang, me, myS)).put("ahead", n(ahead)).put("restNext", n(restNext)).put("group", n(group))
            .put("brief", brief(lang, nearby, group, restNext)).put("demo", demo != null)
    }

    /** 앞길 미리보기: 500m 칸마다 앞차들의 평균 속도·와이퍼·위험. 첫 정체 칸이면 권장 속도, 확인된 폭우면 비 예보 */
    private fun ahead(lang: String, myS: Double, myV: Double, others: List<Car>, items: List<Item>): JSONObject {
        val bins = JSONArray()
        val binKmh = mutableListOf<Int?>(); val binCars = mutableListOf<Int>()
        for (i in 0 until BINS) {
            val lo = myS + i * BIN_M; val hi = myS + (i + 1) * BIN_M
            val cs = others.filter { it.s!! > lo && it.s!! <= hi }
            val hz = items.filter { it.confirmed && it.s > lo && it.s <= hi }.map { it.type }.toSet().sortedBy { PRIORITY.getValue(it) }
            val kmh = if (cs.isEmpty()) null else rnd(cs.sumOf { it.speed } / cs.size * 3.6)
            binKmh += kmh; binCars += cs.size
            bins.put(JSONObject().put("cars", cs.size).put("kmh", n(kmh)).put("rain", cs.count { it.wiper.startsWith("CONTINUOUS") }).put("hz", JSONArray(hz)))
        }
        var advice: JSONObject? = null
        val myKmh = myV * 3.6
        for (i in 0 until BINS) {
            val k = binKmh[i]
            if (binCars[i] >= 2 && k != null && k < JAM_KMH) {
                // 일률 80: 따르는 차와 안 따르는 차의 속도 차를 키우지 않게
                val d = i * BIN_M
                val adv10 = ADVISE_KMH
                advice = JSONObject().put("dist", rnd(d)).put("jamS", rnd(myS + d)).put("jamKmh", k).put("adviseKmh", adv10)
                if (d >= BIN_M && myKmh >= adv10 + 8)
                    advice.put("say", L(lang, "전방 ${kmText(lang, d)} 정체 시작. 지금부터 시속 ${adv10}까지 서서히 줄이세요.",
                        "Slow traffic in ${kmText(lang, d)}. Ease off to $adv10 km/h now."))
                break
            }
        }
        // 비 예보: 앞쪽에서 와이퍼를 켠 차가 2대 이상 확인된 지점(폭우 위험) — 하늘 대신 앞차들로
        var rain: JSONObject? = null
        items.filter { it.type == "HEAVY_RAIN" && it.confirmed && it.ahead != null && it.ahead > 0 && it.ahead <= ALERT_RANGE }
            .minWithOrNull(compareBy<Item>({ it.ahead }, { it.id }))?.let { h ->
                val eta = h.ahead!! / maxOf(myV, 8.3)
                val m = maxOf(1, rnd(eta / 60))
                rain = JSONObject().put("dist", h.ahead).put("eta", rnd(eta)).put("cars", h.cars).put("id", h.id)
                    .put("say", L(lang, "약 ${m}분 후 강우 예상. 전방 차량 ${h.cars}대 와이퍼 작동 중.", "Rain expected in about $m min. ${h.cars} cars ahead have wipers on."))
            }
        return JSONObject().put("bin", rnd(BIN_M)).put("bins", bins).put("advice", n(advice)).put("rain", n(rain))
    }

    private fun groupView(g: Group, mid: String?, lang: String, myS: Double?): JSONObject {
        val members = JSONArray()
        for (cid in g.members) {
            val x = car(cid)!!
            val gap = if (myS == null || x.s == null) null else x.s!! - myS
            members.put(JSONObject().put("id", pid(x, mid)).put("name", x.name).put("car", x.car).put("kind", x.kind).put("me", x.id == mid)
                .put("s", n(x.s?.let { rnd(it) })).put("pos", n(x.s?.let { pointAt(it) })).put("gap", n(gap?.let { rnd(it) }))
                .put("gapMin", n(gap?.let { rnd(abs(it) / CRUISE_MS / 60) })).put("speedKmh", rnd(x.speed * 3.6)).put("soc", n(x.soc))
                .put("status", if (x.speed < STOP_MS) "stopped" else if (x.speed < SLOW_MS) "slow" else "moving"))
        }
        var restOut: Any = JSONObject.NULL
        g.rest?.let { R ->
            if (R.optBoolean("none")) restOut = JSONObject().put("none", true).put("say", L(lang, "전방에 일행이 함께 들를 휴게소가 없습니다.", "No rest area ahead for the group."))
            else {
                val nm = if (lang == "ko") R.getString("name") else R.getString("nameEn")
                val by = car(R.getString("by"))
                val d = myS?.let { rnd(R.getDouble("s") - it) }
                val o = JSONObject().put("name", nm).put("s", R.getDouble("s")).put("pos", JSONArray().put(R.getDouble("lat")).put(R.getDouble("lon")))
                    .put("dist", n(d)).put("by", by?.name ?: "").put("mine", R.getString("by") == mid).put("answered", mid in g.answers)
                    .put("yes", JSONArray(g.members.filter { g.answers[it] == true }.map { car(it)!!.name }))
                    .put("pending", JSONArray(g.members.filter { it !in g.answers }.map { car(it)!!.name }))
                val where = if (d == null) "" else L(lang, " ${kmText(lang, d.toDouble())} 전방.", ", ${kmText(lang, d.toDouble())} ahead")
                o.put("say", if (R.getString("by") == mid) L(lang, "$nm 경유 요청을 일행에게 보냈습니다.$where", "Sent the group a request to stop at $nm$where.")
                    else L(lang, "${by?.name ?: ""} 님이 $nm 경유를 제안했습니다.$where 경유할까요?", "${by?.name ?: ""} suggests a stop at $nm$where. Stop there?"))
                restOut = o
            }
        }
        return JSONObject().put("id", g.id).put("code", n(if (codeOk(g)) g.code else null)).put("members", members).put("rest", restOut)
            .put("steps", JSONArray(steps(g).map { a -> JSONObject().put("kind", a.kind).put("label", a.label).put("done", a.state == "done")
                .put("how", n(a.how)).put("doneBy", a.doneBy) }))
            .put("stepsBy", g.stepsBy).put("journey", journeyView(g, lang, mid)).put("log", JSONArray(g.log.takeLast(8).map { L(lang, it.first, it.second) }))
    }

    /** 여정 보기: 차량 관측(observed) · 장소 조건(place) · 할 일(acts: 서비스 판단의 근거, 사람의 선택, 끝난 방법) · 바뀐 계획(changed) */
    private fun journeyView(g: Group, lang: String, mid: String?): JSONObject {
        val ride = car(g.ride)
        val target = restTarget(g, ride)
        fun nm(p: Place) = if (lang == "ko") p.name else p.nameEn
        val acts = JSONArray()
        for (a in g.acts) {
            if (a.state == "cancelled") continue
            var st = a.state
            if (a.type == "rest" && st == "proposed") {      // 수락은 사람의 선택: 모두 답했으면 합의(agreed) 또는 갈림(split)
                val pending = g.members.any { it !in g.answers }
                st = if (pending) "proposed" else if (g.members.all { g.answers[it] == true }) "agreed" else "split"
            }
            acts.put(JSONObject().put("id", a.id).put("type", a.type).put("kind", a.kind).put("label", a.place?.let { nm(it) } ?: a.label)
                .put("state", st).put("by", a.by).put("basis", a.basis?.let { BASIS.getValue(it).let { (k, e) -> L(lang, k, e) } } ?: "")
                .put("how", n(a.how)).put("doneBy", a.doneBy).put("at", n(a.at)))
        }
        val rs = ride?.s
        return JSONObject().put("ride", n(ride?.let { JSONObject().put("id", pid(it, mid)).put("name", it.name) }))
            .put("observed", n(if (ride == null || rs == null) null else JSONObject().put("s", rnd(rs)).put("kmh", rnd(ride.speed * 3.6))
                .put("stopped", ride.speed < STOP_MS).put("age", r1(now - ride.t))))
            .put("place", n(target?.let { t -> JSONObject().put("name", nm(t)).put("s", rnd(t.s)).put("dist", n(rs?.let { rnd(t.s - it) }))
                .put("basis", BASIS.getValue(t.basis).let { (k, e) -> L(lang, k, e) }) }))
            .put("acts", acts)
            .put("changed", n(g.changed?.let { (f, t) -> JSONObject().put("from", nm(f)).put("to", nm(t)) }))
    }

    /** 서비스 톤 한 문장: '전방 Xkm {무엇}. {누가} 확인. {행동}.' — 짧고 격식 있게(내비 안내체) */
    private fun sentence(lang: String, type: String, ahead: Double, named: List<String>, anon: Int, cars: Int, wet: Boolean = false,
                         restAt: Rest? = null, value: String = "", evidence: List<String> = emptyList(), level: Int = 1, partial: Boolean = false,
                         official: Official? = null, zone: Zone? = null): String {
        val where = L(lang, "전방 ${kmText(lang, ahead)}", "${kmText(lang, ahead)} ahead")
        if (type == "INCIDENT" && official != null) {     // 공식 확인: '의심'을 떼고 짧게
            val kind = L(lang, OFFICIAL_KO.getValue(official.type), OFFICIAL_EN.getValue(official.type))
            return L(lang, "$where $kind 구간(도로 정보 확인). 서행하세요.", "$where, $kind confirmed by road authority. Drive slowly.")
        }
        val rn = restAt?.let { if (lang == "ko") it.name else it.nameEn } ?: ""
        val mins = if (value.isNotEmpty() && value.all { it.isDigit() }) value else "?"
        val who = if (named.isNotEmpty())
            L(lang, if (anon > 0) "${named[0]} 차량 외 ${anon}대" else "${named[0]} 차량",
                if (anon > 0) "${named[0]}'s car and $anon more" else "${named[0]}'s car")
        else L(lang, "차량 ${cars}대", "$cars cars")
        val what = when (type) {
            "HARD_BRAKE" -> L(lang, "급제동 구간", "hard braking")
            "CONGESTION" -> L(lang, "정체 구간", "slow traffic")
            "INCIDENT" -> if (zone != null) L(lang, "사고 다발 구간, 사고 의심", "accident-prone section, possible crash") else L(lang, "사고 의심 구간", "possible crash")
            "HEAVY_RAIN" -> L(lang, "폭우 구간", "heavy rain")
            "ICING" -> L(lang, "결빙 주의 구간", "possible ice")
            "BLOCKED" -> L(lang, if (wet) "침수 의심 구간" else "통행 불가 의심 구간", if (wet) "possible flooding" else "possible road block")
            "DEBRIS" -> L(lang, "낙하물 의심 구간", "possible debris")
            "SMOKE" -> L(lang, "연기 의심 구간", "possible smoke")
            "DOWNHILL" -> L(lang, "긴 내리막 구간", "long downhill")
            "CHARGER_FAULT" -> L(lang, "$rn 충전기 고장 의심", "possible charger fault at $rn")
            "CHARGER_WAIT" -> L(lang, "$rn 충전 대기", "charging queue at $rn")
            else -> throw IllegalArgumentException(type)
        }
        val how = when (type) {
            "HARD_BRAKE" -> L(lang, "급제동", "braked hard")
            "CONGESTION" -> L(lang, "서행", "slowed down")
            "INCIDENT" -> evidencePhrase(lang, evidence)
            "HEAVY_RAIN" -> L(lang, "와이퍼 작동", "wipers on")
            "ICING" -> L(lang, "감속", "slowing")
            "BLOCKED" -> L(lang, "회차", "turned back")
            "DEBRIS" -> L(lang, "급조향", "swerved")
            "SMOKE" -> L(lang, "내기순환 전환", "switched to recirculation")
            "CHARGER_FAULT" -> L(lang, "충전 실패", "failed to charge")
            "CHARGER_WAIT" -> L(lang, "${mins}분 대기", "waited $mins min")
            else -> L(lang, "장시간 제동", "long braking")
        }
        val act = when (type) {
            "HARD_BRAKE", "CONGESTION" -> L(lang, "감속하세요.", "Slow down.")
            "INCIDENT" -> if (partial) L(lang, "일부 차로 통행 중. 서행하세요.", "Some lanes open. Drive slowly.")
                else if (level >= 2) L(lang, "교통 막힘 주의, 서행하세요.", "Expect a queue — drive slowly.")
                else L(lang, "교통 막힘 주의, 감속 후 차로를 확인하세요.", "Expect a queue — slow down and check your lane.")
            "HEAVY_RAIN" -> L(lang, "차간거리를 유지하세요.", "Keep your distance.")
            "ICING" -> L(lang, "서행하세요.", "Drive slowly.")
            "BLOCKED" -> L(lang, "우회로를 확인하세요.", "Check for a detour.")
            "DEBRIS" -> L(lang, "차로를 확인하세요.", "Check your lane.")
            "SMOKE" -> L(lang, "창문을 닫으세요.", "Close the windows.")
            "CHARGER_FAULT" -> L(lang, "다음 충전소를 확인하세요.", "Check the next charging station.")
            "CHARGER_WAIT" -> L(lang, "충전 계획을 확인하세요.", "Check your charging plan.")
            else -> L(lang, "회생제동을 사용하세요.", "Use regenerative braking.")
        }
        return L(lang, "$where $what. $who $how 확인. $act", "$where, $what. $who $how. $act")
    }

    /** 긴급차량 한 문장: '후방 Xkm 긴급차량 접근 중. {누가} 사이렌 감지 확인. (약 N초 후 통과 예상.) 진로를 양보하세요.'(도로교통법 제29조)
     *  500m 안(두 번째 안내)은 짧게: '후방 500m 긴급차량 근접. 진로를 양보하세요.' */
    private fun sirenSentence(lang: String, behind: Double, named: List<String>, anon: Int, cars: Int, eta: Double?): String {
        if (behind <= SIREN_NEAR) return L(lang, "후방 ${kmText(lang, behind)} 긴급차량 근접. 진로를 양보하세요.", "Emergency vehicle ${kmText(lang, behind)} behind. Make way now.")
        val who = if (named.isNotEmpty())
            L(lang, if (anon > 0) "${named[0]} 차량 외 ${anon}대" else "${named[0]} 차량",
                if (anon > 0) "${named[0]}'s car and $anon more" else "${named[0]}'s car")
        else L(lang, "차량 ${cars}대", "$cars cars")
        val time = if (eta != null && eta <= SIREN_ETA_MAX) maxOf(10, rnd(eta / 10) * 10).let { L(lang, " 약 ${it}초 후 통과 예상.", " Passing in about $it s.") } else ""
        return L(lang, "후방 ${kmText(lang, behind)} 긴급차량 접근 중. $who 사이렌 감지 확인.$time 진로를 양보하세요.",
            "Emergency vehicle ${kmText(lang, behind)} behind. $who heard a siren.$time Make way.")
    }

    /** 휴게소의 충전기 상태: 살아 있는 충전기 고장 보고가 있으면 "fault", 충전 대기 보고가 있으면 "wait", 없으면 null */
    private fun chargerAt(r: Rest): String? {
        for ((type, tag) in listOf("CHARGER_FAULT" to "fault", "CHARGER_WAIT" to "wait"))
            if (hazards.any { it.type == type && it.reporters.isNotEmpty() && abs(it.s - r.s) <= CHARGER_NEAR }) return tag
        return null
    }

    /** 연결 브리핑 한 문장: 주변 Tago 차 수(+그룹 일행 위치) + 다음 휴게소(+충전기 고장·대기 보고) */
    private fun brief(lang: String, nearby: Int, group: JSONObject?, restNext: JSONObject?): String {
        val parts = mutableListOf(L(lang, "주변 길벗 차량 ${nearby}대 연결", "Linked with $nearby Gilbeot cars"))
        group?.getJSONArray("members")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                if (x.getBoolean("me") || x.isNull("gap")) continue
                val gap = x.getInt("gap"); val d = kmText(lang, abs(gap).toDouble())
                parts += L(lang, "${x.getString("name")} $d ${if (gap > 0) "앞" else "뒤"}", "${x.getString("name")} $d ${if (gap > 0) "ahead" else "behind"}")
            }
        }
        var out = parts.joinToString(", ")
        restNext?.let { r ->
            out += L(lang, ". 다음 휴게소 ${r.getString("name")}, ${kmText(lang, r.getInt("dist").toDouble())}", ". Next stop ${r.getString("name")}, ${kmText(lang, r.getInt("dist").toDouble())}")
            val ch = if (r.isNull("charger")) "" else r.optString("charger")   // optString 은 JSON null 을 "null" 문자열로 돌려준다
            if (ch.isNotEmpty()) out += L(lang, if (ch == "fault") " (충전기 고장 의심 보고 있음)" else " (충전 대기 보고 있음)",
                if (ch == "fault") " (charger fault reported)" else " (charging queue reported)")
        }
        return "$out."
    }

    /** 관제 화면(3D): 도로 위 모든 차(익명)·위험·최근 신호 */
    fun world(mid: String?): JSONObject {
        val me = car(mid)
        val mine = me?.group?.let { groups[it]?.members?.toSet() } ?: emptySet()
        val amb = demo?.amb
        val cs = JSONArray(cars.filter { it.s != null && now - it.t <= ACTIVE_SEC && !it.gone }.map { c ->
            JSONObject().put("id", pid(c, mid)).put("s", rnd(c.s!!)).put("kmh", rnd(c.speed * 3.6)).put("rain", c.wiper.startsWith("CONTINUOUS"))
                .put("me", c.id == mid).put("name", if (c.id in mine) c.name else "").put("stopped", c.speed < STOP_MS)
                .put("yield", amb != null && amb.on && c.s!! - amb.s >= 0 && c.s!! - amb.s <= YIELD_M)
        })
        val hz = JSONArray(hazards.filter { it.reporters.isNotEmpty() }.map { h ->
            JSONObject().put("id", h.id).put("type", h.type).put("s", rnd(if (h.type == "SIREN") siren(h).first else h.s)).put("cars", h.reporters.size)
                .put("confirmed", h.reporters.size >= ANON_CONFIRM)
        })
        for (o in official) hz.put(JSONObject().put("id", o.id).put("type", "OFFICIAL").put("s", rnd(o.s)).put("cars", 0).put("confirmed", true))
        val ems = JSONArray().apply { amb?.let { put(JSONObject().put("s", rnd(it.s)).put("on", it.on)) } }   // 시연 구급차(Tago 차가 아니다 — 관제 화면에만)
        return JSONObject().put("now", r1(now)).put("cars", cs).put("hazards", hz)
            .put("pulses", JSONArray(pulses.filter { now - it.getDouble("t") <= PULSE_SEC }.map { p ->   // 남의 차는 pub(내 신호만 내 id)
                JSONObject().put("t", p.get("t")).put("s", p.get("s")).put("type", p.get("type"))
                    .put("car", p.getString("car").let { cid -> if (cid == mid) cid else p.getString("pub") })
            })).put("ems", ems)
            .put("s0", n(demo?.let { rnd(it.s0) })).put("scale", DEMO_SCALE)
    }

    fun routeInfo(): JSONObject = JSONObject().put("name", file.getString("name")).put("nameEn", file.getString("nameEn")).put("route", file.getJSONArray("route"))
        .put("length", rnd(length)).put("restAreas", JSONArray(rest.map { JSONObject().put("name", it.name).put("nameEn", it.nameEn).put("kind", it.kind).put("s", it.s).put("lat", it.lat).put("lon", it.lon) }))
        .put("zones", file.optJSONArray("zones") ?: JSONArray()).put("sources", file.getJSONArray("sources"))

    /** 두 서버가 같은 경로 규칙: /api/road/…(도로 링크), /api/link/…(가족 그룹) — (코드, 응답) */
    @Synchronized
    fun handle(method: String, parts: List<String>, query: Map<String, String>, b: JSONObject): Pair<Int, JSONObject> {
        val nf = 404 to JSONObject().put("error", "not found")
        if (method == "GET" && (parts == listOf("api", "road", "route") || parts == listOf("api", "link", "route"))) return 200 to routeInfo()
        if (parts.size >= 2 && parts[0] == "api" && parts[1] == "road") {
            val act = parts.getOrNull(2) ?: ""
            if (method == "GET" && (act == "" || act == "world")) {
                query["t"]?.toDoubleOrNull()?.let { clock(JSONObject().put("t", it)) }
                return 200 to (if (act == "") view(query["me"]) else world(query["me"]))
            }
            if (method == "POST") {
                when (act) {
                    "reset" -> { reset(); return 200 to JSONObject().put("ok", true) }
                    "join" -> {
                        val c = joinRoad(b) ?: return 503 to JSONObject().put("error", "full")
                        return 200 to JSONObject().put("me", c.id).put("view", view(c.id))
                    }
                    "state" -> state(b)
                    "event" -> event(b)
                    "respond" -> respond(b)
                    "official" -> official(b)
                    "demo" -> demoRoad(b)
                    else -> return nf
                }
                return 200 to view(str(b, "me").ifEmpty { null })
            }
        }
        if (parts.size >= 2 && parts[0] == "api" && parts[1] == "link") {
            if (method == "POST" && parts.drop(2) == listOf("group")) {
                val g = createGroup(b) ?: return 404 to JSONObject().put("error", "car")
                return 200 to JSONObject().put("group", g.id).put("code", g.code).put("view", view(str(b, "me")))
            }
            if (method == "POST" && parts.drop(2) == listOf("join")) {
                val j = joinGroup(b)
                val g = j.g; val c = j.c
                if (g == null || c == null) return (when (j.error) { "locked" -> 429; "full" -> 503; else -> 404 }) to JSONObject().put("error", j.error)
                return 200 to JSONObject().put("group", g.id).put("code", g.code).put("me", c.id).put("view", view(c.id))
            }
            val g = parts.getOrNull(2)?.let { groups[it] } ?: return nf
            val act = parts.drop(3).joinToString("/")
            if (method == "GET" && act == "") {
                query["t"]?.toDoubleOrNull()?.let { clock(JSONObject().put("t", it)) }
                return 200 to view(query["me"])
            }
            if (method == "POST") {
                when (act) {
                    "demo" -> demoGroup(g, b)
                    "rest" -> rest(g, b)
                    "rest/answer" -> restAnswer(g, b)
                    "steps" -> setSteps(g, b)
                    "steps/done" -> stepDone(g, b)
                    "journey/ride" -> journeyRide(g, b)
                    "journey/ack" -> journeyAck(g, b)
                    else -> return nf
                }
                return 200 to view(str(b, "me").ifEmpty { null })
            }
        }
        return nf
    }
}
