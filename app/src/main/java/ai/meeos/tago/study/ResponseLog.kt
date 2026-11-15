package ai.meeos.tago.study

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.app.Services
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 경고 반응 로그(연구 기록). 길벗 경고(사고 의심·정체 꼬리·연계 공식 안내)를 받은 운전자가 실제로 어떻게 반응했는지
 * 경고 시각(t = 0)을 기준으로 −30 s ~ +119 s 를 1 Hz 로 남긴다 — SUMO 논문의 '측정하지 않은 운전자 반응'(감속·준수·반응 지연)을 잴 자료.
 *
 * - 참가자가 동의하고 공변량(연령대·운전 경력·차종)을 넣으면 세션이 시작된다. 세션 id(무작위 12자리)만 쓰고 이름·번호판·좌표·출발지는 남기지 않는다
 * - 저장: 앱 내부 저장소 files/study/<세션>.jsonl. 경고 1건 = 묶음 하나(warning → event… → sample×150 → warning_end), 150초가 다 차면 한 번에 쓴다
 * - 내보내기(공유 시트, 없으면 앱 외부 폴더 복사)와 서버 업로드(기본 끔, 주소는 빌드 속성 meeos.studyUploadUrl)
 * - 경고 동작은 바꾸지 않는다: RoadLinkController 가 말한 순간(onSpoken), AppController 가 헤드라인에 띄운 순간(onScreen)을 읽기만 한다
 * 형식: docs/driver-response-log-format.md
 */
class ResponseLog(private val context: Context, private val s: Services) {
    data class Profile(val ageBand: String, val drivingYears: Int, val carType: String, val consent: Boolean, val consentAt: Long, val source: String)

    private class Sample(val ts: Long, val el: Long, val o: JSONObject, val jamDist: Int?, val hzDist: Map<String, Int>, val adviceKmh: Int?)
    private class Ev(val ts: Long, val o: JSONObject)
    private class Bundle(val wid: Int, val key: String, val t0: Long, val header: JSONObject, val pre: List<Sample>, val preEvents: List<Ev>) {
        val post = mutableListOf<Sample>()
        val events = mutableListOf<Ev>()
        var screenAt = 0L; var voiceQueuedAt = 0L; var voiceStartAt = 0L; var voiceEndAt = 0L
        var voiceText: String? = null
        val hazardId: String? get() = if (header.isNull("hazard")) null else header.optString("hazard").takeIf { it.isNotEmpty() }   // optString 은 JSON null 을 "null" 글자로 준다
        val kind: String get() = header.getString("kind")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val writer = Executors.newSingleThreadExecutor()
    private val dir get() = File(context.filesDir, "study").also { it.mkdirs() }

    var sessionId: String? = null
        private set
    var profile: Profile? = null
        private set
    val active: Boolean get() = sessionId != null
    var uploadOn: Boolean = prefs.getBoolean("upload", false)
        private set
    val uploadUrl: String = BuildConfig.STUDY_UPLOAD_URL
    var warningsDone = 0
        private set
    val warningsOpen: Int get() = open.size
    val file: File? get() = sessionId?.let { File(dir, "$it.jsonl") }

    private val ring = ArrayDeque<Sample>()             // 지난 30초(경고 전 구간)
    private val ringEv = ArrayDeque<Ev>()
    private val open = mutableListOf<Bundle>()
    private val keysSeen = mutableMapOf<String, Long>() // 경고 키 → 처음 띄운 시각(같은 경고를 다시 세지 않는다)
    private var wid = prefs.getInt("wid", 0)
    private var prevSpeed: Float? = null; private var prevEl = 0L
    private var touches = 0
    private var appFg = true
    private var jamEpisode = 0; private var jamSeenAt = 0L
    private var speakingText: String? = null
    private var prevBrake: Boolean? = null

