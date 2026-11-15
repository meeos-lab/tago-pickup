package ai.meeos.tago.emergency

import ai.meeos.tago.app.Edition
import ai.meeos.tago.R
import ai.meeos.tago.app.Services
import ai.meeos.tago.pickup.PickupController
import ai.meeos.tago.ui.CardLine
import org.json.JSONObject

/**
 * 응급 상황 브리핑: 차 안 사람의 의료 응급(사고 아님). 음성("구급차", "어지러워" 등) 또는 버튼으로 시작한다.
 * 신고자가 그대로 읽어 줄 상황 카드(위치·정차 여부·좌석 센서 인원·경과 시간)를 만들고, 픽업 중이면 가족 링크를 '늦어져요'로 바꾼다.
 * 자동 발신·진단은 하지 않는다. 전화는 다이얼 화면만 연다.
 */
class EmergencyController(private val s: Services, private val pickup: PickupController, private val dial: (String) -> Boolean, private val locate: () -> Pair<String, String>? = { null }, private val roadLines: () -> List<String> = { emptyList() }) {
    var startedAt = 0L
        private set
    val active: Boolean get() = startedAt != 0L
    var lines: List<CardLine> = emptyList()
        private set

    private val t get() = s.texts

    /** [autoDial]: 충돌 질문에 무응답이라 시작한 경우 — 카드를 띄운 뒤 119 **다이얼 화면**을 자동으로 연다(발신은 사람이 한다). 정차가 확실할 때만(달리는 중이면 화면을 덮지 않는다) */
    fun start(autoDial: Boolean = false) {
        if (!Edition.emergency || active) return
        startedAt = System.currentTimeMillis()
        s.dialog.cancel()
        s.car.readAllSeatOccupancy { seats -> showCard(seats.values.count { it }, autoDial) }
    }

    private fun showCard(people: Int, autoDial: Boolean = false) {
        if (!active) return
        val moving = s.car.locked && s.car.speedMs > 1f   // 기어를 모르는 동안에도 속도가 있으면 달리는 중으로 본다(V-01)
        // 위치(V-06): 허브·픽업이 아는 장소 이름 > 차 안 GPS 의 좌표(카드에만, 서버로 보내지 않는다) > '위치를 알 수 없습니다' — 해결되지 않을 '확인 중'은 쓰지 않는다
        val named = pickup.lastState?.optString("carPlace")?.takeIf { it.isNotEmpty() }
        val place = named ?: locate()?.let { (la, lo) -> t[R.string.emg_place_gps, la, lo] } ?: t[R.string.emg_place_unknown]
        // 도로 이름·방향·가까운 나들목·휴게소(연결 중일 때, 경로 위 위치로 — 좌표 아님)가 있으면 위치 줄 아래에 붙인다
        val road = roadLines().map { CardLine(R.drawable.ic_flag, it) }
        lines = listOf(
            CardLine(R.drawable.ic_pin, place),
            *road.toTypedArray(),
            if (moving) CardLine(R.drawable.ic_car, t[R.string.emg_moving]) else CardLine(R.drawable.ic_parking, t[R.string.emg_stopped]),
            CardLine(R.drawable.ic_people, t[R.string.emg_people, people]),
        )
        s.render()
        s.say(t[R.string.say_emergency_card, if (moving) t[R.string.say_pull_over] else "", place, people, s.region.emergencyNumber])
        pickup.emergencyPost(true, JSONObject().put("place", named ?: t[R.string.emg_place_unknown]).put("moving", moving).put("people", people))
        if (autoDial && !moving && !s.car.locked) {
            s.log.d("충돌 무응답 — 119 전화 화면을 자동으로 연다(발신은 사람이 한다)")
            s.main.postDelayed({ if (active) dialEmergency() }, AUTO_DIAL_DELAY_MS)   // 카드 음성이 먼저 시작되도록 잠깐 뒤에
        }
    }

    /** 전화 앱의 다이얼 화면만 연다(자동 발신 안 함). 전화 앱이 없으면 SOS 버튼을 안내 */
    fun dialEmergency() {
        if (!dial(s.region.emergencyNumber)) s.say(t[R.string.say_no_phone_app])
    }

    fun end() {
        if (!active) return
        startedAt = 0L
        lines = emptyList()
        pickup.reaskProposalIfPending()   // 묻던 중이었다면 다음 상태에서 다시 묻는다
        pickup.emergencyPost(false)
        s.say(t[R.string.say_emergency_closed])
        s.render()
    }

    private companion object { const val AUTO_DIAL_DELAY_MS = 1500L }

    fun elapsedSec(): Long = if (active) (System.currentTimeMillis() - startedAt) / 1000 else 0
}
