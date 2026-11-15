package ai.meeos.tago.navi

import ai.meeos.tago.R
import ai.meeos.tago.app.AppLog
import ai.meeos.tago.app.InitRetry
import ai.meeos.tago.app.Region
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TrafficEventInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import org.json.JSONObject

/**
 * 순정 내비(NaviHelper) 연동. 경로 탐색 중에 다음 요청을 겹쳐 보내면 내비가 '경로 탐색 오류'를 띄워서 한 단계씩 간다:
 * 목적지 경로 시작(onRouteStarted) → 경유지(매장) 추가 → 재탐색 → (어린이 픽업이면) 스쿨존 경로 옵션.
 * onRoutingCompleted는 onRouteEnded를 대체한 '안내 종료' 콜백이라(SDK 릴리스 노트) 단계 신호로 쓰지 않는다.
 */
class NaviBridge(private val context: Context, private val main: Handler, private val log: AppLog, private val regionProvider: () -> Region) {
    private var navi: NaviHelper? = null
    private var pendingStop: JSONObject? = null
    private var wantSchoolZone = false
    /** 순정 내비가 경로 안내 중인지(onRouteStarted → true, 안내 종료·오류 → false). 우회 안내가 경유지/목적지를 고른다 */
    private var routeActive = false
    /** 지금 순정 안내가 이 앱의 주행 연결이 넘긴 목적지(휴게소·우회 새 목적지)인가 — [끝내기] 때 이것만 끈다. 사용자가 잡은 목적지의 재탐색·픽업 안내는 아니다 */
    @Volatile private var roadRoute = false
    /** 지금 순정 안내가 픽업이 넘긴 만날 곳인가 — 탑승하면 이것만 끈다 */
    @Volatile private var pickupRoute = false
    /** 해외 지역 내비 요청: 시험(navi_all) 중이거나 아직 실패한 적이 없을 때만(에뮬레이터 내비 위치가 국내 고정) */
    private var overseasFailed = false
    /** 순정 내비가 마지막으로 알린 현재 도로 제한속도(km/h). null = 아직 안 옴·모름. 메인 스레드에서만 바뀐다 */
    var speedLimitKmh: Int? = null
        private set
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var allowOverseas: Boolean
        get() = prefs.getBoolean("naviAll", false)
        set(v) { prefs.edit().putBoolean("naviAll", v).apply(); overseasFailed = false; log.d("naviAll=$v") }
    /** 순정 내비 돌발 정보(경로 안내 중 RouteStateInfo.trafficStatus.trafficEventList) — 도로 링크가 허브에 올린다(설계 10판 §8) */
    var onTrafficEvents: ((List<TrafficEventInfo>) -> Unit)? = null

    private var initRetry: InitRetry? = null
    /** 순정 내비가 붙었나(초기화 콜백 성공) — 아니면 경로 요청은 실패로 처리한다(V-05) */
    val ready: Boolean get() = initRetry?.ready == true && !simOff
    /** 시연 훅 navi_off: 내비가 없는 것처럼(요청이 실패하는 경로 시험, V-05) */
    @Volatile var simOff = false
    /** 시연 훅 init_fail: 이 시각(elapsedRealtime ms)까지는 초기화가 실패한 것처럼 군다 */
    @Volatile var simInitFailUntil = 0L
    /** 요청을 보내지 못했을 때(내비 없음·준비 안 됨·예외) — 앱이 운전자에게 알린다(Services.naviFailed). 메인 스레드 */
    var onRequestFailed: () -> Unit = {}
    private val gate = RequestGate()

    /** 순정 내비 연결: 실패하면 5초마다 다시 시도한다(시동 직후 내비 서비스가 늦게 뜨는 경우, V-04) */
    fun init() {
        initRetry = InitRetry(schedule = { d, f -> main.postDelayed(f, d) }) { r -> connect(r) }.also { it.start() }
    }