    // IMU: 1초 평균(장치 좌표, 헤드 유닛은 차에 고정이라 차 좌표와 일정한 회전만 다르다)
    private val sm = context.getSystemService(SensorManager::class.java)
    private var imuType: String? = null
    private val imuSum = DoubleArray(3); private var imuN = 0; private var imuMax = 0.0
    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
            s.main.post {
                imuSum[0] += x; imuSum[1] += y; imuSum[2] += z; imuN++
                imuMax = maxOf(imuMax, sqrt(x * x + y * y + z * z))
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // GPS: 차속 신호가 없을 때만 쓰는 속도(좌표는 저장하지 않는다)
    private val lm = context.getSystemService(LocationManager::class.java)
    private var gpsSpeed: Float? = null; private var gpsAt = 0L
    private val gpsListener = LocationListener { loc: Location -> if (loc.hasSpeed()) { gpsSpeed = loc.speed; gpsAt = SystemClock.elapsedRealtime() } }
    private var gpsOn = false

    // 차로 변경 후보: 깜빡이가 켜진 뒤 8초 안에 조향이 3° 이상 움직이면
    private var turnOnAt = 0L; private var turnSide: String? = null; private var turnSteer0: Float? = null; private var laneSaid = false

    /** 보존 기한(StudyRetention.KEEP_DAYS 일)이 지난 세션 파일을 지운다. 쓰기와 같은 스레드에서 해서 쓰는 중인 파일과 겹치지 않는다 */
    private fun purgeOld() {
        writer.execute { runCatching {
            // 내보내기가 앱 외부 폴더(Android/data/<패키지>/files/study/)에 만든 사본도 같은 기한으로 지운다(내부만 지우면 사본이 영구히 남는다)
            val now = System.currentTimeMillis()
            val n = StudyRetention.purge(dir, now, keep = file) + (context.getExternalFilesDir(null)?.let { StudyRetention.purge(File(it, "study"), now, keep = file) } ?: 0)
            if (n > 0) s.log.d("연구 기록: 보존 기한 지난 파일 ${n}개 지움")
        } }
    }

    /** 파일 첫 줄(세션 머리): 참가자 공변량·형식. 전원이 끊겨 첫 줄을 잃은 파일을 이어 쓸 때는 rewritten=true 로 다시 쓴다(V-12) */
    private fun headRecord(sid: String, p: Profile, now: Long, rewritten: Boolean): JSONObject =
        JSONObject().put("rec", "session").put("format", FORMAT).put("session", sid)
            .put("app", BuildConfig.APPLICATION_ID).put("version", BuildConfig.VERSION_NAME).put("build", BuildConfig.VERSION_CODE)
            .put("ts", now).put("iso", iso(now)).put("tz", ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).id)
            .put("participant", JSONObject().put("age_band", p.ageBand).put("driving_years", p.drivingYears).put("car_type", p.carType)
                .put("consent", p.consent).put("consent_version", CONSENT_VERSION).put("consent_ts", p.consentAt).put("consent_source", p.source))
            .put("window", JSONObject().put("pre_s", PRE_S).put("post_s", POST_S).put("hz", 1))
            .put("route", JSONObject().put("name", "gyeongbu_southbound_demo").put("distance_scale", ai.meeos.tago.engine.LinkHub.DEMO_SCALE))
            .put("sensors", JSONObject().put("imu", imuType ?: JSONObject.NULL).put("gps", gpsOn).put("turn_signal", true))
            .put("advice_policy", JSONObject().put("mode", "uniform").put("kmh", ai.meeos.tago.engine.LinkHub.ADVISE_KMH))
            .apply { if (rewritten) put("head_rewritten", true) }

