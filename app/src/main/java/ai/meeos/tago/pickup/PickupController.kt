package ai.meeos.tago.pickup

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.app.Member
import ai.meeos.tago.app.Question
import ai.meeos.tago.app.Region
import ai.meeos.tago.app.Services
import ai.pleos.playground.vehicle.constant.area.SeatValue
import org.json.JSONObject
import kotlin.math.abs

/**
 * 데리러 가기 한 번(세션)의 흐름.
 * 1) 가족을 골라 세션을 열고 링크를 보낸다 → 2) 차 진행(속도·기어·와이퍼)을 서버로 보내고 만남 지점 제안을 음성으로 묻는다
 * → 3) 확정 지점으로 순정 내비 안내 → 4) 2열 좌석·벨트·도어로 탑승/하차를 판정해 가족에게 알린다.
 * 지나침 자동 재약속, 차도 쪽 문 안내, 정차 한도(주차장 제안)도 여기서 본다. 근처 매장·주문은 NearbyOffers.
 */
class PickupController(private val s: Services, private val onMapUrl: (String) -> Unit) {
    val nearby = NearbyOffers(s, this)

    var member: Member? = null
        private set
    var sessionId: String? = null
        private set
    var status = PickupStatus.IDLE
        private set
    var lastState: JSONObject? = null
        private set
    var lastMessage: String? = null
        private set
    /** 경로 위 진행 거리(m, 출발점 기준). 서버의 target.x 와 같은 축 */
    var progress = 0.0
        private set
    var boardedSeat: SeatValue? = null
        private set
    var replanAt = 0L
        private set
    var parkedWaiting = false
    var arrivedAt = 0L
        private set
    /** 이번 픽업에서 순정 내비 안내를 시작했는지(재약속 때 내비를 다시 잡을지) */
    var naviRouted = false
    /** 시연: 에뮬레이터에 벨트 조작이 없어 인텐트(belt_sim)로 태울 좌석 벨트를 넣는다 */
    var beltSim: Boolean? = null

    private var lastProposalVersion = 0
    private var announcedConfirm = false
    private var announcedLeave = false
    private var announcedArrive = false
    private var spokenUntil = 0.0
    private var parkingAsked = false
    private var askedPointId: String? = null      // 운전자에게 음성으로 물은 지점 (확정은 이 지점으로)
    private var askedAltId: String? = null
    private var lastCarSig = ""
    private var carSentAt = 0L; private var carSeq = 0   // 차 상태 보고의 응답 대기 표시(elapsedRealtime, 0 = 응답 받음) — 응답 전에는 다음 보고를 쌓지 않는다
    private var beltAtConfirm: Map<SeatValue, Boolean> = emptyMap()   // 확정 시점의 좌석별 벨트 — 이미 매고 있던 사람을 '탑승'으로 오판하지 않게
    private var roadDoorWarnedAt = 0L                                 // 차도 쪽 문 경고(한 번 열릴 때 한 번)
    private var roadDoorWasOpen = false
    private var passSent = false                                      // 이번 확정 지점을 지나쳤다고 보냈는지
    private var confirmedSeen: String? = null                         // 마지막으로 안내한 확정 지점(자동 재약속 감지)
    private var replanAckSaid = false
    private var replanNagSaid = false
    private var aboardSaid = false                                    // 아이가 [제가 탔어요]로 본인 확인한 것을 말했는지

    private val t get() = s.texts
    private val car get() = s.car
    val active: Boolean get() = sessionId != null
    val region: Region get() = s.region

    // ------------------------------------------------------------ 시작·끝

    private var startGen = 0   // 시작 세대: reset 마다 올린다 — 늦게 온 앞 시작 요청의 응답이 뒤 시작(또는 종료)을 덮지 않게