    private fun connect(r: InitRetry) {
        if (released) return
        var n: NaviHelper? = null
        try {
            if (android.os.SystemClock.elapsedRealtime() < simInitFailUntil) throw IllegalStateException("시연: 서비스 연결 실패")
            n = NaviHelper(context)
            val helper = n
            helper.initialize({ main.post {
                if (released) { runCatching { helper.release() }; return@post }
                navi = helper; log.d("NaviHelper 준비"); runCatching { helper.addListener(listener) }; r.succeeded()
            } }, { e -> main.post { log.d("NaviHelper 초기화 실패(${r.attempts}번째 시도, 5초 뒤 재시도): $e"); runCatching { helper.release() }; r.failed() } })
        } catch (e: Throwable) {
            runCatching { n?.release() }
            log.d("NaviHelper 사용 불가(${r.attempts}번째 시도, 5초 뒤 재시도): ${e.message}")
            r.failed()
        }
    }

    /** 요청 전 공통 검사: 연타(2초 안)는 무시, 내비가 없으면 실패를 알린다. 보낼 수 있으면 NaviHelper */
    private fun gateRequest(what: String): Pair<NaviResult, NaviHelper?> {
        if (!gate.tryEnter(android.os.SystemClock.elapsedRealtime())) { log.d("내비 요청 무시(2초 안 되풀이): $what"); return NaviResult.IGNORED to null }
        val n = navi
        if (n == null || !ready) { log.d("내비 요청 실패(내비 준비 안 됨): $what"); main.post { onRequestFailed() }; return NaviResult.FAILED to null }
        return NaviResult.SENT to n
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            main.post {
                log.d("내비 경로 시작")
                routeActive = true
                val stop = pendingStop
                if (stop != null) {
                    pendingStop = null
                    val c = stop.getJSONArray("curb")
                    runCatching {
                        navi?.addWaypoint(RequestWaypointInfo(latitude = c.getDouble(0), longitude = c.getDouble(1), waypointIndex = WaypointIndex.FIRST,
                            poiId = "", poiName = context.getString(R.string.navi_poi_pickup, stop.getString("name")), poiSubId = "0", address = ""))
                    }.onSuccess { log.d("내비 경유지 추가"); log.demo("내비 경유지: ${stop.getString("name")}") }.onFailure { log.d("경유지 추가 실패: ${it.message}") }
                    // 경유지 재탐색 뒤 onRouteStarted가 다시 오지 않을 수 있어(에뮬 실측) 잠시 뒤 스쿨존 단계로 넘어간다
                    if (wantSchoolZone) main.postDelayed({ if (!released) applySchoolZone() }, SCHOOL_ZONE_DELAY_MS)
                } else applySchoolZone()
            }
        }