    /** 앱이 다시 열리면(프로세스 재시작) 동의한 세션을 이어 쓴다 */
    fun restore() {
        purgeOld()
        val sid = prefs.getString("session", null) ?: return
        val p = Profile(prefs.getString("age", "") ?: "", prefs.getInt("years", 0), prefs.getString("car", "") ?: "", true, prefs.getLong("consentAt", 0L), prefs.getString("source", "screen") ?: "screen")
        sessionId = sid; profile = p
        warningsDone = prefs.getInt("done", 0)
        // 전원이 끊겨 첫 줄(세션 머리)이 사라진 파일(0 바이트가 되는 것을 에뮬레이터에서 확인, V-12): 이어 쓰기 전에 머리를 설정에 저장해 둔 값으로 다시 쓴다
        if (StudyFileCheck.headMissing(file)) append(listOf(headRecord(sid, p, prefs.getLong("consentAt", 0L).takeIf { it > 0 } ?: System.currentTimeMillis(), rewritten = true)))
        append(listOf(JSONObject().put("rec", "session_resume").put("session", sid).put("ts", System.currentTimeMillis()).put("iso", iso(System.currentTimeMillis()))))
        startSensors()
        s.log.d("연구 기록 이어서"); s.log.demo("연구 기록 세션 $sid")
    }

    /** 동의 + 공변량 → 새 세션. 파일 첫 줄에 세션 머리(참가자 공변량·형식)를 쓴다 */
    fun start(p: Profile) {
        if (active) stop()
        val sid = randomId()
        sessionId = sid; profile = p; warningsDone = 0; wid = 0
        purgeOld()
        prefs.edit().putString("session", sid).putString("age", p.ageBand).putInt("years", p.drivingYears).putString("car", p.carType)
            .putLong("consentAt", p.consentAt).putString("source", p.source).putInt("wid", 0).putInt("done", 0).apply()
        startSensors()
        append(listOf(headRecord(sid, p, System.currentTimeMillis(), rewritten = false)))
        s.log.d("연구 기록 시작"); s.log.demo("연구 기록 세션 $sid (${p.ageBand}, ${p.drivingYears}년, ${p.carType})")
    }

    /** 끄기: 진행 중인 경고 묶음은 complete=false 로 쓰고 센서를 내린다. 파일은 남긴다(지우기는 앱 데이터 삭제로) */
    fun stop() {
        val sid = sessionId ?: return
        for (b in open.toList()) finish(b, complete = false)
        open.clear()
        val now = System.currentTimeMillis()
        append(listOf(JSONObject().put("rec", "session_end").put("session", sid).put("ts", now).put("iso", iso(now)).put("warnings", warningsDone)))
        sessionId = null; profile = null
        prefs.edit().remove("session").apply()
        stopSensors()
        ring.clear(); ringEv.clear(); keysSeen.clear()
        s.log.d("연구 기록 끔: 경고 ${warningsDone}건"); s.log.demo("연구 기록 세션 $sid 끝")
    }

    /** 앱이 끝날 때: 묶음은 미완으로 쓰되 세션은 이어 쓸 수 있게 남긴다 */
    fun close() {
        if (!active) return
        for (b in open.toList()) finish(b, complete = false)
        open.clear(); stopSensors()
    }

    fun setUpload(on: Boolean) { uploadOn = on; prefs.edit().putBoolean("upload", on).apply() }

    /** 위치 권한을 막 받았을 때 GPS 를 붙인다 */
    fun refreshSensors() { if (active) startSensors() }

    private fun startSensors() {
        val car = s.car
        car.setTurnSignalWatch(true)
        car.onTurnSignal = { v -> onTurn(v) }
        if (imuType == null) {
            val lin = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val acc = lin ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (acc != null && sm?.registerListener(imuListener, acc, SensorManager.SENSOR_DELAY_GAME) == true) imuType = if (lin != null) "linear" else "accel_with_gravity"
        }
        if (!gpsOn && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            gpsOn = runCatching { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, s.main.looper); lm != null }.getOrDefault(false)
        }
    }

    private fun stopSensors() {
        s.car.setTurnSignalWatch(false); s.car.onTurnSignal = null
        runCatching { sm?.unregisterListener(imuListener) }; imuType = null
        if (gpsOn) runCatching { lm?.removeUpdates(gpsListener) }; gpsOn = false
    }

