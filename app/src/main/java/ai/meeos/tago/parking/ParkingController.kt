package ai.meeos.tago.parking

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.app.Edition
import ai.meeos.tago.app.Services
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * 주차 위치 기억(새 기능 H): 입구 판단 → 나선 경사로 회전 적분 → 기어 P 에서 기록 → (선택) 기둥 사진 글자로 층·기둥 확정.
 * 판단은 ParkingTracker(순수), 사진 글자 해석은 PillarParser, 글자 인식은 PillarOcr(ML Kit, 기기 안). 이 클래스는 신호를 잇고 화면 문장을 만든다.
 *
 * 개인정보(83 원칙): 입구 좌표는 메모리에만 있고 우리 허브로 보내지 않는다(방법 C 키가 있으면 외부 API — TMAP 역지오코딩·건축HUB — 로는 나간다).
 * 층·기둥 글은 차 안 전용 저장소에 마지막 한 건(ParkStore, 좌표 없음, 사흘)과, 가족 그룹에 연결된 동안 그룹 화면(허브 park, 좌표 없음)에 간다. 사진은 허브가 5분만 메모리에 두었다 차가 한 번 가져가면 지우고,
 * 차는 글자만 뽑은 뒤 사진을 버린다. 기록은 정차 중에만 화면에 보인다(주행 중에는 나오지 않는다).
 */
class ParkingController(private val context: Context, private val s: Services, private val group: () -> Pair<String, String>?) {
    /** Edition.roadLink 가 켜진 판에서만 켠다 — 그렇지 않은 판에는 이 기능이 없다 */
    val enabled: Boolean = Edition.roadLink
    private val tracker = ParkingTracker(NoFloorRange)   // 방법 C(건물 층수 공공데이터)는 틀만: 키가 없어 NoFloorRange. 키가 생기면 FloorRangeProvider 를 갈아 끼운다
    private val gps = ParkingGps(context)
    /** 차 관성 센서(있으면 쓰고 없으면 null — 실차에서 읽히는지는 미확인) */
    private val imu = ParkingImu(context)
    private var imuLogged = false
    private var ocr: PillarOcr? = null
    private var simOn = false
    private var pendingOcr: PillarInfo? = null
    private var polling = false
    private var pollAt = 0L
    private var diagN = 0
    private var lastPhase = ParkingTracker.Phase.OUTSIDE
    // 방법 C(건물 층수): 건물 단위 캐시 + 빈도 제한(30 s 에 한 번, 앱 실행 한 번에 20번까지). 값은 메모리에만
    private val bldgByParcel = HashMap<String, FloorRange?>()
    private val parcelByCoord = HashMap<String, String>()
    private var bldgAt = 0L
    private var bldgCalls = 0
    private var bldgBusy = false
    private val bldgKey: String get() = if (enabled) BuildConfig.BLDG_API_KEY else ""
    var editing = false
        private set
    // 마지막 기록 보관(앱 전용 저장소, 글자만 — 입구 좌표는 넣지 않는다). 기록이 바뀔 때마다 덮어쓰고, 없어지면 지운다. ParkStore.MAX_AGE_MS 지나면 버린다
    private val store = context.getSharedPreferences(ParkStore.PREFS, Context.MODE_PRIVATE)
    private var saved: ParkRecord? = null
    private var recordWallMs: Long? = null   // 이 기록을 만든 벽시계(보관·"N시간 전")
    private var restored = false

    private val t get() = s.texts
    val record: ParkRecord? get() = if (enabled) tracker.record else null

    fun start() {
        if (!enabled) return
        s.car.readParkSignals = true
        runCatching { imu.start() }
        s.log.d("주차 위치 기억: 관성 센서 ${imu.describe()} (값이 실제로 변해야 사용)")
        ParkStore.decodeMount(store.getString(ParkStore.MOUNT_KEY, null), imu.describe())?.let { (off, sec) ->   // 지난 주행에서 배운 장착 기울기를 이어 쓴다
            tracker.incline.restoreMount(off, sec); s.log.d("주차 위치 기억: 장착 기울기 이어 씀(배운 시간 ${(sec / 60).toInt()}분)")
        }
        s.log.d("주차 위치 기억: 건물 정보(방법 C) 키 ${if (bldgKey.isNotBlank() && BuildConfig.TMAP_KEY.isNotBlank()) "있음" else "없음 — 건너뜀"}")   // 키 값은 남기지 않는다
        val now = System.currentTimeMillis()
        ParkStore.decode(store.getString(ParkStore.KEY, null), now)?.let { (r, wall) ->
            tracker.restore(r); saved = tracker.record; recordWallMs = wall; restored = true
            s.log.d("주차 위치 기억: 보관한 기록을 되살림(${floorLabel(r.floor, true) ?: "층 모름"}, ${(now - wall) / 60_000}분 전)")
        } ?: store.edit().remove(ParkStore.KEY).apply()   // 없거나 오래됐거나 깨졌으면 지운다
    }