        override fun onRoutingCompleted() { main.post { routeActive = false; roadRoute = false; pickupRoute = false; log.d("내비 안내 종료") } }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val evs = routeStateInfo.trafficStatus?.trafficEventList ?: return
            main.post { onTrafficEvents?.invoke(evs) }
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            // 현재 도로 제한속도(km/h). 0 이하 = 모름. 도로 링크가 1 s 상태에 실어 허브가 정체 꼬리 권고 속도(80)를 이 값으로 상한한다(ADR-44).
            // 에뮬레이터 내비는 위치가 고정이라 실제로 오는지는 미확인(실차 검증 전) — 오면 로그에 남긴다
            main.post { val v = speedLimit.takeIf { it > 0 }; if (v != speedLimitKmh) { speedLimitKmh = v; log.d("내비 제한속도: ${v ?: "모름"}") } }
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            // 위험성(설계 10판 ④) 입력 후보: 미끄럼·노면 파손 주의 지점. 지금은 기록만
            val risky = cautionPointList.filter { it.type == CautionPointType.SLIPPERY_ROAD_ALERT || it.type == CautionPointType.ROAD_DAMAGE_ALERT }
            if (risky.isNotEmpty()) main.post { log.d("내비 주의 지점: " + risky.joinToString { "${it.type} ${it.remainDistance}m" }) }
        }
        override fun onError(errorCode: NaviErrorCode) {
            main.post {
                log.d("내비 오류: $errorCode")
                routeActive = false
                pendingStop = null; wantSchoolZone = false
                if (regionProvider() != Region.DAECHI) overseasFailed = true   // 이 내비에 해외 지도가 없으면 다음부터는 요청하지 않는다
            }
        }
    }

    private fun applySchoolZone() {
        if (!wantSchoolZone) return
        wantSchoolZone = false
        runCatching { navi?.changeRouteOption(RouteOption.SCHOOL_ZONE) }.onSuccess { log.d("내비 스쿨존 경로 옵션") }.onFailure { log.d("스쿨존 옵션 실패: ${it.message}") }
    }

    private fun allowed(what: String): Boolean {
        if (regionProvider() == Region.DAECHI) return true
        if (allowOverseas && !overseasFailed) return true
        log.d("내비 안내 생략(에뮬레이터 내비 위치가 국내 고정): $what"); return false
    }

    /** 목적지 한 곳으로 안내. 돌려주는 값: 요청을 보냈는지 */
    fun routeTo(lat: Double, lon: Double, poiName: String, what: String): Boolean {
        if (!allowed(what)) return false
        val (res, n) = gateRequest(what)
        if (res != NaviResult.SENT || n == null) return false
        pendingStop = null; wantSchoolZone = false; roadRoute = false
        return runCatching { n.requestRoute(info(lat, lon, poiName)); pickupRoute = true }.onFailure { log.d("내비 안내 실패($what): ${it.message}"); main.post { onRequestFailed() } }.isSuccess
    }

    /** 만날 곳을 목적지로, 매장(있으면)을 첫 경유지로 (경유지 최대 2개 — NaviHelper 문서). 어린이 픽업이면 스쿨존 경로 */
    fun routeVia(stop: JSONObject?, lat: Double, lon: Double, poiName: String, schoolZone: Boolean): Boolean {
        if (!allowed("경유 ${stop?.optString("name")}")) return false
        val (res, n) = gateRequest("경유")
        if (res != NaviResult.SENT || n == null) return false
        wantSchoolZone = schoolZone; roadRoute = false; pickupRoute = true
        pendingStop = stop                         // 경유지는 목적지 경로가 잡힌 뒤(onRouteStarted)에 넣는다
        val ok = runCatching {
            n.requestRoute(info(lat, lon, poiName))
            log.d("내비: 목적지 안내${if (stop != null) " · 경유 1곳" else ""}"); log.demo("내비: 목적지 $poiName${if (stop != null) " · 경유 ${stop.getString("name")}" else ""}${if (schoolZone) " · 스쿨존 경로" else ""}")
        }.onFailure { log.d("내비 경유 안내 실패: ${it.message}"); pendingStop = null; wantSchoolZone = false; main.post { onRequestFailed() } }.isSuccess
        return ok
    }

    /** 휴게소처럼 국내 경로가 확실한 목적지: 지역 설정과 무관하게 요청 */
    fun routeDomestic(lat: Double, lon: Double, poiName: String) {
        val (res, n) = gateRequest("국내 목적지")
        if (res != NaviResult.SENT || n == null) return
        runCatching { n.requestRoute(info(lat, lon, poiName)); roadRoute = true; log.d("내비: 목적지 안내"); log.demo("내비: 목적지 $poiName") }
            .onFailure { log.d("내비 안내 실패: ${it.message}"); main.post { onRequestFailed() } }
    }

    /** 최소 우회 안내(FR-G-36, 11-08 통합 기준): 운전자가 [우회 안내]를 눌렀을 때만. 경로 안내 중이면 순정 내비 재탐색(requestReRoute) —
     *  어느 나들목으로 나갈지는 내비가 정한다. 안내 중이 아니면(목적지 없음) 다음 나들목(lat, lon)을 목적지로. 자동 경로 변경은 하지 않는다 */
    fun detour(lat: Double, lon: Double, name: String) {
        val (res, n) = gateRequest("우회")
        if (res != NaviResult.SENT || n == null) return
        runCatching {
            if (routeActive) n.requestReRoute() else { n.requestRoute(info(lat, lon, name)); roadRoute = true }
            log.d("내비: 우회 ${if (routeActive) "재탐색" else "다음 나들목 목적지"}"); log.demo("내비: 우회 ${if (routeActive) "재탐색" else "다음 나들목"}")
        }.onFailure { log.d("우회 안내 요청 실패: ${it.message}"); main.post { onRequestFailed() } }
    }

    fun clearWaypoints() { runCatching { navi?.removeWaypoint(WaypointIndex.ALL) } }

    /** 시연 빌드 훅 navi_req=cancel 전용: 누가 걸었든 지금 순정 안내를 끈다(NaviHelper 3.0.5 cancelRoute) — 시연 녹화 전에 에뮬레이터에 남은 안내 정리. 제품 동작에서는 부르지 않는다 */
    fun cancelAny() {
        roadRoute = false
        val n = navi
        if (n == null || !ready) { log.d("내비 안내 끄기 못 함: 내비 연결 준비 전(앱을 켜고 몇 초 뒤 다시)"); return }   // 준비 전에는 아무것도 안 하면서 '끔'이라 적던 것(11-14)
        runCatching { n.cancelRoute() }.onSuccess { log.d("내비: 안내 끔(시연 훅)") }.onFailure { log.d("내비 안내 끄기 실패: ${it.message}") }
    }

    /** 픽업 탑승: 픽업이 넘긴 만날 곳 안내만 끈다(NaviHelper 3.0.5 cancelRoute) */
    fun cancelPickupRoute() {
        if (!pickupRoute) return
        pickupRoute = false
        cancelKeepingApp("내비: 만날 곳 안내 끔")
    }

    /** 주행 연결 [끝내기]: 그 연결이 넘긴 순정 안내(휴게소·우회 새 목적지)만 끈다(NaviHelper 3.0.5 cancelRoute). 그런 안내가 없으면 아무것도 하지 않는다 */
    fun cancelRoadRoute() {
        if (!roadRoute) return
        roadRoute = false
        cancelKeepingApp("내비: 연결이 넘긴 안내 끔")
    }

    /** 이 앱 화면이 지금 앞에 있는가 / 이 앱 화면을 다시 앞으로 — MainActivity 가 채운다 */
    var appInFront: () -> Boolean = { false }
    var bringAppBack: () -> Unit = {}

    /** 순정 내비는 cancelRoute 를 받으면 자기 화면을 앞으로 띄운다(11-14 에뮬레이터 실측: 28 ms 뒤 내비 MainActivity START — 탑승 순간 앱 화면이 가려졌다).
     *  끄기 전에 이 앱이 앞에 있었으면 잠깐 뒤 이 앱을 다시 앞으로. 사용자가 내비 화면을 보고 있었으면 건드리지 않는다 */
    private fun cancelKeepingApp(done: String) {
        val n = navi ?: return
        val wasFront = appInFront()
        runCatching { n.cancelRoute() }
            .onSuccess { log.d(done); if (wasFront) main.postDelayed({ bringAppBack() }, REFRONT_DELAY_MS) }
            .onFailure { log.d("내비 안내 끄기 실패: ${it.message}") }
    }

    private fun info(lat: Double, lon: Double, poiName: String) =
        RouteInfo(latitude = lat, longitude = lon, displayLongitude = null, displayLatitude = null, poiName = poiName, poiId = "", poiSubId = "0", address = "")

    @Volatile private var released = false   // 해제 뒤 미뤄 둔 작업(스쿨존 옵션)이 돌지 않게

    fun release() {
        released = true
        initRetry?.cancel()
        runCatching { navi?.removeListener(listener) }
        runCatching { navi?.release() }
    }

    companion object {
        const val PREFS = "tago"
        private const val SCHOOL_ZONE_DELAY_MS = 5000L   // 경로가 잡힌 뒤 스쿨존 옵션을 거는 시간
        private const val REFRONT_DELAY_MS = 250L        // 안내를 끈 뒤 내비가 자기 화면을 띄우는 요청(실측 16~60 ms)이 먼저 처리되도록 기다렸다 앱을 다시 앞으로
    }
}