    /** 차속 신호가 없을 때만 GPS 를 쓰므로, 동의 화면에서 위치 권한을 물을지 정한다 */
    fun needsGps(): Boolean = s.car.speedEventAt == 0L && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ 이벤트(메인 스레드)

    fun touch() { if (!active) return; touches++; event("touch", null) }

    /** 운전자가 정차 중에 남긴 표시(예: [경고 틀림], 2026-11-13). 기록만 — 판정·경고에는 쓰이지 않는다 */
    fun driverFlag(kind: String, hazardId: String) { if (active) event("driver_flag", JSONObject().put("kind", kind).put("hazard", hazardId)) }

    fun appVisible(fg: Boolean) { if (!active || fg == appFg) return; appFg = fg; event(if (fg) "app_fg" else "app_bg", null) }

    /** TTS 실제 시작·끝(VoiceAssistant.onSpeakingChanged). 경고 문장이면 그 묶음에 시각을 남긴다 */
    fun speaking(on: Boolean) {
        if (!active) return
        val now = System.currentTimeMillis()
        val cur = if (on) s.voice.currentText else null
        val prev = speakingText
        if (prev != null && prev != cur) for (b in open) if (b.voiceText == prev && b.voiceStartAt > 0 && b.voiceEndAt == 0L) { b.voiceEndAt = now; addEv(b, now, "voice_end", null) }
        speakingText = cur
        if (cur != null) for (b in open) if (b.voiceText == cur && b.voiceStartAt == 0L) { b.voiceStartAt = now; addEv(b, now, "voice_start", cur) }
    }

    /** 접근 경고음(RoadLinkController) — 경고 반응에 영향을 주는 자극이라 이벤트로 남긴다 */
    fun beep(stage: Int, ttcS: Double, distM: Double, target: String) {
        if (!active) return
        event("beep", JSONObject().put("stage", stage).put("ttc_s", r1(ttcS)).put("dist_m", distM.toInt()).put("target", target))
    }

    /** 지난 알림음(사고 지점을 지남 / 정체 구간을 벗어남) */
    fun passed(target: String) { if (active) event("pass_chime", JSONObject().put("target", target)) }

    private fun onTurn(v: String) {
        val now = System.currentTimeMillis()
        event("turn_signal", v)
        if (v == "LEFT" || v == "RIGHT") { turnOnAt = now; turnSide = v; turnSteer0 = s.car.steerAngle; laneSaid = false }
    }

