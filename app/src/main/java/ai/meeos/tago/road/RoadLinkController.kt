package ai.meeos.tago.road

import ai.meeos.tago.app.Edition
import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.SirenMic
import ai.meeos.tago.SirenYamnet
import ai.meeos.tago.app.Question
import ai.meeos.tago.app.Services
import ai.meeos.tago.engine.LinkHub
import ai.meeos.tago.engine.RouteGraft
import ai.meeos.tago.map.TmapPoi
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.navi.data.TrafficEventInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 함께 가기(도로 링크, 차대차). 주행마다 새 익명 차 id로 도로에 들어가고, 1초마다 경로 위 위치와 차가 감지한 위험
 * (급제동·폭우·급조향·긴 내리막·내기순환·되돌아감·사이렌)을 보고한다. 정체·사고 의심은 서버가 속도 흐름으로 판단한다.
 * 서버 보기(view)는 한 번에 한 문장만 말한다: 위험 > 권장 속도 > 비 예보 > 휴게소. 가족 그룹은 선택이다.
 */
class RoadLinkController(private val context: Context, private val s: Services, private val onMapUrl: (String?) -> Unit) {
    /** {me: 익명 차 id, group?: 가족 그룹 id, code?} */
    var convoy: JSONObject? = null
        private set
    var view: JSONObject? = null
        private set
    val active: Boolean get() = convoy != null
    val hasGroup: Boolean get() = convoy?.has("group") == true

    private var linkS = 0.0                                 // 경로 위 내 위치(시연 경로 경부고속도로 하행)
    /** 경로 위 내 자리(m) — 시험 도구가 읽는다(내장 서버 /api/app/pos) */
    val linkPos: Double get() = linkS
    /** 경로 위 내 위치(m) — 응급 카드의 도로명·나들목(RoutePlace)이 읽는다 */
    val routeS: Double get() = linkS
    /** 경로 자산에서 만든 장소 설명(MainActivity 가 채운다). 없으면 응급 카드에 도로 줄이 없다 */
    var routePlace: RoutePlace? = null
    /** 이번 주행 요약·[경고 틀림] 집계(기록만 — 판정·경고 순서와 무관, DriveSummary) */
    val summary = DriveSummary()

    private val spoken = mutableMapOf<String, Int>()        // 위험 id → 말한 단계(차가 3대 이상 확인되면 한 번 더)
    private val detourSaid = mutableSetOf<String>()          // 우회 안내 id(= 막힘 위험 id) — 같은 막힘에 한 번만 말한다
    private val detourSeen = mutableMapOf<String, Long>()    // 우회 안내 id → 보기에서 처음 본 시각(발화 지연 기록, DETOUR_DELAY_S)
    // 우회 권고 수락(FR-G-36 확장): 준수 점수는 이 기기 안에만(DetourComply) — 허브에는 수락·거절만, 연구 기록에는 넣지 않는다
    private val comply by lazy { DetourComply.load(context) }
    private val detourDecided = mutableMapOf<String, Boolean>()   // 막힘 id → 수락(true)·거절(false). 본선 유지(stay) 배정은 넣지 않는다
    private var pendingAck: JSONObject? = null                     // 다음 상태 보고에 실을 detourAck
    private var judging: Pair<String, Double>? = null              // (막힘 id, 권고 때 다음 나들목의 s) — 따랐는지 판정 중
    // 경로 이탈(휴게소 진입 등) — 차 안에서 GPS 와 경로 선으로 판정해 offRoute 불리언만 보낸다(위경도는 보내지 않는다)
    private val baseGeo by lazy { runCatching { LinkHub(context.assets.open("link/gyeongbu_route.json").use { String(it.readBytes(), Charsets.UTF_8) }) }.getOrNull() }
    // ---- 실제 경로 위 연결(B-107, 11-14): 고른 목적지까지의 실제 경로로 경로 파일을 만들어(RouteGraft) 허브·경로 계산을 그 경로로 갈아 끼운다.
    // 꺼져 있거나(realRouteEnabled) 실제 경로가 없거나 너무 짧으면(시연 각본이 안 들어감) 지금까지처럼 시연 경로(경부 하행)
    private var liveGeo: LinkHub? = null
    private val routeGeo: LinkHub? get() = liveGeo ?: baseGeo
    /** MainActivity 가 꽂는다: 기준 경로 파일 글, 내장 서버의 허브를 갈아 끼우는 함수(null = 시연 경로로) */
    var baseRouteText: String? = null
    var swapHub: ((String?) -> Unit)? = null
    private var baseRoutePlace: RoutePlace? = null
    /** 기능 스위치(시연 빌드 훅 real_route — 끄면 늘 시연 경로) */
    var realRouteEnabled = true
    /** 지금 허브가 실제 경로 위에 서 있나 */
    val onRealRoute: Boolean get() = liveGeo != null
    private var liveKey = ""
    /** 지도 주소에 붙이는 경로 판 번호 — 허브의 경로가 바뀌면 지도 페이지가 경로를 다시 읽게 */
    private var routeVer = 0
    fun rvParam(): String = if (routeVer > 0) "&rv=$routeVer" else ""
    private var gpsS0 = Double.NaN
    private var gpsLive = false
    /** 실제 경로 위에서 연결할 수 있나: 스위치·실제 위치·목적지·실제 경로가 있고, 그 경로가 시연 각본이 들어갈 만큼 길다 */
    fun realRouteReady(): Boolean = realRouteEnabled && realLocEnabled && swapHub != null && baseRouteText != null && dest != null && realHere() != null &&
        (realRoute?.let { it.points.size >= 2 && it.distM >= MIN_REAL_ROUTE_M } ?: false)
    /** 연결 시작 직전(메인 스레드): 허브를 실제 경로 또는 시연 경로로 맞춘다. 실제 경로 위에 섰으면 true */
    private fun prepareRoute(): Boolean {
        val base = baseRouteText; val swap = swapHub
        if (base == null || swap == null) return false
        if (baseRoutePlace == null) baseRoutePlace = routePlace
        val p = realRoute; val d = dest
        if (realRouteReady() && p != null && d != null) {
            val key = "${p.distM}:${p.points.size}:${d.name}"
            val made = runCatching {
                val file = RouteGraft.graft(JSONObject(base), p.points.map { it.lat to it.lon }, t[R.string.route_real_name, d.name], "Current location → ${d.name}")
                val text = file.toString()
                val geo = LinkHub(text)
                swap(text); liveGeo = geo; routePlace = RoutePlace(file)
                if (key != liveKey) { liveKey = key; routeVer++ }
                s.log.d("실제 경로 위 연결: ${"%.1f".format(geo.length / 1000)} km · 휴게소 ${geo.rest.size} · 구역 ${geo.zones.size} · 나들목 ${geo.junctions.size}")
            }.onFailure { e -> s.log.d("실제 경로 파일 만들기 실패: ${e.message} — 시연 경로로") }.isSuccess
            if (made) return true
        }
        if (dest != null) s.log.d("실제 경로로 못 감: " + listOfNotNull("스위치".takeIf { !realRouteEnabled }, "실제 위치 끔".takeIf { !realLocEnabled }, "위치 모름".takeIf { realHere() == null },
            "경로 없음".takeIf { p == null }, p?.let { "경로 ${it.distM} m·점 ${it.points.size}".takeIf { _ -> it.points.size < 2 || it.distM < MIN_REAL_ROUTE_M } }).joinToString())
        if (liveGeo != null) { runCatching { swap(null) }; liveGeo = null; routePlace = baseRoutePlace; liveKey = ""; routeVer++; s.log.d("허브를 시연 경로로 되돌림") }
        return false
    }
    private val offRoute by lazy { OffRouteSensor(context, s.log) { lat, lon -> routeGeo?.lateral(lat, lon) ?: Double.NaN } }
    /** 위치 권한 요청(MainActivity 가 채운다). 함께 가기 시작 때 권한이 없으면 이유를 먼저 보이고 한 번만 묻는다 — 거부하면 offRoute 를 보내지 않는다(이전 규칙) */
    var requestLocation: (() -> Unit)? = null
    private var locationAsked = false
    private var restKey = ""                                // 이미 물은/알린 휴게소 제안
    private var briefed = false
    private var prevSpeed = 0f
    private var prevSpeed2 = 0f
    private var lastBrakeAt = 0L
    private var rainReported = false
    private var advSaid = false                             // 이번 정체의 권장 속도를 말했는지
    private var jamWatch = false                            // 권장 속도를 말한 뒤 정체를 지나는 중
    private var jamBrakes = 0                               // 그동안 내 차 급제동 수
    private var jamMinKmh = 999.0
    private val rainSaid = mutableSetOf<String>()
    private val zoneSaid = mutableSetOf<String>()           // 이미 말한 구역(다발 구간·교량 결빙) — 구역마다 한 번
    /** 사고 위험성(④): 허브의 환경·도로 점수 + 이 차의 운전 묶음(밖으로 보내지 않는다) */
    val driveRisk = DriveRisk()
    private var riskSaidAt = 0L; private var fatigueSaidAt = 0L
    private var lastSayAt = 0L                              // 말 예산: 마지막으로 말한 시각
    private var prevSteer: Float? = null
    private var lastSwerveAt = 0L
    private var lastYieldAt = 0L                            // 길 터 주기(YIELD) 마지막 보고 시각
    private var brakeHoldSince = 0L
    private var brakeHoldReported = false
    private var recircReported = false
    private var reverseSince = 0L
    private var turnbackReported = false
    private var demoCalled = false
    /** 시연 차량(익명 14대·가상 가족)을 허브에 부를지(B-11). 기본 켬 — 실제 두 차만 보려면 DemoHooks `demo_traffic` false */
    var demoTraffic = true
    /** 시연 각본 '핵심만'(시연 훅 demo_core) — 허브 시연 차량이 사고·정체·구급차만 낸다. 기본 false(평소 시연 그대로).
     *  구급차 근거는 위치 신호(beacon)만: 마이크 보고(mic)는 들은 차의 자리에 남아(30초) 구급차가 지나간 뒤 '뒤 500 m 긴급차량'이 한 번 더 떴다(11-14 녹화 v6~v9) */
    var demoCore = false

    // 사이렌(긴급차량): 도로 링크 중 차 안 마이크로 듣는다. 듣는 동안 5초마다 보고. 음성 인식이 듣는 동안은 마이크를 놓아 준다
    private val sirenMic = SirenMic({ SirenYamnet.loadModel(context) }, { on -> s.main.post { sirenHeard = on; if (on) s.log.d("감지: 사이렌 → 앞차들에게") } }, { s.log.d(it) })
    private var sirenHeard = false
    private var sirenSentAt = 0L
    /** 시험용: 마이크 대신 흘려 넣을 wav(인텐트 siren_wav) */
    var sirenWav: File? = null
        set(v) { field = v; if (v != null) sirenMic.stop() }
    private var sirenFails = 0; private var sirenEndSeen: SirenMic.SelfEnd? = null; private var sirenStartAt = 0L   // 사이렌 마이크 재시작 대기(manageSiren)
    private var sirenFile = false                           // 지금 wav를 흘려 넣는 중(음성 인식 때도 멈추지 않는다)

    // 시연 주행(인텐트 convoy_sim=true): 영상용으로 내 차가 권장 속도를 따라 달린다(1초에 1.5 m/s까지만 바꿈) — 기본은 실제 차속
    var simDrive = false
        set(v) { val was = field; field = v; simV = 25f; s.car.simulatedSpeed = if (v) simV else null; if (was && !v && simToVc) vcRampDown() }
    /** 시연 녹화(시연 빌드 훅 sim_vc): 시연 주행 속도를 에뮬레이터 가상 차량 콘솔(기기 안 127.0.0.1:8088)에도 넣어 운전자 화면 계기가 같이 움직이게 한다.
     *  기본 false. 시연 주행이 끝나면 0 으로 되돌린다. 제품 빌드에는 훅이 없어 켜지지 않는다 */
    var simToVc = false
    private var vcSentAt = 0L
    @Volatile private var vcLast = 0
    /** 시연 주행이 끝날 때 계기 속도를 한 번에 0 으로 두지 않는다 — 앱이 급감속을 충돌로 보고 "괜찮으세요?"를 물었다(11-14 녹화 v7·v8: 10 km/h 씩도 2.5→0 m/s 로 충돌).
     *  0.3초마다 2 km/h 씩(약 1.9 m/s², 평범한 제동) */
    private fun vcRampDown() {
        if (!BuildConfig.DEMO_HOOKS) return
        val from = vcLast
        s.io.execute { var k = from; while (k > 0) { k = maxOf(0, k - 2); vcPut(k); Thread.sleep(300) }; vcPut(0) }
    }
    private fun vcSpeed(kmh: Int) {
        if (!BuildConfig.DEMO_HOOKS) return
        vcLast = kmh
        s.io.execute { vcPut(kmh) }
    }
    private fun vcPut(kmh: Int) {
        runCatching {
            ai.meeos.tago.net.Http.status("http://127.0.0.1:8088/browser/session?client=meeos-sim", "POST", ByteArray(0), emptyMap(), 1000, 1000)
            ai.meeos.tago.net.Http.status("http://127.0.0.1:8088/vehicle/controls?speed=$kmh", "PUT", ByteArray(0), emptyMap(), 1000, 1000)
        }
    }
    private var simV = 25f
    /** 두 차 시연: 출발점을 LINK_START 에서 이만큼(m) 옮긴다(뒷차는 음수). 같은 허브에 붙는 두 에뮬레이터가 같은 자리에서 출발하지 않게 */
    var startOffset = 0.0
    /** 연결을 시작하면 놓일 경로 위 자리(m) — 첫 화면 지도가 같은 자리에 내 위치 표식을 그린다(시연 경로) */
    val startS: Double get() = LINK_START + startOffset