    fun start(m: Member) {
        s.serverError?.let { s.toast(it); return }      // 내장 서버가 안 떴으면 시작하지 않고 이유를 다시 보인다
        member = m
        reset()
        val gen = startGen
        val body = JSONObject()
            .put("member", JSONObject().put("name", m.name).put("type", m.type))
            .put("region", region.id)
            .apply { if (region == Region.DAECHI) put("carDesc", "흰색 그랜저 · 12가 3456") }
        s.api.post("/api/session", body, onError = { e -> if (gen == startGen) s.log.d("세션 생성 실패: ${e.message}") }) { res ->
            if (gen != startGen) return@post   // 그사이 다시 시작했거나 끝냈다
            sessionId = res.getString("id"); carSentAt = 0L
            status = PickupStatus.WAITING_RIDER
            onMapUrl("${BuildConfig.SERVER_URL}/car/$sessionId")
            s.log.demo("가족(보호자) 보기: ${BuildConfig.PUBLIC_URL}/f/$sessionId")
            s.log.demo("링크: ${BuildConfig.PUBLIC_URL}${res.getString("link")}")
            s.say(t[R.string.say_link_sent, m.name])
            s.render()
        }
    }

    private fun reset() {
        startGen++
        sessionId = null; lastProposalVersion = 0; askedPointId = null; askedAltId = null
        announcedConfirm = false; announcedLeave = false; announcedArrive = false; spokenUntil = 0.0
        arrivedAt = 0L; parkingAsked = false; parkedWaiting = false
        nearby.reset()
        status = PickupStatus.IDLE; car.rearDoorOpened = false; progress = 0.0; lastState = null; lastMessage = null
        naviRouted = false; replanAt = 0L; replanAckSaid = false; replanNagSaid = false; aboardSaid = false; boardedSeat = null
        roadDoorWarnedAt = 0L; roadDoorWasOpen = false; passSent = false; confirmedSeen = null; beltAtConfirm = emptyMap()
        s.dialog.cancel()
    }

    // ------------------------------------------------------------ 1초마다

    fun tick(dt: Double, tickCount: Int) {
        if (car.driving) progress = (progress + car.speedMs * dt).coerceIn(0.0, maxProgress())
        // 시연 보정: 확정 지점 80m 안에서 멈추면 그 지점 길가에 정차한 것으로 본다.
        // 매장을 들르는 중에는 만날 곳으로 보정하지 않는다(매장과 만날 곳이 가까우면 도착이 겹친다)
        if (nearby.orderStage != OrderStage.PLACED) targetX()?.let { tx -> if (car.speedMs < 1f && abs(progress - tx) < CURB_SNAP_M) progress = tx }
        val id = sessionId
        // 탑승·하차는 정차 중에만 판단한다 — 그 구간(확정~하차, 정차 중)에서만 좌석·벨트·문을 매초, 달리는 동안은 5초마다
        car.readCabin = id != null && status in CABIN_WATCH && car.stopped
        if (id != null && status != PickupStatus.DROPPED) {   // 내린 뒤에는 차 위치·속도를 더 보내지 않는다
            // 바뀐 게 있을 때만 보낸다(주행 중이면 매초, 정차 중이면 2초마다 — 응답으로 아이 쪽 변화를 받아 화면·음성을 갱신)
            val g = if (car.driving) "D" else "P"
            val sig = "$g|${car.wiper}|${"%.0f".format(progress)}|${"%.1f".format(car.speedMs)}"
            val nowE = android.os.SystemClock.elapsedRealtime()
            // 앞 보고의 응답을 아직 못 받았으면 건너뛴다(서버가 느릴 때 단일 io 스레드 대기열에 1초마다 쌓이지 않게). 8초 뒤에는 다시 보낸다
            if ((sig != lastCarSig || tickCount % 2 == 0) && (carSentAt == 0L || nowE - carSentAt >= CAR_STALL_MS)) {
                lastCarSig = sig
                carSentAt = nowE
                val seq = ++carSeq
                s.api.post("/api/session/$id/car", JSONObject().put("progress", progress).put("speed", car.speedMs.toDouble()).put("gear", g).put("wiper", car.wiper),
                    onError = { e -> if (seq == carSeq) carSentAt = 0L; s.log.d("서버 오류: ${e.message}") }) { st -> if (seq == carSeq) carSentAt = 0L; onState(st) }
            }
        }
        checkBoarding()
        checkRoadDoor()
        checkPassed()
        checkReplanAck()
        checkParkingTime()
        nearby.checkStoreStop()
    }