    private fun event(type: String, value: Any?) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("type", type).put("value", value ?: JSONObject.NULL)
        ringEv.addLast(Ev(now, o)); while (ringEv.isNotEmpty() && now - ringEv.first().ts > PRE_S * 1000L) ringEv.removeFirst()
        for (b in open) b.events += Ev(now, JSONObject(o.toString()))
    }

    private fun addEv(b: Bundle, ts: Long, type: String, value: Any?) { b.events += Ev(ts, JSONObject().put("type", type).put("value", value ?: JSONObject.NULL)) }

    // ------------------------------------------------------------ 경고 시작(읽기만)

    /** 헤드라인에 띄운 경고(AppController.build). hazard = 화면 위험, advice = 권장 속도(정체 꼬리) */
    fun onScreen(hazard: JSONObject?, advice: JSONObject?, headline: String) {
        if (!active || !appFg) return                      // 다른 앱(순정 내비)이 앞에 있으면 화면에 뜬 것이 아니다
        val k = classify(hazard, advice) ?: return
        val b = bundleFor(k.first, k.second, hazard, advice, "screen", headline, null)
        if (b.screenAt == 0L) { b.screenAt = System.currentTimeMillis(); addEv(b, b.screenAt, "screen_shown", headline) }
    }

    /** RoadLinkController 가 경고 문장을 말하라고 넘긴 순간(줄에 들어간 시각 — 실제 소리 시작은 speaking()) */
    fun onSpoken(hazard: JSONObject?, advice: JSONObject?, text: String) {
        if (!active) return
        val k = classify(hazard, advice) ?: return
        val b = bundleFor(k.first, k.second, hazard, advice, "voice", null, text)
        if (b.voiceQueuedAt == 0L) {
            b.voiceQueuedAt = System.currentTimeMillis(); b.voiceText = text; addEv(b, b.voiceQueuedAt, "voice_queued", text)
            if (s.voice.currentText == text && s.voice.speaking) { b.voiceStartAt = b.voiceQueuedAt; addEv(b, b.voiceQueuedAt, "voice_start", text) }
        } else if (b.voiceText != text) addEv(b, System.currentTimeMillis(), "voice_repeat", text)   // 차 3대 이상 확인 등으로 한 번 더 말함
    }

    /** (종류, 키). 종류: incident(사고 의심) · official(연계 공식 안내 — 도로 정보로 확인된 사고) · jam_tail(정체 꼬리 권장 속도). 그 밖은 기록 대상이 아니다 */
    private fun classify(h: JSONObject?, adv: JSONObject?): Pair<String, String>? {
        if (h != null) {
            val type = h.optString("type")
            val official = h.optJSONObject("official") != null || type == "OFFICIAL"
            return when {
                type == "INCIDENT" && official -> "official" to "${h.optString("id")}:official"
                type == "INCIDENT" -> "incident" to "${h.optString("id")}:incident"
                type == "OFFICIAL" -> "official" to "${h.optString("id")}:official"
                type == "CONGESTION" -> "jam_tail" to "${h.optString("id")}:jam"
                else -> null
            }
        }
        if (adv != null) {
            if (jamEpisode == 0) noteJam(System.currentTimeMillis())
            return "jam_tail" to "advice:$jamEpisode"
        }
        return null
    }

    /** 권장 속도(정체 꼬리)가 보기에 있으면 매초 부른다: 20초 넘게 없다가 다시 뜨면 새 정체 꼬리 */
    private fun noteJam(now: Long) {
        if (jamSeenAt == 0L || now - jamSeenAt > JAM_GAP_MS) jamEpisode++
        jamSeenAt = now
    }

    private fun bundleFor(kind: String, key: String, h: JSONObject?, adv: JSONObject?, by: String, headline: String?, text: String?): Bundle {
        open.firstOrNull { it.key == key }?.let { return it }
        // 같은 경고가 창(150초)을 지난 뒤 다시 떠도 새 묶음으로 세지 않는다 — 처음 띄운 순간만 반응의 기준
        if (keysSeen.containsKey(key)) return Bundle(-1, key, 0, JSONObject().put("kind", kind), emptyList(), emptyList())
        val now = System.currentTimeMillis()
        keysSeen[key] = now
        wid++; prefs.edit().putInt("wid", wid).apply()
        val view = currentView
        val ad = adv ?: view?.optJSONObject("ahead")?.optJSONObject("advice")
        val dist = if (h != null) h.optInt("ahead").takeIf { !h.isNull("ahead") } else adv?.optInt("dist")
        val header = JSONObject().put("rec", "warning").put("session", sessionId).put("wid", wid).put("kind", kind)
            .put("hazard", h?.optString("id") ?: JSONObject.NULL).put("hazard_type", h?.optString("type") ?: "ADVICE")
            .put("onset_ts", now).put("onset_iso", iso(now)).put("onset_by", by)
            .put("advice", adviceOf(ad)).put("dist_tail_m", dist ?: JSONObject.NULL)
            .put("level", h?.optInt("level", 1) ?: JSONObject.NULL).put("cars", h?.optInt("cars") ?: JSONObject.NULL)
            .put("evidence", h?.optJSONArray("evidence") ?: JSONArray()).put("official", h?.optJSONObject("official")?.let { o -> JSONObject().put("type", o.optString("type")).put("kind", o.optString("kind")) } ?: JSONObject.NULL)   // 공식 돌발의 경로 위치(s)는 뺀다 — 꼬리 거리와 합치면 내 위치가 된다
            .put("label", h?.optString("label") ?: JSONObject.NULL)
            .put("say", h?.optString("say")?.takeIf { it.isNotEmpty() } ?: adv?.optString("say")?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        val pre = ring.toList().takeLast(PRE_S)
        val preEv = ringEv.filter { now - it.ts <= PRE_S * 1000L }
        val b = Bundle(wid, key, now, header, pre, preEv)
        open += b
        s.log.d("연구 기록: 경고 #$wid $kind (${by}, ${dist ?: "?"}m)")
        return b
    }

    private fun adviceOf(adv: JSONObject?): JSONObject {
        if (adv == null || !adv.has("adviseKmh")) return JSONObject().put("mode", "none")
        adv.optJSONArray("stages")?.let { st -> return JSONObject().put("mode", "staged").put("kmh", st) }   // 단계 90·70·50 (허브가 단계 권고를 줄 때)
        return JSONObject().put("mode", "uniform").put("kmh", adv.optInt("adviseKmh")).put("jam_kmh", adv.optInt("jamKmh"))
    }

    // ------------------------------------------------------------ 1초마다(AppController.tick)

    private var currentView: JSONObject? = null

    fun tick(view: JSONObject?, roadActive: Boolean) {
        if (!active) return
        currentView = view
        val now = System.currentTimeMillis(); val el = SystemClock.elapsedRealtime()
        val car = s.car
        val (speed, src, age) = speedNow(el)
        val ps = prevSpeed   // 지역 값으로 잡아 스마트 캐스트(!! 없이)
        val accLong = if (speed != null && ps != null && el > prevEl) (speed - ps) / ((el - prevEl) / 1000.0) else null
        prevSpeed = speed; prevEl = el
        val o = JSONObject().put("ts", now)
            .put("speed_kmh", speed?.let { r1(it * 3.6) } ?: JSONObject.NULL).put("speed_src", src ?: JSONObject.NULL).put("speed_age_s", age?.let { r1(it) } ?: JSONObject.NULL)
            .put("acc_long_mps2", accLong?.let { r2(it) } ?: JSONObject.NULL)
            .put("imu", if (imuN > 0) JSONObject().put("type", imuType).put("ax", r2(imuSum[0] / imuN)).put("ay", r2(imuSum[1] / imuN)).put("az", r2(imuSum[2] / imuN)).put("amax", r2(imuMax)).put("n", imuN) else JSONObject.NULL)
            .put("turn", car.turnSignal ?: JSONObject.NULL).put("steer_deg", car.steerAngle?.let { r1(it.toDouble()) } ?: JSONObject.NULL)
            .put("brake", car.brakePedal ?: JSONObject.NULL).put("gear", car.gear?.name ?: JSONObject.NULL)
            .put("app_fg", appFg).put("touch_n", touches).put("speaking", s.voice.speaking).put("road_link", roadActive)
        imuSum.fill(0.0); imuN = 0; imuMax = 0.0; touches = 0
        // 경고별 거리: 정체 꼬리(권장 속도의 dist), 위험 id → 앞 거리
        val adv = view?.optJSONObject("ahead")?.optJSONObject("advice")
        val jam = adv?.optInt("dist")
        if (adv != null) noteJam(now)
        val hz = mutableMapOf<String, Int>()
        view?.optJSONArray("hazards")?.let { a -> for (i in 0 until a.length()) { val h = a.getJSONObject(i); if (!h.isNull("ahead")) hz[h.optString("id")] = h.optInt("ahead") } }
        val smp = Sample(now, el, o, jam, hz, adv?.optInt("adviseKmh"))
        ring.addLast(smp); while (ring.size > PRE_S) ring.removeFirst()
        // 브레이크 페달 밟음·뗌(1초 읽기 — 첫 감속 행동의 단서. 가속 페달은 공개 API 가 없다)
        val br = car.brakePedal
        if (br != null && br != prevBrake) { if (prevBrake != null) event(if (br) "brake_on" else "brake_off", null); prevBrake = br }
        // 차로 변경 후보
        val st0 = turnSteer0; val st = car.steerAngle
        if (turnSide != null && !laneSaid && now - turnOnAt <= LANE_WINDOW_MS && st0 != null && st != null && abs(st - st0) >= LANE_STEER_DEG) {
            laneSaid = true; event("lane_change_candidate", JSONObject().put("side", turnSide).put("steer_delta_deg", r1((st - st0).toDouble())))
        }
        for (b in open.toList()) {
            b.post += smp
            if (b.post.size >= POST_S) finish(b, complete = true)
        }
    }

    private fun speedNow(el: Long): Triple<Float?, String?, Double?> {
        val car = s.car
        if (car.simulatedSpeed != null) return Triple(car.speedMs, "sim", 0.0)
        val vAge = if (car.speedEventAt > 0) (el - car.speedEventAt) / 1000.0 else null
        if (vAge != null && vAge <= 5) return Triple(car.speedMs, "vehicle", vAge)
        val gAge = if (gpsAt > 0) (el - gpsAt) / 1000.0 else null
        if (gAge != null && gAge <= 3) return Triple(gpsSpeed, "gps", gAge)
        if (vAge != null) return Triple(car.speedMs, "vehicle", vAge)   // 오래된 값이라도 차 신호(정차 중에는 콜백이 드물다)
        return Triple(null, null, null)
    }

    private fun finish(b: Bundle, complete: Boolean) {
        open.remove(b)
        if (b.wid < 0) return
        val lines = mutableListOf<JSONObject>()
        val h = JSONObject(b.header.toString())
        h.put("screen_ts", b.screenAt.takeIf { it > 0 } ?: JSONObject.NULL).put("voice_queued_ts", b.voiceQueuedAt.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("voice_start_ts", b.voiceStartAt.takeIf { it > 0 } ?: JSONObject.NULL).put("voice_end_ts", b.voiceEndAt.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("voice_text", b.voiceText ?: JSONObject.NULL)
        lines += h
        for (e in (b.preEvents + b.events).sortedBy { it.ts }) lines += JSONObject(e.o.toString()).put("rec", "event").put("wid", b.wid).put("ts", e.ts).put("dt_ms", e.ts - b.t0)
        val pre = b.pre
        pre.forEachIndexed { i, smp -> lines += sampleLine(b, smp, i - pre.size) }
        b.post.forEachIndexed { i, smp -> lines += sampleLine(b, smp, i) }
        val minSpeed = b.post.mapNotNull { it.o.optDouble("speed_kmh").takeIf { v -> !v.isNaN() } }.minOrNull()
        lines += JSONObject().put("rec", "warning_end").put("wid", b.wid).put("complete", complete).put("n_pre", pre.size).put("n_post", b.post.size)
            .put("n", pre.size + b.post.size).put("min_speed_kmh", minSpeed ?: JSONObject.NULL)
        if (complete) { warningsDone++; prefs.edit().putInt("done", warningsDone).apply() }
        append(lines)
        if (uploadOn) upload(lines)
        s.log.d("연구 기록: 경고 #${b.wid} ${if (complete) "완료" else "미완"} (${pre.size}+${b.post.size}줄)")
    }

    /** 줄 순서: rec·wid·t·dt_ms → 이 경고 기준 거리·권고 → 1초 표본 공통 값 */
    private fun sampleLine(b: Bundle, smp: Sample, t: Int): JSONObject {
        val dist = if (b.hazardId == null) smp.jamDist else smp.hzDist[b.hazardId]
        val o = JSONObject().put("rec", "sample").put("wid", b.wid).put("t", t).put("dt_ms", smp.ts - b.t0)
            .put("dist_tail_m", dist ?: JSONObject.NULL).put("advice_kmh", smp.adviceKmh ?: JSONObject.NULL)
        smp.o.keys().forEach { k -> o.put(k, smp.o.get(k)) }
        return o
    }

    // ------------------------------------------------------------ 파일·내보내기·업로드

    private fun append(lines: List<JSONObject>) {
        val f = file ?: return
        val text = lines.joinToString("\n", postfix = "\n") { it.toString() }
        // 쓴 뒤 fsync(V-12): 전원이 끊겨도 마지막 줄·머리 줄이 사라지지 않게. 쓰기는 드물고 별도 스레드라 비용은 무시할 만하다
        writer.execute { runCatching { java.io.FileOutputStream(f, true).use { o -> o.write(text.toByteArray(Charsets.UTF_8)); o.flush(); o.fd.sync() } }.onFailure { s.main.post { s.log.d("연구 기록 쓰기 실패: ${it.message}") } } }
    }

    /** 다 쓴 뒤에 콜백(파일 핸들 순서를 지키려고 같은 스레드에서) */
    fun afterWrites(cb: () -> Unit) { writer.execute { s.main.post(cb) } }

    /** 앱을 닫을 때: 남은 쓰기는 마저 하고 쓰기 스레드를 끝낸다(액티비티를 다시 만들 때마다 스레드가 쌓이지 않게) */
    fun release() { writer.shutdown() }

    /** 업로드 허용: https, 또는 개발용 평문 http 는 정확히 127.0.0.1·10.0.2.2(접두어 비교는 127.0.0.1.example 같은 주소를 통과시켰다) */
    private fun uploadAllowed(url: String): Boolean = runCatching {
        val u = java.net.URI(url)
        u.scheme == "https" && !u.host.isNullOrEmpty() || u.scheme == "http" && u.host in setOf("127.0.0.1", "10.0.2.2") && u.rawUserInfo == null
    }.getOrDefault(false)

    /** 업로드: 묶음 하나를 세션 머리 줄과 함께 NDJSON 으로 POST. 실패해도 파일은 남는다 */
    private fun upload(lines: List<JSONObject>) {
        val url = uploadUrl.takeIf { uploadAllowed(it) } ?: return
        val body = (listOf(JSONObject().put("rec", "session_ref").put("session", sessionId).put("participant", profile?.let { p ->
            JSONObject().put("age_band", p.ageBand).put("driving_years", p.drivingYears).put("car_type", p.carType) } ?: JSONObject.NULL)) + lines)
            .joinToString("\n", postfix = "\n") { it.toString() }
        writer.execute {
            runCatching {
                val code = ai.meeos.tago.net.Http.status(url, "POST", body.toByteArray(Charsets.UTF_8),
                    mapOf("Content-Type" to "application/x-ndjson; charset=utf-8"), UPLOAD_CONNECT_MS, UPLOAD_READ_MS)
                s.main.post { s.log.d("연구 기록 업로드 $code") }
            }.onFailure { e -> s.main.post { s.log.d("연구 기록 업로드 실패: ${e.message}") } }
        }
    }

    private fun randomId(): String { val b = ByteArray(6); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) } }
    private fun iso(ms: Long) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    companion object {
        const val PREFS = "study"
        const val FORMAT = "gilbeot-driver-response/1"
        const val CONSENT_VERSION = "2026-11-04"
        const val PRE_S = 30
        const val POST_S = 120                // t = 0 … 119 → 경고 전 30 + 뒤 120 = 150줄
        const val UPLOAD_CONNECT_MS = 5000
        const val UPLOAD_READ_MS = 10_000
        const val JAM_GAP_MS = 20_000L        // 권장 속도가 20초 넘게 사라졌다 다시 뜨면 새 정체 꼬리
        const val LANE_WINDOW_MS = 8_000L
        const val LANE_STEER_DEG = 3f
        val AGE_BANDS = listOf("10s", "20s", "30s", "40s", "50s", "60s", "70plus")
        val CAR_TYPES = listOf("sedan", "suv", "van_truck", "other")
    }
}