    // ---- 목적지(11-14, 3단계): 실제 장소 검색(TMAP) → 시연 경로 가까이·앞에 있는 곳만 → 지정 → 지도 깃발·도착 안내
    /** 고른 목적지(없으면 null). 연결을 끝내도 남는다 — 다음 출발에 그대로 */
    var dest: DestPick.Cand? = null; private set
    /** 검색 결과(null = 검색 안 함), 검색 중, 안내 한 줄(결과 없음·실패) */
    var destCands: List<DestPick.Cand>? = null; private set
    var destBusy = false; private set
    var destNote: String? = null; private set
    private var destArrived = false
    /** 기능 스위치(시연 빌드 훅 dest_off 로 끈다 — 끄면 검색 칸이 사라지고 고른 목적지도 지운다) */
    var destEnabled = true
        set(v) { field = v; if (!v) { dest = null; destCands = null; destNote = null; s.render() } }
    val destAvailable: Boolean get() = destEnabled && BuildConfig.TMAP_KEY.isNotBlank() && baseGeo != null
    // ---- 실제 위치 모드(11-14 오후, 사용자 "저게 맞는위치냐고"): 기기의 실제 위치(AppController 가 꽂는다, 없으면 null)를 검색 기준점으로 쓰고,
    // 실제 위치가 있으면 검색 결과를 모두 고를 수 있으며 고르면 TMAP 길 찾기로 실제 위치 → 목적지 경로를 받는다. 연결·위험 공유는 여전히 시연 경로(경부 하행)
    var here: () -> ai.meeos.tago.parking.GpsFix? = { null }
    /** 기능 스위치(시연 빌드 훅 real_loc — 끄면 예전처럼 시연 경로 기준) */
    var realLocEnabled = true
    /** 고른 목적지까지의 실제 경로(없으면 null). 지도 페이지가 /api/app/myroute 로 읽는다 */
    @Volatile var realRoute: ai.meeos.tago.map.TmapRoute.Plain? = null; private set
    private var routeGen = 0
    /** 연결을 시연 구간에서 시작했나(실제 위치가 시연 경로 밖) — 판에 '시연 주행' */
    var demoDrive = false; private set
    fun realHere(): ai.meeos.tago.parking.GpsFix? = if (realLocEnabled) here() else null
    /** 실제 위치가 시연 출발 자리와 다른가(경로에서 1 km 넘게 떨어졌거나, 경로 위라도 출발 자리에서 1 km 넘게). 위치를 모르면 true(시연 구간이라고 알린다) */
    fun offDemoStart(): Boolean = realLocEnabled && (realHere()?.let { h -> baseGeo?.let { g -> g.lateral(h.lat, h.lon) > DEMO_MISMATCH_M || kotlin.math.abs(g.sAt(h.lat, h.lon) - startS) > DEMO_MISMATCH_M } ?: true } ?: true)

    fun searchDest(keyword: String) {
        val kw = keyword.trim().take(30)
        val g = baseGeo ?: return
        if (kw.isEmpty() || destBusy || !destAvailable) return
        val c = g.pointAt(startS)
        val h = realHere()   // 실제 위치가 있으면 그 둘레에서 찾는다
        val cLat = h?.lat ?: c.getDouble(0); val cLon = h?.lon ?: c.getDouble(1)
        destBusy = true; destNote = null; s.render()
        s.io.execute {
            val r = runCatching { TmapPoi.fetch(BuildConfig.TMAP_KEY, kw, cLat, cLon) }
            s.main.post {
                destBusy = false
                r.onSuccess { places ->
                    val cls = DestPick.classify(places, { la, lo -> g.sAt(la, lo) to g.lateral(la, lo) }, startS, g.length)
                    // 실제 위치가 있으면 모두 고를 수 있다(실제 경로를 받는다). 시연 경로 가까이인지는 nearDemo 로 남긴다(도착 안내는 그때만)
                    val cands = if (h != null) cls.map { it.copy(onRoute = true, nearDemo = it.onRoute) } else cls
                    val on = cands.count { it.onRoute }
                    destCands = cands
                    destNote = when { places.isEmpty() -> t[R.string.dest_none]; on == 0 -> t[R.string.dest_off_route, places.size]; else -> null }
                    s.log.d("목적지 검색: 결과 ${places.size}곳, 경로 가까이 ${on}곳, 경로 밖 ${cands.size - on}곳 보임")   // 검색어는 기록하지 않는다
                }.onFailure { e -> destCands = emptyList(); destNote = t[R.string.dest_failed]; s.log.d("목적지 검색 실패: ${e.javaClass.simpleName} ${e.message}") }
                s.render()
            }
        }
    }

    fun pickDest(i: Int) {
        val c = destCands?.getOrNull(i) ?: return
        if (!c.onRoute) { destNote = t[R.string.dest_pick_off]; s.render(); return }   // 경로 밖은 고를 수 없다 — 까닭을 한 줄로
        dest = c; destCands = null; destNote = null; destArrived = false; realRoute = null
        val h = realHere()
        if (h == null) {
            s.log.d("목적지 지정: 경로 ${"%.1f".format(c.s / 1000)} km")
            s.say(t[R.string.say_dest_set, c.name, fmtKm(c.s - startS)])
        } else {   // 실제 위치 → 목적지 실제 경로(TMAP 길 찾기). 받는 동안 판은 '경로를 찾는 중'
            val gen = ++routeGen
            destBusy = true
            s.io.execute {
                val r = runCatching { ai.meeos.tago.map.TmapRoute.fetchPlain(BuildConfig.TMAP_KEY, ai.meeos.tago.map.TmapCarMap.LL(h.lat, h.lon), ai.meeos.tago.map.TmapCarMap.LL(c.lat, c.lon)) }
                s.main.post {
                    if (gen != routeGen || dest !== c) return@post
                    destBusy = false
                    r.onSuccess { p -> realRoute = p; s.log.d("목적지 실제 경로: ${"%.1f".format(p.distM / 1000.0)} km · ${(p.timeS + 30) / 60}분 · 점 ${p.points.size}개")
                        s.say(t[R.string.say_dest_real, c.name, fmtKm(p.distM.toDouble()), maxOf(1, (p.timeS + 30) / 60)]) }
                     .onFailure { e -> s.log.d("목적지 실제 경로 실패: ${e.message}"); s.say(t[R.string.say_dest_noroute, c.name]) }
                    s.render(); refreshMapUrl()
                }
            }
        }
        refreshMapUrl(); s.render()
    }

    fun clearDest() { dest = null; destCands = null; destNote = null; destArrived = false; realRoute = null; routeGen++; refreshMapUrl(); s.render() }
    fun closeDestSearch() { destCands = null; destNote = null; s.render() }

    /** 지도 주소에 붙이는 목적지(경로 위 자리|위도|경도|이름). 없으면 빈 글 */
    fun destParam(): String = dest?.let { "&dest=" + java.net.URLEncoder.encode("${it.s.toInt()}|${it.lat}|${it.lon}|${it.name}", "UTF-8") } ?: ""
    /** 목적지가 바뀌면 지도 주소를 다시 넘긴다(페이지는 제자리 전환) — 연결 중이면 여기서, 첫 화면은 AppController 가 */
    var onDestChanged: () -> Unit = {}
    private fun refreshMapUrl() { if (active) currentMapBase?.let { onMapUrl(it + destParam()) } else onDestChanged() }
    private var currentMapBase: String? = null
    private fun mapUrl(base: String) { currentMapBase = base; onMapUrl(base + destParam()) }
    private fun fmtKm(m: Double) = "%.0f".format(kotlin.math.max(0.0, m) / 1000)
    // 충전: 꽂은 때·그때 배터리 %·자리, 이번 꽂음의 질문·결과, 정차 시작 시각(충전 대기 계산)
    private var portSince = 0L
    private var portSoc: Int? = null
    private var plugS = 0.0
    private var plugAsked = false                            // 이번 꽂음에서 "충전기 고장인가요?"를 물었다
    private var plugDone = false                             // 이번 꽂음에서 충전이 시작됐다(보고 끝)
    private var stopSince = 0L
    // 충전 실패: 안 된 자리·시각, 허브에 CHARGE_FAIL 을 보냈는지('아니오'면 안 보냄), 질문 중인지
    private var failSpot: Double? = null
    private var failAt = 0L
    private var failSent = false
    private var chargeAsking = false
    /** 충전기 고장 신고 1분 설문(열려 있으면 화면이 설문으로 바뀐다). 정차 중에만 */
    var survey: ChargeSurvey? = null
        private set
    // 사고 판단(docs/incident-judgment-design.md 10판): 충돌은 CarSignals 의 10 Hz 감지기가 알리고, 여기서 즉시 보고·운전자 확인·2차 사고 예방 모드
    private var impactAt = 0L                                // 마지막 충돌 감지 시각(에어백 30초 읽기 창)
    private var airbagReported = false
    private var crashAsked = false
    /** 무응답·[도움 필요] → 응급 카드(MainActivity 가 EmergencyController.start 로 잇는다) */
    var onCrashHelp: ((noAnswer: Boolean) -> Unit)? = null
    // 공식 돌발 정보(순정 내비): 링크 중 ACCIDENT·ROAD_DAMAGE·DISASTER 를 허브에 올린다 — 새 사건이 생겼거나 60 s 지났을 때
    private val officialKeys = mutableSetOf<String>()
    private var officialSentAt = 0L
    /** 시연 훅 comm_sim: 보고를 멈춰 '사고로 인한 통신 끊김'을 흉내 낸다(이벤트는 이미 나간 뒤) */
    var commSim = false
    /** 두 차 시연: 이 차의 운전자 이름(기본은 지역 문자열 driver_name — 두 에뮬레이터가 같은 이름이면 그룹에서 구분이 안 된다) */
    var nameOverride: String? = null
    private val myName get() = nameOverride ?: t[R.string.driver_name]
    /** 연구 기록(경고 반응 로그): 경고 문장을 말하는 순간만 알린다 — 말할지·언제 말할지는 바꾸지 않는다 */
    var study: ai.meeos.tago.study.ResponseLog? = null
    // 접근 경고음(주의 환기): 확인된 사고 지점·정체 꼬리에 덜 줄인 채 다가가면 꼬리까지 남은 시간(TTC)에 따라 삐 → 삐삐 → 삐삐삐(줄일 때까지 1초마다)
    private val beep = ApproachBeep()
    private var beepKey = ""
    private var beepStage = 0
    private var beepAt = 0L
    /** 이 꼬리에서 낼 첫 단계의 바닥: 앞 꼬리에서 2단계 이상까지 올랐는데 덜 줄인 채 다음 꼬리로 넘어가면 2(1단계로 되돌아가지 않게) */
    private var beepFloor = 0
    private var looping = false
    private fun loopOff() { if (looping) { looping = false; beep.loop3(false) } }
    // 지난 알림음: 확인된 사고 의심 id → 지난 틱의 앞 거리(양수 → 0 이하로 바뀌면 지남), 정체 구간 안내 중이었는지
    private val aheadLast = HashMap<String, Double>()
    private var jamAhead = false
    private var passAt = 0L
    /** 허브가 마지막으로 알린 내 시계 오차(초, 없으면 null) — 로그용 */
    private var clockSkew: Double? = null
    /** 시연 훅 sim_kmh: 시연 주행이 권장 속도를 따르지 않고 이 속도(km/h)로 달린다 — 덜 줄인 운전자(접근 경고음 시험). null = 권장 속도 따름 */
    var simTargetKmh: Float? = null

    private val t get() = s.texts
    private val car get() = s.car

    // ------------------------------------------------------------ 시작·그룹·끝

