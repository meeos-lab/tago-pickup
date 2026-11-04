package ai.meeos.tago.road

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.SirenMic
import ai.meeos.tago.app.Question
import ai.meeos.tago.app.Services
import ai.meeos.tago.engine.LinkHub
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
    private val spoken = mutableMapOf<String, Int>()        // 위험 id → 말한 단계(차가 3대 이상 확인되면 한 번 더)
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
    private var brakeHoldSince = 0L
    private var brakeHoldReported = false
    private var recircReported = false
    private var reverseSince = 0L
    private var turnbackReported = false
    private var demoCalled = false

    // 사이렌(긴급차량): 도로 링크 중 차 안 마이크로 듣는다. 듣는 동안 5초마다 보고. 음성 인식이 듣는 동안은 마이크를 놓아 준다
    private val sirenMic = SirenMic({ on -> s.main.post { sirenHeard = on; if (on) s.log.d("감지: 사이렌 → 앞차들에게") } }, { s.log.d(it) })
    private var sirenHeard = false
    private var sirenSentAt = 0L
    /** 시험용: 마이크 대신 흘려 넣을 wav(인텐트 siren_wav) */
    var sirenWav: File? = null
        set(v) { field = v; if (v != null) sirenMic.stop() }
    private var sirenFile = false                           // 지금 wav를 흘려 넣는 중(음성 인식 때도 멈추지 않는다)

    // 시연 주행(인텐트 convoy_sim=true): 영상용으로 내 차가 권장 속도를 따라 달린다(1초에 1.5 m/s까지만 바꿈) — 기본은 실제 차속
    var simDrive = false
        set(v) { field = v; simV = 25f; s.car.simulatedSpeed = if (v) simV else null }
    private var simV = 25f
    /** 두 차 시연: 출발점을 LINK_START 에서 이만큼(m) 옮긴다(뒷차는 음수). 같은 허브에 붙는 두 에뮬레이터가 같은 자리에서 출발하지 않게 */
    var startOffset = 0.0
    // 충전: 꽂은 때·그때 배터리 %, 고장 보고 여부, 정차 시작 시각(충전 대기 계산)
    private var portSince = 0L
    private var portSoc: Int? = null
    private var chargeFailReported = false
    private var stopSince = 0L
    // 사고 판단(docs/incident-judgment-design.md 10판): 충돌은 CarSignals 의 10 Hz 감지기가 알리고, 여기서 즉시 보고·운전자 확인·2차 사고 예방 모드
    private var impactAt = 0L                                // 마지막 충돌 감지 시각(에어백 30초 읽기 창)
    private var airbagReported = false
    private var crashAsked = false
    /** 무응답·[도움 필요] → 응급 카드(MainActivity 가 EmergencyController.start 로 잇는다) */
    var onCrashHelp: (() -> Unit)? = null
    // 공식 돌발 정보(순정 내비): 링크 중 ACCIDENT·ROAD_DAMAGE·DISASTER 를 허브에 올린다 — 새 사건이 생겼거나 60 s 지났을 때
    private val officialKeys = mutableSetOf<String>()
    private var officialSentAt = 0L
    /** 시연 훅 comm_sim: 보고를 멈춰 '사고로 인한 통신 끊김'을 흉내 낸다(이벤트는 이미 나간 뒤) */
    var commSim = false
    /** 두 차 시연: 이 차의 운전자 이름(기본은 지역 문자열 driver_name — 두 에뮬레이터가 같은 이름이면 그룹에서 구분이 안 된다) */
    var nameOverride: String? = null
    private val myName get() = nameOverride ?: t[R.string.driver_name]

    private val t get() = s.texts
    private val car get() = s.car

    // ------------------------------------------------------------ 시작·그룹·끝

    /** 도로 링크 켜기. 시연(내장 서버)은 도로를 비우고 시작하고, 출발하면 시연 도로(모르는 Tago 차 14대)를 부른다 */
    fun start(pickupActive: Boolean) {
        if (!BuildConfig.ROAD_LINK || active || pickupActive) return
        val body = JSONObject().put("t", s.epoch()).put("lang", s.lang).put("name", myName).put("car", t[R.string.driver_car])
        s.io.execute {
            runCatching {
                if (BuildConfig.EMBEDDED_SERVER) s.api.http("POST", "/api/road/reset", "{}")
                JSONObject(s.api.http("POST", "/api/road/join", body.toString()))
            }.onSuccess { r -> s.main.post {
                convoy = JSONObject().put("me", r.getString("me"))
                linkS = LINK_START + startOffset; spoken.clear(); restKey = ""; briefed = false; rainReported = false; demoCalled = false
                advSaid = false; jamWatch = false; rainSaid.clear(); zoneSaid.clear(); simV = 25f
                driveRisk.reset(); riskSaidAt = 0L; fatigueSaidAt = 0L; lastSayAt = System.currentTimeMillis()
                car.readRoadSignals = true
                car.onImpact = { pre, nowV, samples -> onImpact(pre, nowV, samples) }
                car.setFastSpeed(true)
                impactAt = 0L; airbagReported = false; crashAsked = false; commSim = false
                officialKeys.clear(); officialSentAt = 0L
                s.navi.onTrafficEvents = { onOfficial(it) }
                post("/api/road/state", state())
                onMapUrl("${BuildConfig.SERVER_URL}/link/road?me=${r.getString("me")}&car=1")
                s.log.demo("3D 관제 화면: ${BuildConfig.PUBLIC_URL}/world?me=${r.getString("me")}")
                s.say(t[R.string.say_road_started]); lastSayAt = System.currentTimeMillis()
                s.render()
            } }.onFailure { e -> s.main.post { s.log.d("도로 링크 시작 실패: ${e.message}") } }
        }
    }

    /** 가족 그룹(선택): 코드·링크로 가족 차를 묶는다. 위험은 같은 도로 링크에서 오고, 그룹 차는 이름·위치를 서로 본다 */
    fun startGroup() {
        val c = convoy ?: return
        if (c.has("group")) return
        val body = JSONObject().put("t", s.epoch()).put("me", c.getString("me")).put("name", myName).put("car", t[R.string.driver_car])
        s.api.post("/api/link/group", body, onError = { e -> s.log.d("가족 그룹 실패: ${e.message}") }) { r ->
            if (convoy !== c) return@post
            c.put("group", r.getString("group")).put("code", r.getString("code"))
            s.log.demo("가족 참가 링크: ${BuildConfig.PUBLIC_URL}/link/${r.getString("group")}?code=${r.getString("code")}")
            s.say(t[R.string.say_group_created, r.getString("code").toCharArray().joinToString(" ")])
            briefed = false
            groupPost("demo", JSONObject().put("t", s.epoch()))      // 시연: 가상 가족(삼촌·이모)
            onMapUrl("${BuildConfig.SERVER_URL}/link/${r.getString("group")}?me=${c.getString("me")}&car=1")
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
            s.log.d("가족 그룹 합류: ${r.getString("group")}")
            briefed = false
            onMapUrl("${BuildConfig.SERVER_URL}/link/${r.getString("group")}?me=${c.getString("me")}&car=1")
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
        if (yes && r != null) { s.say(t[R.string.say_rest_guide, t.ro(r.optString("name"))]); naviToRest(r) }
        else s.say(t[R.string.say_rest_skip])
    }

    /** 휴게소를 순정 내비 목적지로(국내 경로라 지역 설정과 무관하게 요청) */
    private fun naviToRest(r: JSONObject) {
        val pos = r.optJSONArray("pos") ?: return
        s.navi.routeDomestic(pos.getDouble(0), pos.getDouble(1), "${r.optString("name")} (Tago)")
    }

    fun end() {
        convoy = null; view = null
        s.dialog.cancel()
        sirenMic.stop(); sirenHeard = false; sirenFile = false
        car.readRoadSignals = false
        car.setFastSpeed(false); car.onImpact = null
        s.navi.onTrafficEvents = null
        if (simDrive) simDrive = false
        s.say(t[R.string.say_road_ended])
        onMapUrl(null)
        s.render()
    }

    fun stop() { sirenMic.stop() }

    // ------------------------------------------------------------ 1초마다

    private fun state() = JSONObject().put("t", s.epoch()).put("s", linkS).put("speed", car.speedMs.toDouble()).put("wiper", car.wiper)
        .also { b -> car.outsideTemp?.let { b.put("temp", it.toDouble()) } }

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
        onCrashHelp?.invoke()
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
            adv == null -> 25f
            adv.optInt("dist") > 0 -> adv.optInt("adviseKmh") / 3.6f
            else -> maxOf(adv.optInt("jamKmh"), 14) / 3.6f
        }
        simV = (simV + (target - simV).coerceIn(-1.5f, 1.5f)).coerceAtLeast(0f)
        return simV
    }

    private fun roadTick(dt: Double) {
        val driving = simDrive || car.driving
        if (simDrive) car.simulatedSpeed = simFollow()
        val speed = car.speedMs
        if (driving) linkS += speed * dt * LinkHub.DEMO_SCALE
        if (!demoCalled && driving && speed > 3f) { demoCalled = true; post("/api/road/demo", JSONObject().put("t", s.epoch())) }
        val now = System.currentTimeMillis()
        // 급제동: 1초 사이 3 m/s 이상 감속(18 km/h 이상에서) + 다음 1초에도 낮게 유지(순간 출렁임 제외), 10초에 한 번
        if (prevSpeed2 >= 5f && prevSpeed2 - prevSpeed >= 3f && speed <= prevSpeed + 1f && now - lastBrakeAt > 10_000) {
            lastBrakeAt = now
            if (jamWatch) jamBrakes++
            driveRisk.brake(now)
            event("HARD_BRAKE", "%.1f→%.1f m/s".format(prevSpeed2, prevSpeed))
            s.log.d("감지: 급제동 → 뒤차들에게 (%.1f→%.1f→%.1f m/s)".format(prevSpeed2, prevSpeed, speed))
        }
        prevSpeed2 = prevSpeed; prevSpeed = speed
        driveRisk.tick(now, speed, car.steerAngle)
        if (jamWatch) jamMinKmh = minOf(jamMinKmh, speed * 3.6)
        // 폭우: 와이퍼 연속 작동(워셔·간헐 제외)
        val rain = car.wiper.startsWith("CONTINUOUS")
        if (rain != rainReported) {
            rainReported = rain
            event(if (rain) "HEAVY_RAIN" else "HEAVY_RAIN_CLEARED", car.wiper)
        }
        // 급조향(낙하물 회피): 40km/h 이상에서 1초 사이 핸들 60° 이상, 브레이크는 밟지 않음. 15초에 한 번
        val a = car.steerAngle; val pa = prevSteer
        if (a != null && pa != null && speed > 11f && abs(a - pa) >= 60f && car.brakePedal != true && now - lastSwerveAt > 15_000) {
            lastSwerveAt = now
            event("SWERVE", "%.0f→%.0f°".format(pa, a))
            s.log.d("감지: 급조향 → 뒤차들에게 (%.0f→%.0f°)".format(pa, a))
        }
        prevSteer = a
        // 브레이크 오래 밟음(긴 내리막): 30km/h 이상에서 20초 넘게 계속. 떼면 다시 셈
        if (car.brakePedal == true && speed > 8f) {
            if (brakeHoldSince == 0L) brakeHoldSince = now
            if (!brakeHoldReported && now - brakeHoldSince >= 20_000) {
                brakeHoldReported = true
                event("BRAKE_HOLD", "20s")
                s.log.d("감지: 브레이크 20초 이상 → 긴 내리막")
            }
        } else { brakeHoldSince = 0L; brakeHoldReported = false }
        // 내기순환으로 바꿈(연기·매연): 켜질 때 보고, 꺼지면 해제 보고(서버는 10분 동안 보고를 남긴다)
        val rc = car.recirc == true
        if (rc != recircReported) {
            recircReported = rc
            event(if (rc) "RECIRC_ON" else "RECIRC_OFF", "recirc")
            if (rc) s.log.d("감지: 내기순환 → 뒤차들에게")
        }
        // 되돌아감(침수·통제 의심): 도로 위에서 후진 기어 3초 이상
        if (car.reversing && linkS > 0) {
            if (reverseSince == 0L) reverseSince = now
            if (!turnbackReported && now - reverseSince >= 3_000) {
                turnbackReported = true
                event("TURNBACK", "reverse")
                s.log.d("감지: 되돌아감 → 뒤차들에게")
            }
        } else reverseSince = 0L
        // 사이렌(긴급차량): 마이크가 듣는 동안 5초마다. 서버가 차마다 처음 들은 곳을 이어 긴급차량이 다가오는 방향·속도를 잰다
        // 충전(EV): 정차 중 충전구를 열고(꽂고) 90초 넘게 충전이 시작되지 않으면 충전기 고장·결제 오류 의심 → CHARGE_FAIL, 충전이 시작되면 CHARGE_OK(해제).
        // 정차한 지 3분 넘어서야 꽂으면 충전 대기 → CHARGE_START(대기 분). 허브는 휴게소 500m 안일 때만 받아 그 휴게소에 붙인다
        if (car.stopped) { if (stopSince == 0L) stopSince = now } else stopSince = 0L
        val port = car.chargePort
        if (port == true && car.stopped) {
            val soc = car.soc
            if (portSince == 0L) {
                portSince = now; portSoc = soc; chargeFailReported = false
                val waited = if (stopSince > 0) (now - stopSince) / 1000 else 0L
                if (waited >= CHARGE_WAIT_SEC) { event("CHARGE_START", "${waited / 60}"); s.log.d("감지: 충전 대기 ${waited / 60}분 → 일행에게") }
            }
            val progressing = car.charging == true || (soc != null && portSoc != null && soc > portSoc!!)
            if (progressing && chargeFailReported) { chargeFailReported = false; event("CHARGE_OK", "charging"); s.log.d("충전 시작 — 충전기 고장 의심 해제") }
            else if (!progressing && !chargeFailReported && now - portSince >= CHARGE_FAIL_SEC * 1000L) {
                chargeFailReported = true
                event("CHARGE_FAIL", "${CHARGE_FAIL_SEC}s"); s.log.d("감지: 충전기 고장 의심(꽂은 뒤 ${CHARGE_FAIL_SEC}초 충전 없음) → 뒤차들에게")
            }
        } else if (port != true) { portSince = 0L; chargeFailReported = false }
        // 충돌 뒤 30초 동안 에어백 전개를 읽는다(평소엔 읽지 않는다). 에뮬레이터는 미지원이라 훅 airbag_sim 으로만
        if (impactAt > 0 && !airbagReported && now - impactAt < 30_000) car.readAirbag { b ->
            if (b == true && !airbagReported) { airbagReported = true; event("AIRBAG", "deployed"); s.log.d("감지: 에어백 전개 → 뒤차들에게") }
        }
        if (sirenHeard && now - sirenSentAt >= 5_000) {
            sirenSentAt = now
            event("SIREN", "mic")
        }
        if (!commSim) post("/api/road/state", state())   // 말하는 중에도 보고한다(안내는 한 번씩 큐에 쌓여 겹치지 않는다). comm_sim 이면 '통신 끊김' 흉내
    }

    /** 사이렌 감지는 도로 링크 중에만, 음성 인식이 듣지 않을 때만 마이크를 쓴다(1초마다 맞춘다) */
    fun manageSiren() {
        val want = active && !s.dialog.awaiting &&
            (sirenWav != null || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (!sirenMic.active) sirenFile = false
        if (want && !sirenMic.active) { val w = sirenWav; sirenWav = null; sirenFile = w != null; sirenMic.start(w) }
        else if (!want && sirenMic.active && !sirenFile) sirenMic.stop()
    }

    /** 음성 인식이 마이크를 쓰도록 사이렌 감지는 잠시 쉰다(답을 받으면 manageSiren 이 다시 켠다) */
    fun releaseMicForListening() { if (!sirenFile) sirenMic.stop() }

    // ------------------------------------------------------------ 서버 보기 → 음성(한 번에 한 문장) + 화면

    private fun onView(v: JSONObject) {
        view = v
        s.render()
        val now = System.currentTimeMillis()
        if (!briefed && v.optBoolean("demo") && v.optInt("nearby") > 0) { briefed = true; speak(v.optString("brief"), now); return }
        // 말 줄이기(설계 10-11): ① 바로 말함 = 긴급차량·사고 의심·가까운 급제동(예산과 상관없이)
        // ② 조용할 때만 = 나머지 위험(종류별 행동 거리 안)·권장 속도·비 예보·교량 결빙·위험성 — 도로 시간 30초에 한 문장
        // ③ 화면에만 = 행동 거리 밖의 위험, 사고 다발 구간, 위험성 '주의'
        val hz = v.optJSONArray("hazards")
        var normal: JSONObject? = null
        if (hz != null) for (i in 0 until hz.length()) {
            val h = hz.getJSONObject(i)
            if (!h.has("say")) continue
            val type = h.optString("type"); val lvl = h.optInt("level", 1); val id = h.getString("id")
            if ((spoken[id] ?: 0) >= lvl) continue
            if (type != "SIREN" && h.optDouble("ahead", 0.0) > (SAY_RANGE[type] ?: SAY_RANGE_DEFAULT)) continue   // 아직 멀다: 화면에만(가까워지면 그때 말한다)
            if (type in URGENT) { spoken[id] = lvl; speak(h.getString("say"), now); return }
            if (normal == null) normal = h
        }
        if (now - lastSayAt < roadMs(BUDGET_MS)) return                 // 말 예산: 방금 말했다 — 다음 틱에 다시 본다(거리 문장은 그때 새로 온다)
        // 지나치기 전에 말해야 하는 것부터: 행동 거리 안 위험과 교량 결빙(1.5 km 앞부터) 중 더 가까운 것. 마감이 없는 위험성은 맨 뒤
        val zs = v.optJSONArray("zones")
        val bridge = zs?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { z -> z.has("say") && z.getString("id") !in zoneSaid } }
        if (bridge != null && (normal == null || bridge.optDouble("ahead") <= normal.optDouble("ahead"))) {
            zoneSaid += bridge.getString("id"); speak(bridge.getString("say"), now); return
        }
        normal?.let { spoken[it.getString("id")] = it.optInt("level", 1); speak(it.getString("say"), now); return }
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
            speak(adv.getString("say"), now); return
        }
        val rain = ahead?.optJSONObject("rain")
        if (rain != null && rain.has("say") && rainSaid.add(rain.optString("id"))) {
            speak(rain.getString("say"), now); return      // 창문 상태는 Vehicle 창문 신호로 확장(에뮬레이터 창문 닫힘)
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

    /** 말하고 예산 시계를 맞춘다 */
    private fun speak(text: String, now: Long) { s.say(text); lastSayAt = now }

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
                val d = LinkHub.kmText(lang, r.optDouble("ahead", 0.0))
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
        "SIREN" -> t[R.string.hz_siren]; else -> h.optString("label").ifEmpty { h.optString("type") }   // 새 종류(충전기 고장·대기)는 허브가 준 이름
    }

    companion object {
        const val LINK_START = 18000.0   // 시연 출발점: 경부고속도로 하행 18km(기흥휴게소 9km 전)
        const val CRASH_ASK_MS = 30_000L // "괜찮으세요?" 10초 + 카운트다운 20초
        // 말 줄이기(설계 10-11). 시간은 도로 시간(시연은 DEMO_SCALE 배 압축)
        const val BUDGET_MS = 30_000L          // '조용할 때만' 등급: 30초에 한 문장
        const val RISK_GAP_MS = 600_000L       // 위험성 '높음' 단독 안내: 10분에 한 번
        const val FATIGUE_GAP_MS = 1_800_000L  // 휴식 권장: 30분에 한 번
        val URGENT = setOf("SIREN", "INCIDENT", "HARD_BRAKE")   // 바로 말함
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
    }
}