    /** 서버 요청 — 응답은 세션 상태라 onState 로 */
    fun post(path: String, body: JSONObject) {
        s.api.post(path, body, onError = { e -> s.log.d("서버 오류: ${e.message}") }) { st -> onState(st) }
    }

    // ------------------------------------------------------------ 서버 상태 → 음성 안내

    private fun onState(st: JSONObject) {
        if (st.optString("id") != sessionId) return      // 새 픽업을 시작한 뒤 도착한 지난 세션 응답은 버린다
        runCatching { applyState(st) }.onFailure { e -> s.log.d("상태 처리 실패: ${e.message}") }
        s.render()
    }

    private fun applyState(st: JSONObject) {
        lastState = st
        val m = member ?: return
        val serverStatus = st.optString("status")
        if (status !in RIDER_ON_BOARD) status = serverStatus
        val confirmed = st.optString("confirmed").takeIf { it.isNotEmpty() && it != "null" }
        if (confirmed == null && announcedConfirm) {
            // 아이가 '화장실 급해요'를 눌러 만남 지점을 다시 정하는 중: 확정·도착 안내를 처음부터
            announcedConfirm = false; announcedLeave = false; announcedArrive = false
            arrivedAt = 0L; parkingAsked = false; parkedWaiting = false
        }
        // 아이 메시지를 먼저 읽는다(예: "화장실이 급해요" → 그다음 새 만남 지점을 묻는다)
        val ms = st.optJSONArray("messages")
        if (ms != null) for (i in 0 until ms.length()) {
            val msg = ms.getJSONObject(i)
            if (msg.getDouble("t") <= spokenUntil) continue
            spokenUntil = msg.getDouble("t")
            val text = msg.getString("text")
            lastMessage = text
            if (parkedWaiting) {
                parkedWaiting = false
                s.say(t[R.string.say_message_and_back, m.name, text, destination(pointName(st, confirmed ?: ""))])
                routeToMeetingPoint()
            } else {
                s.say(t[R.string.say_message, m.name, text])
                // "2분만 기다려 주세요"처럼 늦게 나오면, 그 시간 안에 들를 수 있는 가는 길 포장 주문을 제안
                val late = Regex("""(\d+)\s*분만|give me (\d+) min""", RegexOption.IGNORE_CASE).find(text)
                if (late != null && confirmed != null && nearby.canSuggest() && status == PickupStatus.CONFIRMED) {
                    val n = late.groupValues[1].ifEmpty { late.groupValues[2] }
                    nearby.suggestTakeout(t[R.string.lead_late_minutes, m.name, n])
                }
            }
        }
        val version = st.optInt("proposalVersion")
        if (confirmed == null && version > lastProposalVersion) {
            lastProposalVersion = version
            val p = st.getJSONObject("proposal")
            askedPointId = p.optJSONObject("best")?.optString("id")
            askedAltId = p.optJSONObject("alt")?.optString("id")
            askPoint(t[R.string.say_propose, p.optString("speech", p.getString("reason"))])
            p.optJSONObject("alt")?.optString("name")?.let { s.log.d("대안: $it") }
        }
        if (confirmed != null && announcedConfirm && confirmed != confirmedSeen && st.optString("confirmedBy") == "auto") {
            // 지나친 지점 대신 차가 다시 정한 지점: 묻지 않고 알린 뒤 내비를 새 지점으로
            confirmedSeen = confirmed; passSent = false
            replanAt = System.currentTimeMillis(); replanAckSaid = false; replanNagSaid = false
            announcedLeave = true                         // 재약속 문장에 '지수에게도 알렸습니다'가 들어 있어 '지금 나오세요' 안내는 다시 말하지 않는다
            announcedArrive = false; arrivedAt = 0L; parkingAsked = false
            val prev = st.optJSONObject("replan")?.optString("from").orEmpty()
            s.say(t[R.string.say_replan, prev, destination(pointName(st, confirmed)), m.name])
            if (naviRouted) routeToMeetingPoint()         // 순정 내비로 안내 중이었을 때만 새 지점으로 다시 안내(아니면 Tago 화면을 그대로 둔다)
        }
        if (replanAt != 0L && st.optJSONObject("replan")?.optBoolean("ack") == true && !replanAckSaid) {
            replanAckSaid = true
            s.say(t[R.string.say_replan_ack, t.subj(m.name)])
        }
        if (status == PickupStatus.BOARDED && st.optJSONObject("boarding")?.optBoolean("riderConfirmed") == true && !aboardSaid) {
            aboardSaid = true
            s.say(t[R.string.say_rider_confirmed, t.subj(m.name)])
        }
        if (confirmed != null && !announcedConfirm) {
            announcedConfirm = true
            confirmedSeen = confirmed; passSent = false
            beltAtConfirm = Region.REAR_SEATS.associateWith { beltOf(it) == true }
            car.rearDoorOpened = false                   // 확정 이후의 도어 열림만 탑승으로 본다
            s.dialog.cancel()
            val dest = destination(pointName(st, confirmed))
            s.say(if (st.optString("confirmedBy") == "driver") t[R.string.say_confirmed_driver, dest, m.name]
                else t[R.string.say_confirmed_rider, t.subj(m.name), dest])
        }
        val eta = st.optJSONObject("eta")
        if (confirmed != null && status == PickupStatus.CONFIRMED && nearby.canSuggest() && eta != null && eta.optInt("walkSec") - eta.optInt("carSec") > TAKEOUT_SLACK_S) {
            // 대치동은 가는 길 포장, 해외는 드라이브스루(지역 데이터에 포장 매장이 없다)
            nearby.suggestTakeout(t[R.string.lead_walk_slower, m.name, t.mins(eta.optInt("walkSec") - eta.optInt("carSec"))], if (region == Region.DAECHI) "takeout" else "drivethru")
        }
        if (st.optBoolean("leaveNow") && !announcedLeave) {
            announcedLeave = true
            s.say(t[R.string.say_leave_now, m.name])
        }
        if (serverStatus == PickupStatus.ARRIVED && !announcedArrive) {
            announcedArrive = true
            arrivedAt = System.currentTimeMillis()
            s.say(t[R.string.say_arrived, pointName(st, confirmed ?: ""), m.name, curbDoorLabel()])
        }
    }