    /** 도로 링크 켜기. 시연(내장 서버)은 도로를 비우고 시작하고, 출발하면 시연 도로(모르는 Tago 차 14대)를 부른다 */
    fun start(pickupActive: Boolean) {
        // 시작 요청이 가는 중(starting)에 또 눌려도 두 번 시작하지 않는다 — 내장 허브는 응답이 빨라 틈이 좁지만 PC 허브·느린 망에서는 두 번째 reset·join 이 첫 차를 지우거나 유령 차를 남긴다
        if (!Edition.roadLink || active || starting || pickupActive) return
        s.serverError?.let { s.toast(it); return }      // 내장 서버가 안 떴으면 시작하지 않고 이유를 다시 보인다
        val body = JSONObject().put("t", s.epoch()).put("lang", s.lang).put("units", s.units).put("name", myName).put("car", t[R.string.driver_car])
        val real = prepareRoute()   // 허브를 실제 경로(있고 충분히 길 때) 또는 시연 경로로 — 합류 요청 전에
        val realS0 = if (real) realHere()?.let { h -> liveGeo?.sAt(h.lat, h.lon) } ?: 0.0 else 0.0
        starting = true
        s.io.execute {
            runCatching {
                if (BuildConfig.EMBEDDED_SERVER) s.api.http("POST", "/api/road/reset", "{}")
                JSONObject(s.api.http("POST", "/api/road/join", body.toString()))
            }.onSuccess { r -> s.main.post {
                starting = false
                summary.start(System.currentTimeMillis())
                freshness.ok(android.os.SystemClock.elapsedRealtime()); staleAnnounced = false
                convoy = JSONObject().put("me", r.getString("me"))
                linkS = if (real) realS0 else LINK_START + startOffset; gpsS0 = Double.NaN; gpsLive = false; lastHdg = null; sideHazards = emptyList(); spoken.clear(); detourSaid.clear(); detourSeen.clear(); detourDecided.clear(); pendingAck = null; judging = null; restKey = ""; briefed = false; rainReported = false; demoCalled = false
                advSaid = false; jamWatch = false; rainSaid.clear(); zoneSaid.clear(); simV = 25f
                driveRisk.reset(); riskSaidAt = 0L; fatigueSaidAt = 0L; lastSayAt = System.currentTimeMillis()
                car.readRoadSignals = true
                if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) offRoute.start()   // 경로 이탈 판정
                else if (!locationAsked && requestLocation != null) { locationAsked = true; s.toast(t[R.string.toast_location_why]); requestLocation?.invoke() }
                car.setTurnSignalWatch(true)                 // 길 터 주기(YIELD): 오른쪽 깜빡이
                car.onImpact = { pre, nowV, samples -> onImpact(pre, nowV, samples) }
                car.setFastSpeed(true)
                impactAt = 0L; airbagReported = false; crashAsked = false; commSim = false; stateSentAt = 0L
                officialKeys.clear(); officialSentAt = 0L
                s.navi.onTrafficEvents = { onOfficial(it) }
                postState()      // 첫 보고도 같은 통로로(응답이 오기 전 다음 틱이 또 보내지 않게)
                mapUrl("${BuildConfig.SERVER_URL}/link/road?me=${r.getString("me")}&car=1&camui=app${rvParam()}")   // camui=app: 단속 알림은 앱 판 한 줄(지도 #camalert 끔, 11-15)
                // 실제 위치가 시연 출발 자리와 다르면(경로에서 떨어졌거나, 경로 위라도 출발 자리에서 1 km 넘게) = 시연 구간에서 보인다. 위치를 모르면 시연 구간이라고 알린다
                demoDrive = !real && offDemoStart()   // 실제 경로 위면 '시연 구간' 알림은 없다
                if (demoDrive) s.say(t[R.string.say_demo_section])
                dest?.let { d -> destArrived = false; if (!demoMismatch()) s.navi.routeDomestic(d.lat, d.lon, d.name) }   // 실차만 순정 내비에도(시연 주행은 엉뚱한 실제 길이라 넘기지 않는다)
                if (real) s.say(t[R.string.say_real_route])
                s.log.demo("3D 관제 화면: ${BuildConfig.PUBLIC_URL}/world?me=${r.getString("me")}")
                s.say(t[R.string.say_road_started]); lastSayAt = System.currentTimeMillis()
                if (pendingGroup) { pendingGroup = false; startGroup() }
                s.render()
            } }.onFailure { e -> s.main.post { starting = false; pendingGroup = false; s.log.d("도로 링크 시작 실패: ${e.message}") } }
        }
    }
    /** 시작 요청(reset·join)이 가는 중 — 중복 시작 방지 */
    private var starting = false
    /** 허브 보기의 신선도(V-03): 마지막 성공 응답 뒤 10초가 넘으면 '연결 끊김' — 화면은 낡은 안내를 지우고 경고음·[우회 안내]는 멈춘다 */
    private val freshness = LinkFreshness()
    private var staleAnnounced = false
    /** 허브 응답이 끊겼나(연결 중에만). 화면·경고음·우회 버튼이 이것을 본다 */
    fun linkStale(): Boolean = active && freshness.stale(android.os.SystemClock.elapsedRealtime())
    fun linkAgeSec(): Int = freshness.ageSec(android.os.SystemClock.elapsedRealtime())

    /** 가족 그룹(선택): 코드·링크로 가족 차를 묶는다. 위험은 같은 도로 링크에서 오고, 그룹 차는 이름·위치를 서로 본다 */
    private var pendingGroup = false                    // 연결이 되면 이어서 일행을 만든다
    fun startGroup() {
        if (convoy == null) { pendingGroup = true; if (!starting) start(false); return }   // 연결 전에 부르면 먼저 연결하고 이어서 만든다
        val c = convoy ?: return
        if (c.has("group")) return
        val body = JSONObject().put("t", s.epoch()).put("me", c.getString("me")).put("name", myName).put("car", t[R.string.driver_car])
        s.api.post("/api/link/group", body, onError = { e -> s.log.d("가족 그룹 실패: ${e.message}") }) { r ->
            if (convoy !== c) return@post
            c.put("group", r.getString("group")).put("code", r.getString("code"))
            s.log.demo("가족 참가 링크: ${BuildConfig.PUBLIC_URL}/link/${r.getString("group")}?code=${r.getString("code")}")
            s.say(t[R.string.say_group_created, r.getString("code").toCharArray().joinToString(" ")])
            briefed = false
            if (demoTraffic) groupPost("demo", JSONObject().put("t", s.epoch()).put("near", true).apply { if (demoCore) { put("core", true); put("ems", "beacon") } })      // 시연: 가상 가족(삼촌·이모). 그룹 시연이 시연 도로를 먼저 열 수 있어 core 도 같이(11-14 v4)
            mapUrl("${BuildConfig.SERVER_URL}/link/${r.getString("group")}?me=${c.getString("me")}&car=1${rvParam()}")
            onView(r.getJSONObject("view"))
        }
    }

    /** 가족 코드로 합류(두 번째 Pleos 차): 같은 도로 링크의 내 차 id 로 그룹에 들어간다 — 그룹 차의 보고는 1대로 바로 확정된다 */
    fun joinGroup(code: String) {
        val c = convoy ?: return
        if (c.has("group")) return
        val body = JSONObject().put("t", s.epoch()).put("me", c.getString("me")).put("code", code.trim()).put("name", myName).put("car", t[R.string.driver_car])
        s.api.post("/api/link/join", body, onError = { e -> s.log.d("가족 코드 합류 실패: ${e.message}") }) { r ->
            if (convoy !== c) return@post
            c.put("group", r.getString("group")).put("code", r.getString("code"))
            s.log.d("가족 그룹 합류")   // 그룹 id 는 합류 자격의 절반(코드와 함께) — 제품 빌드 로그에 남기지 않는다
            briefed = false
            mapUrl("${BuildConfig.SERVER_URL}/link/${r.getString("group")}?me=${c.getString("me")}&car=1${rvParam()}")
            onView(r.getJSONObject("view"))
        }
    }

    /** 다같이 휴게소: 서버가 가장 앞선 일행도 아직 지나지 않은 휴게소를 고르고 일행에게 묻는다. 내 차는 순정 내비 목적지로 */
    fun restTogether() {
        val c = convoy ?: return
        val gid = c.optString("group").takeIf { it.isNotEmpty() } ?: return
        val body = JSONObject().put("t", s.epoch()).put("me", c.getString("me"))
        s.api.post("/api/link/$gid/rest", body, onError = { e -> s.log.d("휴게소 제안 실패: ${e.message}") }) { v ->
            val r = v.optJSONObject("group")?.optJSONObject("rest")
            if (r != null && !r.optBoolean("none")) { restKey = "${r.optString("name")}|${r.optString("by")}"; s.say(r.optString("say")); naviToRest(r) }
            onView(v)
        }
    }

    private fun askRest(r: JSONObject) {
        s.dialog.ask(Question(Question.Kind.REST, t[R.string.btn_yes_stop], t[R.string.btn_no_thanks], t[R.string.listen_yes_no], onAnswer = ::answerRest), r.optString("say"))
    }

    private fun answerRest(yes: Boolean) {
        groupPost("rest/answer", JSONObject().put("t", s.epoch()).put("yes", yes))
        val r = view?.optJSONObject("group")?.optJSONObject("rest")
        if (yes && r != null) s.say(t[if (naviToRest(r)) R.string.say_rest_guide else R.string.say_rest_noted, t.ro(r.optString("name"))])
        else s.say(t[R.string.say_rest_skip])
    }

    /** 위치 권한 결과(MainActivity.onRequestPermissionsResult): 허용이고 함께 가기 중이면 경로 이탈 판정을 켠다 */
    fun onLocationPermission() { if (active) offRoute.start() }

    /** 화면 [우회 안내] 글. 허브 보기에 detour 가 없거나(막힘 해제·나들목 1 km 안) 이 차가 수락하지 않았으면(본선 유지 배정·거절) null = 버튼 없음 */
    fun detourLabel(): String? = if (linkStale()) null else view?.optJSONObject("detour")?.takeIf { detourDecided[it.optString("id")] == true }?.let { t[R.string.btn_detour] }

    /** 우회를 따랐는지 스스로 판정해 준수 점수를 고친다: 보기 offRoute 가 true 면 따름, false 인데 그 나들목을 OFFICIAL_OFFROUTE 넘게 지났으면 안 따름.
     *  허브 보기 offRoute 도 차 안 GPS 횡거리도 없으면 판정을 미룬다 — 모르는 것을 '안 따름'으로 세지 않는다 */
    private fun judgeDetour(v: JSONObject) {
        val (_, icS) = judging ?: return
        // 허브 보기 offRoute 는 앱이 위경도를 보낼 때만 채워진다(안 보냄) — 그래서 차 안 GPS 횡거리로도 본다(simDrive 중에는 GPS 가 경로와 무관해 쓰지 않는다)
        val viewOff = if (v.has("offRoute") && !v.isNull("offRoute")) v.getBoolean("offRoute") else null
        val off = DetourComply.offRoute1km(viewOff, if (simDrive) null else offRoute.lateralM(), LinkHub.OFFICIAL_OFFROUTE) ?: return
        if (off) {
            comply.judge(true); DetourComply.save(context, comply); judging = null
            s.log.demo("우회 따름 — 준수 점수 ${"%.2f".format(comply.score)}"); return
        }
        if (!v.isNull("s") && v.getDouble("s") >= icS + LinkHub.OFFICIAL_OFFROUTE) {
            comply.judge(false); DetourComply.save(context, comply); judging = null
            s.log.demo("우회 안 따름(나들목을 지나도 본선) — 준수 점수 ${"%.2f".format(comply.score)}")
        }
    }

    // ---- 이번 주행 요약 · [경고 틀림](기록만) · 장소 설명 · 시연 한 바퀴 · 긴급차량 배너 (2026-11-13)

    /** [경고 틀림]을 보일 수 있나: 정차 중(주행 상태를 알 때)에 가장 최근 알린 위험이 있고 아직 표시하지 않았다 */
    fun canFlagWrong(): Boolean = active && car.stoppedKnown && summary.lastAlert?.let { !summary.isFlagged(it) } == true

    /** 운전자가 정차 중에 "방금 경고가 틀렸다"고 표시했다. **기록만** — 허브·판정·경고 순서는 그대로다. 연구 기록이 켜져 있으면 그곳에 이벤트로 남는다 */
    fun flagWrong() {
        if (!canFlagWrong()) return
        val id = summary.flagLast() ?: return
        study?.driverFlag("alert_wrong", id)
        s.log.d("운전자: 경고 틀림 표시(기록만, 판정 불변)")
        s.toast(t[R.string.toast_alert_wrong])
        s.render()
    }

    /** 경로 끝에 닿았고 정차했나 — 요약을 도로 화면에도 보인다 */
    fun arrivedNow(): Boolean = active && car.stoppedKnown && routePlace?.arrived(linkS) == true
    /** 지금 보일 단속 알림(연결 중일 때만, 경로 파일 cameras — 실제 경로 모드에는 자료가 없어 null) */
    fun camAlert(): RoutePlace.CamAlert? = if (active) routePlace?.camAlert(linkS) else null

    /** 응급 카드용 장소 줄: 도로 이름·방향, 가까운 나들목, 가까운 휴게소(앞/뒤 거리). 경로 정보가 없거나 연결 중이 아니면 빈 목록 */
    fun placeLines(): List<String> {
        val rp = routePlace ?: return emptyList()
        if (!active) return emptyList()
        val en = s.lang == "en"
        val out = mutableListOf(rp.road(en))
        rp.nearestJunction(linkS)?.let { (p, d) -> out += placeLine(R.string.emg_ic_ahead, R.string.emg_ic_behind, if (en) p.nameEn else p.name, d) }
        rp.nearestRest(linkS)?.let { (p, d) -> out += placeLine(R.string.emg_rest_ahead, R.string.emg_rest_behind, if (en) p.nameEn else p.name, d) }
        return out
    }

    private fun placeLine(ahead: Int, behind: Int, name: String, signedM: Double): String =
        if (signedM >= 0) t[ahead, name, t.km(signedM.toInt())] else t[behind, name, t.km((-signedM).toInt())]

    /** 빠르게 감기 배율(시연 훅 ff, 1 = 평소). 사건 없는 구간을 줄여 보이려고 허브 시각과 내 자리를 같은 배율로 빨리 가게 한다 — 화면 알약에 "빠르게 감기 ×N"을 보인다.
     *  시연 주행 중에만 켜지고, 위험·긴급 안내가 뜨거나 남은 거리가 FF_END_M 안이거나 주행이 끝나면 스스로 1 로 돌아간다 */
    /** 방향 구분(11-14): 고속도로의 맞은편 위험은 허브가 주지 않고, 일반 도로의 맞은편·방향이 애매한 위험은 낮은 단계(side)로 온다.
     *  낮은 단계 항목은 받는 자리(onView)에서 따로 덜어 둔다 — 카드·음성·경고음·오로라·기록의 어느 후보에도 들지 않고 화면의 한 줄(sideNote)로만 쓴다 */
    var dirAwareEnabled = true
    var applyDirAware: ((Boolean) -> Unit)? = null     // MainActivity 가 채운다(지금 허브의 스위치)
    fun setDirAware(on: Boolean) { dirAwareEnabled = on; applyDirAware?.invoke(on); s.log.d("방향 구분 ${if (on) "켬" else "끔"}") }
    private var sideHazards: List<JSONObject> = emptyList()
    fun sideNote(): String? = if (!active || linkStale()) null else sideHazards.firstOrNull()?.optString("note")?.takeIf { it.isNotBlank() }
    private var lastHdg: Double? = null                // 마지막으로 움직일 때의 GPS 방위(멈춘 뒤에도 차의 방향은 그대로)

    /** 시연 주행을 세울 자리(경로 위 m, 시연 훅 sim_stop_at — 휴게소 자리에서 정차하는 장면). null = 없음 */
    var simStopAt: Double? = null
    /** 시연 훅 값: 숫자(m) · "rest"(다음 휴게소 자리) · "off" */
    fun setSimStop(v: String) {
        simStopAt = when (v.trim()) {
            "off", "" -> null
            "rest" -> view?.optJSONObject("restNext")?.optInt("dist", -1)?.takeIf { it > 0 }?.let { linkS + it }
            else -> v.trim().toDoubleOrNull()
        }
        s.log.d("시연 주행 세울 자리 ${simStopAt?.let { "%.0f m".format(it) } ?: "없음"}")
    }

    val ff: Double get() = s.clock.rate
    private var ffForce = false                             // 재생 막대에서 손으로 고른 배속: 안내가 떠 있어도 그 배속으로 간다(세울 자리·끝 앞에서는 그대로 ×1)
    fun setFf(v: Double, force: Boolean = false) {
        ffForce = force && v > 1.0
        val on = v > 1.0 && active && simDrive && !ffBlocked()
        val to = if (on) v else 1.0
        if (to == s.clock.rate) return
        s.clock.rate(to); s.log.d("빠르게 감기 ×${"%.0f".format(s.clock.rate)}"); s.render()
    }
    private fun ffBlocked(): Boolean {
        val a = view?.optJSONArray("hazards")
        if (!ffForce && a != null && (0 until a.length()).any { i -> a.optJSONObject(i)?.has("say") == true }) return true   // 안내가 떠 있는 동안은 실제 속도로
        if (simStopAt?.let { it - linkS <= FF_END_M } == true) return true                                           // 세울 자리 앞에서도 실제 속도로
        val left = liveGeo?.let { it.length - linkS } ?: dest?.takeIf { it.nearDemo }?.let { it.s - linkS }
        return left != null && left <= FF_END_M
    }

    /** 시연 주행 재생 막대(11-14 사용자 "주행 시연할때 N배속" · "동영상 재생 처럼" · "해당 구간 누르면 뒤로가기도 되고 앞으로가기도"). 시연 주행 중에만.
     *  멈춤 = 시연 시계를 세우고(허브 시각이 요청의 t 를 따르므로 허브의 시연 차·각본도 같이 선다) 내 자리도 안 옮긴다. 배율은 그대로 남아, 풀면 그 배율로 다시 간다.
     *  자리 옮기기 = 내 차만 경로 위 그 자리로 간다(허브의 다른 차·각본 시각은 되감기지 않는다). 뒤로 가면 이미 말한 안내를 다시 말할 수 있게 말한 기록을 비운다 */
    val simPlaying: Boolean get() = active && simDrive
    val simPaused: Boolean get() = s.clock.paused
    private var simS0 = Double.NaN                          // 재생 막대의 왼쪽 끝(시연 주행을 시작한 자리)
    fun setSimPause(on: Boolean) {
        val to = on && simPlaying
        if (to == s.clock.paused) return
        s.clock.pause(to); s.log.d(if (to) "시연 주행 멈춤" else "시연 주행 다시 재생"); s.render()
    }
    /** 재생 막대의 범위(경로 위 m): 출발 자리 ~ 끝(실제 경로 끝 · 고른 목적지 · 시연 경로 끝). 시연 주행이 아니면 null */
    fun simSpan(): Pair<Double, Double>? {
        if (!simPlaying) return null
        if (simS0.isNaN()) simS0 = linkS
        val end = liveGeo?.length ?: dest?.takeIf { it.nearDemo }?.s ?: baseGeo?.length ?: return null
        return if (end - simS0 >= SEEK_END_M * 2) simS0 to end else null
    }
    /** 재생 막대에서 누른 자리(경로 위 m)로 내 차를 옮긴다 — 앞·뒤 모두. 끝 SEEK_END_M 앞까지만(도착 장면은 달려서 본다) */
    fun simSeek(to: Double) {
        val (a, b) = simSpan() ?: return
        val x = to.coerceIn(a, b - SEEK_END_M)
        if (x < linkS) { spoken.clear(); detourSaid.clear(); destArrived = false }
        beepKey = ""; beepStage = 0; aheadLast.clear(); loopOff()   // 자리가 뛰면 접근 경고음의 직전 거리 기록이 맞지 않는다
        s.log.d("시연 주행 자리 옮김 ${"%.1f".format(linkS / 1000)} → ${"%.1f".format(x / 1000)} km")
        linkS = x; s.render()
    }

    /** 시연 한 바퀴(시연 빌드 전용 버튼): 시연 주행(convoy_sim)을 켜고 연결을 시작한다 — 심사자가 가상 페달 없이도 한 바퀴를 본다 */
    /** 시연 주행(첫 화면 [▶ 시연 주행]). 11-15 사용자 "시험주행 현재위치에서부터 시작해": 실제 위치를 알고 목적지가 없으면 시연 경로 끝 나들목을
     *  목적지로 두고 지금 자리 → 그곳 실제 경로(TMAP 길 찾기)를 받아 그 위에서 시작한다(시연 각본·휴게소·단속카메라는 RouteGraft 가 옮긴 것만).
     *  목적지가 이미 있으면 그 경로로, 위치를 모르거나 길 찾기가 안 되거나 경로가 짧으면 예전처럼 시연 경로(경부 하행)에서 */
    fun startDemoLap() {
        if (active || starting || demoRouting) return
        val h = realHere(); val g = baseGeo; val end = (baseRoutePlace ?: routePlace)?.junctions?.lastOrNull()
        if (dest != null || h == null || g == null || end == null || !realRouteEnabled || !destAvailable || swapHub == null) { simDrive = true; start(false); return }
        // simDrive 는 길을 받은 뒤에 켠다 — 먼저 켜면 '주행 중'으로 보여 자동 연결(linkTick)이 길이 오기 전에 시연 경로로 시작해 버렸다(11-15 22:04 기록 "실제 경로로 못 감: 경로 없음")
        val p = g.pointAt(end.s)
        val c = DestPick.Cand(if (s.lang == "en") end.nameEn else end.name, "", p.getDouble(0), p.getDouble(1), end.s, 0.0, onRoute = true, nearDemo = true)
        dest = c; destCands = null; destNote = null; destArrived = false; realRoute = null
        val gen = ++routeGen
        demoRouting = true; destBusy = true; s.render()
        s.io.execute {
            val r = runCatching { ai.meeos.tago.map.TmapRoute.fetchPlain(BuildConfig.TMAP_KEY, ai.meeos.tago.map.TmapCarMap.LL(h.lat, h.lon), ai.meeos.tago.map.TmapCarMap.LL(c.lat, c.lon)) }
            s.main.post {
                demoRouting = false; destBusy = false
                if (gen != routeGen || dest !== c || active || starting) { s.render(); return@post }   // 그사이 목적지를 지웠거나 다른 길로 이미 연결했다
                r.onSuccess { q -> realRoute = q; s.log.d("시연 주행 실제 경로: ${"%.1f".format(q.distM / 1000.0)} km · 점 ${q.points.size}개") }
                 .onFailure { e -> s.log.d("시연 주행 실제 경로 실패: ${e.message} — 시연 경로로") }
                refreshMapUrl(); simDrive = true; start(false)
            }
        }
    }
    /** 시연 주행 길 찾기가 가는 중 — 두 번 누름 막기 */
    private var demoRouting = false

    /** 긴급차량(사이렌·위치 신호·길 비킴) 안내가 지금 떠 있나 — 화면이 흑백 반전 배너를 띄운다(청각 보조) */
    fun sirenBanner(): Boolean {
        if (!active || linkStale()) return false
        val a = view?.optJSONArray("hazards") ?: return false
        return (0 until a.length()).any { i -> a.optJSONObject(i)?.let { it.optString("type") == "SIREN" && it.has("say") } == true }
    }

    /** 긴급차량 예상 경로의 넓힌 안내(새 기능 I): 가까운 안내(say)가 없을 때만, 허브가 준 약한 문구 한 줄("긴급차량 올 수 있음 · ○○나들목 방향 추정").
     *  화면에만 — 말하지 않고, 경고음·우선순위에 끼지 않는다. 연결이 낡았으면 없음 */
    fun farNotice(): String? {
        if (!active || linkStale()) return null
        val a = view?.optJSONArray("hazards") ?: return null
        val sirens = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") == "SIREN" }
        if (sirens.any { it.has("say") }) return null
        return sirens.firstNotNullOfOrNull { it.optJSONObject("far")?.optString("text")?.takeIf { s -> s.isNotBlank() } }
    }

    /** [우회 안내]를 눌렀을 때만 순정 내비에 요청한다(자동 경로 변경 없음): 안내 중이면 재탐색, 아니면 다음 나들목을 목적지로 */
    fun requestDetour() {
        if (linkStale()) return   // 낡은 우회 정보로 순정 내비에 요청하지 않는다(V-03)
        val d = view?.optJSONObject("detour") ?: return
        // 누른 순간 한 문장(11-14 음성 빈 곳). 시연 주행이면 순정 내비에 넘기지 않고 그렇게 말한다(휴게소와 같은 원칙 — 순정 내비가 앱을 덮었다)
        if (demoMismatch()) { s.say(t[R.string.say_detour_demo]); s.log.d("시연 주행: 우회를 순정 내비에 넘기지 않음"); return }
        s.navi.detour(d.getDouble("lat"), d.getDouble("lon"), t[R.string.btn_detour])
        s.say(t[R.string.say_detour_sent])
    }

    /** 휴게소를 순정 내비 목적지로(국내 경로라 지역 설정과 무관하게 요청) — 운전자가 [다같이 휴게소]를 누르거나 "예"라고 답했을 때만 불린다.
     *  시연 주행(실제 위치를 모르거나 경로 위 내 자리와 1 km 넘게 다름)에서는 넘기지 않는다: 순정 내비가 지금 실제 자리에서 엉뚱한 길을 안내했다(11-14 에뮬레이터, 판교 ↔ 경부선 시연 자리).
     *  돌려주는 값: 넘겼는지(말할 문장을 고른다) */
    private fun naviToRest(r: JSONObject): Boolean {
        val pos = r.optJSONArray("pos") ?: return false
        if (demoMismatch()) { s.log.d("시연 주행: 휴게소를 순정 내비에 넘기지 않음"); return false }
        s.navi.routeDomestic(pos.getDouble(0), pos.getDouble(1), t[R.string.navi_poi_road, r.optString("name")])
        return true
    }

    /** 순정 내비에 목적지를 넘겨도 되나(=시연 주행이 아닌가): 최근 GPS 가 있고, 그 자리가 경로에서 1 km 안이며 앱이 아는 경로 위 내 자리와도 1 km 안일 때만.
     *  GPS 를 모르면 넘기지 않는다 — 처음엔 '모르면 실차'로 봤는데 에뮬레이터 시연(GPS 없음)에서 순정 내비가 앞으로 나와 앱을 덮었다(11-14 녹화 v2). 시연 한 바퀴(simDrive)는 늘 넘기지 않는다 */
    private fun demoMismatch(): Boolean {
        if (simDrive) return true
        val fix = offRoute.freshFix() ?: return true
        val g = routeGeo ?: return true
        return g.lateral(fix.latitude, fix.longitude) > DEMO_MISMATCH_M || kotlin.math.abs(g.sAt(fix.latitude, fix.longitude) - linkS) > DEMO_MISMATCH_M
    }

    /** 자동 연결(주행을 시작하면 스스로 — 일행 없이, 11-14 사용자 "서로 모르는 차들 차대차 링크가 핵심"): 화면 쪽이 주행 시작에 부른다.
     *  자동으로 시작한 연결은 일행이 없고 정차(P)가 AUTO_IDLE_S 넘게 이어지면 말없이 끊는다. 단추·시연 훅으로 시작한 연결은 그대로 둔다 */
    var autoStarted = false
        private set
    private var autoIdleS = 0.0
    fun startAuto(pickupActive: Boolean = false) { if (active || starting) return; start(pickupActive); autoStarted = true; autoIdleS = 0.0 }

    fun end(quiet: Boolean = false) {
        if (!active) return   // [끝내기] 연타·종료 중 재호출: 같은 종료 안내를 두 번 말하지 않는다
        demoDrive = false
        s.navi.cancelRoadRoute()   // 이 연결이 넘긴 순정 안내만 끈다(사용자가 따로 잡은 목적지는 그대로)
        summary.finish(System.currentTimeMillis())
        convoy = null; view = null; freshness.reset(); staleAnnounced = false
        s.dialog.cancel()
        sirenMic.stop(); sirenHeard = false; sirenFile = false; sirenFails = 0
        car.readRoadSignals = false
        offRoute.stop()
        if (study?.active != true) car.setTurnSignalWatch(false)   // 연구 기록 중이면 그쪽이 계속 쓴다
        car.setFastSpeed(false); car.onImpact = null
        s.navi.onTrafficEvents = null
        if (simDrive) simDrive = false
        if (s.clock.rate > 1.0) s.clock.rate(1.0)
        if (s.clock.paused) s.clock.pause(false)
        simS0 = Double.NaN
        beepKey = ""; beepStage = 0; aheadLast.clear(); jamAhead = false; loopOff()
        survey = null; chargeAsking = false; clearFail(); portSince = 0L
        autoStarted = false; autoIdleS = 0.0; simStopAt = null; pendingGroup = false
        if (!quiet) s.say(t[R.string.say_road_ended])
        onMapUrl(null)
        s.render()
    }

    fun stop() { sirenMic.stop(); beep.release() }

    // ---- 충전기 고장: 질문 · 1분 설문 · 말 신고

    private fun askCharge(now: Long) {
        chargeAsking = true; failSpot = plugS; failAt = now; failSent = false
        s.log.d("감지: 꽂은 뒤 ${CHARGE_FAIL_SEC}초 충전 없음 → 운전자에게 묻기")
        s.dialog.ask(Question(Question.Kind.CHARGER, t[R.string.btn_charger_broken], t[R.string.btn_charger_fine], t[R.string.listen_charger], onAnswer = { answerCharge(it) }),
            t[R.string.say_charger_ask])
        s.main.postDelayed({ if (chargeAsking && s.dialog.pending?.kind == Question.Kind.CHARGER) { s.dialog.cancel(); answerCharge(null) } }, CHARGE_ASK_MS)
    }

    /** true = 고장이에요(CHARGE_FAIL + 설문), false = 아니에요(보고 안 함), null = 무응답·답 전에 뽑음(CHARGE_FAIL 만) */
    private fun answerCharge(yes: Boolean?) {
        if (!chargeAsking) return
        chargeAsking = false
        if (yes == false) { s.log.d("운전자: 충전기 고장 아님 — 보고 안 함"); return }
        failSent = true
        event("CHARGE_FAIL", "${CHARGE_FAIL_SEC}s")
        s.log.d("충전기 고장 의심 → 뒤차들에게 (${if (yes == null) "무응답" else "운전자 확인"})")
        if (yes == true) openSurvey(true)
    }

    private fun clearFail() { failSpot = null; failSent = false }

    /** 충전소(휴게소 services) 반경 안: 허브 보기의 restHere(차 위치 500 m 안 휴게소 = CHARGER_NEAR). 신고 버튼·설문·음성 신고·90초 질문은 여기서만 */
    val nearCharger: Boolean get() = !linkStale() && view?.let { !it.isNull("restHere") } == true

    /** 1분 설문 열기: 질문에 '고장이에요'로 답했거나 [충전기 고장 신고]를 눌렀다. 주행 중이거나 충전소 반경 밖이면 열지 않는다 */
    fun openSurvey(fromQuestion: Boolean = false) {
        if (!active || car.locked || survey != null || !nearCharger) return   // 이미 열려 있으면 고른 답을 지우지 않는다
        survey = ChargeSurvey(failSpot ?: linkS)
        s.say(t[if (fromQuestion) R.string.say_survey_open_after else R.string.say_survey_open])
        s.render()
    }
    /** 그림 타일 하나 = 지금 문항의 답. 마지막 문항이면 바로 보낸다 */
    fun surveyPick(id: String) { val sv = survey ?: return; sv.pick(id); if (sv.done) surveySend() else s.render() }
    fun surveySkip() { val sv = survey ?: return; sv.skip(); if (sv.done) surveySend() else s.render() }
    fun surveyBack() { survey?.back(); s.render() }
    fun surveyClose() { survey = null; s.render() }
    private fun surveySend() {
        val sv = survey ?: return
        survey = null
        event("CHARGE_REPORT", "survey", sv.body())
        s.log.d("충전기 고장 신고(설문) → 뒤차들에게 ${sv.answers.filterValues { it.isNotEmpty() }}")
        s.say(t[R.string.say_survey_sent])
        s.render()
    }

    /** 운전자 말 "충전기 고장": 충전소 반경 안에 서 있으면 설문을 연다. 주행 중이거나 반경 밖이면 안내만 한다(신고하지 않는다) */
    fun chargeVoiceReport() {
        if (!active) return
        if (car.locked || !nearCharger) { s.log.d("충전기 고장 신고(말) — 충전소 반경 밖·주행 중이라 받지 않음"); s.say(t[R.string.say_charger_far]); return }
        openSurvey()
    }

    /** 시연 훅 ev_sim move=N: 정차한 채 자리를 N m 옮긴다(에뮬레이터는 휴게소 안 이동을 흉내 낼 방법이 없다) */
    fun demoMove(m: Double) { linkS += m; s.log.d("시연: 자리 ${m}m 이동 → s ${"%.1f".format(linkS)}") }

    /** 시연 훅: 경고음 단계(1·2·3, ApproachBeep.PASSED)를 바로 울린다 / 네 소리를 WAV 로 쓴다 */
    fun beepTest(stage: Int) { if (stage == ApproachBeep.LOOP3) { beep.loop3(true); s.main.postDelayed({ beep.loop3(false) }, 4000) } else beep.play(stage) }
    fun beepWavs(dir: File) { s.io.execute { val fs = beep.writeWavs(dir); s.main.post { s.log.d("경고음 WAV: " + fs.joinToString { it.name }) } } }

    // ------------------------------------------------------------ 1초마다

    /** 지금 주차 글("B2 · D-14" — 층·기둥만, 좌표 없음). 기록이 없거나 다시 움직였으면 null → 허브에서 지운다(AppController 가 이어 준다) */
    var parkText: () -> String? = { null }

    private fun state() = JSONObject().put("t", s.epoch()).put("s", linkS).put("speed", car.speedMs.toDouble()).put("wiper", car.wiper)
        .put("park", parkText() ?: JSONObject.NULL)
        .also { b -> car.outsideTemp?.let { b.put("temp", it.toDouble()) }; s.navi.speedLimitKmh?.let { b.put("limit", it) } }   // limit: 순정 내비 제한속도(km/h), 모르면 안 보냄
        .also { b -> pendingAck?.let { b.put("detourAck", it); pendingAck = null } }   // 우회 권고 수락·거절(점수는 보내지 않는다)
        .also { b -> if (!simDrive) lastHdg?.let { b.put("hdg", it) } }   // 진행 방위(실차 GPS 가 움직일 때 잰 값) — 허브가 멈춘 차의 방향도 가른다. 시연 주행은 안 보낸다
        .also { b -> if (!simDrive) offRoute.offRoute()?.let { b.put("offRoute", it) } }   // 경로 이탈 불리언만(시연 주행 중에는 GPS 가 경로 위치와 무관해 보내지 않는다)

    /** 도로 링크 요청(state·event·demo) — 응답은 내 차 기준 보기 */
    private fun post(path: String, body: JSONObject) {
        val c = convoy ?: return
        body.put("me", c.getString("me"))
        s.api.post(path, body, onError = { e -> s.log.d("도로 링크 서버 오류: ${e.message}") }) { v -> if (convoy === c) onView(v) }
    }

    /** 위험 이벤트는 그 순간의 위치·속도를 같이 보낸다 — 허브가 마지막 상태 보고(최대 1초 전, 압축 거리로 100m)의 자리에 찍지 않게 */
    private fun event(type: String, value: String, extra: JSONObject? = null) = post("/api/road/event", JSONObject().put("t", s.epoch()).put("type", type).put("value", value).put("s", linkS).put("speed", car.speedMs.toDouble()).also { b -> extra?.keys()?.forEach { k -> b.put(k, extra.get(k)) } })

    /** 충돌(10 Hz 감지기): 1초 틱을 기다리지 않고 바로 보고(직전 2초 표본을 실어 허브가 같은 규칙으로 재검증) → 운전자에게 묻는다 */
    private fun onImpact(pre: Float, nowV: Float, samples: List<Pair<Long, Float>>) {
        if (!active) return
        impactAt = System.currentTimeMillis(); airbagReported = false
        val t0 = samples.lastOrNull()?.first ?: 0L
        val arr = JSONArray(); samples.takeLast(20).forEach { (ms, v) -> arr.put(JSONArray().put(((ms - t0) / 1000.0)).put(v.toDouble())) }
        event("IMPACT", "%.1f→%.1f m/s".format(pre, nowV), JSONObject().put("samples", arr))
        s.log.d("감지: 충돌 → 뒤차들에게 (%.1f→%.1f m/s)".format(pre, nowV))
        askOk()
    }

    /** "괜찮으세요?" — 10초 묻고 20초 더 기다린다(아이폰 10+30 과 같은 구조). 답이 없거나 [도움 필요]면 2차 사고 예방 모드 */
    private fun askOk() {
        if (crashAsked) return
        crashAsked = true
        val en = s.lang == "en"
        s.dialog.ask(Question(Question.Kind.CRASH, if (en) "I'm OK" else "괜찮아요", if (en) "Need help" else "도움 필요",
            if (en) "Say \"I'm OK\"" else "'괜찮아요'라고 말해 주세요", onAnswer = { ok -> answerOk(ok) }),
            if (en) "Are you OK? Say \"I'm OK\" if you are." else "괜찮으세요? 괜찮으면 '괜찮아요'라고 말해 주세요.")
        s.main.postDelayed({ if (crashAsked && s.dialog.pending?.kind == Question.Kind.CRASH) { s.dialog.cancel(); answerOk(null) } }, CRASH_ASK_MS)
    }

    private fun answerOk(ok: Boolean?) {
        if (!crashAsked) return
        crashAsked = false
        post("/api/road/respond", JSONObject().put("t", s.epoch()).put("ok", ok ?: JSONObject.NULL).put("timeout", ok == null))
        val en = s.lang == "en"
        if (ok == true) { s.log.d("운전자 응답: 괜찮음 — 사고 아님"); return }
        s.log.d(if (ok == null) "운전자 무응답 30초 → 2차 사고 예방 모드" else "운전자: 도움 필요 → 2차 사고 예방 모드")
        s.say(if (en) "I've warned cars behind and your family. Turn on hazards and get behind the guardrail."
              else "사고 의심을 뒤차와 가족에게 알렸습니다. 비상등을 켜고 가드레일 밖으로 대피하세요.")
        onCrashHelp?.invoke(ok == null)
    }

    /** 순정 내비 돌발 정보 → 허브. 같은 사건(종류·위치·시작 시각)만 다시 오면 60 s 에 한 번 */
    private fun onOfficial(list: List<TrafficEventInfo>) {
        if (!active) return
        val picked = list.filter { it.eventType.name in OFFICIAL_TYPES }
        if (picked.isEmpty()) return
        val keys = picked.map { "${it.eventType.name}|${"%.4f".format(it.latitude)}|${"%.4f".format(it.longitude)}|${it.startTime}" }.toSet()
        val now = System.currentTimeMillis()
        if (keys.all { it in officialKeys } && now - officialSentAt < OFFICIAL_EVERY_MS) return
        officialKeys.clear(); officialKeys += keys; officialSentAt = now
        val arr = JSONArray()
        for (e in picked) arr.put(JSONObject().put("type", e.eventType.name).put("lat", e.latitude).put("lon", e.longitude)
            .put("start", epochOf(e.startTime) ?: JSONObject.NULL).put("end", epochOf(e.endTime) ?: JSONObject.NULL).put("desc", e.description.orEmpty().take(40)))
        s.log.d("내비 돌발 정보 ${picked.size}건 → 허브: " + picked.joinToString { "${it.eventType.name} ${e2(it.description)}" })
        post("/api/road/official", JSONObject().put("t", s.epoch()).put("events", arr))
    }

    private fun e2(d: String?) = d.orEmpty().take(12)

    /** 내비 시각 "2023-11-05T22:08:28"(차 시간대) → epoch 초. 허브는 숫자만 받는다(시간대는 차가 안다) */
    private fun epochOf(iso: String?): Double? = iso?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.time.LocalDateTime.parse(it.take(19)).atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble() }.getOrNull()
    }

    /** 시연 훅 official_sim "ACCIDENT,2000"(내 앞 2 km) · "ACCIDENT,s=27119"(경로 위 절대 위치) · "ACCIDENT,2000,end=60"(60 s 뒤 공식 해제) */
    fun simOfficial(spec: String) {
        val p = spec.split(",").map { it.trim() }
        val type = p.getOrNull(0)?.uppercase() ?: return
        if (type !in OFFICIAL_TYPES) { s.log.d("official_sim 형식: ACCIDENT|ROAD_DAMAGE|DISASTER,거리m|s=절대,[end=초]"); return }
        val at = p.getOrNull(1) ?: "0"
        val sAt = if (at.startsWith("s=")) at.drop(2).toDoubleOrNull() ?: return else linkS + (at.toDoubleOrNull() ?: 0.0)
        val endIn = p.firstOrNull { it.startsWith("end=") }?.drop(4)?.toDoubleOrNull()
        val e = JSONObject().put("type", type).put("s", sAt).put("desc", if (s.lang == "en") "demo" else "시연")
            .put("end", endIn?.let { s.epoch() + it } ?: JSONObject.NULL)
        s.log.d("공식 돌발 시연: $type s=${sAt.toInt()}${endIn?.let { " end+${it.toInt()}s" } ?: ""}")
        post("/api/road/official", JSONObject().put("t", s.epoch()).put("events", JSONArray().put(e)))
    }

    /** 시연 훅 impact_sim "25,0": 충돌 표본을 감지기에 넣는다(실차 표본과 같은 모양) */
    fun simImpact(spec: String) {
        val p = spec.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size == 2) car.simImpact(p[0], p[1]) else s.log.d("impact_sim 형식: pre,post (m/s)")
    }
    private fun groupPost(act: String, body: JSONObject) { convoy?.optString("group")?.takeIf { it.isNotEmpty() }?.let { post("/api/link/$it/$act", body) } }

    /**
     * 1초마다. 말하는 동안에도 위치 적분·위험 감지·상태 보고를 모두 한다 — 전에는 말하는 동안 틱을 통째로 건너뛰어, 두 차 시연에서
     * 앞차가 안내를 말하는 사이에 밟은 급제동이 빠지고 허브의 앞차 위치가 출발점에 묵었다(2026-11-01)
     */
    fun tick(dt: Double, speaking: Boolean) {
        if (!active) return
        roadTick(dt)
    }

    private fun simFollow(): Float {
        val adv = view?.optJSONObject("ahead")?.optJSONObject("advice")
        val target = when {
            simTargetKmh != null -> simTargetKmh!! / 3.6f
            adv == null -> 25f
            adv.optInt("dist") > 0 -> adv.optInt("adviseKmh") / 3.6f
            else -> maxOf(adv.optInt("jamKmh"), 14) / 3.6f
        }
        // 시연 훅 sim_stop_at: 그 자리에 맞춰 줄이고 선다(0.8 m/s² — 경로 거리는 시연 배율·빠르게 감기 배율만큼 압축돼 있어 실제 거리로 바꿔 셈한다).
        // 마지막에도 한 틱에 1.5 m/s 넘게 떨어뜨리지 않는다 — 속도가 한 번에 2.2 m/s 넘게 꺼지면 충돌 감지(ImpactDetector)가 '괜찮으세요?'를 묻는다(11-14 첫 4막 녹화)
        // 세울 자리가 없으면 실제 경로에서는 경로 끝(목적지)에서 선다 — 끝을 지나쳐 서면 먼저 주차한 일행이 '뒤 700 m'로 보였다(11-14 첫 4막 녹화)
        val stop = (simStopAt ?: liveGeo?.length)?.let { at -> ((at - linkS) / (LinkHub.DEMO_SCALE * s.clock.rate)).let { left -> if (left <= 0.5) 0f else kotlin.math.sqrt(2 * 0.8 * left).toFloat() } }
        val goal = if (stop != null) minOf(target, stop) else target
        simV = (simV + (goal - simV).coerceIn(-1.5f, 1.5f)).coerceAtLeast(0f)
        return simV
    }

    /** 1초 틱: 진행 거리 → 감지기들(순서가 의미 있다: 급제동·길 비킴은 직전 두 속도를 본 뒤 갱신) → 충전 → 에어백·사이렌 → 접근 경고음 → 허브 보고 */
    private fun roadTick(dt: Double) {
        val driving = simDrive || car.driving
        if (simDrive) car.simulatedSpeed = simFollow()
        if (simDrive && simToVc) { val now = android.os.SystemClock.elapsedRealtime(); if (now - vcSentAt >= 1000) { vcSentAt = now; vcSpeed(kotlin.math.round(simV * 3.6f).toInt()) } }
        val speed = car.speedMs
        if (!simDrive) here()?.takeIf { it.ageS <= GPS_FRESH_S }?.bearing?.let { lastHdg = it }
        val gps = if (liveGeo != null && !simDrive) here()?.takeIf { it.ageS <= GPS_FRESH_S } else null
        val gpsS = gps?.let { f -> liveGeo?.let { g -> if (g.lateral(f.lat, f.lon) <= GPS_ON_ROUTE_M) g.sAt(f.lat, f.lon) else null } }
        if (gpsS != null) { if (gpsS0.isNaN()) gpsS0 = gpsS else if (kotlin.math.abs(gpsS - gpsS0) > GPS_MOVED_M) gpsLive = true }
        // 실제 경로 위: GPS 가 실제로 움직이고 있으면(실차) 내 자리 = GPS 를 경로에 맞춘 자리. GPS 가 제자리면(에뮬레이터·시연) 지금처럼 속도로 간다. 실차 미확인
        if (gpsLive && gpsS != null) linkS = gpsS
        else if (driving && !s.clock.paused) linkS += speed * dt * LinkHub.DEMO_SCALE * s.clock.rate   // 재생 막대의 멈춤 동안은 자리도 선다
        liveGeo?.let { g -> if (linkS > g.length) linkS = g.length }   // 실제 경로의 끝을 넘지 않는다
        if (s.clock.rate > 1.0 && (!simDrive || ffBlocked())) setFf(1.0)
        if (autoStarted && view?.optJSONObject("group") == null && s.car.stoppedKnown) {   // 자동 연결: 일행 없이 정차가 이어지면 말없이 끊는다
            autoIdleS += dt
            if (autoIdleS >= AUTO_IDLE_S) { s.log.d("자동 연결을 정차 ${AUTO_IDLE_S.toInt()}초 뒤 말없이 끊음"); end(quiet = true); return }
        } else autoIdleS = 0.0
        liveGeo?.let { g -> dest?.let { d -> if (!destArrived && linkS >= g.length - ARRIVE_M) { destArrived = true; s.say(t[R.string.say_dest_arrived, d.name]); s.log.d("목적지 근처 도착(실제 경로)") } } }
        dest?.let { d -> if (liveGeo == null && d.nearDemo && !destArrived && linkS >= d.s) { destArrived = true; s.say(t[R.string.say_dest_arrived, d.name]); s.log.d("목적지 근처 도착") } }
        if (demoTraffic && !demoCalled && driving && speed > 3f) { demoCalled = true; post("/api/road/demo", JSONObject().put("t", s.epoch()).apply { if (demoCore) { put("core", true); put("ems", "beacon") } }) }
        val now = System.currentTimeMillis()
        detectHardBrake(now, speed)
        detectYield(now, speed)
        prevSpeed2 = prevSpeed; prevSpeed = speed
        driveRisk.tick(now, speed, car.steerAngle)
        if (jamWatch) jamMinKmh = minOf(jamMinKmh, speed * 3.6)
        detectRain()
        detectSwerve(now, speed)
        detectBrakeHold(now, speed)
        detectRecirc()
        detectTurnback(now)
        chargeTick(now)
        airbagTick(now)
        sirenTick(now)
        approachTick(now, speed)
        staleTick()
        if (!commSim) postState()   // 말하는 중에도 보고한다(안내는 한 번씩 큐에 쌓여 겹치지 않는다). comm_sim 이면 '통신 끊김' 흉내
    }

    /** 연결이 끊기는 순간·돌아오는 순간에 한 번씩 말한다(V-03). 화면은 build() 가 linkStale() 로 그린다 */
    private fun staleTick() {
        val st = linkStale()
        if (st == staleAnnounced) return
        staleAnnounced = st
        s.log.d(if (st) "허브 응답 ${LinkFreshness.STALE_MS / 1000}초 넘게 없음 — 연결 끊김 표시·경고음 중지" else "허브 응답 복귀")
        s.say(t[if (st) R.string.say_link_lost else R.string.say_link_back])
        s.render()
    }

    private var stateSentAt = 0L   // 앞 상태 보고를 보낸 시각(elapsedRealtime). 0 = 응답을 이미 받았다(또는 아직 안 보냈다)
    private var stateSeq = 0       // 상태 보고마다 하나씩: 늦게 온 옛 응답이 뒤 보고의 '보냈다' 표시를 지우지 못하게

    /**
     * 1초 상태 보고. 앞 보고의 응답(보기)을 아직 못 받았으면 이번 것은 건너뛴다 — 요청은 한 줄(io 스레드)로 나가므로 허브가 느리거나 멎어
     * 응답이 3초씩 걸리면 1초마다 쌓인 보고가 끝없이 밀려(메모리·낡은 위치가 한꺼번에 도착) 나간다. 건너뛴 보고의 값은 다음 보고가 최신으로 싣는다
     * (우회 수락·거절 pendingAck 도 보낼 때만 꺼낸다). 응답이 영영 안 와도 STATE_STALL_MS 뒤에는 다시 보낸다(그래도 쌓이는 속도는 8초에 한 건)
     */
    private fun postState() {
        val c = convoy ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (stateSentAt != 0L && now - stateSentAt < STATE_STALL_MS) return
        stateSentAt = now
        val seq = ++stateSeq
        s.api.post("/api/road/state", state().put("me", c.getString("me")),
            onError = { e -> if (seq == stateSeq) stateSentAt = 0L; s.log.d("상태 보고 서버 오류: ${e.message}") }) { v -> if (seq == stateSeq) stateSentAt = 0L; if (convoy === c) onView(v) }
    }

    /** 급제동: 1초 사이 3 m/s 이상 감속(18 km/h 이상에서) + 다음 1초에도 낮게 유지(순간 출렁임 제외), 10초에 한 번 */
    private fun detectHardBrake(now: Long, speed: Float) {
        if (isHardBrake(prevSpeed2, prevSpeed, speed) && now - lastBrakeAt > HARD_BRAKE_GAP_MS) {
            lastBrakeAt = now
            if (jamWatch) jamBrakes++
            driveRisk.brake(now)
            event("HARD_BRAKE", "%.1f→%.1f m/s".format(prevSpeed2, prevSpeed))
            s.log.d("감지: 급제동 → 뒤차들에게 (%.1f→%.1f→%.1f m/s)".format(prevSpeed2, prevSpeed, speed))
        }
    }

    /**
     * 길 터 주기(YIELD, docs/dev/40-algorithms.md A-13b): 시속 30 이상에서 2초 사이 3 m/s 넘게 줄이며 오른쪽으로 비킴(오른쪽 깜빡이,
     * 또는 핸들 오른쪽 10° 이상 — 부호는 Android VHAL PERF_STEERING_ANGLE 규약 '왼쪽 음수'를 따름, Pleos 실차 미확인). 60초에 한 번.
     * 허브는 여러 차의 YIELD 지점이 교통 흐름보다 빨리 앞으로 옮겨 갈 때만 뒤에 긴급차량이 있다고 본다(한 대의 비킴은 아무것도 아니다)
     */
    private fun detectYield(now: Long, speed: Float) {
        val steer = car.steerAngle; val right = car.turnSignal == "RIGHT"
        if (isYield(prevSpeed2, speed, right, steer) && now - lastYieldAt > YIELD_GAP_MS) {
            lastYieldAt = now
            event("YIELD", if (right) "turn" else "steer")
            s.log.d("감지: 길 터 주기(%.1f→%.1f m/s, %s) → 허브".format(prevSpeed2, speed, if (right) "오른쪽 깜빡이" else "핸들 %.0f°".format(steer)))
        }
    }

    /** 폭우: 와이퍼 연속 작동(워셔·간헐 제외) — 바뀔 때만 보고 */
    private fun detectRain() {
        val rain = car.wiper.startsWith("CONTINUOUS")
        if (rain != rainReported) {
            rainReported = rain
            event(if (rain) "HEAVY_RAIN" else "HEAVY_RAIN_CLEARED", car.wiper)
        }
    }

    /** 급조향(낙하물 회피): 40km/h 이상에서 1초 사이 핸들 60° 이상, 브레이크는 밟지 않음. 15초에 한 번 */
    private fun detectSwerve(now: Long, speed: Float) {
        val a = car.steerAngle; val pa = prevSteer
        if (isSwerve(pa, a, speed, car.brakePedal) && now - lastSwerveAt > SWERVE_GAP_MS) {
            lastSwerveAt = now
            event("SWERVE", "%.0f→%.0f°".format(pa, a))
            s.log.d("감지: 급조향 → 뒤차들에게 (%.0f→%.0f°)".format(pa, a))
        }
        prevSteer = a
    }

    /** 브레이크 오래 밟음(긴 내리막): 30km/h 이상에서 20초 넘게 계속. 떼면 다시 셈 */
    private fun detectBrakeHold(now: Long, speed: Float) {
        if (car.brakePedal == true && speed > BRAKE_HOLD_FROM_MS) {
            if (brakeHoldSince == 0L) brakeHoldSince = now
            if (!brakeHoldReported && now - brakeHoldSince >= BRAKE_HOLD_MS) {
                brakeHoldReported = true
                event("BRAKE_HOLD", "20s")
                s.log.d("감지: 브레이크 20초 이상 → 긴 내리막")
            }
        } else { brakeHoldSince = 0L; brakeHoldReported = false }
    }

    /** 내기순환으로 바꿈(연기·매연): 켜질 때 보고, 꺼지면 해제 보고(서버는 10분 동안 보고를 남긴다) */
    private fun detectRecirc() {
        val rc = car.recirc == true
        if (rc != recircReported) {
            recircReported = rc
            event(if (rc) "RECIRC_ON" else "RECIRC_OFF", "recirc")
            if (rc) s.log.d("감지: 내기순환 → 뒤차들에게")
        }
    }

    /** 되돌아감(침수·통제 의심): 도로 위에서 후진 기어 3초 이상 */
    private fun detectTurnback(now: Long) {
        if (car.reversing && linkS > 0) {
            if (reverseSince == 0L) reverseSince = now
            if (!turnbackReported && now - reverseSince >= TURNBACK_MS) {
                turnbackReported = true
                event("TURNBACK", "reverse")
                s.log.d("감지: 되돌아감 → 뒤차들에게")
            }
        } else reverseSince = 0L
    }

    /**
     * 충전(EV, docs/dev/40-algorithms.md A-15): 정차 중 꽂고 90초 넘게 충전이 시작되지 않으면 바로 올리지 않고 묻는다 "충전기 고장인가요?"
     *  [고장이에요]·'고장' → CHARGE_FAIL + 1분 설문, [아니에요] → 보고 안 함(결제·꽂음 실수), 60초 무응답·답 전에 뽑음 → CHARGE_FAIL(차 신호만, 전과 같다).
     *  실패 뒤 같은 휴게소에서 5 m 넘게 옮겨 충전되면 CHARGE_MOVED(그 기기 고장의 강한 근거 — 해제하지 않는다), 같은 자리에서 되면 CHARGE_OK(해제).
     *  실패 없이 충전되면 CHARGE_SUCCESS(허브: 같은 자리 남의 고장 기록은 거두고, 다른 자리면 '다른 기기는 된다'의 근거).
     *  정차한 지 3분 넘어서야 꽂으면 충전 대기 → CHARGE_START(대기 분). 허브는 휴게소 500m 안일 때만 받아 그 휴게소에 붙인다
     */
    private fun chargeTick(now: Long) {
        if (car.stopped) { if (stopSince == 0L) stopSince = now } else stopSince = 0L
        failSpot?.let { if (now - failAt > CHARGE_KEEP_MS || abs(linkS - it) > CHARGE_REST_M) clearFail() }   // 30분 지났거나 휴게소를 떠났다
        if (survey != null && car.locked) { survey = null; s.log.d("주행 시작 — 충전기 신고 설문 닫음") }
        val port = car.chargePort
        if (port == true && car.stopped) {
            val soc = car.soc
            if (portSince == 0L) {
                portSince = now; portSoc = soc; plugS = linkS; plugAsked = false; plugDone = false
                val waited = if (stopSince > 0) (now - stopSince) / 1000 else 0L   // 실패 뒤 다시 꽂음(failSpot)은 대기 줄이 아니라 고장 탓 — 대기로 보내지 않는다
                if (waited >= CHARGE_WAIT_SEC && failSpot == null) { event("CHARGE_START", "${waited / 60}"); s.log.d("감지: 충전 대기 ${waited / 60}분 → 일행에게") }
            }
            val progressing = car.charging == true || (soc != null && portSoc != null && soc > portSoc!!)
            if (progressing && !plugDone) {
                plugDone = true
                if (chargeAsking) { s.dialog.cancel(); chargeAsking = false }   // 묻는 사이 충전이 시작됐다 = 고장 아님
                val fs = failSpot
                if (fs != null && failSent) {
                    val d = abs(linkS - fs)
                    if (d >= CHARGE_MOVE_M) { event("CHARGE_MOVED", "${d.toInt()}", JSONObject().put("from", fs)); s.log.d("충전 시작: ${d.toInt()}m 옮긴 자리 — 앞 자리 충전기 고장 근거(해제 안 함)") }
                    else { event("CHARGE_OK", "charging"); s.log.d("충전 시작: 같은 자리 — 충전기 고장 의심 해제") }
                } else { event("CHARGE_SUCCESS", "charging"); s.log.d("충전 시작 → 허브(같은 자리 고장 기록 정리)") }
                clearFail()
            } else if (!progressing && !plugDone && !plugAsked && now - portSince >= CHARGE_FAIL_SEC * 1000L) {
                plugAsked = true
                if (nearCharger) askCharge(now) else s.log.d("꽂은 뒤 ${CHARGE_FAIL_SEC}초 충전 없음 — 충전소 반경 밖이라 묻지 않음")
            }
        } else if (port != true) {
            portSince = 0L
            if (chargeAsking) { s.dialog.cancel(); answerCharge(null) }   // 답하기 전에 뽑았다 → 차 신호만 보고
        }
    }

    /** 충돌 뒤 30초 동안 에어백 전개를 읽는다(평소엔 읽지 않는다). 에뮬레이터는 미지원이라 훅 airbag_sim 으로만 */
    private fun airbagTick(now: Long) {
        if (impactAt > 0 && !airbagReported && now - impactAt < AIRBAG_WATCH_MS) car.readAirbag { b ->
            if (b == true && !airbagReported) { airbagReported = true; event("AIRBAG", "deployed"); s.log.d("감지: 에어백 전개 → 뒤차들에게") }
        }
    }

    /** 사이렌(긴급차량): 마이크가 듣는 동안 5초마다. 서버가 차마다 처음 들은 곳을 이어 긴급차량이 다가오는 방향·속도를 잰다 */
    private fun sirenTick(now: Long) {
        if (sirenHeard && now - sirenSentAt >= SIREN_REPORT_MS) {
            sirenSentAt = now
            event("SIREN", "mic")
        }
    }

    /**
     * 접근 경고음. 꼬리 = 앞의 확인된 사고 의심(정지, 0 km/h) 또는 권장 속도 안내의 정체 시작(정체 속도) 중 가까운 것.
     * 내 차가 꼬리보다 BEEP_SLOW_KMH 넘게 빠를 때만, 꼬리까지 남은 시간 TTC = 거리 ÷ (속도 차 × DEMO_SCALE) 로 단계를 정한다.
     * 단계는 꼬리마다 한 번씩 올라가고(같은 단계를 되풀이하지 않는다), 3단계만 1초마다 되풀이한다. 충분히 줄이면 멈춘다
     */
    private fun approachTick(now: Long, speed: Float) {
        if (linkStale()) { beepKey = ""; beepStage = 0; loopOff(); return }   // 허브 응답이 끊겼다: 얼어붙은 거리로 경고음을 내지 않는다(V-03)
        val v = view ?: return
        passTick(v, now, speed)
        var key: String? = null; var dist = 0.0; var tailKmh = 0.0
        v.optJSONArray("hazards")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                if (h.optString("type") != "INCIDENT" || !h.optBoolean("confirmed") || h.isNull("ahead")) continue
                val d = h.optDouble("ahead")
                if (d > 0 && (key == null || d < dist)) { key = h.optString("id"); dist = d; tailKmh = 0.0 }
            }
        }
        v.optJSONObject("ahead")?.optJSONObject("advice")?.let { adv ->
            val d = adv.optDouble("dist")
            if (d > 0 && (key == null || d < dist)) { key = "jam"; dist = d; tailKmh = adv.optDouble("jamKmh") }
        }
        val k = key
        if (k == null) { beepKey = ""; beepStage = 0; loopOff(); return }
        if (k != beepKey) {
            loopOff()
            beepFloor = if (beepStage >= 2 && now - beepAt < BEEP_CARRY_MS) 2 else 0
            beepKey = k; beepStage = 0
        }
        val closingKmh = speed * 3.6 - tailKmh
        if (closingKmh < BEEP_SLOW_KMH) { loopOff(); return }                 // 충분히 줄였다: 3단계 반복을 멈춘다
        val ttc = dist / (closingKmh / 3.6 * LinkHub.DEMO_SCALE)
        val raw = when { ttc <= BEEP_TTC[2] -> 3; ttc <= BEEP_TTC[1] -> 2; ttc <= BEEP_TTC[0] -> 1; else -> 0 }
        val stage = if (raw > 0) maxOf(raw, beepFloor) else 0
        if (stage < 3) loopOff()
        if (stage == 0 || !(stage > beepStage || (stage == 3 && now - beepAt >= 1000))) return
        if (stage < 3 && s.voice.speaking) return          // 안내 음성이 나오는 중: 1·2단계는 끝난 뒤 다음 틱에(겹치면 둘 다 흐려진다). 3단계는 그대로
        beepStage = maxOf(beepStage, stage); beepAt = now
        if (stage == 3) { if (!looping) { looping = true; beep.loop3(true) } }   // 3단계는 1초 고리로 끊김 없이(기록은 1초마다 그대로)
        else beep.play(stage)
        study?.beep(stage, ttc, dist, k)
        s.log.d("주의음 ${stage}단계: 꼬리 ${dist.toInt()}m, ${"%.1f".format(ttc)}초, 속도 차 ${closingKmh.toInt()}km/h")
    }

    /**
     * 지난 알림음. ① 확인된 사고 의심의 앞 거리가 양수에서 0 이하로 바뀌면(그 지점을 지남) ② 권장 속도 안내(정체 꼬리·정체 안)가 이어지다
     * 사라지고 시속 40 이상으로 달리면(정체 구간을 빠져나옴) "딩–동" 한 번. 3초 안에 겹치면 한 번만
     */
    private fun passTick(v: JSONObject, now: Long, speed: Float) {
        val seen = HashSet<String>()
        var passed: String? = null
        v.optJSONArray("hazards")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                if (h.optString("type") != "INCIDENT" || h.isNull("ahead")) continue
                val id = h.optString("id"); val d = h.optDouble("ahead")
                seen += id
                val prev = aheadLast[id]
                if (prev != null && prev > 0 && d <= 0) passed = id
                if (h.optBoolean("confirmed")) aheadLast[id] = d else aheadLast.remove(id)
            }
        }
        aheadLast.keys.retainAll(seen)
        val adv = v.optJSONObject("ahead")?.optJSONObject("advice")
        if (adv != null) jamAhead = true
        else if (jamAhead && speed * 3.6 >= 40) { jamAhead = false; if (passed == null) passed = "jam" }
        val p = passed ?: return
        if (now - passAt < 3000) return
        passAt = now
        loopOff()
        beep.passed()
        study?.passed(p)
        s.log.d("지난 알림음: ${if (p == "jam") "정체 구간 벗어남" else "사고 지점 $p 지남"}")
    }

    /** 사이렌 감지는 도로 링크 중에만, 음성 인식이 듣지 않을 때만 마이크를 쓴다(1초마다 맞춘다) */
    fun manageSiren() {
        val want = active && !s.dialog.awaiting &&
            (sirenWav != null || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (!sirenMic.active) sirenFile = false
        // 실패로 스스로 끝난 실행(모델 없음·마이크 오류)이 이어지면 매초 TFLite 인터프리터를 다시 만들지 않도록 2·4·8·16 초 쉬고, 5번째에 이 주행에서는 그만둔다.
        // 끝 기록은 실행마다 새 객체라 한 번만 센다(실행 시간도 그 안에 있어 틱 시각과 무관). 10초 넘게 정상으로 돌면 실패 횟수를 지운다
        val now = android.os.SystemClock.elapsedRealtime()
        val end = sirenMic.selfEnd
        if (end != null && end !== sirenEndSeen) { sirenEndSeen = end; if (end.failed && end.lifeMs < SIREN_OK_MS) sirenFails++ else sirenFails = 0 }
        if (sirenFails > 0 && sirenMic.active && now - sirenStartAt >= SIREN_OK_MS) sirenFails = 0
        val last = sirenEndSeen
        val backoff = sirenFails > 0 && last != null && (sirenFails >= SIREN_MAX_FAILS || now - last.at < SIREN_RETRY_MS shl (sirenFails - 1))
        if (want && !sirenMic.active && !backoff) { val w = sirenWav; sirenWav = null; sirenFile = w != null; sirenStartAt = now; sirenMic.start(w) }
        else if (!want && sirenMic.active && !sirenFile) sirenMic.stop()
    }

    /** 음성 인식이 마이크를 쓰도록 사이렌 감지는 잠시 쉰다(답을 받으면 manageSiren 이 다시 켠다) */
    fun releaseMicForListening() { if (!sirenFile) sirenMic.stop() }

    // ------------------------------------------------------------ 서버 보기 → 음성(한 번에 한 문장) + 화면

    private fun onView(v: JSONObject) {
        v.optJSONArray("hazards")?.let { a ->            // 낮은 단계(맞은편·방향 불확실) 항목을 덜어 둔다
            val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            val side = all.filter { it.has("side") }
            sideHazards = side
            if (side.isNotEmpty()) v.put("hazards", org.json.JSONArray(all.filter { !it.has("side") }))
        } ?: run { sideHazards = emptyList() }
        view = v
        freshness.ok(android.os.SystemClock.elapsedRealtime())
        v.optJSONArray("hazards")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { h -> if (h.optBoolean("confirmed") && h.has("id")) summary.confirm(h.getString("id")) } }
        v.optJSONObject("ahead")?.optJSONObject("advice")?.let { summary.confirm("jam") }   // 권장 속도(정체 꼬리)는 허브가 확정한 정체
        s.render()
        val now = System.currentTimeMillis()
        // 시계 오차(ADR-43): 허브가 내 t 를 수신 시각 기준으로 CLOCK_SKEW_SEC 넘게 어긋났다고 보면 clockSkew(초)를 준다 — 동작은 허브가 맞추고 앱은 기록만
        val skew = v.optDouble("clockSkew").takeIf { !it.isNaN() }
        if (skew != clockSkew) { clockSkew = skew; s.log.d(if (skew == null) "시계 오차 해소" else "시계 오차 ${skew}s — 허브가 수신 시각으로 창을 잡음(차 시계 확인)") }
        if (!briefed && v.optBoolean("demo") && v.optInt("nearby") > 0) { briefed = true; speak(v.optString("brief"), now); return }
        // 말 줄이기(설계 10-11): ① 바로 말함 = 긴급차량·사고 의심·가까운 급제동(예산과 상관없이)
        // ② 조용할 때만 = 나머지 위험(종류별 행동 거리 안)·권장 속도·비 예보·교량 결빙·위험성 — 도로 시간 30초에 한 문장
        // ③ 화면에만 = 행동 거리 밖의 위험, 사고 다발 구간, 위험성 '주의'
        val hz = v.optJSONArray("hazards")
        // 최소 우회 안내(FR-G-36, 11-08 통합 기준): 막힘 확정 뒤 DETOUR_DELAY_S 안에 말해야 하므로 말 예산과 무관하게 바로 — 아직 안 말한
        // 긴급차량 문장만 앞선다. 같은 막힘(id)에 한 번. 문장에 나들목 이름이 없다(어느 나들목으로 나갈지는 순정 내비 재탐색)
        judgeDetour(v)
        val dv = v.optJSONObject("detour")
        if (dv != null && dv.optString("assign") == "detour" && dv.getString("id") !in detourDecided) {
            // 허브가 우회에 배정한 차: 준수 점수 c 와 허브의 비율 p 로 수락(c ≥ 1 − p). 거절이면 본선 유지 — 정체 꼬리 권고 속도(ahead.advice)는 그대로 나온다
            val hid = dv.getString("id")
            val p = dv.optDouble("p", LinkHub.DETOUR_P_MIN)
            val c0 = comply.score
            val ok = comply.accepts(p)   // 받은 수는 판정이 날 때(judgeDetour) 센다 — 여기서는 세지 않는다
            detourDecided[hid] = ok
            pendingAck = JSONObject().put("hazard", hid).put("ack", if (ok) "accepted" else "declined")
            if (!v.isNull("s")) judging = hid to (v.getDouble("s") + dv.optInt("icDist"))
            s.log.demo("우회 권고 ${if (ok) "수락" else "거절 — 본선 유지"}: p $p · 준수 점수 ${"%.2f".format(c0)}")   // 시연 빌드 logcat 에만
        }
        val detour = dv?.takeIf { it.has("say") && it.getString("id") !in detourSaid && detourDecided[it.getString("id")] == true }
        if (detour != null) {
            val id = detour.getString("id")
            val seen = detourSeen.getOrPut(id) { now }
            val sirenFirst = hz != null && (0 until hz.length()).any { i -> hz.getJSONObject(i).let { h ->
                h.optString("type") == "SIREN" && h.has("say") && (spoken[h.getString("id")] ?: 0) < h.optInt("level", 1) } }
            if (!sirenFirst) {
                detourSaid += id
                val lag = now - seen
                s.log.demo("우회 안내: 막힘 ${detour.optInt("inc")}m ${detour.optString("reason")} · 다음 나들목 ${detour.optInt("icDist")}m · 보기 수신 뒤 ${lag}ms" +
                    if (lag > LinkHub.DETOUR_DELAY_S * 1000) " (상한 ${LinkHub.DETOUR_DELAY_S}s 넘음)" else "")
                speak(detour.getString("say"), now); return
            }
        }
        var normal: JSONObject? = null
        if (hz != null) for (i in 0 until hz.length()) {
            val h = hz.getJSONObject(i)
            if (!h.has("say")) continue
            val type = h.optString("type"); val lvl = h.optInt("level", 1); val id = h.getString("id")
            if ((spoken[id] ?: 0) >= lvl) continue
            if (type != "SIREN" && h.optDouble("ahead", 0.0) > (SAY_RANGE[type] ?: SAY_RANGE_DEFAULT)) continue   // 아직 멀다: 화면에만(가까워지면 그때 말한다)
            if (type in URGENT) { spoken[id] = lvl; summary.alert(id); study?.onSpoken(h, null, h.getString("say")); speakHazard(h.getString("say"), id, lvl, now); return }
            if (normal == null) normal = h
        }
        if (now - lastSayAt < roadMs(BUDGET_MS)) return                 // 말 예산: 방금 말했다 — 다음 틱에 다시 본다(거리 문장은 그때 새로 온다)
        // 지나치기 전에 말해야 하는 것부터: 행동 거리 안 위험과 교량 결빙(1.5 km 앞부터) 중 더 가까운 것. 마감이 없는 위험성은 맨 뒤
        val zs = v.optJSONArray("zones")
        val bridge = zs?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { z -> z.has("say") && z.getString("id") !in zoneSaid } }
        if (bridge != null && (normal == null || bridge.optDouble("ahead") <= normal.optDouble("ahead"))) {
            zoneSaid += bridge.getString("id"); speak(bridge.getString("say"), now); return
        }
        normal?.let { val say = it.getString("say") + windowNote(it); spoken[it.getString("id")] = it.optInt("level", 1); summary.alert(it.getString("id")); study?.onSpoken(it, null, say); speak(say, now); return }
        val ahead = v.optJSONObject("ahead")
        val adv = ahead?.optJSONObject("advice")
        if (adv == null) {
            // 정체를 지나왔다: 권장 속도를 따르는 동안 급제동이 없었으면 한마디(정체 안까지 들어갔을 때만)
            if (jamWatch && jamMinKmh < 30 && car.speedMs > 15f && jamBrakes == 0) {
                jamWatch = false; advSaid = false
                speak(t[R.string.say_jam_cleared], now); return
            }
            if (car.speedMs > 15f) { jamWatch = false; advSaid = false }
        } else if (adv.has("say") && !advSaid) {
            advSaid = true; jamWatch = true; jamBrakes = 0; jamMinKmh = 999.0
            summary.alert("jam")
            study?.onSpoken(null, adv, adv.getString("say"))
            speak(adv.getString("say"), now); return
        }
        val rain = ahead?.optJSONObject("rain")
        if (rain != null && rain.has("say") && rainSaid.add(rain.optString("id"))) {
            speak(rain.getString("say") + windowNote(null), now); return
        }
        val r = v.optJSONObject("group")?.optJSONObject("rest")
        if (r != null && !r.optBoolean("none")) {
            val key = "${r.optString("name")}|${r.optString("by")}"
            if (key != restKey) {
                restKey = key
                if (!r.optBoolean("mine") && !r.optBoolean("answered") && !s.dialog.awaiting) askRest(r)
            }
        } else if (r != null && r.optBoolean("none") && restKey != "none") { restKey = "none"; speak(r.optString("say"), now); return }
        // 위험성은 마감이 없어 맨 뒤(다른 말이 없을 때만)
        riskLine(v.optJSONObject("risk"), now)?.let { speak(it, now); return }
    }

    /** 말하고 예산 시계를 맞춘다. 경고음·지난 알림음을 낸 지 SPEAK_AFTER_BEEP_MS 가 안 됐으면 그만큼 미뤄 말한다(알림음 → 안내 문장, 순정 내비 순서) */
    /** 비·침수·연기 안내 뒤에 붙이는 말: 1·2열 창문 중 하나라도 열려 있으면 "창문이 열려 있습니다. 창문을 닫아 주세요." (h = null 이면 비 예보).
     *  앱이 창문을 닫지는 않는다(CarSignals.windowPos 참고) */
    private fun windowNote(h: JSONObject?): String {
        val env = h == null || h.optString("type") in WINDOW_TYPES || h.optBoolean("flood")
        return if (env && car.openWindows.isNotEmpty()) " " + t[R.string.say_close_windows] else ""
    }

    private fun speak(text: String, now: Long, key: String? = null, fresh: (() -> String?)? = null, onDropped: (() -> Unit)? = null) {
        val wait = maxOf(beepAt, passAt) + SPEAK_AFTER_BEEP_MS - System.currentTimeMillis()
        if (wait > 0) s.main.postDelayed({ s.say(text, key, fresh, onDropped) }, wait) else s.say(text, key, fresh, onDropped)
        lastSayAt = now
    }

    /** 긴급 위험 문장(긴급차량·사고 의심·급제동). 오디오 포커스를 못 얻어 기다리는 동안(깜빡이 연동 시스템 카메라·Gleo 등) 낡으면 말하기 직전에 최신 문장으로 다시 만들고
     *  (같은 위험·같은 단계가 허브 보기에 없으면 건너뜀), 끝내 못 말하고 버려지면 '말했다' 기록을 지워 다음 보기에서 다시 말한다 */
    private fun speakHazard(text: String, id: String, lvl: Int, now: Long) =
        speak(text, now, key = "hz:$id", fresh = { freshHazardSay(view, id, lvl) }, onDropped = { if (spoken[id] == lvl) spoken.remove(id) })

    /** 도로 시간(ms) → 실제 시간. 시연 경로는 거리를 DEMO_SCALE 배로 압축해 달리므로 간격도 같은 배로 줄인다 */
    private fun roadMs(ms: Long) = (ms / LinkHub.DEMO_SCALE).toLong()

    /** 허브 점수 + 운전 묶음 → (점수, 단계, 이유들). 이유는 점수 큰 순서(같으면 허브 순서 뒤에 운전 묶음) */
    fun riskNow(hub: JSONObject?): Triple<Int, Int, List<JSONObject>> {
        val items = mutableListOf<JSONObject>()
        hub?.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) items += a.getJSONObject(i) }
        for (tag in driveRisk.tags(System.currentTimeMillis())) items += JSONObject().put("tag", tag).put("pts", DriveRisk.PTS)
        val sorted = items.withIndex().sortedWith(compareBy({ -it.value.optInt("pts") }, { it.index })).map { it.value }
        val score = minOf(10, sorted.sumOf { it.optInt("pts") })
        return Triple(score, if (score >= LinkHub.RISK_HIGH) 2 else if (score >= LinkHub.RISK_CAUTION) 1 else 0, sorted)
    }

    /** 위험성은 혼자 말하는 일을 줄인다(말 줄이기 10-11):
     *  - 피로 신호는 단계와 상관없이 도로 시간 30분에 한 번 "휴식을 권장합니다"
     *  - '높음'이고 이미 들은 앞 위험(사고 의심·정체·다발 구간·낙하물) 말고 **날씨·운전 이유**(결빙·비·과속·잦은 급제동)가 있을 때만, 도로 시간 10분에 한 번
     *  - '주의'는 화면에만 */
    private fun riskLine(hub: JSONObject?, now: Long): String? {
        val (_, level, reasons) = riskNow(hub)
        val en = s.lang == "en"
        if (reasons.any { it.optString("tag") == "FATIGUE" } && (fatigueSaidAt == 0L || now - fatigueSaidAt >= roadMs(FATIGUE_GAP_MS))) {
            fatigueSaidAt = now
            return if (en) "Signs of fatigue. Consider taking a break." else "피로 신호가 보입니다. 휴식을 권장합니다."
        }
        if (level < 2) return null
        val own = reasons.filter { it.optString("tag") in RISK_OWN }
        if (own.none { it.optString("tag") in RISK_STRONG }) return null
        if (riskSaidAt != 0L && now - riskSaidAt < roadMs(RISK_GAP_MS)) return null
        riskSaidAt = now
        s.log.d("위험성 $level: " + reasons.joinToString { "${it.optString("tag")}+${it.optInt("pts")}" })
        val why = own.take(2).joinToString(", ") { riskPhrase(it, en) }
        return if (en) "High crash risk. $why. Slow down and keep your lane." else "사고 위험 높음. $why. 속도를 줄이고 차로를 유지하세요."
    }

    private fun riskPhrase(r: JSONObject, en: Boolean): String {
        val lang = if (en) "en" else "ko"
        return when (r.optString("tag")) {
            "AHEAD" -> {
                val d = LinkHub.distText(lang, r.optDouble("ahead", 0.0), s.units)   // 영어 거리 글은 지역 단위(ADR-45)
                if (r.optString("kind") == "incident") (if (en) "possible crash $d ahead" else "전방 $d 사고 의심") else (if (en) "slow traffic $d ahead" else "전방 $d 정체")
            }
            "ICE" -> if (en) "ice risk" else "결빙 위험"
            "ZONE" -> if (en) "accident-prone section" else "사고 다발 구간"
            "RAIN" -> if (en) "rain" else "비"
            "TUNNEL" -> if (en) "tunnel" else "터널"
            "NIGHT" -> if (en) "night" else "야간"
            "DEBRIS" -> if (en) "debris ahead" else "전방 낙하물"
            "SPEEDING" -> if (en) "speeding" else "과속"
            "BRAKES" -> if (en) "repeated hard braking" else "잦은 급제동"
            "FATIGUE" -> if (en) "signs of fatigue" else "피로 신호"
            else -> r.optString("tag")
        }
    }

    /** 위험 이름(화면용). 침수 의심은 TURNBACK 보고에 폭우가 겹친 경우 */
    fun hazardLabel(h: JSONObject): String = if (h.optBoolean("flood")) t[R.string.hz_flood] else when (h.optString("type")) {
        "INCIDENT" -> t[R.string.hz_incident]; "HARD_BRAKE" -> t[R.string.hz_hard_brake]; "BLOCKED" -> t[R.string.hz_blocked]
        "DEBRIS" -> t[R.string.hz_debris]; "SMOKE" -> t[R.string.hz_smoke]; "DOWNHILL" -> t[R.string.hz_downhill]
        "CONGESTION" -> t[R.string.hz_congestion]; "ICING" -> t[R.string.hz_icing]; "HEAVY_RAIN" -> t[R.string.hz_rain]
        "SIREN" -> if (h.optBoolean("beacon") || h.optBoolean("yieldOnly")) h.optString("label") else t[R.string.hz_siren]   // 구급차(위치 신호)·긴급차량 추정은 허브 이름
        else -> h.optString("label").ifEmpty { h.optString("type") }   // 새 종류(충전기 고장·대기)는 허브가 준 이름
    }

    companion object {
        const val MIN_REAL_ROUTE_M = 8000    // 실제 경로가 이보다 짧으면 시연 각본(사고 6.4 km 앞)이 들어가지 않아 시연 경로로 한다
        const val GPS_FRESH_S = 5.0          // 내 자리로 쓸 GPS 의 최대 나이
        const val GPS_ON_ROUTE_M = 150.0     // 경로에서 이 안이어야 경로에 맞춘다
        const val GPS_MOVED_M = 30.0         // 출발 뒤 GPS 가 이만큼 움직였으면 '실제로 움직이는 GPS'
        const val ARRIVE_M = 300.0           // 실제 경로 끝에서 이 안이면 도착 안내
        const val AUTO_IDLE_S = 180.0        // 자동 연결: 일행 없이 정차(P)가 이만큼 이어지면 말없이 끊는다
        const val SEEK_END_M = 300.0         // 재생 막대로 옮길 수 있는 자리는 끝 이만큼 앞까지
        const val FF_END_M = 2000.0          // 빠르게 감기는 목적지 이만큼 앞에서 끝난다(도착 장면은 실제 속도로)
        const val LINK_START = 18000.0   // 시연 출발점: 경부고속도로 하행 18km(기흥휴게소 9km 전)
        /** 시연 주행 판정: 실제 위치와 경로 위 내 자리가 이만큼 넘게 다르면 순정 내비에 목적지를 넘기지 않는다 */
        const val DEMO_MISMATCH_M = 1000.0
        const val SIREN_OK_MS = 10_000L; const val SIREN_RETRY_MS = 2_000L; const val SIREN_MAX_FAILS = 5   // 10초 못 버티고 끝난 실행 = 실패
        const val STATE_STALL_MS = 8_000L // 상태 보고 응답을 이만큼 못 받으면 막힌 것으로 보고 다시 보낸다(요청 시간 한도 3초 × 연결·읽기 + 여유)
        const val CRASH_ASK_MS = 30_000L // "괜찮으세요?" 10초 + 카운트다운 20초
        // 말 줄이기(설계 10-11). 시간은 도로 시간(시연은 DEMO_SCALE 배 압축)
        const val BUDGET_MS = 30_000L          // '조용할 때만' 등급: 30초에 한 문장
        const val RISK_GAP_MS = 600_000L       // 위험성 '높음' 단독 안내: 10분에 한 번
        const val FATIGUE_GAP_MS = 1_800_000L  // 휴식 권장: 30분에 한 번
        val URGENT = setOf("SIREN", "INCIDENT", "HARD_BRAKE")   // 바로 말함
        /** 허브 보기 [v] 에서 위험 [id] 의 지금 문장 — 같은 단계([lvl])일 때만(없어졌거나 단계가 올랐으면 null: 단계가 오르면 새 문장이 따로 온다) */
        fun freshHazardSay(v: JSONObject?, id: String, lvl: Int): String? {
            val hz = v?.optJSONArray("hazards") ?: return null
            for (i in 0 until hz.length()) {
                val h = hz.getJSONObject(i)
                if (h.optString("id") != id) continue
                return if (h.has("say") && h.optInt("level", 1) == lvl) h.getString("say") else null
            }
            return null
        }
        val WINDOW_TYPES = setOf("HEAVY_RAIN", "SMOKE")         // 창문이 열려 있으면 닫으라고 덧붙이는 위험(침수 의심 = BLOCKED + flood 도)
        /** 종류별 행동 거리(m): 이보다 멀면 화면에만, 가까워지면 그때 말한다 */
        val SAY_RANGE = mapOf("HARD_BRAKE" to 3000.0, "INCIDENT" to 6000.0, "CONGESTION" to 3000.0, "DEBRIS" to 2000.0, "BLOCKED" to 5000.0,
            "SMOKE" to 2000.0, "DOWNHILL" to 3000.0, "ICING" to 3000.0, "HEAVY_RAIN" to 3000.0, "CHARGER_FAULT" to 10000.0, "CHARGER_WAIT" to 10000.0)
        const val SAY_RANGE_DEFAULT = 3000.0
        val RISK_OWN = setOf("ICE", "RAIN", "SPEEDING", "BRAKES", "NIGHT", "TUNNEL")   // 위험성 문장에 넣는 이유(앞 위험은 이미 들었다)
        val RISK_STRONG = setOf("ICE", "RAIN", "SPEEDING", "BRAKES")                   // 이 중 하나는 있어야 위험성을 따로 말한다
        val OFFICIAL_TYPES = setOf("ACCIDENT", "ROAD_DAMAGE", "DISASTER")   // 허브에 올리는 순정 내비 돌발 종류(REPAIRING·EVENT·ETC 는 아니다)
        const val OFFICIAL_EVERY_MS = 60_000L
        const val CHARGE_FAIL_SEC = 90   // 꽂은 뒤 이만큼 충전이 시작되지 않으면 충전기 고장 의심
        const val CHARGE_WAIT_SEC = 180  // 정차 뒤 이만큼 지나서 꽂으면 충전 대기로 본다
        const val CHARGE_ASK_MS = 60_000L   // "충전기 고장인가요?" 답을 기다리는 시간. 지나면 차 신호만 보고(전과 같다)
        const val CHARGE_MOVE_M = 5.0       // 실패 뒤 이만큼 옮겨 충전되면 '다른 기기'(주차 두 칸 폭 2.5 m × 2 — 같은 자리 2.5 m 와 사이에 위치 흔들림 여유)
        const val CHARGE_REST_M = 500.0     // 실패 자리에서 이보다 멀어지면 휴게소를 떠난 것(허브 CHARGER_NEAR 와 같다)
        const val CHARGE_KEEP_MS = 1_800_000L   // 실패 자리를 이만큼(30분) 기억한다
        /** 접근 경고음 단계 문턱: 꼬리까지 남은 시간(초) 20 → 삐, 12 → 삐삐, 6 → 삐삐삐(반복). 꼬리보다 20 km/h 안으로 줄였으면 울리지 않는다 */
        val BEEP_TTC = doubleArrayOf(20.0, 12.0, 6.0)
        const val BEEP_SLOW_KMH = 20.0
        const val BEEP_CARRY_MS = 5_000L        // 앞 꼬리의 2·3단계가 이만큼 안에 있었으면 다음 꼬리는 2단계부터
        const val SPEAK_AFTER_BEEP_MS = 600L    // 경고음 뒤 안내 음성까지 띄우는 시간
        // 길 터 주기(YIELD): 시속 30 이상에서 2초 사이 3 m/s 넘게 감속 + 오른쪽(깜빡이 또는 핸들 10°↑). 60초에 한 번. 실차 문턱 미검증
        const val YIELD_FROM_MS = 8.3f
        const val YIELD_DROP_MS = 3f
        const val YIELD_STEER_DEG = 10f
        const val YIELD_GAP_MS = 60_000L
        const val HARD_BRAKE_FROM_MS = 5f      // 급제동: 이 속도(18 km/h) 이상에서
        const val HARD_BRAKE_DROP_MS = 3f      //   1초 사이 이만큼 감속
        const val HARD_BRAKE_REBOUND_MS = 1f   //   다음 1초에 이만큼 넘게 다시 빨라지면 순간 출렁임
        const val HARD_BRAKE_GAP_MS = 10_000L
        const val SWERVE_FROM_MS = 11f         // 급조향: 40 km/h 넘게
        const val SWERVE_GAP_MS = 15_000L
        const val BRAKE_HOLD_FROM_MS = 8f      // 긴 내리막: 30 km/h 넘게
        const val BRAKE_HOLD_MS = 20_000L
        const val TURNBACK_MS = 3_000L         // 도로 위 후진 기어 이만큼이면 되돌아감
        const val AIRBAG_WATCH_MS = 30_000L    // 충돌 뒤 에어백을 읽는 시간
        const val SIREN_REPORT_MS = 5_000L     // 마이크가 듣는 동안 허브 보고 간격

        /** 급제동 판정(직전 두 속도와 지금 속도, m/s). 쿨다운은 호출하는 쪽 */
        fun isHardBrake(prev2: Float, prev: Float, now: Float): Boolean =
            prev2 >= HARD_BRAKE_FROM_MS && prev2 - prev >= HARD_BRAKE_DROP_MS && now <= prev + HARD_BRAKE_REBOUND_MS
        /** 길 비킴 판정(2초 전 속도, 지금 속도, 오른쪽 깜빡이, 핸들 각 — 오른쪽 양수) */
        fun isYield(prev2: Float, now: Float, rightSignal: Boolean, steer: Float?): Boolean =
            prev2 >= YIELD_FROM_MS && prev2 - now >= YIELD_DROP_MS && (rightSignal || (steer != null && steer >= YIELD_STEER_DEG))
        /** 급조향 판정(1초 전·지금 핸들 각, 속도, 브레이크) — 각 차이는 DriveRisk.SWERVE_DEG(60°)와 같은 문턱 */
        fun isSwerve(prevSteer: Float?, steer: Float?, speed: Float, brake: Boolean?): Boolean =
            steer != null && prevSteer != null && speed > SWERVE_FROM_MS && abs(steer - prevSteer) >= DriveRisk.SWERVE_DEG && brake != true
    }
}
