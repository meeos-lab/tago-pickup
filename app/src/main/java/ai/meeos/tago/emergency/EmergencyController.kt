package ai.meeos.tago.emergency

import ai.meeos.tago.BuildConfig
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
class EmergencyController(private val s: Services, private val pickup: PickupController, private val dial: (String) -> Boolean) {
    var startedAt = 0L
        private set
    val active: Boolean get() = startedAt != 0L
    var lines: List<CardLine> = emptyList()
        private set

    private val t get() = s.texts

    fun start() {
        if (!BuildConfig.EMERGENCY || active) return
        startedAt = System.currentTimeMillis()
        s.dialog.cancel()
        s.car.readAllSeatOccupancy { seats -> showCard(seats.values.count { it }) }
    }

    private fun showCard(people: Int) {
        if (!active) return
        val moving = s.car.driving && s.car.speedMs > 1f
        val place = pickup.lastState?.optString("carPlace")?.takeIf { it.isNotEmpty() } ?: t[R.string.locating]
        lines = listOf(
            CardLine(R.drawable.ic_pin, place),
            if (moving) CardLine(R.drawable.ic_car, t[R.string.emg_moving]) else CardLine(R.drawable.ic_parking, t[R.string.emg_stopped]),
            CardLine(R.drawable.ic_people, t[R.string.emg_people, people]),
        )
        s.render()
        s.say(t[R.string.say_emergency_card, if (moving) t[R.string.say_pull_over] else "", place, people, s.region.emergencyNumber])
        pickup.emergencyPost(true, JSONObject().put("place", place).put("moving", moving).put("people", people))
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

    fun elapsedSec(): Long = if (active) (System.currentTimeMillis() - startedAt) / 1000 else 0
}