    /** 기록이 바뀌었으면 보관을 맞춘다(1초마다 tick 끝에서) */
    private fun syncStore() {
        val r = tracker.record
        if (r == saved) return
        saved = r
        if (r == null) { store.edit().remove(ParkStore.KEY).apply(); recordWallMs = null; restored = false; return }
        val wall = recordWallMs ?: System.currentTimeMillis().also { recordWallMs = it }
        store.edit().putString(ParkStore.KEY, ParkStore.encode(r, wall)).apply()
    }

    /** 배운 장착 기울기를 보관(건물에 들어갈 때·앱을 멈출 때). 관성 센서가 살아 있어 실제로 배운 적이 있을 때만 */
    private fun saveMount() {
        val inc = tracker.incline
        if (inc.calibratedS <= 0) return
        runCatching { store.edit().putString(ParkStore.MOUNT_KEY, ParkStore.encodeMount(inc.offsetDeg, inc.calibratedS, imu.describe())).apply() }
    }

    fun stop() { saveMount(); runCatching { imu.stop() }; s.car.readParkSignals = false; runCatching { ocr?.close() }; ocr = null }

    /** 1초마다(AppController.tick). 시연 대본 중에는 실제 신호를 읽지 않는다 */
    fun tick() {
        if (!enabled) return
        val car = s.car
        val now = SystemClock.elapsedRealtime()
        if (!simOn) {
            val parked = !car.locked && car.gear?.name == "GEAR_PARK"
            val fix = gps.fix()
            val sample = runCatching { imu.sample() }.getOrNull()
            if (sample != null && !imuLogged) { imuLogged = true; s.log.d("주차 위치 기억: 관성 센서 값이 변함 — 높이·yaw 를 함께 쓴다") }
            tracker.tick(now, fix, car.speedMs.toDouble(), car.steerAngle?.toDouble(), parked, car.reversing, car.lowBeamOn, sample)?.let { onRecorded(it) }
            onPhase()
            if (tracker.phase == ParkingTracker.Phase.OUTSIDE && ++diagN % 10 == 0 && fix != null)
                s.log.d("주차 GPS(건물 밖): 나이 %.0fs 정확도 %s m".format(fix.ageS, fix.accuracyM?.let { "%.0f".format(it) } ?: "?"))   // 좌표는 기록하지 않는다
            if (tracker.phase == ParkingTracker.Phase.INSIDE && ++diagN % 5 == 0)
                s.log.d("주차 추적: ${tracker.debugLine()} · 속도 %.1f m/s 조향 %s° · GPS %s".format(car.speedMs, car.steerAngle?.let { "%.0f".format(it) } ?: "?", if (fix == null) "없음" else "나이 %.0fs".format(fix.ageS)))
        }
        pollPhoto(now)      // 사진 우편함은 시연 대본 중에도 본다(사진 경로 시험)
        syncStore()
    }

    /** 건물 안으로 들어간 순간 한 번: 입구 좌표로 건물 층수(방법 C)를 물으러 간다. 나오면 출차 역산 결과를 남긴다 */
    private fun onPhase() {
        val p = tracker.phase
        if (lastPhase == ParkingTracker.Phase.OUTSIDE && p == ParkingTracker.Phase.INSIDE) { saveMount(); tracker.entrance?.let { (la, lo) -> lookupBuilding(la, lo) } }
        if (lastPhase != ParkingTracker.Phase.OUTSIDE && p == ParkingTracker.Phase.OUTSIDE) tracker.lastExit?.let {
            s.log.d("출차 역산: 기록 ${floorLabel(it.parkedLevel, true) ?: "모름"} · 나올 때 남은 층 ${it.residual} (${if (it.residual == 0) "일치" else "불일치"})")
        }
        lastPhase = p
    }

