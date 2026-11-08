package ai.meeos.tago.app

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.emergency.EmergencyController
import ai.meeos.tago.navi.NaviBridge
import ai.meeos.tago.pickup.PickupController
import ai.meeos.tago.pickup.RoutineScheduler
import ai.meeos.tago.road.RoadLinkController
import ai.meeos.tago.ui.BinColor
import ai.meeos.tago.ui.CardLine
import ai.meeos.tago.ui.CardTone
import ai.meeos.tago.ui.CardUi
import ai.meeos.tago.ui.MarkerKind
import ai.meeos.tago.ui.MemberUi
import ai.meeos.tago.ui.RoadStripUi
import ai.meeos.tago.ui.StripMarker
import ai.meeos.tago.ui.Panel
import ai.meeos.tago.ui.QuestionUi
import ai.meeos.tago.ui.RegionUi
import ai.meeos.tago.ui.TagoUiState
import ai.meeos.tago.ui.VoiceUi
import ai.meeos.tago.voice.AnswerParser
import android.content.Context
import org.json.JSONObject

/**
 * 앱의 머리: 컨트롤러(픽업·함께 가기·응급·루틴)를 잇고, 1초 틱을 돌리고, 운전자 말을 나눠 주고, 화면 상태(TagoUiState)를 만든다.
 * 화면은 onUiState 로 받은 상태만 그린다. Android 생명주기·인텐트는 MainActivity 가 맡는다.
 */
class AppController(context: Context, val s: Services, private val onMapUrl: (String?) -> Unit, dial: (String) -> Boolean) {
    val pickup = PickupController(s, ::setMap)
    val road = RoadLinkController(context, s, ::setMap)
    val emergency = EmergencyController(s, pickup, dial)
    val routine = RoutineScheduler(s, pickup) { emergency.active }
    /** 연구 기록(경고 반응 로그). 빌드 플래그 STUDY_LOG 가 꺼진 빌드(출품 release)에서는 화면에도 없고 아무것도 기록하지 않는다 */
    val study = ai.meeos.tago.study.ResponseLog(context, s)
    var studyOpen = false
        private set
    private var studyAge = ""
    private var studyYears = 5
    private var studyCar = ""
    /** 차속 신호가 없는 차에서 동의했을 때 위치 권한(GPS 속도)을 묻는다 — MainActivity 가 잇는다 */
    var requestGps: () -> Unit = {}

    var onUiState: (TagoUiState) -> Unit = {}
    /** 다른 앱(순정 내비 등)이 앞에 있으면 주기 렌더를 쉰다(판정·전송은 계속) */
    var paused = false
    var devPanel = false
        private set
    private var toast: String? = null
    private var transientHeard: String? = null       // 시연 인텐트로 넣은 말을 잠깐 보여 준다
    private var tickCount = 0
    private var lastTick = System.currentTimeMillis()
    private val prefs = context.getSharedPreferences(NaviBridge.PREFS, Context.MODE_PRIVATE)

    private val t get() = s.texts

    fun start() {
        s.render = ::render
        s.toast = ::showToast
        pickup.nearby.emergencyActive = { emergency.active }
        s.voice.shouldListen = { s.dialog.awaiting }
        s.voice.beforeListen = { road.releaseMicForListening() }
        s.voice.onListeningChanged = { render() }
        s.voice.onUtterance = { handleUtterance(it) }
        s.log.onChange = { if (devPanel) render() }
        Region.of(prefs.getString("region", null))?.let { applyRegion(it) }
        s.main.postDelayed(tick, 1000)
        if (BuildConfig.STUDY_LOG) {
            road.study = study
            s.voice.onSpeakingChanged = { study.speaking(it) }
            study.restore()
        }
        render()
    }