    /** 만남 지점 질문: 같은 예/아니오가 버튼과 음성으로 들어온다 */
    private fun askPoint(speech: String) {
        s.dialog.ask(Question(Question.Kind.POINT, t[R.string.btn_yes_go], t[R.string.btn_other], t[R.string.listen_point], onAnswer = ::answerPoint), speech)
    }

    private fun answerPoint(yes: Boolean) {
        val id = sessionId ?: return
        val asked = askedPointId ?: return
        if (yes) {
            post("/api/session/$id/confirm", JSONObject().put("pointId", asked))
        } else {
            val alt = askedAltId
            if (alt != null) post("/api/session/$id/choose", JSONObject().put("pointId", alt).put("by", "driver"))
            else {
                s.say(t[R.string.say_no_alternative])
                post("/api/session/$id/confirm", JSONObject().put("pointId", asked))
            }
        }
    }

    // ------------------------------------------------------------ 순정 내비

    /** 합의한 만남 지점(길가 정차점)을 목적지로 순정 내비 안내를 요청한다 */
    fun routeToMeetingPoint() {
        val target = lastState?.optJSONObject("target") ?: return
        if (lastState?.optJSONObject("via") != null) sessionId?.let { post("/api/session/$it/via", JSONObject()) }   // 경유지(매장)를 들렀으니 지운다
        val curb = target.getJSONArray("curb")
        val name = target.getString("name")
        if (s.navi.routeTo(curb.getDouble(0), curb.getDouble(1), t[R.string.navi_poi_meeting, name], "만남 지점 $name")) { naviRouted = true; s.say(t[R.string.say_navi_started, name]) }   // 순정 내비로 넘긴 사실을 한 문장(11-14)
    }