    // ---------------------------------------------------------------- 방법 C: 건물 층수(공공데이터)
    private fun lookupBuilding(lat: Double, lon: Double) {
        val key = bldgKey
        if (key.isBlank() || BuildConfig.TMAP_KEY.isBlank() || bldgBusy) return
        val coord = "%.4f,%.4f".format(java.util.Locale.US, lat, lon)
        parcelByCoord[coord]?.let { pk -> if (bldgByParcel.containsKey(pk)) { applyRange(bldgByParcel[pk], cached = true); return } }
        val now = SystemClock.elapsedRealtime()
        if (bldgCalls >= 20 || (bldgAt != 0L && now - bldgAt < 30_000)) { s.log.d("건물 정보: 빈도 제한으로 건너뜀"); return }
        bldgAt = now; bldgCalls++; bldgBusy = true
        val tmapKey = BuildConfig.TMAP_KEY
        s.io.execute {
            val result = runCatching {
                val addr = ai.meeos.tago.net.Http.text(BuildingFloors.reverseUrl(lat, lon), headers = mapOf("appKey" to tmapKey, "Accept" to "application/json"),
                    connectMs = 5000, readMs = 5000, requireOk = true)
                val parcel = BuildingFloors.parcelFromTmap(addr) ?: return@runCatching Triple(null, null, "지번 없음")
                val cached = synchronized(bldgByParcel) { if (bldgByParcel.containsKey(parcel.key)) bldgByParcel[parcel.key] else null }
                if (cached != null) return@runCatching Triple(parcel, cached, "캐시")
                val title = ai.meeos.tago.net.Http.text(BuildingFloors.titleUrl(key, parcel), headers = mapOf("Accept" to "application/json"),
                    connectMs = 5000, readMs = 8000, requireOk = true)
                Triple(parcel, BuildingFloors.rangeFromTitle(title), "조회")
            }
            s.main.post {
                bldgBusy = false
                result.onSuccess { (parcel, range, how) ->
                    if (parcel != null) { synchronized(bldgByParcel) { bldgByParcel[parcel.key] = range }; parcelByCoord[coord] = parcel.key }
                    if (parcel == null) s.log.d("건물 정보: $how — 건너뜀") else applyRange(range, cached = how == "캐시")
                }.onFailure { s.log.d("건물 정보 실패: ${it.javaClass.simpleName} — 건너뜀") }   // 응답 원문·주소·키는 남기지 않는다
            }
        }
    }

    private fun applyRange(r: FloorRange?, cached: Boolean) {
        if (r == null) { s.log.d("건물 정보: 층수 없음 — 건너뜀"); return }
        s.log.d("건물 정보${if (cached) "(캐시)" else ""}: 지하 ${r.basement ?: "?"}층 · 지상 ${r.above ?: "?"}층")
        tracker.setRange(r)
        s.render()
    }

    private fun onRecorded(r: ParkRecord) {
        recordWallMs = System.currentTimeMillis(); restored = false   // 새 기록(되살린 것 아님)
        s.log.d("주차 기록: 층 ${floorLabel(r.floor, true) ?: "모름"}${r.alt?.let { "~" + floorLabel(it, true) } ?: ""} 신뢰 ${r.confidence}")   // 좌표는 기록하지 않는다
        pendingOcr?.let { applyOcr(it); pendingOcr = null }
        editing = false
        s.render()
        sayPark(R.string.say_park_saved)   // 주차 판이 뜨는 순간 한 번(11-14 "주차 층 기억" 음성 빈 곳)
    }

    /** 주차 위치를 한 문장으로(판 큰 글 그대로 — 실제 값만). 같은 문장은 되풀이하지 않는다 */
    private var lastParkSay = ""
    private fun sayPark(res: Int) {
        if (record?.floor == null) return   // 층을 모르면 말하지 않는다("층 모름"은 들어도 쓸모가 없다 — 11-14 시연 녹화 v2)
        val big = card()?.big ?: return
        if (big == lastParkSay) return
        lastParkSay = big
        s.say(t[res, big])
    }