    fun stop() {
        s.main.removeCallbacks(tick)
        road.stop()
        study.close()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = (now - lastTick) / 1000.0
            lastTick = now
            tickCount++
            s.car.readTick()                                  // 차 신호(기어·와이퍼·좌석·벨트·도어)는 1초마다 읽는다
            pickup.tick(dt, tickCount)
            road.manageSiren()
            road.tick(dt, s.voice.speaking)
            study.tick(road.view, road.active)                // 연구 기록: 1 Hz 표본(경고 전 30초 고리 + 진행 중 경고 묶음)
            if (studyOpen && (s.car.driving || road.active || pickup.active || emergency.active)) studyOpen = false   // 설정 화면은 정차 중·대기에서만
            if (BuildConfig.PICKUP && tickCount % 10 == 0) routine.check()   // 요일 루틴은 픽업 기능
            // 화면이 가려져 있거나 말하는 중이면 그리지 않는다(판정·전송은 계속). 응급 카드는 경과 시간 때문에 매초
            if (!paused && (emergency.active || !s.voice.speaking)) render()
            s.main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ 운전자 입력

    /** 운전자 말(온디바이스 STT 또는 시연 인텐트). 응급 > 주문 받아쓰기 > 휴게소 > 함께 가기 > 가족 묶기 > 근처 > 예/아니오 */
    fun handleUtterance(text: String) {
        val u = AnswerParser.Utterance(text)
        if (BuildConfig.EMERGENCY && AnswerParser.isEmergency(u)) return emergency.start()
        if (s.dialog.pending?.onText != null) { s.dialog.text(text); return }
        if (road.active && s.dialog.pending?.kind == Question.Kind.CHARGER && AnswerParser.isBroken(u) && AnswerParser.yesNo(u) != false) return s.dialog.answer(true)
        if (road.active && !s.dialog.awaiting && AnswerParser.isChargerReport(u)) return road.chargeVoiceReport()
        if (road.active && !s.dialog.awaiting && AnswerParser.isRestStop(u)) return road.restTogether()
        if (BuildConfig.ROAD_LINK && !road.active && !pickup.active && AnswerParser.isStartRoad(u)) return road.start(pickup.active)
        if (road.active && !road.hasGroup && AnswerParser.isGroup(u)) return road.startGroup()
        if (BuildConfig.PICKUP && AnswerParser.isToilet(u)) return pickup.nearby.ask("toilet")
        if (BuildConfig.PICKUP && AnswerParser.isDriveThru(u)) return pickup.nearby.ask("drivethru")
        if (BuildConfig.PICKUP && AnswerParser.isTakeout(u)) return pickup.nearby.ask("takeout")
        if (!s.dialog.awaiting) return
        when (AnswerParser.yesNo(u)) {
            true -> s.dialog.answer(true)
            false -> s.dialog.answer(false)
            null -> s.log.d("인식했지만 답이 아님: $text")
        }
    }

    /** 시연: 에뮬레이터에는 실제 마이크 입력이 없어, 인식된 말을 인텐트로 넣어 같은 처리 경로를 탄다 */
    fun demoUtterance(text: String) {
        transientHeard = text
        s.main.postDelayed({ if (transientHeard == text) { transientHeard = null; render() } }, 4000)
        render()
        handleUtterance(text)
    }

    fun answer(yes: Boolean) = s.dialog.answer(yes)

    /** 픽업 시작. 응급 중이거나 픽업이 없는 판(길벗)이면 시작하지 않는다(false) */
    fun startPickup(member: Member): Boolean {
        if (!BuildConfig.PICKUP || emergency.active) return false
        pickup.start(member)
        return true
    }
    fun startRoad() = road.start(pickup.active)
    fun roadPrimary() { if (road.hasGroup) road.restTogether() else road.startGroup() }
    fun toggleDevPanel() { devPanel = !devPanel; render() }

    // ------------------------------------------------------------ 연구 기록(동의·참가자 정보)

    fun openStudy() { if (BuildConfig.STUDY_LOG && !s.car.driving && !road.active && !pickup.active && !emergency.active) { studyOpen = true; render() } }
    fun closeStudy() { studyOpen = false; render() }
    fun studyAge(id: String) { if (id in ai.meeos.tago.study.ResponseLog.AGE_BANDS) { studyAge = id; render() } }
    fun studyYears(d: Int) { studyYears = (studyYears + d).coerceIn(0, 70); render() }
    fun studyCar(id: String) { if (id in ai.meeos.tago.study.ResponseLog.CAR_TYPES) { studyCar = id; render() } }
    fun studyAgree() {
        if (studyAge.isEmpty() || studyCar.isEmpty()) return
        study.start(ai.meeos.tago.study.ResponseLog.Profile(studyAge, studyYears, studyCar, true, System.currentTimeMillis(), "screen"))
        if (study.needsGps()) requestGps()
        studyOpen = false
        showToast(t[R.string.toast_study_started])
    }
    /** 시연 훅 study_sim: 동의 화면을 거치지 않고 세션을 연다(에뮬레이터 검증용 — 머리 줄의 consent_source 가 demo_hook) */
    fun studyDemo(age: String, years: Int, car: String) {
        if (age !in ai.meeos.tago.study.ResponseLog.AGE_BANDS || car !in ai.meeos.tago.study.ResponseLog.CAR_TYPES) { s.log.d("study_sim 형식: age=30s,years=8,car=sedan"); return }
        study.start(ai.meeos.tago.study.ResponseLog.Profile(age, years.coerceIn(0, 70), car, true, System.currentTimeMillis(), "demo_hook"))
        render()
    }
    fun studyStop() { study.stop(); studyOpen = false; showToast(t[R.string.toast_study_stopped]) }
    fun studyUpload(id: String) { study.setUpload(id == "on"); render() }
    fun studyToast(text: String) = showToast(text)

    private fun studyUi(): ai.meeos.tago.ui.StudyUi {
        val ages = ai.meeos.tago.study.ResponseLog.AGE_BANDS.map { RegionUi(it, t[ageLabel(it)]) }
        val cars = ai.meeos.tago.study.ResponseLog.CAR_TYPES.map { RegionUi(it, t[carLabel(it)]) }
        val sid = study.sessionId?.take(6) ?: ""
        val status = if (!study.active) null else if (study.warningsOpen > 0) t[R.string.sl_study_on_open, sid, study.warningsDone, study.warningsOpen] else t[R.string.sl_study_on, sid, study.warningsDone]
        return ai.meeos.tago.ui.StudyUi(
            active = study.active,
            consentTitle = t[R.string.study_consent_title],
            consentLines = listOf(R.string.study_consent_purpose, R.string.study_consent_what, R.string.study_consent_not, R.string.study_consent_where, R.string.study_consent_stop).map { t[it] },
            ageTitle = t[R.string.study_age_title], ageRows = listOf(ages.take(4), ages.drop(4)), ageSel = studyAge,
            yearsTitle = t[R.string.study_years_title], yearsLabel = t[R.string.study_years, studyYears],
            carTitle = t[R.string.study_car_title], carItems = cars, carSel = studyCar,
            agreeLabel = t[R.string.btn_study_agree], agreeEnabled = studyAge.isNotEmpty() && studyCar.isNotEmpty(), declineLabel = t[R.string.btn_study_decline],
            status = status, exportLabel = t[R.string.btn_study_export], uploadTitle = t[R.string.study_upload_title],
            uploadItems = listOf(RegionUi("off", t[R.string.study_upload_off]), RegionUi("on", t[R.string.study_upload_on])), uploadSel = if (study.uploadOn) "on" else "off",
            uploadNote = if (study.uploadUrl.isEmpty()) t[R.string.study_upload_no_url] else t[R.string.study_upload_note],
            stopLabel = t[R.string.btn_study_stop], closeLabel = t[R.string.btn_study_close],
        )
    }

    private fun ageLabel(id: String) = when (id) {
        "10s" -> R.string.study_age_10s; "20s" -> R.string.study_age_20s; "30s" -> R.string.study_age_30s; "40s" -> R.string.study_age_40s
        "50s" -> R.string.study_age_50s; "60s" -> R.string.study_age_60s; else -> R.string.study_age_70plus
    }
    private fun carLabel(id: String) = when (id) { "sedan" -> R.string.study_car_sedan; "suv" -> R.string.study_car_suv; "van_truck" -> R.string.study_car_van_truck; else -> R.string.study_car_other }

    /** 지역을 바꾼다(대기 화면에서만): 가족 목록·버튼 글·음성 언어를 그 지역 것으로 */
    fun setRegion(id: String) {
        val r = Region.of(id) ?: return
        if (pickup.active || road.active || emergency.active || s.dialog.awaiting || r == s.region) return   // 질문 중에 바꾸면 말과 글이 섞인다
        applyRegion(r)
        prefs.edit().putString("region", r.id).apply()
        s.say(t[R.string.say_region_switched, r.label])
        showToast(t[R.string.toast_region, t.ro(r.label)])
    }

    private fun applyRegion(r: Region) {
        s.region = r
        s.texts.setRegion(r)
        s.voice.setRegion(r)
        render()
    }

    private fun showToast(text: String) {
        toast = text
        render()
        s.main.postDelayed({ if (toast == text) { toast = null; render() } }, 3000)
    }

    // ------------------------------------------------------------ 화면 상태

    fun render() = onUiState(build())

    /** 운전자가 한눈에 볼 것만: 지금 상태 한 줄, 보조 한 줄, 이 상태에서 누를 버튼 (docs/ui-design.md §6) */
    private fun build(): TagoUiState {
        val region = s.region
        val m = pickup.member
        val st = pickup.lastState
        val tName = pickup.targetName()
        val best = st?.optJSONObject("proposal")?.optJSONObject("best")
        val q = s.dialog.pending
        val emg = emergency.active
        val cv = road.view
        val hzList = cv?.optJSONArray("hazards")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val topHz = hzList?.firstOrNull { it.has("say") && it.optString("type") == "SIREN" }   // 긴급차량 양보가 가장 먼저
            ?: hzList?.firstOrNull { it.has("say") && it.optString("type") in setOf("INCIDENT", "BLOCKED", "HARD_BRAKE", "DEBRIS") }
        val adv = cv?.optJSONObject("ahead")?.optJSONObject("advice")
        val rainAhead = cv?.optJSONObject("ahead")?.optJSONObject("rain")
        val nb = pickup.nearby
        val offer = nb.offer
        val status = pickup.status
        var showMembers = false
        var showNavi = false
        val studyPanel = studyOpen && !emg && !road.active && !pickup.active
        val chargePanel = road.active && !emg && road.survey != null && !s.car.driving
        val (head, sub) = when {
            emg -> t[R.string.hl_emergency] to t[R.string.sl_emergency, region.emergencyNumber]
            studyPanel && study.active -> t[R.string.hl_study_on] to (studyUi().status ?: "")
            studyPanel -> t[R.string.hl_study_consent] to t[R.string.sl_study_consent]
            chargePanel -> t[R.string.hl_charge_survey] to t[R.string.sl_charge_survey]
            road.active && q?.kind == Question.Kind.CHARGER -> t[R.string.hl_charger_ask] to t[R.string.sl_charger_ask]
            road.active && q?.kind == Question.Kind.REST -> t[R.string.hl_rest_ask] to
                (cv?.optJSONObject("group")?.optJSONObject("rest")?.let { "${it.optString("by")} · ${it.optString("name")} ${t.km(it.optInt("dist"))}" } ?: "")
            road.active && topHz?.optString("type") == "SIREN" -> {   // 구급차 위치 신호(beacon) · 길 비킴만(yieldOnly) · 마이크
                val bc = topHz.optBoolean("beacon"); val yo = topHz.optBoolean("yieldOnly")
                t[if (bc) R.string.hl_siren_ev else if (yo) R.string.hl_siren_yield else R.string.hl_siren, t.km(topHz.optInt("ahead"))] to
                    ((if (bc) t[R.string.sl_siren_beacon] else if (yo) t[R.string.sl_siren_yield, topHz.optInt("yielded")] else t[R.string.sl_siren, topHz.optInt("cars")]) +
                        if (topHz.isNull("eta")) "" else t[R.string.sl_siren_eta, maxOf(10, (topHz.optInt("eta") + 5) / 10 * 10)])
            }
            road.active && topHz != null -> t[R.string.hl_hazard, t.km(topHz.optInt("ahead")), road.hazardLabel(topHz)] to
                (topHz.optJSONArray("by")?.optString(0)?.takeIf { it.isNotEmpty() }?.let { n -> t[R.string.sl_hazard_by, n] + if (topHz.optInt("anon") > 0) t[R.string.sl_hazard_more, topHz.optInt("anon")] else "" }
                    ?: t[R.string.sl_hazard_cars, topHz.optInt("cars")])
            road.active && adv != null && adv.optInt("dist") > 0 -> t[R.string.hl_advice, adv.optInt("adviseKmh")] to t[R.string.sl_advice, t.km(adv.optInt("dist")), adv.optInt("jamKmh")]
            road.active && rainAhead != null -> t[R.string.hl_rain, maxOf(1, (rainAhead.optInt("eta") + 30) / 60)] to t[R.string.sl_rain, t.km(rainAhead.optInt("dist")), rainAhead.optInt("cars")]
            road.active -> t[R.string.hl_road_on] to (t[R.string.sl_road_nearby, cv?.optInt("nearby") ?: 0] +
                (cv?.optJSONObject("restNext")?.let { t[R.string.sl_road_next, it.optString("name"), t.km(it.optInt("dist"))] } ?: ""))
            q?.kind == Question.Kind.ROUTINE -> routine.asked.let { r -> t[R.string.hl_routine, r?.member ?: "", r?.label ?: ""] to t[R.string.sl_routine] }
            nb.orderStage == "listen" -> t[R.string.hl_order_listen] to
                (if (nb.offerKind == "takeout") t[R.string.sl_order_listen_takeout, nb.orderPlace?.optString("name") ?: ""] else t[R.string.sl_order_listen_drivethru, nb.orderPlace?.optString("name") ?: ""])
            nb.orderStage == "confirm" -> t[R.string.hl_order_confirm] to nb.orderSummary()
            q?.kind == Question.Kind.DRIVETHRU && nb.offerKind == "takeout" -> t[R.string.hl_takeout_ask] to
                (if ((offer?.optInt("lateSec") ?: 0) < 45) t[R.string.sl_takeout_ontime, offer?.optString("name") ?: ""] else t[R.string.sl_takeout_late, offer?.optString("name") ?: "", t.mins(offer?.optInt("lateSec") ?: 0)])
            q?.kind == Question.Kind.DRIVETHRU -> t[R.string.hl_drivethru_ask] to t[R.string.name_dist, offer?.optString("name") ?: "", offer?.optInt("dist") ?: 0]
            q?.kind == Question.Kind.PARKING -> (if (nb.offerKind == "toilet") t[R.string.hl_toilet_ask] else t[R.string.hl_parking_ask]) to t[R.string.name_dist, offer?.optString("name") ?: "", offer?.optInt("dist") ?: 0]
            pickup.parkedWaiting && m != null -> t[R.string.hl_parked_waiting] to t[R.string.sl_parked_waiting, t.subj(m.name)]
            m == null || !pickup.active -> {
                showMembers = !road.active
                if (!BuildConfig.PICKUP) t[R.string.hl_idle_gilbeot] to t[R.string.sl_idle_gilbeot]
                else if (BuildConfig.ROAD_LINK) t[R.string.hl_idle_road] to t[R.string.sl_idle_road] else t[R.string.hl_idle] to t[R.string.sl_idle]
            }
            status == "dropped" -> { showMembers = true; t[R.string.hl_dropped, m.name] to t[R.string.sl_dropped] }
            status == "boarded" && !pickup.riderConfirmed() -> t[R.string.hl_boarded_detected, t.seatLabel(pickup.boardedSeat ?: region.riderSeat)] to t[R.string.sl_boarded_detected, m.name]
            status == "boarded" -> t[R.string.hl_boarded, m.name] to
                t[R.string.sl_boarded, t.seatLabel(pickup.boardedSeat ?: region.riderSeat), if (pickup.beltOf(pickup.boardedSeat ?: region.riderSeat) == true) t[R.string.belt_on] else t[R.string.belt_off]]
            status == "arrived" && tName != null -> t[R.string.hl_arrived, tName] to t[R.string.sl_arrived, m.name, pickup.curbDoorLabel()]
            status in setOf("confirmed", "arrived") && tName != null -> {
                showNavi = true
                val eta = st?.optJSONObject("eta")
                t[R.string.hl_enroute, tName] to when {
                    pickup.replanPending() -> t[R.string.sl_enroute_replan, m.name]
                    eta != null && st?.optBoolean("leaveNow") == true -> t[R.string.sl_enroute_leave, t.mins(eta.optInt("carSec")), m.name]
                    eta != null -> t[R.string.sl_enroute_eta, t.mins(eta.optInt("carSec")), m.name, t.mins(eta.optInt("walkSec"))]
                    else -> t[R.string.sl_enroute_told, m.name]
                }
            }
            q?.kind == Question.Kind.POINT && best != null -> t[R.string.hl_proposal, best.optString("name")] to
                t[R.string.sl_proposal, m.name, t.mins(best.optInt("walkSec")), t.mins(best.optInt("carSec")), best.optInt("stopLimit") / 60]
            else -> t[R.string.hl_waiting_rider, m.name] to t[R.string.sl_waiting_rider]
        }

        // 연구 기록: 헤드라인에 띄운 경고(사고 의심·공식 안내·정체 꼬리)를 읽기만 한다 — 화면 노출 시각(t = 0 후보)
        if (study.active && road.active && !emg && q?.kind != Question.Kind.REST) {
            if (topHz != null && topHz.optString("type") != "SIREN") study.onScreen(topHz, null, head)
            else if (topHz == null && adv != null && adv.optInt("dist") > 0) study.onScreen(null, adv, head)
        }
        val members = region.members.map { MemberUi(it.name, t.memberType(it.type), memberIcon(it.type), it.type) }
        val panel: Panel = when {
            emg -> Panel.Emergency(
                card = CardUi(CardTone.SAFETY, t[R.string.emg_card_title], R.drawable.ic_medical, emergency.lines + CardLine(R.drawable.ic_timer, t[R.string.emg_elapsed, emergency.elapsedSec()])),
                dialLabel = t[R.string.btn_dial, region.emergencyNumber], endLabel = t[R.string.btn_end_emergency])
            studyPanel -> Panel.Study(studyUi())
            chargePanel -> Panel.ChargeReport(chargeUi(road.survey!!))
            road.active -> roadPanel(cv, q, topHz?.optString("id"))
            showMembers && !pickup.active -> Panel.Idle(if (BuildConfig.ROAD_LINK) t[R.string.btn_road_link] else null, if (BuildConfig.PICKUP) members else emptyList(),
                studyLabel = if (BuildConfig.STUDY_LOG && BuildConfig.ROAD_LINK) t[if (study.active) R.string.btn_study_on else R.string.btn_study_off] else null)
            else -> {
                val step = when {
                    !pickup.active || q?.kind == Question.Kind.ROUTINE -> 0
                    status == "boarded" || status == "dropped" -> 4
                    status == "arrived" -> 3
                    status == "confirmed" -> 2
                    else -> 1
                }
                val message = pickup.lastMessage?.takeIf { status in setOf("waiting_rider", "proposed", "confirmed", "arrived") }?.let { "${m?.name}: $it" }
                val card = when {
                    nb.orderStage == "placed" && status !in setOf("boarded", "dropped") -> orderCard(nb)
                    pickup.replanPending() && status in setOf("confirmed", "arrived") -> CardUi(CardTone.WARNING, null, null, listOf(CardLine(R.drawable.ic_flag, t[R.string.card_replan, pickup.replanFrom()])))
                    else -> null
                }
                Panel.Pickup(step = if (status == "dropped") 0 else step, stepLabels = listOf(t[R.string.step_link], t[R.string.step_meet], t[R.string.step_arrive], t[R.string.step_board]),
                    message = message, naviLabel = if (showNavi) t[R.string.btn_navigate] else null, card = card, members = if (showMembers) members else emptyList())
            }
        }

        val voice = when {
            s.voice.listening -> VoiceUi.LISTENING
            s.voice.speaking -> VoiceUi.SPEAKING
            else -> VoiceUi.IDLE
        }
        val voiceText = when {
            transientHeard != null -> "“$transientHeard”"
            voice == VoiceUi.LISTENING -> s.voice.heard?.let { "“$it”" } ?: q?.listenHint
            voice == VoiceUi.SPEAKING -> t[R.string.voice_speaking]
            else -> null
        }
        return TagoUiState(
            headline = head, subline = sub,
            voice = voice, voiceText = voiceText,
            question = q?.let { if (it.yesLabel != null && it.noLabel != null) QuestionUi(it.yesLabel, it.noLabel) else null },
            panel = panel,
            emergencyLabel = if (BuildConfig.EMERGENCY && !emg) t[R.string.btn_emergency] else null,
            regions = if (BuildConfig.PICKUP && !pickup.active && !road.active && !emg && !studyPanel) Region.entries.map { RegionUi(it.id, it.label) } else null,
            regionTitle = t[R.string.region_section],
            regionId = region.id,
            mapUrl = mapUrl,
            mapEmptyText = if (!BuildConfig.PICKUP) t[R.string.map_empty_gilbeot] else if (BuildConfig.ROAD_LINK) t[R.string.map_empty_road] else t[R.string.map_empty],
            toast = toast,
            devLog = if (devPanel) s.log.lines.toList() else null,
            devStatus = if (devPanel) devStatus() else null,
        )
    }

    private var mapUrl: String? = null
    /** 내장 서버가 떴는지(PC 서버면 처음부터). 뜨기 전에 미리보기 주소를 열면 WebView 에 오류 페이지가 남는다 */
    private var serverUp = !BuildConfig.EMBEDDED_SERVER

    /** MainActivity 가 내장 서버를 띄운 뒤 부른다: 대기 화면 지도를 미리보기로 채운다 */
    fun serverReady() { serverUp = true; if (mapUrl == null) setMap(null) }

    /** 대기 화면 지도: 길벗 판은 시연 경로 미리보기(경로·휴게소·사고 다발 구간만, 폴링 없음). 픽업이 있는 판은 빈 상태 그대로 */
    private fun idleMap(): String? = if (serverUp && BuildConfig.ROAD_LINK && !BuildConfig.PICKUP) "${BuildConfig.SERVER_URL}/link/road?car=1&preview=1" else null

    /** 지도 페이지: 픽업 /car/<세션>, 함께 가기 /link/…, 끝나면 null → 대기 지도(길벗은 경로 미리보기, 그 밖은 빈 상태) */
    private fun setMap(url: String?) {
        mapUrl = url ?: idleMap()
        onMapUrl(mapUrl)
        render()
    }

    /** 위험 종류 → 주의 표지 그림(지도 link.html 의 HZ[].g 와 같은 짝) */
    private fun hazardSign(type: String) = when (type) {
        "INCIDENT" -> R.drawable.hz_sign_crash; "HARD_BRAKE" -> R.drawable.hz_sign_brake; "CONGESTION" -> R.drawable.hz_sign_jam
        "ICING" -> R.drawable.hz_sign_ice; "HEAVY_RAIN" -> R.drawable.hz_sign_rain; "BLOCKED" -> R.drawable.hz_sign_blocked
        "DEBRIS" -> R.drawable.hz_sign_debris; "SMOKE" -> R.drawable.hz_sign_smoke; "DOWNHILL" -> R.drawable.hz_sign_downhill
        "SIREN" -> R.drawable.hz_sign_siren; "CHARGER_FAULT" -> R.drawable.hz_sign_charger_fault; "CHARGER_WAIT" -> R.drawable.hz_sign_charger_wait
        else -> R.drawable.hz_sign_official
    }

    private fun memberIcon(type: String) = when (type) { "child" -> R.drawable.ic_child; "senior" -> R.drawable.ic_senior; else -> R.drawable.ic_adult }

    /** 함께 가기 패널: 앞길 세로 스트립 — 가까운 거리 축(앞 [STRIP_AHEAD_M]·뒤 2km)에 내 차·위험(앞 3개)·뒤에서 오는 긴급차량·일행·다음 휴게소를 놓는다.
     *  축 밖(더 먼 앞)은 지금 안내 중인 위험과 일행만 축 맨 위에 붙인다(거리 글은 실제 값). 휴게소·다른 위험은 빼고, 헤드라인·보조 줄이 알려 준다.
     *  축 색은 500m 칸마다 앞차 평균 속도(초록·주황·빨강), 비(파랑), 위험(빨강·보라) */
    private fun roadPanel(v: JSONObject?, q: Question?, topHazardId: String?): Panel.RoadLink {
        val bins = v?.optJSONObject("ahead")?.optJSONArray("bins")?.let { arr ->
            (0 until minOf(arr.length(), STRIP_AHEAD_M / 500)).map { i ->
                val b = arr.getJSONObject(i)
                val first = b.optJSONArray("hz")?.optString(0) ?: ""
                when {
                    first in setOf("INCIDENT", "BLOCKED", "HARD_BRAKE", "DEBRIS") -> BinColor.DANGER
                    first in setOf("SMOKE", "DOWNHILL") -> BinColor.PURPLE
                    b.optInt("rain") > 0 -> BinColor.RAIN
                    b.isNull("kmh") -> BinColor.UNKNOWN
                    b.optInt("kmh") < 40 -> BinColor.SLOW
                    b.optInt("kmh") < 70 -> BinColor.MEDIUM
                    else -> BinColor.FREE
                }
            }
        } ?: emptyList()
        val markers = mutableListOf<StripMarker>()
        markers += StripMarker(0, MarkerKind.ME, R.drawable.ic_car, "", t[R.string.road_me], null)
        val hz = v?.optJSONArray("hazards")
        var shown = 0
        if (hz != null) for (i in 0 until hz.length()) {
            val h = hz.getJSONObject(i)
            if (h.optString("type") == "SIREN" && h.has("say")) {   // 뒤에서 다가오는 긴급차량(앞 위험 개수에 안 센다)
                val d = -kotlin.math.abs(h.optInt("ahead"))
                val sub = if (h.optBoolean("beacon")) (if (h.optInt("cars") > 0) t[R.string.siren_cars, h.optInt("cars")] else null) else if (h.optBoolean("yieldOnly")) t[R.string.sl_siren_yield, h.optInt("yielded")] else t[R.string.siren_cars, h.optInt("cars")]
                markers += StripMarker(d, MarkerKind.SIREN, R.drawable.ic_flag, t[R.string.behind_distance, t.km(d)], road.hazardLabel(h), sub, sign = R.drawable.hz_sign_siren)
                continue
            }
            if (!h.optBoolean("confirmed") || h.isNull("ahead") || h.optInt("ahead") <= 0 || shown >= 3) continue
            val top = h.optString("id") == topHazardId
            if (h.optInt("ahead") > STRIP_AHEAD_M && !top) continue   // 축 밖: 지금 안내 중인 위험만 맨 위에 붙인다
            shown++
            val rain = h.optString("type") == "HEAVY_RAIN" || h.optBoolean("flood")
            val kind = if (top) MarkerKind.HAZARD_TOP else MarkerKind.HAZARD
            markers += StripMarker(h.optInt("ahead"), kind, if (rain) R.drawable.ic_rain else R.drawable.ic_flag, t.km(h.optInt("ahead")), road.hazardLabel(h), t[R.string.n_cars, h.optInt("cars")],
                sign = if (h.optBoolean("flood")) R.drawable.hz_sign_rain else hazardSign(h.optString("type")))
        }
        v?.optJSONObject("group")?.optJSONArray("members")?.let { a ->
            for (i in 0 until a.length()) {
                val mb = a.getJSONObject(i)
                if (mb.optBoolean("me") || mb.isNull("gap")) continue
                val gap = mb.optInt("gap")
                val soc = if (mb.isNull("soc")) null else t[R.string.battery_pct, mb.optInt("soc")]
                val st = when (mb.optString("status")) { "stopped" -> t[R.string.member_stopped]; "slow" -> t[R.string.member_slow]; else -> null }
                val name = mb.optString("name") + if (mb.optString("kind") == "web") t[R.string.member_phone] else ""
                val distLabel = if (gap >= 0) t.km(gap) else t[R.string.behind_distance, t.km(gap)]
                markers += StripMarker(gap, MarkerKind.MEMBER, R.drawable.ic_people, distLabel, name, listOfNotNull(st, soc).joinToString(" · ").ifEmpty { null })
            }
        }
        v?.optJSONObject("restNext")?.takeIf { it.optInt("dist") <= STRIP_AHEAD_M }?.let { r -> markers += StripMarker(r.optInt("dist"), MarkerKind.REST, R.drawable.ic_cafe, t.km(r.optInt("dist")), r.optString("name"), null, sign = R.drawable.hz_sign_rest) }
        return Panel.RoadLink(
            strip = if (v == null) null else RoadStripUi(aheadM = STRIP_AHEAD_M, behindM = 2_000, bins = bins, markers = markers, aheadLabel = t[R.string.ribbon_label]),
            connecting = if (v == null) t[R.string.road_connecting] else null,
            primaryLabel = if (road.hasGroup) t[R.string.btn_rest_together] else t[R.string.btn_link_family],
            primaryIcon = if (road.hasGroup) R.drawable.ic_cafe else R.drawable.ic_people,
            endLabel = t[R.string.btn_end],
            showButtons = q?.kind != Question.Kind.REST && q?.kind != Question.Kind.CHARGER,
            // 충전소(휴게소 services) 반경 500 m 안에 서 있을 때만(허브 restHere). 질문 중·주행 중·반경 밖에서는 숨김
            chargeLabel = if (BuildConfig.ROAD_LINK && s.car.stopped && q?.kind != Question.Kind.CHARGER && road.nearCharger)
                t[R.string.btn_charger_report] else null,
            detourLabel = if (BuildConfig.ROAD_LINK && q?.kind != Question.Kind.CHARGER) road.detourLabel() else null,
        )
    }

    /** 충전기 고장 신고 1분 설문 한 화면: 지금 문항의 그림 타일(ChargeSurvey.OPTIONS 순서). 기기 번호는 숫자 타일 */
    private fun chargeUi(sv: ai.meeos.tago.road.ChargeSurvey): ai.meeos.tago.ui.ChargeReportUi {
        val key = sv.key ?: "alt"
        val ids = ai.meeos.tago.road.ChargeSurvey.OPTIONS.getValue(key)
        val tiles = ids.map { id ->
            if (key == "unit") ai.meeos.tago.ui.SurveyTileUi(id, id, null)
            else CS_LABEL.getValue("${key}_$id").let { (label, icon) -> ai.meeos.tago.ui.SurveyTileUi(id, t[label], icon) }
        }
        val q = mapOf("sym" to R.string.cs_q_sym, "unit" to R.string.cs_q_unit, "place" to R.string.cs_q_place, "broken" to R.string.cs_q_broken, "alt" to R.string.cs_q_alt)
        return ai.meeos.tago.ui.ChargeReportUi(t[R.string.cs_progress, sv.step + 1, sv.keys.size], t[q.getValue(key)], tiles,
            columns = when (key) { "unit" -> 4; "broken" -> 4; else -> 3 }, sel = sv.answers[key] ?: "",
            backLabel = if (sv.step > 0) t[R.string.btn_back] else null, skipLabel = t[R.string.btn_skip_unknown], closeLabel = t[R.string.btn_close])
    }

    private fun orderCard(nb: ai.meeos.tago.pickup.NearbyOffers): CardUi {
        val store = nb.orderPlace?.optString("name") ?: ""
        val lines = mutableListOf<CardLine>()
        nb.orderItems.forEach { (q, n) -> lines += CardLine(null, t[R.string.order_item, n, q]) }
        if (nb.offerKind == "takeout") lines += CardLine(R.drawable.ic_timer, t[R.string.order_card_ready, (nb.orderPlace?.optInt("prepSec") ?: 300) / 60, t.mins(nb.orderPlace?.optInt("carToStoreSec") ?: 0)])
        lines += CardLine(null, if (nb.offerKind == "takeout") t[R.string.order_card_pay_takeout] else t[R.string.order_card_pay_drivethru])
        return CardUi(CardTone.INFO, if (nb.offerKind == "takeout") t[R.string.order_card_takeout, store, nb.orderNo] else t[R.string.order_card_drivethru, store, nb.orderNo], R.drawable.ic_cafe, lines)
    }

    /** 개발 패널 한 줄(한국어 고정 — 운전자용 글이 아니다) */
    private fun devStatus(): String {
        val car = s.car
        val seat = s.region.riderSeat
        return "${if (car.driving) "주행" else "정차"} · ${"%.0f".format(car.speedMs * 3.6)}km/h · 와이퍼 ${car.wiper} · 상태 ${pickup.status}" +
            "\n${t.seatLabel(seat)}: ${if (car.seatOcc[seat] == true) "착석" else "빈자리"} · 벨트 ${if (pickup.beltOf(seat) == true) "체결" else "-"} · 도어 ${if (car.doorIsOpen(s.region.curbDoor)) "열림" else "닫힘"}"
    }
}

/** 앞길 스트립의 앞쪽 축 길이(m). 가까운 곳만 보인다 — 먼 위험은 헤드라인이, 다음 휴게소는 보조 줄이 알려 준다(docs/ui-design.md §5) */
private const val STRIP_AHEAD_M = 3_000

/** 1분 설문 타일(문항_답 → 글자·그림). 그림은 GPT Image 2.6 시트를 잘라 넣은 3D 렌더 그림(res/drawable-nodpi/cs_*.png, 투명 바탕) */
private val CS_LABEL = mapOf(
    "sym_no_start" to (R.string.cs_sym_no_start to R.drawable.cs_sym_no_start), "sym_screen_off" to (R.string.cs_sym_screen_off to R.drawable.cs_sym_screen_off),
    "sym_payment" to (R.string.cs_sym_payment to R.drawable.cs_sym_payment), "sym_connector" to (R.string.cs_sym_connector to R.drawable.cs_sym_connector),
    "sym_notice" to (R.string.cs_sym_notice to R.drawable.cs_sym_notice),
    "place_entrance" to (R.string.cs_place_entrance to R.drawable.cs_place_entrance), "place_middle" to (R.string.cs_place_middle to R.drawable.cs_place_middle),
    "place_end" to (R.string.cs_place_end to R.drawable.cs_place_end),
    "broken_1" to (R.string.cs_broken_1 to R.drawable.cs_broken_1), "broken_2" to (R.string.cs_broken_2 to R.drawable.cs_broken_2),
    "broken_3" to (R.string.cs_broken_3 to R.drawable.cs_broken_3), "broken_all" to (R.string.cs_broken_all to R.drawable.cs_broken_all),
    "alt_yes" to (R.string.cs_alt_yes to R.drawable.cs_alt_yes), "alt_no" to (R.string.cs_alt_no to R.drawable.cs_alt_no), "alt_wait" to (R.string.cs_alt_wait to R.drawable.cs_alt_wait),
)