    /** 만날 곳을 목적지로, 매장을 첫 경유지로 */
    fun routeWithStop(stop: JSONObject?) {
        val target = lastState?.optJSONObject("target") ?: return
        val curb = target.getJSONArray("curb")
        if (s.navi.routeVia(stop, curb.getDouble(0), curb.getDouble(1), t[R.string.navi_poi_meeting, target.getString("name")], schoolZone = member?.type == "child")) naviRouted = true
    }

    // ------------------------------------------------------------ 탑승·하차·문

    fun beltOf(seat: SeatValue): Boolean? = if (seat == region.riderSeat && beltSim != null) beltSim else car.seatBelt[seat]

    /** 탑승/하차 판정 — 2열 세 좌석을 모두 본다. 실차: 확정 뒤 새로 잠긴 벨트(그 좌석 착석) / 에뮬레이터: 착석 값이 고정이라 2열 문 열림→모두 닫힘.
     *  탄 좌석은 벨트가 새로 잠긴 좌석, 없으면 태울 좌석(착석일 때), 없으면 착석한 첫 좌석 */
    private fun checkBoarding() {
        val id = sessionId ?: return
        val m = member ?: return
        val stopped = car.stopped
        val newlyBelted = Region.REAR_SEATS.filter { beltOf(it) == true && beltAtConfirm[it] == false && car.seatOcc[it] != false }
        val doorCycle = car.rearDoorOpened && !car.rearDoorOpen()
        if ((status == PickupStatus.ARRIVED || (status == PickupStatus.CONFIRMED && stopped)) && stopped && (newlyBelted.isNotEmpty() || (doorCycle && Region.REAR_SEATS.any { car.seatOcc[it] == true }))) {
            val seat = newlyBelted.firstOrNull { it == region.riderSeat } ?: newlyBelted.firstOrNull()
                ?: region.riderSeat.takeIf { car.seatOcc[it] == true } ?: Region.REAR_SEATS.first { car.seatOcc[it] == true }
            status = PickupStatus.BOARDED
            boardedSeat = seat
            car.rearDoorOpened = false
            post("/api/session/$id/boarding", JSONObject().put("state", PickupStatus.BOARDED).put("seat", seat.name).put("seatLabel", t.seatLabel(seat)).put("belt", beltOf(seat) == true))
            s.say(t[R.string.say_boarded, t.seatLabel(seat), m.name])
            if (naviRouted) { s.navi.cancelPickupRoute(); naviRouted = false }   // 탔으니 만날 곳 안내는 끝(이 앱이 넘긴 것만 — 사용자가 잡은 목적지는 그대로, 11-14)
            s.render()
        } else if (status == PickupStatus.BOARDED && !car.driving && car.rearDoorOpen() && (boardedSeat?.let { car.seatOcc[it] } == false || progress > maxProgress() - DROP_END_MARGIN_M)) {
            // 하차: 정차 후 2열 문 열림 + 탄 좌석이 빔(실차) / 에뮬레이터는 착석 값이 고정이라 경로 끝(도곡로 동쪽) 도착으로 대신
            status = PickupStatus.DROPPED
            val seat = boardedSeat ?: region.riderSeat
            post("/api/session/$id/boarding", JSONObject().put("state", PickupStatus.DROPPED).put("seat", seat.name).put("seatLabel", t.seatLabel(seat)))
            s.say(t[R.string.say_dropped, m.name])
            s.render()
        }
    }