    // ---------------------------------------------------------------- 사진(휴대폰 → 허브 → 차)
    private fun pollPhoto(now: Long) {
        if (polling || tracker.phase == ParkingTracker.Phase.OUTSIDE || now - pollAt < 3000) return
        val (gid, me) = group() ?: return
        pollAt = now; polling = true
        val body = JSONObject().put("t", s.epoch()).put("me", me)
        s.api.post("/api/link/$gid/parkphoto/take", body, onError = { polling = false }) { r ->
            polling = false
            val img = r.optJSONObject("photo")?.optString("img").orEmpty()
            if (img.isNotEmpty()) runCatching { readPhoto(img) }.onFailure { s.toast(t[R.string.park_ocr_fail]) }
        }
    }

    private fun readPhoto(b64: String) {
        val o = ocr ?: PillarOcr().also { ocr = it }
        o.recognize(b64) { lines ->
            if (lines == null) { s.toast(t[R.string.park_ocr_fail]); return@recognize }
            handleLines(lines)
        }
    }

    /** 글 줄 → 해석 → 기록에 반영(기록이 아직 없으면 P 를 누를 때까지 들고 있는다). 시연 훅 ocr_sim 도 여기로 온다 */
    fun handleLines(lines: List<String>) {
        val info = PillarParser.parse(lines)
        s.log.d("주차 사진 글자 해석: 층 ${floorLabel(info?.floor, true) ?: "없음"} 기둥 ${info?.pillar ?: "없음"} (줄 ${lines.size}개)")   // 글자 원문은 기록하지 않는다
        if (info == null) { s.toast(t[R.string.park_ocr_none]); return }
        if (tracker.record == null) { pendingOcr = info; s.toast(t[R.string.park_ocr_wait]); return }
        applyOcr(info)
        s.toast(t[R.string.park_ocr_ok]); s.render()
        sayPark(R.string.say_park_updated)   // 기둥 사진 글로 층·기둥이 바뀌었을 때 한 번
    }

    private fun applyOcr(i: PillarInfo) {
        val now = SystemClock.elapsedRealtime()
        if (i.floor != null) tracker.setFloor(i.floor, "ocr", now)
        if (i.pillar != null) tracker.setPillar(i.pillar)
    }

    // ---------------------------------------------------------------- 운전자 입력
    fun toggleEdit() { if (tracker.record != null) { editing = !editing; s.render() } }

    /** [한 층 위/아래]: 지금 층을 한 칸 옮긴다(지하 B3 에서 위 = B2, B1 에서 위 = 1층). 층 모름이면 위 = 1층, 아래 = B1 에서 시작 */
    fun floorDelta(up: Boolean) {
        val r = tracker.record ?: return
        tracker.setFloor(stepFloor(r.floor, up), "user", SystemClock.elapsedRealtime())
        s.render()
    }

    fun clear() { tracker.clear(); editing = false; pendingOcr = null; simOn = false; s.render() }

    // ---------------------------------------------------------------- 시연 훅(adb 전용 수신기)
    /** parking_sim: spiral:N · straight · flat · off */
    fun simulate(spec: String) {
        if (!enabled) return
        if (spec.trim().lowercase() == "off") { simOn = false; tracker.reset(); editing = false; s.render(); return }
        val at = spec.substringAfter('@', "").split(",").mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 }
        val steps = ParkingSim.steps(spec.substringBefore('@')) ?: run { s.log.d("parking_sim 형식: spiral:N | straight | flat | off [@위도,경도]"); return }
        tracker.reset(); pendingOcr = null; editing = false; simOn = true
        var ms = SystemClock.elapsedRealtime()
        var rec: ParkRecord? = null
        for (st in steps) repeat(st.sec) {
            ms += 1000
            val fix = if (st.fix) GpsFix(at?.get(0) ?: ParkingSim.LAT, at?.get(1) ?: ParkingSim.LON, 8.0, 1.0) else null
            tracker.tick(ms, fix, st.speed, st.steer, st.parked, false)?.let { rec = it }
        }
        s.log.d("주차 시연: ${spec.trim()} → 층 ${floorLabel(rec?.floor, true) ?: "모름"} 신뢰 ${rec?.confidence}")
        rec?.let { onRecorded(it) }
        tracker.entrance?.let { (la, lo) -> lookupBuilding(la, lo) }   // 방법 C 도 실제 경로와 같이(키가 있을 때만)
    }

    // ---------------------------------------------------------------- 화면 문장
    /** 지도 우선 화면(A4) 주차 판: 큰 글("B2 · D-14", 범위면 "B2~B3"), 근거 한 줄(실제 값만), 사진으로 받았는지. 기록이 없으면 null */
    fun card(): ai.meeos.tago.ui.ParkCardUi? {
        val r = record ?: return null
        val ko = s.lang == "ko"
        val alt = r.alt
        val ranged = r.floor != null && alt != null && r.floorSource == "est"
        val floor = if (ranged) {
            val (a, b) = listOf(r.floor!!, alt!!).let { l -> if (l.all { it < 0 }) l.sortedDescending() else l.sorted() }
            t[R.string.park_range, floorLabel(a, ko) ?: "", floorLabel(b, ko) ?: ""]
        } else floorLabel(r.floor, ko) ?: t[R.string.park_floor_unknown]
        val big = floor + (r.pillar?.let { " · $it" } ?: "")
        val ev = buildList {
            // 출처: 사진·고침·차 추정(높이 센서를 썼는지까지 — 빠진 근거도 보이게, 채점 2바퀴)
            add(t[when (r.floorSource) { "ocr" -> R.string.park_ev_photo; "user" -> R.string.park_ev_user
                else -> if (r.heightUsed) R.string.park_ev_est_height else R.string.park_ev_est_turn }])
            if (r.rampRuns > 0) add(t[R.string.park_ev_ramps, r.rampRuns])
            r.bldgBasement?.takeIf { it > 0 }?.let { add(t[R.string.park_ev_bldg, it]) }
            if (r.bldgBasement == null && r.floorSource == "est") add(t[R.string.park_ev_bldg_none])   // 키 없음·조회 실패·층수 없음 모두
            if (r.bldgConflict && r.floorSource == "est") add(t[R.string.park_ev_bldg_conflict])   // 추정이 대장보다 깊다 — 자르지 않고 범위로 보인다
            if (ranged || (r.bldgConflict && r.floorSource == "est")) add(t[R.string.park_check_photo])   // 대장과 다르면 범위가 없어도 사진 확인
            val was = r.estFloor
            if (r.floorSource != "est" && was != null && was != r.floor) add(t[R.string.park_est_was, floorLabel(was, ko) ?: ""])
            // 앱이 다시 켜져 보관에서 되살린 기록이면 언제 기록했는지(10분 넘었을 때만)
            recordWallMs?.takeIf { restored }?.let { w -> ParkStore.ageText(System.currentTimeMillis() - w)?.let { (n, hours) ->
                add(t[if (hours) R.string.park_ev_age_h else R.string.park_ev_age_m, n]) } }
        }
        return ai.meeos.tago.ui.ParkCardUi(big, ev.joinToString(" · "), photo = r.floorSource == "ocr" || r.pillar != null)   // 기둥은 사진 글자로만 들어온다
    }

    /** "주차 B3 · C-12" 한 줄. 기록이 없으면 null */
    fun line(): String? {
        val r = record ?: return null
        val ko = s.lang == "ko"
        var floor = floorLabel(r.floor, ko) ?: t[R.string.park_floor_unknown]
        val alt = r.alt
        val ranged = r.floor != null && alt != null && r.floorSource == "est"
        if (ranged) {                                   // 신뢰도가 낮으면 숫자를 단정하지 않고 범위로(지하는 얕은 층부터: B2~B3)
            val (a, b) = listOf(r.floor!!, alt!!).let { l -> if (l.all { it < 0 }) l.sortedDescending() else l.sorted() }
            floor = t[R.string.park_range, floorLabel(a, ko) ?: "", floorLabel(b, ko) ?: ""]
        }
        if (r.floor != null && r.floorSource == "est") floor = t[R.string.park_est, floor]
        var line = t[R.string.park_line, floor]
        if (ranged || (r.bldgConflict && r.floorSource == "est")) line += " · ${t[R.string.park_check_photo]}"
        if (r.pillar != null) line += " · ${r.pillar}"
        val was = r.estFloor
        if (r.floorSource != "est" && was != null && was != r.floor) line += " · ${t[R.string.park_est_was, floorLabel(was, ko) ?: ""]}"
        return line
    }
}