    /** 차도 쪽 문 안내: 픽업 중 길가에 선 채 2열 차도 쪽 문이 열리면 인도 쪽 문으로 타라고 한 번 말한다(주차장에서 기다릴 때는 말하지 않음).
     *  다가오는 차에 대한 경고는 순정 '안전 하차 경고'가 맡는다 — Tago는 어느 문으로 탈지를 미리(아이 화면) 그리고 이때 한 번 더 안내한다 */
    private fun checkRoadDoor() {
        val m = member ?: return
        val open = car.doorIsOpen(region.roadDoor)
        val wasOpen = roadDoorWasOpen
        roadDoorWasOpen = open
        if (!open || wasOpen || sessionId == null || parkedWaiting) return
        if (status !in CABIN_WATCH || car.speedMs >= 1f) return
        if (System.currentTimeMillis() - roadDoorWarnedAt < ROAD_DOOR_REPEAT_MS) return
        roadDoorWarnedAt = System.currentTimeMillis()
        val roadSide = if (region.leftHandTraffic) t[R.string.side_right] else t[R.string.side_left]
        s.say(if (status == PickupStatus.BOARDED) t[R.string.say_exit_curb_door, curbSideLabel()] else t[R.string.say_board_curb_door, m.name, curbSideLabel(), roadSide])
        s.log.d("차도 쪽 문 경고")
    }

    /** 자동 재약속 뒤 30초 안에 아이가 [확인했어요]를 누르지 않으면 운전자에게 한 번 알린다(아이가 옛 지점에서 기다릴 수 있다) */
    private fun checkReplanAck() {
        val m = member ?: return
        if (replanAt == 0L || replanAckSaid || replanNagSaid || status !in EN_ROUTE) return
        if (lastState?.optJSONObject("replan")?.optBoolean("ack") == true) return
        if (System.currentTimeMillis() - replanAt < REPLAN_NAG_MS) return
        replanNagSaid = true
        s.say(t[R.string.say_replan_nag, t.subj(m.name)])
    }

    /** 지나침: 확정 지점에서 서지 못하고(2 m/s 넘게) 80m 넘게 지나가면 서버에 알려 다음 지점으로 다시 정한다.
     *  80m는 '그 안에서 멈추면 그 지점에 정차한 것으로 보는' 시연 보정 범위 */
    private fun checkPassed() {
        val id = sessionId ?: return
        if (passSent || status != PickupStatus.CONFIRMED || nearby.orderStage == OrderStage.PLACED) return
        val tx = targetX() ?: return
        if (car.driving && car.speedMs > 2f && progress > tx + CURB_SNAP_M) {
            passSent = true
            s.log.d("만남 지점을 지나침 — 다시 정함")
            post("/api/session/$id/passed", JSONObject())
        }
    }

    /** 근처 주차장: 도착 후 정차 한도가 30초 남으면 한 번 묻는다 */
    private fun checkParkingTime() {
        if (arrivedAt == 0L || parkingAsked || s.dialog.awaiting || status != PickupStatus.ARRIVED) return
        if (nearby.emergencyActive()) return      // 포장·드라이브스루를 이미 제안했어도 주차장 안내는 따로 한다
        val waited = (System.currentTimeMillis() - arrivedAt) / 1000
        if (waited >= stopLimitSec() - PARKING_ASK_BEFORE_S) {
            parkingAsked = true
            nearby.ask("parking", auto = true)
        }
    }

    /** 지금 만남 지점의 정차 한도(초). 제안 1순위가 확정 지점이면 그 값, 아니면 기본 5분 */
    private fun stopLimitSec(): Int {
        val st = lastState ?: return DEFAULT_STOP_LIMIT_S
        val tid = st.optJSONObject("target")?.optString("id")
        val best = st.optJSONObject("proposal")?.optJSONObject("best")
        return if (best != null && best.optString("id") == tid) best.optInt("stopLimit", DEFAULT_STOP_LIMIT_S) else DEFAULT_STOP_LIMIT_S
    }

    // ------------------------------------------------------------ 도우미

    /** 확정 지점까지의 경로상 거리(m, 출발점 기준). 서버가 도곡로 위 투영 위치로 계산해 준다 */
    fun targetX(): Double? = lastState?.optJSONObject("target")?.optDouble("x")?.takeIf { !it.isNaN() }

    /** 시연 경로(도곡로) 끝까지의 거리 */
    private fun maxProgress(): Double = lastState?.optDouble("maxProgress")?.takeIf { !it.isNaN() } ?: DEFAULT_ROUTE_M

    fun pointName(st: JSONObject, id: String): String =
        st.optJSONObject("target")?.takeIf { it.optString("id") == id }?.optString("name")
            ?: st.optJSONObject("proposal")?.optJSONObject("best")?.takeIf { it.optString("id") == id }?.optString("name")
            ?: id

    /** "GS25 앞으로" 같은 목적지 구(한국어는 '앞' + 로/으로, 영어는 이름 그대로) */
    private fun destination(name: String) = t.ro(t[R.string.in_front_of, name])

    fun curbSideLabel() = if (region.leftHandTraffic) t[R.string.side_left] else t[R.string.side_right]
    fun curbDoorLabel() = if (region.leftHandTraffic) t[R.string.door_row2_left] else t[R.string.door_row2_right]
    fun targetName(): String? = lastState?.optJSONObject("target")?.optString("name")
    fun riderConfirmed(): Boolean = lastState?.optJSONObject("boarding")?.optBoolean("riderConfirmed") == true
    fun replanPending(): Boolean = replanAt != 0L && lastState?.optJSONObject("replan")?.optBoolean("ack") != true
    fun replanFrom(): String = lastState?.optJSONObject("replan")?.optString("from").orEmpty()
    fun emergencyPost(on: Boolean, card: JSONObject? = null) {
        val id = sessionId ?: return
        post("/api/session/$id/emergency", JSONObject().put("on", on).apply { if (card != null) put("card", card) })
    }
    /** 응급 중에 묻던 질문이 있었다면 다음 상태에서 다시 묻는다 */
    fun reaskProposalIfPending() { if (lastState?.isNull("confirmed") != false) lastProposalVersion = 0 }

    private companion object {
        const val CAR_STALL_MS = 8_000L   // 차 상태 보고 응답을 이만큼 못 받으면 막힌 것으로 보고 다시 보낸다
        val CABIN_WATCH = setOf(PickupStatus.CONFIRMED, PickupStatus.ARRIVED, PickupStatus.BOARDED)   // 좌석·벨트·문을 보는 상태(탑승 판단)
        val EN_ROUTE = setOf(PickupStatus.CONFIRMED, PickupStatus.ARRIVED)                 // 만날 곳으로 가는 중
        val RIDER_ON_BOARD = setOf(PickupStatus.BOARDED, PickupStatus.DROPPED)             // 차가 직접 정한 상태 — 서버 상태로 되돌리지 않는다
        const val CURB_SNAP_M = 80.0            // 이 안에서 멈추면 그 지점 정차로 보정, 이만큼 넘어 달리면 지나침
        const val TAKEOUT_SLACK_S = 240         // 아이가 이만큼(초) 늦게 오면 가는 길 포장을 제안
        const val DROP_END_MARGIN_M = 50        // 경로 끝 이만큼 앞이면 하차로 본다(에뮬레이터 좌석 신호 대신)
        const val ROAD_DOOR_REPEAT_MS = 8_000L  // 도로 쪽 문 경고 반복 간격
        const val REPLAN_NAG_MS = 30_000L       // 바뀐 만날 곳을 확인 안 하면 다시 알리기까지
        const val PARKING_ASK_BEFORE_S = 30     // 정차 한도 이만큼 전에 주차 안내
        const val DEFAULT_STOP_LIMIT_S = 300    // 정차 한도 기본값(5분)
        const val DEFAULT_ROUTE_M = 2000.0      // 경로 길이를 모를 때
    }
}
